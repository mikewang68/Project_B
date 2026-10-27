import { AppError } from '../middleware/errorHandler.js';

export function beijingToday(now = new Date()): string {
  return new Date(now.getTime() + 8 * 3600_000).toISOString().slice(0, 10);
}
/** Inclusive Beijing calendar days, represented as an exclusive upper bound. */
export function reportPeriod(start: string, end: string) {
  const parse = (value: string) => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new AppError(400, 'INVALID_DATE', '日期须为 YYYY-MM-DD');
    const utc = new Date(value + 'T00:00:00Z');
    if (!Number.isFinite(utc.getTime()) || utc.toISOString().slice(0, 10) !== value)
      throw new AppError(400, 'INVALID_DATE', '日期无效');
    return utc.getTime() - 8 * 3600_000;
  };
  const from = parse(start), until = parse(end) + 24 * 3600_000;
  if (until <= from || until - from > 366 * 24 * 3600_000)
    throw new AppError(400, 'INVALID_DATE_RANGE', '报表时间范围须为 1 至 366 天');
  return { from: new Date(from), until: new Date(until), timeZone: 'Asia/Shanghai' };
}
