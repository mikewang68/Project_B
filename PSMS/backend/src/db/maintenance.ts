/**
 * 存储维护操作：数据重置与审计保留期清理。
 *
 * 关系库不同于文档库的地方：这里用 TRUNCATE（按依赖顺序，一次事务内完成）
 * 代替「逐个 drop collection」，用 DELETE + 时间条件代替 TTL 索引。
 */
import { getPool } from './openGaussClient.js';
import { TRUNCATE_ORDER } from './schema.js';
import { logger } from '../lib/logger.js';

/**
 * 清空全部业务表（保留表结构与索引）。
 *
 * 供演示重置与种子脚本使用。使用一条 `TRUNCATE t1, t2, ... CASCADE`
 * 而不是逐表 DELETE：更快，且能自动处理子表外键依赖。
 */
export async function resetAllData(): Promise<string[]> {
  const pool = getPool();
  // 按「先子后父」的顺序列出，CASCADE 会补上遗漏的引用
  const list = TRUNCATE_ORDER.join(', ');
  await pool.query(`TRUNCATE TABLE ${list} CASCADE`);
  logger.warn({ tables: TRUNCATE_ORDER.length }, '已清空全部业务表（演示重置）');
  return [...TRUNCATE_ORDER];
}

/**
 * 按保留期清理审计日志。
 *
 * 对应契约 DO-015 的 `auditRetentionDays`（1~3650 天）。
 * 文档库时代用的是 TTL 索引（由 mongod 后台线程删除）；
 * 关系库里没有 TTL，改为按时间条件删除，由启动期 + 定时任务触发。
 *
 * @returns 实际删除的行数
 */
export async function purgeAuditLogs(retentionDays: number): Promise<number> {
  const days = Math.min(3650, Math.max(1, Math.floor(retentionDays)));
  const pool = getPool();
  const res = await pool.query(
    `DELETE FROM audit_logs
      WHERE COALESCE(occurred_at, created_at) < now() - ($1::int * INTERVAL '1 day')`,
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
       FROM audit_logs`,
  );
  const row = res.rows[0];
  return {
    total: Number(row?.total ?? 0),
    oldest: row?.oldest ?? null,
    newest: row?.newest ?? null,
  };
}
