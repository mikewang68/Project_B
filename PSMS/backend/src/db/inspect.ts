/**
 * 库内状态速查：给健康检查、种子脚本与控制台工具共用。
 */
import { getPool, queryScalar } from './openGaussClient.js';
import { CORE_TABLES } from './schema.js';

/** 审计保留期兜底值（对应契约 DO-015 的 auditRetentionDays 默认值） */
export const AUDIT_RETENTION_DAYS_DEFAULT = 365;

/** 各业务表的当前行数 */
export async function currentTableCounts(): Promise<Record<string, number>> {
  const out: Record<string, number> = {};
  for (const table of CORE_TABLES) {
    const n = await queryScalar<number | string>(`SELECT COUNT(*) FROM ${table}`);
    out[table] = Number(n ?? 0);
  }
  return out;
}

/** 业务表总行数 */
export async function totalBusinessRows(): Promise<number> {
  const counts = await currentTableCounts();
  return Object.values(counts).reduce((sum, n) => sum + n, 0);
}

/**
 * 读取当前生效的审计保留天数。
 *
 * 以 config_versions 表里的配置为准（契约字段 audit_retention_days），
 * 没有配置时回落到默认值。
 */
export async function currentAuditRetentionDays(): Promise<number> {
  const pool = getPool();
  const res = await pool.query<{ audit_retention_days: number }>(
    `SELECT audit_retention_days FROM config_versions
      ORDER BY (status = 'PUBLISHED') DESC, updated_at DESC LIMIT 1`,
  );
  const value = res.rows[0]?.audit_retention_days;
  if (typeof value === 'number' && value >= 1) return value;
  return AUDIT_RETENTION_DAYS_DEFAULT;
}

/** 库版本与容量概览 */
export async function databaseOverview(): Promise<{
  version: string;
  encoding: string;
  size: string;
}> {
  const pool = getPool();
  const res = await pool.query<{ version: string; encoding: string; size: string }>(
    `SELECT version() AS version,
            current_setting('server_encoding') AS encoding,
            pg_size_pretty(pg_database_size(current_database())) AS size`,
  );
  return res.rows[0];
}
