/**
 * openGemini 轻量 HTTP 客户端。
 *
 * 设计对齐组内基线（SCS/OpenGeminiClient.java、EHMS/init-opengemini.sh）：
 *   - 不引入第三方 SDK，只依赖 openGemini 的 InfluxDB 兼容 HTTP API
 *   - GET  /ping                探活，2xx（通常 204）视为可用，版本在响应头 X-Geminidb-Version
 *   - GET  /query?db=&q=        查询 / 执行 DDL
 *   - POST /write?db=&precision= 行协议写入
 *   - 探针失败不阻塞业务：由调用方根据 isEnabled/探针结果降级
 *
 * 注意：本模块使用 Node 内置 fetch（undici），**不读取 HTTP_PROXY/HTTPS_PROXY 环境变量**，
 * 因此在存在宿主代理的机器上访问 127.0.0.1 隧道不会被劫持。
 */
import { env } from '../config/env.js';
import { logger } from '../lib/logger.js';
import { buildLines, type PointInput } from './lineProtocol.js';

export interface OpenGeminiProbeResult {
  /** 配置开关是否打开 */
  enabled: boolean;
  /** HTTP 探针是否返回 2xx */
  reachable: boolean;
  /** 服务端返回的版本（响应头 X-Geminidb-Version，可能为空） */
  version: string;
  /** 目标地址 */
  url: string;
  /** 目标库名 */
  database: string;
  /** 该库是否已存在 */
  databaseExists: boolean;
  /** 探针耗时（毫秒） */
  latencyMs: number;
  /** 失败原因（成功时为 null） */
  error: string | null;
}

export interface OpenGeminiSeries {
  name?: string;
  tags?: Record<string, string>;
  columns?: string[];
  values?: unknown[][];
}

export interface OpenGeminiQueryResult {
  series: OpenGeminiSeries[];
  /** InfluxQL 语句级错误（存在即表示该语句失败） */
  error: string | null;
}

export class OpenGeminiError extends Error {
  constructor(message: string, readonly status?: number, readonly body?: string) {
    super(message);
    this.name = 'OpenGeminiError';
  }
}

const MEASUREMENT_TELEMETRY = 'equipment_telemetry';

/** 时序 measurement 清单：key 为逻辑名，value 为实际 measurement 名 */
export const TELEMETRY_MEASUREMENTS = {
  /** 设备遥测点值（温度/电流/振动…） */
  equipmentTelemetry: MEASUREMENT_TELEMETRY,
} as const;

function buildUrl(pathname: string, params: Record<string, string | undefined>): string {
  const base = env.OPENGEMINI_URL.replace(/\/+$/, '');
  const url = new URL(base + pathname);
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') url.searchParams.set(key, value);
  }
  return url.toString();
}

function authHeaders(): Record<string, string> {
  const { OPENGEMINI_USERNAME: user, OPENGEMINI_PASSWORD: pass } = env;
  if (!user) return {};
  const token = Buffer.from(`${user}:${pass}`).toString('base64');
  return { Authorization: `Basic ${token}` };
}

async function request(
  url: string,
  init: RequestInit & { timeoutMs?: number } = {},
): Promise<Response> {
  const { timeoutMs = env.OPENGEMINI_TIMEOUT_MS, ...rest } = init;
  return fetch(url, {
    ...rest,
    headers: { ...authHeaders(), ...(rest.headers ?? {}) },
    signal: AbortSignal.timeout(timeoutMs),
  });
}

/** 探活：永不抛异常，用于健康检查与启动降级判断 */
export async function probe(): Promise<OpenGeminiProbeResult> {
  const startedAt = Date.now();
  const base: OpenGeminiProbeResult = {
    enabled: env.OPENGEMINI_ENABLED,
    reachable: false,
    version: '',
    url: env.OPENGEMINI_URL,
    database: env.OPENGEMINI_DATABASE,
    databaseExists: false,
    latencyMs: 0,
    error: null,
  };

  if (!env.OPENGEMINI_ENABLED) {
    return { ...base, error: 'OPENGEMINI_ENABLED=false（按配置关闭）' };
  }

  try {
    const res = await request(buildUrl('/ping', {}), { method: 'GET' });
    base.reachable = res.ok;
    base.version =
      res.headers.get('x-geminidb-version') ?? res.headers.get('x-influxdb-version') ?? '';
    if (!res.ok) {
      base.error = `探针返回 HTTP ${res.status}`;
      base.latencyMs = Date.now() - startedAt;
      return base;
    }
    // 库是否存在单独判断，失败不影响可达性结论
    try {
      const dbs = await query('SHOW DATABASES');
      const names = (dbs.series[0]?.values ?? []).map((row) => String(row[0]));
      base.databaseExists = names.includes(env.OPENGEMINI_DATABASE);
    } catch (err) {
      base.error = `探针可用，但 SHOW DATABASES 失败: ${(err as Error).message}`;
    }
  } catch (err) {
    base.error = (err as Error).message;
  }

  base.latencyMs = Date.now() - startedAt;
  return base;
}

