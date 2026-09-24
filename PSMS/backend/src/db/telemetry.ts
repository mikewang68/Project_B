/**
 * 设备遥测访问器 —— 由 openGemini 承载。
 *
 * 原实现是 MongoDB 时序集合；本模块保持相同的方法形态
 * （find / insertMany / countDocuments），使路由与种子脚本无需改写。
 *
 * 数据落地形态（InfluxDB 行协议）：
 *   measurement : equipment_telemetry
 *   tags        : equipment_id, point_code, quality
 *   fields      : value, unit, metadata（metadata 以 JSON 字符串存放）
 *   time        : source_timestamp（毫秒精度）
 */
import { env } from '../config/env.js';
import { logger } from '../lib/logger.js';
import {
  TELEMETRY_MEASUREMENTS,
  countPoints,
  latestPoints,
  query,
  write,
} from './openGeminiClient.js';
import type { PointInput } from './lineProtocol.js';

const MEASUREMENT = TELEMETRY_MEASUREMENTS.equipmentTelemetry;

export interface TelemetryPointDoc {
  equipmentId: string;
  pointCode: string;
  value: number;
  quality: string;
  sourceTimestamp: Date;
  unit?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface TelemetryFilter {
  /** 支持直接给值或给 { $in: [...] } */
  equipmentId?: string | { $in?: string[] };
  pointCode?: string | { $in?: string[] };
  /** 支持直接给值、给数组，或给 { $in: [...] }（与关系库侧调用点保持一致） */
  quality?: string | string[] | { $in?: string[] };
  /** 起始时间（含） */
  since?: Date;
  /** 结束时间（含） */
  until?: Date;
}

function escapeInflux(value: string): string {
  return value.replace(/'/g, "\\'");
}

/** 把「值 | 数组 | { $in }」三种写法统一成字符串数组 */
function toList(value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (typeof value === 'string') return [value];
  if (Array.isArray(value)) return value.map(String);
  const inList = (value as { $in?: unknown }).$in;
  return Array.isArray(inList) ? inList.map(String) : [];
}

/** 把筛选条件翻译成 InfluxQL WHERE —— 值做转义，避免拼串注入 */
function buildWhere(filter?: TelemetryFilter): string {
  const parts: string[] = [];

  const eq = (column: string, value: unknown) => {
    const list = toList(value);
    if (list.length === 0) return;
    const clauses = list.map((v) => `${column} = '${escapeInflux(v)}'`);
    parts.push(clauses.length === 1 ? clauses[0] : `(${clauses.join(' OR ')})`);
  };

  eq('equipment_id', filter?.equipmentId);
  eq('point_code', filter?.pointCode);
  eq('quality', filter?.quality);
  if (filter?.since) parts.push(`time >= ${filter.since.getTime()}ms`);
  if (filter?.until) parts.push(`time <= ${filter.until.getTime()}ms`);
  return parts.length > 0 ? `WHERE ${parts.join(' AND ')}` : '';
}



function toDoc(row: Record<string, unknown>): TelemetryPointDoc {
  let metadata: Record<string, unknown> | null = null;
  const rawMeta = row.metadata;
  if (typeof rawMeta === 'string' && rawMeta.length > 0) {
    try {
      metadata = JSON.parse(rawMeta) as Record<string, unknown>;
    } catch {
      metadata = { raw: rawMeta };
    }
  }
  return {
    equipmentId: String(row.equipment_id ?? ''),
    pointCode: String(row.point_code ?? ''),
    value: Number(row.value ?? 0),
    quality: String(row.quality ?? 'GOOD'),
    sourceTimestamp: new Date(String(row.time)),
    unit: (row.unit as string | null) ?? null,
    metadata,
  };
}

/** 可 await 的查询对象，兼容 .sort().limit().lean() 链式调用 */
class TelemetryQuery implements PromiseLike<TelemetryPointDoc[]> {
  private limitCount = 100;
  private descending = true;

  constructor(private readonly filter?: TelemetryFilter) {}

  sort(_spec: Record<string, 1 | -1>): this {
    const dir = Object.values(_spec)[0];
    this.descending = dir !== 1;
    return this;
  }

  limit(n: number): this {
    this.limitCount = Math.max(1, Math.min(5000, n));
    return this;
  }

  lean(): this {
    return this;
  }

  private async execute(): Promise<TelemetryPointDoc[]> {
    if (!env.OPENGEMINI_ENABLED) {
      logger.warn('openGemini 未启用，遥测查询返回空集合');
      return [];
    }
    const where = buildWhere(this.filter);
    const order = this.descending ? 'DESC' : 'ASC';
    const sql = `SELECT * FROM "${MEASUREMENT}" ${where} ORDER BY time ${order} LIMIT ${this.limitCount}`;
    const res = await query(sql);
    if (res.error) throw new Error(`遥测查询失败: ${res.error}`);
    const rows = res.series.flatMap((s) =>
      (s.values ?? []).map((values) => {
        const record: Record<string, unknown> = {};
        (s.columns ?? []).forEach((col, i) => {
          record[col] = values[i];
        });
        return record;
      }),
    );
    return rows.map(toDoc);
  }

  then<TResult1 = TelemetryPointDoc[], TResult2 = never>(
    onfulfilled?: ((value: TelemetryPointDoc[]) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): Promise<TResult1 | TResult2> {
    return this.execute().then(onfulfilled, onrejected);
  }
}

export const TelemetryPoint = {
  find(filter?: TelemetryFilter): TelemetryQuery {
    return new TelemetryQuery(filter);
  },

  /** 批量写入时序库；返回实际写入的点数（宽松入参，便于种子脚本直接投喂） */
  async insertMany(docs: Array<Record<string, unknown>>): Promise<number> {
    if (!env.OPENGEMINI_ENABLED) {
      logger.warn('openGemini 未启用，跳过遥测写入');
      return 0;
    }
    const points: PointInput[] = docs.map((raw) => {
      const d = raw as unknown as TelemetryPointDoc;
      return {
        measurement: MEASUREMENT,
        tags: {
          equipment_id: String(d.equipmentId ?? ''),
          point_code: String(d.pointCode ?? ''),
          quality: String(d.quality ?? 'GOOD'),
        },
        fields: {
          value: Number(d.value ?? 0),
          unit: d.unit ?? '',
          metadata: d.metadata ? JSON.stringify(d.metadata) : '',
        },
        timestamp: d.sourceTimestamp instanceof Date ? d.sourceTimestamp : new Date(String(d.sourceTimestamp)),
      };
    });
    const written = await write(points);
    logger.info({ points: written }, '遥测数据已写入 openGemini');
    return written;
  },

  async countDocuments(filter?: TelemetryFilter): Promise<number> {
    if (!env.OPENGEMINI_ENABLED) return 0;
    const where = buildWhere(filter);
    const res = await query(`SELECT COUNT(*) FROM "${MEASUREMENT}" ${where}`);
    if (res.error) throw new Error(`遥测计数失败: ${res.error}`);
    const value = res.series[0]?.values?.[0]?.[1];
    return typeof value === 'number' ? value : 0;
  },

  /** 按时间窗口统计（报表用） */
  async countInWindow(window = '7d'): Promise<number> {
    if (!env.OPENGEMINI_ENABLED) return 0;
    return countPoints(MEASUREMENT, window);
  },

  /** 某设备最近若干条 */
  async latestForEquipment(equipmentId: string, limit = 20): Promise<TelemetryPointDoc[]> {
    if (!env.OPENGEMINI_ENABLED) return [];
    const series = await latestPoints(MEASUREMENT, limit);
    const docs = series.flatMap((s) =>
      (s.values ?? []).map((values) => {
        const record: Record<string, unknown> = {};
        (s.columns ?? []).forEach((col, i) => {
          record[col] = values[i];
        });
        return record;
      }),
    );
    return docs.map(toDoc).filter((d) => d.equipmentId === equipmentId);
  },

  measurement: MEASUREMENT,
};

export { MEASUREMENT as TELEMETRY_MEASUREMENT };
