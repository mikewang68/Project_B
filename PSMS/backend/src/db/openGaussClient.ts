/**
 * openGauss 连接池客户端。
 *
 * 组内约定（见 SCS/backend/.../application.yml 注释）：
 *   openGauss 6.0 的官方 JDBC 驱动与 PostgreSQL 线协议对齐，
 *   因此 Node 侧直接使用标准 PostgreSQL 驱动 `pg`，URL 语义同 postgresql://。
 *
 * 本模块只负责「连接 + 查询 + 事务 + 健康探针」，不含任何业务 SQL。
 */
import { Pool, type PoolClient, type QueryResultRow } from 'pg';
import { env } from '../config/env.js';
import { logger } from '../lib/logger.js';

let pool: Pool | null = null;

/** 建连接池（幂等） */
export function getPool(): Pool {
  if (pool) return pool;
  pool = new Pool({
    host: env.OPENGAUSS_HOST,
    port: env.OPENGAUSS_PORT,
    database: env.OPENGAUSS_DATABASE,
    user: env.OPENGAUSS_USERNAME,
    password: env.OPENGAUSS_PASSWORD,
    max: env.OPENGAUSS_POOL_MAX,
    min: env.OPENGAUSS_POOL_MIN,
    connectionTimeoutMillis: env.OPENGAUSS_CONNECT_TIMEOUT_MS,
    idleTimeoutMillis: env.OPENGAUSS_IDLE_TIMEOUT_MS,
    statement_timeout: env.OPENGAUSS_STATEMENT_TIMEOUT_MS || undefined,
    // 组内 openGauss 默认不启用 TLS（容器内网互通）
    ssl: false,
    application_name: 'psms-backend',
  });

  pool.on('error', (err) => {
    // 空闲连接上的错误不应让进程退出
    logger.error({ err }, 'openGauss 空闲连接异常');
  });

  return pool;
}

/**
 * 取出一个连接并挂上错误监听。
 *
 * 背景：pg-pool 的 `pool.on('error')` 只覆盖**空闲**连接；
 * `pool.connect()` 借出的连接在事务进行中被远端断开时，
 * 错误是从该 Client 实例上 emit 的，没人接就会抛
 * `Unhandled 'error' event` 让整个进程崩溃（真实踩过：隧道断连时后端直接退出）。
 * 连接中断应表现为"当前请求失败（500）"，而不是"进程死掉"。
 */
export async function connectClient(): Promise<PoolClient> {
  const client = await getPool().connect();
  client.on('error', (err) => {
    logger.error({ err }, 'openGauss 连接被远端断开（本次请求失败，进程不受影响）');
  });
  return client;
}

/** 参数化查询；返回行数组 */
export async function query<T extends QueryResultRow = QueryResultRow>(
  sql: string,
  params: unknown[] = [],
): Promise<T[]> {
  const res = await getPool().query<T>(sql, params as never[]);
  return res.rows;
}

/** 查询单行 */
export async function queryOne<T extends QueryResultRow = QueryResultRow>(
  sql: string,
  params: unknown[] = [],
): Promise<T | null> {
  const rows = await query<T>(sql, params);
  return rows[0] ?? null;
}

/** 查询单个标量值（COUNT / SUM / EXISTS 等） */
export async function queryScalar<T = unknown>(
  sql: string,
  params: unknown[] = [],
): Promise<T | null> {
  const row = await queryOne<Record<string, T>>(sql, params);
  if (!row) return null;
  const values = Object.values(row);
  return (values[0] ?? null) as T | null;
}

/**
 * 在单个连接上执行事务。
 * 命令流水线（校验 → 状态迁移 → 落库 → 审计）必须整体原子，
 * 因此涉及写操作的多语句一律走这里。
 */
export async function withTransaction<T>(
  fn: (client: PoolClient) => Promise<T>,
): Promise<T> {
  const client = await connectClient();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try {
      await client.query('ROLLBACK');
    } catch (rollbackErr) {
      logger.error({ rollbackErr }, 'openGauss 回滚失败');
    }
    throw err;
  } finally {
    client.release();
  }
}

/** 在事务内执行参数化查询的小封装 */
export async function txQuery<T extends QueryResultRow = QueryResultRow>(
  client: PoolClient,
  sql: string,
  params: unknown[] = [],
): Promise<T[]> {
  const res = await client.query<T>(sql, params as never[]);
  return res.rows;
}

export interface OpenGaussHealth {
  connected: boolean;
  database: string;
  user: string;
  host: string;
  port: number;
  version: string;
  serverEncoding: string;
  latencyMs: number;
  /** 连接池运行态 */
  pool: { total: number; idle: number; waiting: number };
  error: string | null;
}

/** 健康探针：永不抛异常，供 /api/health 与启动自检使用 */
export async function health(): Promise<OpenGaussHealth> {
  const startedAt = Date.now();
  const base: OpenGaussHealth = {
    connected: false,
    database: env.OPENGAUSS_DATABASE,
    user: env.OPENGAUSS_USERNAME,
    host: env.OPENGAUSS_HOST,
    port: env.OPENGAUSS_PORT,
    version: '',
    serverEncoding: '',
    latencyMs: 0,
    pool: { total: 0, idle: 0, waiting: 0 },
    error: null,
  };
  try {
    const row = await queryOne<{
      version: string;
      server_encoding: string;
      current_database: string;
    }>('SELECT version() AS version, current_setting(\'server_encoding\') AS server_encoding, current_database() AS current_database');
    base.connected = true;
    base.version = row?.version?.split(' ').slice(0, 3).join(' ') ?? '';
    base.serverEncoding = row?.server_encoding ?? '';
    if (row?.current_database) base.database = row.current_database;
  } catch (err) {
    base.error = (err as Error).message;
  }
  const p = getPool();
  base.pool = { total: p.totalCount, idle: p.idleCount, waiting: p.waitingCount };
  base.latencyMs = Date.now() - startedAt;
  return base;
}

/** 进程退出时释放连接池 */
export async function closePool(): Promise<void> {
  if (!pool) return;
  const p = pool;
  pool = null;
  await p.end();
}

/**
 * 把 openGauss/PG 的错误码归类，便于 HTTP 层映射状态码。
 * 参考 PostgreSQL 错误码（openGauss 兼容）。
 */
export function classifyDbError(err: unknown): {
  kind: 'unique' | 'foreign_key' | 'not_null' | 'check' | 'serialization' | 'timeout' | 'unknown';
  code: string | undefined;
  detail: string | undefined;
  constraint: string | undefined;
} {
  const e = err as { code?: string; detail?: string; constraint?: string };
  const code = e?.code;
  const constraint = e?.constraint;
  const detail = e?.detail;

  switch (code) {
    case '23505':
      return { kind: 'unique', code, detail, constraint };
    case '23503':
      return { kind: 'foreign_key', code, detail, constraint };
    case '23502':
      return { kind: 'not_null', code, detail, constraint };
    case '23514':
      return { kind: 'check', code, detail, constraint };
    case '40001':
    case '40P01':
      return { kind: 'serialization', code, detail, constraint };
    case '57014':
    case '55P03':
      return { kind: 'timeout', code, detail, constraint };
    default:
      return { kind: 'unknown', code, detail, constraint };
  }
}
