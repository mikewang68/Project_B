import express from 'express';
import cors from 'cors';
import morgan from 'morgan';
import { env, isDev } from './config/env.js';
import { closePool, health as gaussHealth } from './db/openGaussClient.js';
import { migrate } from './db/migrate.js';
import { ensureDatabase as ensureGeminiDatabase, probe as geminiProbe } from './db/openGeminiClient.js';
import { AUDIT_RETENTION_DAYS_DEFAULT, currentTableCounts } from './db/inspect.js';
import { purgeAuditLogs } from './db/maintenance.js';
import { logger } from './lib/logger.js';
import { errorHandler } from './middleware/errorHandler.js';
import { seedDatabase } from './seeds/seed-data.js';

import { authRouter } from './routes/auth.routes.js';
import { plansRouter } from './routes/plans.routes.js';
import { workOrdersRouter } from './routes/workOrders.routes.js';
import { appointmentsRouter } from './routes/appointments.routes.js';
import { monitorRouter } from './routes/monitor.routes.js';
import { exceptionsRouter } from './routes/exceptions.routes.js';
import { interlocksRouter } from './routes/interlocks.routes.js';
import { offlineSyncRouter } from './routes/offlineSync.routes.js';
import { reportsRouter } from './routes/reports.routes.js';
import { settingsRouter } from './routes/settings.routes.js';
import { auditRouter } from './routes/audit.routes.js';
import { demoRouter } from './routes/demo.routes.js';

const app = express();

app.use(cors({ origin: env.CORS_ORIGIN, credentials: true }));
app.use(express.json({ limit: '1mb' }));
if (isDev) app.use(morgan('short'));

/**
 * 健康检查：分别反映两个存储的真实状态。
 * 分工依据组内基线 —— openGauss 承载事务数据，openGemini 承载时序数据。
 */
app.get('/api/health', async (_req, res) => {
  const [gauss, gemini] = await Promise.all([gaussHealth(), geminiProbe()]);

  let tableCounts: Record<string, number> | null = null;
  if (gauss.connected) {
    try {
      tableCounts = await currentTableCounts();
    } catch (err) {
      logger.warn({ err }, '读取表行数失败');
    }
  }

  res.json({
    ok: gauss.connected,
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    openGauss: { ...gauss, tables: tableCounts },
    openGemini: gemini,
  });
});

app.use('/api/auth', authRouter);
app.use('/api/plans', plansRouter);
app.use('/api/work-orders', workOrdersRouter);
app.use('/api/appointments', appointmentsRouter);
app.use('/api/monitor', monitorRouter);
app.use('/api/exceptions', exceptionsRouter);
app.use('/api/interlocks', interlocksRouter);
app.use('/api/offline', offlineSyncRouter);
app.use('/api/reports', reportsRouter);
app.use('/api/settings', settingsRouter);
app.use('/api/audit-logs', auditRouter);
app.use('/api/demo', demoRouter);

app.use(errorHandler);

/** 业务表为空时自动灌入种子数据，保证服务起来就有可用数据 */
async function autoSeedIfEmpty(): Promise<void> {
  if (!env.AUTO_SEED) return;

  const counts = await currentTableCounts();
  const total = Object.values(counts).reduce((sum, n) => sum + n, 0);

  if (total > 0) {
    logger.info({ rows: total }, '检测到已有数据，跳过自动播种');
    return;
  }

  logger.info('业务表为空，开始自动写入种子数据…');
  await seedDatabase({ drop: false, withTelemetry: true });
}

/**
 * 带重试的连接自检。
 *
 * 开发机经 Tailscale 中继访问集群时，隧道可能出现"端口可连但转发通道已死"的
 * 瞬时故障（表现为 ECONNRESET / 建连超时）。这里重试几次再判定失败，
 * 避免一次网络抖动就让服务起不来；重试仍失败则如实报错退出（不静默降级）。
 */
async function waitForOpenGauss(attempts = 4, delayMs = 5000) {
  let last = '';
  for (let i = 1; i <= attempts; i += 1) {
    const health = await gaussHealth();
    if (health.connected) {
      if (i > 1) logger.info({ attempt: i }, 'openGauss 连接在第 %d 次尝试成功', i);
      return health;
    }
    last = health.error ?? '未知错误';
    if (i < attempts) {
      logger.warn({ attempt: i, error: last }, `openGauss 连接失败，${delayMs / 1000}s 后重试`);
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  throw new Error(
    `openGauss 连接失败: ${last}。请确认隧道已建立：`
    + ` ssh -N -L ${env.OPENGAUSS_PORT}:<node4>:${env.OPENGAUSS_PORT} lrz@<跳板机>`,
  );
}

async function start(): Promise<void> {
  // 1. 业务库：连不通就直接失败，不做静默降级
  const gauss = await waitForOpenGauss();
  logger.info(
    { version: gauss.version, database: gauss.database, latencyMs: gauss.latencyMs },
    'openGauss 已连接',
  );

  // 2. 幂等补齐表结构
  if (env.OPENGAUSS_AUTO_MIGRATE) {
    const result = await migrate();
    logger.info({ tables: result.tables.length, statements: result.statements }, '表结构已就绪');
  }

  // 3. 时序库：不可达只告警，不阻塞业务启动（与组内 SCS/EHMS 的探针降级一致）
  if (env.OPENGEMINI_ENABLED) {
    const p = await geminiProbe();
    if (p.reachable) {
      const { created } = await ensureGeminiDatabase();
      logger.info(
        { url: p.url, database: p.database, created, latencyMs: p.latencyMs },
        'openGemini 已连接',
      );
    } else {
      logger.warn({ error: p.error }, 'openGemini 不可达，时序读写将降级（业务库不受影响）');
    }
  } else {
    logger.warn('openGemini 已按配置关闭（OPENGEMINI_ENABLED=false）');
  }

  // 4. 按保留期清理审计日志（关系库没有 TTL，改为启动期主动清理）
  await purgeAuditLogs(AUDIT_RETENTION_DAYS_DEFAULT).catch((err) =>
    logger.warn({ err }, '审计清理失败（不影响启动）'),
  );

  // 5. 空库自动播种
  await autoSeedIfEmpty();

  const server = app.listen(env.PORT, () => {
    logger.info(
      { port: env.PORT, env: env.NODE_ENV, openGauss: env.OPENGAUSS_DSN },
      '🎉 PSMS 后端已启动（openGauss + openGemini）',
    );
    logger.info(`健康检查: http://localhost:${env.PORT}/api/health`);
  });

  const shutdown = async (signal: string): Promise<void> => {
    logger.info({ signal }, '收到退出信号，开始优雅关闭…');
    server.close();
    await closePool().catch((err) => logger.warn({ err }, '关闭 openGauss 连接池失败'));
    logger.info('已安全退出');
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

start().catch(async (err) => {
  logger.fatal({ err }, '服务启动失败');
  await closePool().catch(() => undefined);
  process.exit(1);
});

export { app };
