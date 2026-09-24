/**
 * 表结构迁移：把 db/schema.ts 里声明的 DDL 幂等地应用到 openGauss。
 *
 * 与文档模型时代的差别：这里不再是"集合首次写入自动创建"，
 * 而是显式的、可重复执行的 DDL，便于在不同环境保持一致。
 */
import { closePool, getPool } from './openGaussClient.js';
import { SCHEMA_STATEMENTS, ALL_TABLES } from './schema.js';
import { logger } from '../lib/logger.js';

export interface MigrationResult {
  statements: number;
  tables: string[];
  created: number;
  durationMs: number;
}

/** 列出当前库中已存在的业务表 */
export async function listExistingTables(): Promise<string[]> {
  const res = await getPool().query<{ table_name: string }>(
    `SELECT table_name FROM information_schema.tables
      WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'`,
  );
  return res.rows.map((r) => r.table_name).sort();
}

/**
 * 执行迁移。
 *
 * 两条路径：
 *  1. **批量**：DDL 全部无参数，node-postgres 会用简单查询协议一次发过去。
 *     经 SSH 隧道的 RTT 很高（每条约 2~3 秒），批量能把 200+ 条语句压成一次往返。
 *  2. **逐条回退**：批量失败时改为逐条执行，用于精确定位是哪一条 DDL 有问题。
 *
 * 全部语句都是 IF NOT EXISTS 幂等的，重复执行安全。
 */
export async function migrate(): Promise<MigrationResult> {
  const startedAt = Date.now();
  const before = new Set(await listExistingTables());
  const pool = getPool();

  try {
    await pool.query(`${SCHEMA_STATEMENTS.join(';\n')};`);
    logger.info({ statements: SCHEMA_STATEMENTS.length }, 'DDL 批量执行成功');
  } catch (batchErr) {
    logger.warn({ err: (batchErr as Error).message }, 'DDL 批量执行失败，改为逐条定位');
    for (const [index, sql] of SCHEMA_STATEMENTS.entries()) {
      try {
        await pool.query(sql);
      } catch (err) {
        logger.error(
          { index, sql: sql.slice(0, 160).replace(/\s+/g, ' '), err },
          'DDL 执行失败，迁移中断',
        );
        throw new Error(`第 ${index + 1} 条 DDL 失败: ${(err as Error).message}`);
      }
    }
  }

  const after = await listExistingTables();
  const created = after.filter((t) => !before.has(t));
  const missing = ALL_TABLES.filter((t) => !after.includes(t));

  if (missing.length > 0) {
    throw new Error(`迁移后仍缺少表: ${missing.join(', ')}`);
  }

  const result: MigrationResult = {
    statements: SCHEMA_STATEMENTS.length,
    tables: after,
    created: created.length,
    durationMs: Date.now() - startedAt,
  };
  logger.info(
    { statements: result.statements, tables: after.length, created: result.created },
    'openGauss 表结构迁移完成',
  );
  return result;
}

/** 独立运行时：直接执行迁移并打印结果 */
async function main(): Promise<void> {
  const { env } = await import('../config/env.js');
  console.log(`[migrate] 目标 ${env.OPENGAUSS_DSN}（schema=${env.OPENGAUSS_SCHEMA}）`);
  const result = await migrate();
  console.log(`[migrate] 执行 ${result.statements} 条 DDL，新增 ${result.created} 张表，耗时 ${result.durationMs}ms`);
  console.log(`[migrate] 当前表（${result.tables.length}）:`);
  for (const t of result.tables) console.log('   -', t);
  await closePool();
}

const isDirectRun = process.argv[1]?.replace(/\\/g, '/').endsWith('db/migrate.ts')
  || process.argv[1]?.replace(/\\/g, '/').endsWith('db/migrate.js');

if (isDirectRun) {
  main().catch(async (err) => {
    console.error('[migrate] 失败:', err);
    await closePool().catch(() => undefined);
    process.exit(1);
  });
}