/** 执行 InfluxQL；DDL 与查询共用同一入口 */
export async function query(sql: string, database?: string): Promise<OpenGeminiQueryResult> {
  if (!env.OPENGEMINI_ENABLED) {
    throw new OpenGeminiError('openGemini 未启用（OPENGEMINI_ENABLED=false）');
  }
  const url = buildUrl('/query', {
    db: database ?? env.OPENGEMINI_DATABASE,
    q: sql,
  });
  const res = await request(url, { method: 'GET' });
  const text = await res.text();

  if (!res.ok) {
    throw new OpenGeminiError(`openGemini 查询失败 HTTP ${res.status}`, res.status, text);
  }

  let payload: { results?: Array<{ series?: OpenGeminiSeries[]; error?: string }> };
  try {
    payload = JSON.parse(text) as typeof payload;
  } catch {
    throw new OpenGeminiError('openGemini 返回内容不是合法 JSON', res.status, text);
  }

  const first = payload.results?.[0];
  if (first?.error) {
    return { series: [], error: first.error };
  }
  return { series: first?.series ?? [], error: null };
}

/** 建库（幂等：已存在则静默跳过） */
export async function ensureDatabase(): Promise<{ created: boolean }> {
  if (!env.OPENGEMINI_ENABLED) return { created: false };
  const name = env.OPENGEMINI_DATABASE;
  const existing = await query('SHOW DATABASES');
  const names = (existing.series[0]?.values ?? []).map((row) => String(row[0]));
  if (names.includes(name)) return { created: false };

  const res = await query(`CREATE DATABASE "${name}"`);
  if (res.error && !/exist/i.test(res.error)) {
    throw new OpenGeminiError(`建库失败: ${res.error}`);
  }
  logger.info({ database: name }, 'openGemini 库已创建');
  return { created: true };
}

/** 行协议写入；返回实际写入的点数 */
export async function write(points: PointInput[]): Promise<number> {
  if (!env.OPENGEMINI_ENABLED || !env.OPENGEMINI_WRITE_ENABLED) return 0;
  const lines = buildLines(points);
  if (lines.length === 0) return 0;

  const url = buildUrl('/write', { db: env.OPENGEMINI_DATABASE, precision: 'ms' });
  const res = await request(url, {
    method: 'POST',
    body: lines.join('\n'),
    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
  });

  if (!res.ok) {
    const body = await res.text();
    throw new OpenGeminiError(`openGemini 写入失败 HTTP ${res.status}`, res.status, body);
  }
  return lines.length;
}

/** 读取某个 measurement 的最近若干条（供页面与自检使用） */
export async function latestPoints(
  measurement: string,
  limit = 20,
): Promise<OpenGeminiSeries[]> {
  const res = await query(
    `SELECT * FROM "${measurement}" ORDER BY time DESC LIMIT ${Math.max(1, Math.floor(limit))}`,
  );
  if (res.error) throw new OpenGeminiError(`读取时序数据失败: ${res.error}`);
  return res.series;
}

/** 统计某 measurement 在时间窗口内的点数 */
export async function countPoints(
  measurement: string,
  window = '7d',
): Promise<number> {
  const res = await query(
    `SELECT COUNT(*) FROM "${measurement}" WHERE time > now() - ${window}`,
  );
  if (res.error) throw new OpenGeminiError(`统计时序点数失败: ${res.error}`);
  const value = res.series[0]?.values?.[0]?.[1];
  return typeof value === 'number' ? value : 0;
}
