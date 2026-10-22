/**
 * 存储维护操作：数据重置与审计保留期清理。
 *
 * 所有清理均通过 valid=0 标记无效，原始行与子表历史版本仍保留。
 */
import { connectClient, getPool } from './openGaussClient.js';
import { TRUNCATE_ORDER } from './schema.js';
import { logger } from '../lib/logger.js';
import type { PoolClient } from 'pg';

/**
 * 将演示业务记录标记无效（保留表结构、历史行、账号和审计）。
 *
 * 供演示重置与种子脚本使用，可与新代次种子写入共用同一事务。
 */
export async function resetAllData(transaction?: PoolClient): Promise<string[]> {
  const tables = TRUNCATE_ORDER.filter((table) => !['users', 'user_data_scopes', 'audit_logs'].includes(table));
  const client = transaction ?? await connectClient();
  try {
    if (!transaction) await client.query('BEGIN');
    for (const table of tables) await client.query(`UPDATE ${table} SET valid = 0 WHERE valid = 1`);
    if (!transaction) await client.query('COMMIT');
  } catch (error) { if (!transaction) await client.query('ROLLBACK').catch(() => undefined); throw error; }
  finally { if (!transaction) client.release(); }
  logger.warn({ tables: tables.length }, '演示业务记录已标记无效；账号和审计保留');
  return tables;
}

/**
 * 按保留期清理审计日志。
 *
 * 对应契约 DO-015 的 `auditRetentionDays`（1~3650 天）。
 * 文档库时代用的是 TTL 索引（由 mongod 后台线程删除）；
 * 关系库里按时间条件标记 valid=0，由启动期 + 定时任务触发。
 *
 * @returns 本次标记为无效的行数
 */
export async function purgeAuditLogs(retentionDays: number): Promise<number> {
  const days = Math.min(3650, Math.max(1, Math.floor(retentionDays)));
  const pool = getPool();
  const res = await pool.query(
    `UPDATE audit_logs SET valid = 0
      WHERE valid = 1 AND COALESCE(occurred_at, created_at) < now() - ($1::int * INTERVAL '1 day')`,
    [days],
  );
  const deleted = res.rowCount ?? 0;
  if (deleted > 0) {
    logger.info({ retentionDays: days, deleted }, '已按保留期清理审计日志');
  }
  return deleted;
}

/** 统计审计日志总量与最早/最新时间，便于判断保留期是否合理 */
export async function auditLogStats(): Promise<{
  total: number;
  oldest: Date | null;
  newest: Date | null;
}> {
  const pool = getPool();
  const res = await pool.query<{ total: string; oldest: Date | null; newest: Date | null }>(
    `SELECT COUNT(*) AS total,
            MIN(COALESCE(occurred_at, created_at)) AS oldest,
            MAX(COALESCE(occurred_at, created_at)) AS newest
       FROM audit_logs WHERE valid = 1`,
  );
  const row = res.rows[0];
  return {
    total: Number(row?.total ?? 0),
    oldest: row?.oldest ?? null,
    newest: row?.newest ?? null,
  };
}
