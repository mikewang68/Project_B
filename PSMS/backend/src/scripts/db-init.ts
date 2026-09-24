/**
 * 存储初始化：把两个存储都准备好（幂等，可反复执行）。
 *
 *   1. openGauss  —— 建表、建索引、写注释（见 db/schema.ts）
 *   2. openGemini —— 建时序库
 *
 * 用法：pnpm db:init
 */
import { closePool } from '../db/openGaussClient.js';
import { migrate } from '../db/migrate.js';
import { ensureDatabase, probe } from '../db/openGeminiClient.js';
import { env } from '../config/env.js';

async function main(): Promise<void> {
  console.log('PSMS 存储初始化');
  console.log();

  console.log('--- 1/2 openGauss ---');
  console.log(`  目标: ${env.OPENGAUSS_DSN}`);
  const result = await migrate();
  console.log(`  执行 ${result.statements} 条 DDL；当前共 ${result.tables.length} 张表，本次新增 ${result.created} 张；耗时 ${result.durationMs}ms`);

  console.log();
  console.log('--- 2/2 openGemini ---');
  if (!env.OPENGEMINI_ENABLED) {
    console.log('  已按配置关闭（OPENGEMINI_ENABLED=false），跳过');
  } else {
    const p = await probe();
    if (!p.reachable) {
      console.log(`  ⚠️ 不可达：${p.error}`);
      console.log('  时序库未创建，但不影响业务库使用；供数后重跑本命令即可。');
    } else {
      const { created } = await ensureDatabase();
      console.log(`  目标: ${env.OPENGEMINI_URL} / ${env.OPENGEMINI_DATABASE}`);
      console.log(`  ${created ? '已创建时序库' : '时序库已存在'}`);
    }
  }

  console.log();
  console.log('初始化完成。');
  await closePool();
}

main().catch(async (err) => {
  console.error('初始化失败:', err);
  await closePool().catch(() => undefined);
  process.exit(1);
});
