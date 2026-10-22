import { cargoTypes, do001Schema, type Plan } from '../../contracts';
import { z } from 'zod';

const aliases: Record<string, string> = { '计划批次': 'planBatchNo', '车次': 'trainNo', '到达时间': 'arrivalTime', '离开时间': 'departureTime', '股道': 'trackNo', '货类': 'cargoType' };
const required = ['planBatchNo', 'trainNo', 'arrivalTime', 'departureTime', 'trackNo', 'cargoType'];
export const csvTemplate = '\ufeff计划批次,车次,到达时间,离开时间,股道,货类\r\nIMPORT-20261012-01,T88001,2026-10-12T08:00:00+08:00,2026-10-12T10:00:00+08:00,T1,FLY_ASH\r\n';

/** RFC 4180: escaped quotes, quoted commas/newlines, CRLF and UTF-8 BOM. */
export function readCsv(input: string): string[][] {
  const text = input.replace(/^\ufeff/, '');
  const rows: string[][] = [];
  let row: string[] = [], cell = '', quoted = false, closed = false;
  const finish = () => { row.push(cell.trim()); cell = ''; closed = false; };
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (quoted) {
      if (char === '"' && text[i + 1] === '"') { cell += '"'; i++; }
      else if (char === '"') { quoted = false; closed = true; }
      else cell += char;
    } else if (char === '"') {
      if (cell || closed) throw new Error(`第 ${rows.length + 1} 行引号位置不合法`);
      quoted = true;
    } else if (char === ',') finish();
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[i + 1] === '\n') i++;
      finish(); if (row.some(Boolean)) rows.push(row); row = [];
    } else {
      if (closed && char.trim()) throw new Error(`第 ${rows.length + 1} 行引号后存在额外字符`);
      if (!closed) cell += char;
    }
  }
  if (quoted) throw new Error('CSV 引号未闭合');
  finish(); if (row.some(Boolean)) rows.push(row);
  return rows;
}

export function parsePlanCsv(input: string, existing: readonly Plan[] = []): Plan[] {
  if (new TextEncoder().encode(input).length > 2 * 1024 * 1024) throw new Error('CSV 文件不能超过 2 MB');
  const [rawHeader, ...rows] = readCsv(input);
  if (!rawHeader || !rows.length) throw new Error('CSV 必须包含表头和至少一条计划');
  if (rows.length > 1000) throw new Error('单次最多导入 1000 条计划');
  const header = rawHeader.map((name) => aliases[name] ?? name);
  if (new Set(header).size !== header.length) throw new Error('CSV 表头存在重复列');
  const missing = required.filter((field) => !header.includes(field));
  if (missing.length) throw new Error(`CSV 缺少列：${missing.join('、')}`);
  const batches = new Set(existing.map((plan) => plan.planBatchNo));
  const now = new Date().toISOString();
  return rows.map((row, index) => {
    const label = `第 ${index + 2} 行`;
    if (row.length !== header.length) throw new Error(`${label}列数与表头不一致`);
    const value = Object.fromEntries(header.map((field, col) => [field, row[col]]));
    if (required.some((field) => !value[field])) throw new Error(`${label}必填字段为空`);
    if (batches.has(value.planBatchNo)) throw new Error(`${label}计划批次重复：${value.planBatchNo}`);
    batches.add(value.planBatchNo);
    if (!cargoTypes.includes(value.cargoType as typeof cargoTypes[number])) throw new Error(`${label}货类应为 ${cargoTypes.join('、')}`);
    const times = [value.arrivalTime, value.departureTime];
    if (times.some((time) => !z.iso.datetime({ offset: true }).safeParse(time).success || !Number.isFinite(Date.parse(time)))) throw new Error(`${label}时间应使用包含时区的 ISO 格式，如 2026-10-12T08:00:00+08:00`);
    if (Date.parse(times[1]) <= Date.parse(times[0])) throw new Error(`${label}离开时间必须晚于到达时间`);
    return do001Schema.parse({ id: `PLAN-CSV-${crypto.randomUUID()}`, planBatchNo: value.planBatchNo, trainNo: value.trainNo,
      arrivalDepartureTime: times.join('/'), trackNo: value.trackNo, cargoType: value.cargoType, status: 'PENDING_CONFIRM',
      sourceSystem: 'MANUAL_CSV', sourceTime: value.arrivalTime, missingFields: [], conflicts: [], version: 1, createdAt: now, updatedAt: now });
  });
}
