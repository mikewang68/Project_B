/**
 * 种子数据脚本。
 *
 * 用法：pnpm seed
 *
 * 会清空全部业务表后重新写入固定种子数据，
 * 与 POST /api/demo/reset 使用同一份数据定义（src/seeds/seed-data.ts）。
 */
import { closePool, health as gaussHealth } from '../db/openGaussClient.js';
import { ensureDatabase as ensureGeminiDatabase, probe as geminiProbe } from '../db/openGeminiClient.js';
import { logger } from '../lib/logger.js';
import { DEMO_PASSWORD, seedDatabase } from './seed-data.js';

async function main(): Promise<void> {
  // 1. 业务库连通性先过一遍，不通就直接失败
  const gauss = await gaussHealth();
  if (!gauss.connected) {
    throw new Error(`openGauss 连接失败: ${gauss.error}`);
  }
  logger.info({ database: gauss.database, version: gauss.version }, 'openGauss 已连接');

  // 2. 时序库：可达则确保库存在；不可达只告警（业务数据仍会写入）
  if (gauss.connected) {
    const p = await geminiProbe();
    if (p.reachable) {
      await ensureGeminiDatabase();
    } else {
      logger.warn({ error: p.error }, 'openGemini 不可达，本次不写入遥测数据');
    }
  }

  const result = await seedDatabase({ drop: true, withTelemetry: true });

  logger.info(`种子数据写入完成：${JSON.stringify(result.counts)}`);
  logger.info(
    `可用账号：admin / scheduler / dispatcher / operator / viewer（密码：${DEMO_PASSWORD}）`,
  );

  await closePool();
  process.exit(0);
}

main().catch(async (err) => {
  logger.fatal({ err }, '种子数据写入失败');
  await closePool().catch(() => undefined);
  process.exit(1);
});
