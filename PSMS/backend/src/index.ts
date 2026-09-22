import express from 'express';
import cors from 'cors';
import morgan from 'morgan';
import { env, isDev } from './config/env.js';
import { connectDatabase, disconnectDatabase, getNativeDb, getResolvedUri, isConnected } from './config/db.js';
import { logger } from './lib/logger.js';
import { errorHandler } from './middleware/errorHandler.js';
import { allModels, syncAllIndexes } from './models/index.js';
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

// 健康检查：同时反映 MongoDB 真实连接状态与集合文档量
app.get('/api/health', async (_req, res) => {
  const connected = isConnected();
  let mongodb: Record<string, unknown> = { connected };

  if (connected) {
    try {
      const db = getNativeDb();
      const serverInfo = await db.admin().serverInfo();
      const collections = await db.listCollections().toArray();
      let documents = 0;
      for (const info of collections) {
        if (info.name.startsWith('system.')) continue;
        documents += await db.collection(info.name).countDocuments();
      }
      mongodb = {
        connected: true,
        uri: getResolvedUri(),
        dbName: env.MONGODB_DB_NAME,
        version: serverInfo.version,
        mode: env.USE_EMBEDDED_MONGODB ? 'embedded' : 'external',
        collections: collections.filter((c) => !c.name.startsWith('system.')).length,
        documents,
      };
    } catch (err) {
      mongodb = { connected: true, error: (err as Error).message };
    }
  }

  res.json({
    ok: true,
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    mongodb,
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

/** 集合为空时自动灌入种子数据，保证服务起来就有可用数据 */
async function autoSeedIfEmpty(): Promise<void> {
  if (!env.AUTO_SEED) return;

  const db = getNativeDb();
  const existing = await db.listCollections().toArray();
  const businessCollections = existing.filter((c) => !c.name.startsWith('system.'));

  let total = 0;
  for (const info of businessCollections) {
    total += await db.collection(info.name).countDocuments();
  }

  if (total > 0) {
    logger.info({ documents: total }, '检测到已有数据，跳过自动播种');
    return;
  }

  logger.info('数据库为空，开始自动写入种子数据...');
  await seedDatabase({ drop: true, withTelemetry: true });
}

async function start(): Promise<void> {
  await connectDatabase();
  await syncAllIndexes();
  await autoSeedIfEmpty();

  const server = app.listen(env.PORT, () => {
    logger.info(
      { port: env.PORT, env: env.NODE_ENV, mongodb: getResolvedUri() },
      '🎉 PSMS 后端已启动（MongoDB 持久化）',
    );
    logger.info(`健康检查: http://localhost:${env.PORT}/api/health`);
  });

  // 优雅关闭：先停止接收请求，再断开 mongoose，最后停止内嵌 mongod（保留数据）
  const shutdown = async (signal: string): Promise<void> => {
    logger.info({ signal }, '收到退出信号，开始优雅关闭...');
    server.close();
    await disconnectDatabase().catch((err) => logger.warn({ err }, '关闭数据库连接失败'));
    logger.info('已安全退出');
    process.exit(0);
  };

  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
}

start().catch(async (err) => {
  logger.fatal({ err }, '服务启动失败');
  await disconnectDatabase().catch(() => undefined);
  process.exit(1);
});

export { app, allModels };
