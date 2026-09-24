/**
 * InfluxDB 行协议（Line Protocol）构造与转义工具。
 *
 * 语法：measurement[,tag=value...] field=value[,field=value...] [timestamp]
 * 时间戳单位由写入请求的 precision 参数决定，本项目统一使用毫秒（ms）。
 */

/** measurement / tag key / tag value / field key 需要转义的字符 */
function escapeToken(value: string): string {
  return value.replace(/([,= ])/g, '\\$1');
}

/** field 的字符串值需要转义双引号与反斜杠 */
function escapeFieldString(value: string): string {
  return value.replace(/(["\\])/g, '\\$1');
}

export type FieldValue = string | number | boolean | null | undefined;

export interface PointInput {
  /** 测量名（对应关系库里的"表"） */
  measurement: string;
  /** 标签：参与索引，取值基数要低（如设备号、工单号） */
  tags?: Record<string, string | number | null | undefined>;
  /** 字段：真实数值，不参与索引（如温度、电流、状态） */
  fields: Record<string, FieldValue>;
  /** 时间戳；缺省取当前时间 */
  timestamp?: Date | number;
}

/**
 * 把布尔值转成 0/1 —— InfluxDB 行协议本身支持 t/f/T/F/true/false，
 * 但为了查询侧统一按数值处理，这里显式落成 0/1。
 */
function formatField(value: Exclude<FieldValue, null | undefined>): string {
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error(`字段值不是有限数字: ${value}`);
    return String(value);
  }
  if (typeof value === 'boolean') return value ? '1' : '0';
  return `"${escapeFieldString(value)}"`;
}

/** 构造一行行协议；fields 全为空时返回 null（InfluxDB 不接受无字段的点） */
export function buildLine(input: PointInput): string | null {
  const { measurement, tags, fields, timestamp } = input;

  const fieldParts: string[] = [];
  for (const [key, raw] of Object.entries(fields)) {
    if (raw === null || raw === undefined) continue;
    if (typeof raw === 'string' && raw.length === 0) continue;
    fieldParts.push(`${escapeToken(key)}=${formatField(raw)}`);
  }
  if (fieldParts.length === 0) return null;

  let line = escapeToken(measurement);

  if (tags) {
    const tagParts: string[] = [];
    for (const [key, raw] of Object.entries(tags)) {
      if (raw === null || raw === undefined) continue;
      const value = String(raw);
      if (value.length === 0) continue;
      tagParts.push(`${escapeToken(key)}=${escapeToken(value)}`);
    }
    // 行协议要求 tag key 按字典序排列，否则写入效率下降
    tagParts.sort();
    if (tagParts.length > 0) line += `,${tagParts.join(',')}`;
  }

  line += ` ${fieldParts.join(',')}`;

  if (timestamp !== undefined) {
    const ms = timestamp instanceof Date ? timestamp.getTime() : timestamp;
    if (!Number.isFinite(ms)) throw new Error(`时间戳非法: ${String(timestamp)}`);
    line += ` ${Math.trunc(ms)}`;
  }

  return line;
}

/** 批量构造；自动丢弃无有效字段的点 */
export function buildLines(points: PointInput[]): string[] {
  const out: string[] = [];
  for (const point of points) {
    const line = buildLine(point);
    if (line) out.push(line);
  }
  return out;
}
