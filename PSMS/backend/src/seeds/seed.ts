/**
 * 种子数据脚本。
 *
 * 用法：pnpm seed
 *
 * 会清空全部业务集合后重新写入固定种子数据，
 * 与 POST /api/demo/reset 使用同一份数据定义（src/seeds/seed-data.ts）。
 */
import { connectDatabase, disconnectDatabase } from '../config/db.js';
import { logger } from '../lib/logger.js';
import { DEMO_PASSWORD, seedDatabase } from './seed-data.js';

async function main(): Promise<void> {
  await connectDatabase();
  const result = await seedDatabase({ drop: true, withTelemetry: true });

  logger.info(`种子数据写入完成：${JSON.stringify(result.counts)}`);
  logger.info(
    `可用账号：admin / scheduler / dispatcher / operator / viewer（密码：${DEMO_PASSWORD}）`,
  );

  await disconnectDatabase();
  process.exit(0);
}

main().catch(async (err) => {
  logger.fatal({ err }, '种子数据写入失败');
  await disconnectDatabase().catch(() => undefined);
  process.exit(1);
});
