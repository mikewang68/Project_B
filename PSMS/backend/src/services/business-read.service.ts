import { z } from 'zod';
import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';

import { businessCollections, type BusinessCollection } from '../lib/businessCatalog.js';
export { isBusinessCollection } from '../lib/businessCatalog.js';

const filters = z.object({
  page: z.coerce.number().int().min(1).max(100000).default(1),
  pageSize: z.coerce.number().int().min(1).max(200).default(50),
  workArea: z.string().regex(/^AREA-[A-Z0-9_-]+$/).optional(),
  status: z.string().regex(/^[A-Z_]{1,40}$/).optional(),
  planId: z.string().min(1).max(160).optional(),
}).strict();

export function businessReadQuery(collection: BusinessCollection, scopes: readonly string[], input: unknown) {
  const parsed = filters.safeParse(input);
  if (!parsed.success) throw new AppError(400, 'INVALID_BUSINESS_FILTER', '查询条件或分页格式无效');
  const f = parsed.data, spec = businessCollections[collection];
  const global = scopes.includes('*') || scopes.includes('GLOBAL');
  const areas = scopes.filter(s => /^AREA-[A-Z0-9_-]+$/.test(s));
  if (!global && (!spec.area || !areas.length))
    throw new AppError(403, 'DATA_SCOPE_DENIED', '该数据尚不支持当前作业区权限');
  if (f.workArea && (!spec.area || (!global && !areas.includes(f.workArea))))
    throw new AppError(403, 'DATA_SCOPE_DENIED', '无权读取指定作业区');
  if (f.planId && !['work-orders', 'tasks'].includes(collection))
    throw new AppError(400, 'INVALID_BUSINESS_FILTER', '该列表不支持计划筛选');
  const values: unknown[] = [], where = ['valid=1'];
  const add = (sql: string, value: unknown) => { values.push(value); where.push(sql.replace('?', '$' + values.length)); };
  if (f.workArea) add('work_area=?', f.workArea);
  else if (!global) add('work_area=ANY(?::text[])', areas);
  if (f.status) add('status=?', f.status);
  if (f.planId) add('plan_id=?', f.planId);
  return { table: spec.table, columns: spec.columns, where: where.join(' AND '), values,
    page: f.page, pageSize: f.pageSize, global };
}

export async function readBusinessPage(collection: BusinessCollection, scopes: readonly string[], input: unknown,
    connect = connectClient) {
  const q = businessReadQuery(collection, scopes, input), db = await connect();
  try {
    await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const total = (await db.query(`SELECT count(*)::int AS total FROM public.${q.table} WHERE ${q.where}`, q.values)).rows[0].total;
    const items = (await db.query(`SELECT ${q.columns},updated_at::text AS record_version FROM public.${q.table} WHERE ${q.where}
      ORDER BY updated_at DESC,id LIMIT $${q.values.length + 1} OFFSET $${q.values.length + 2}`,
      [...q.values, q.pageSize, (q.page - 1) * q.pageSize])).rows;
    await db.query('COMMIT');
    return { collection, items, total, page: q.page, pageSize: q.pageSize, generatedAt: new Date().toISOString() };
  } catch (error) {
    try { await db.query('ROLLBACK'); } catch { /* preserve original failure */ }
    throw error;
  } finally { db.release(); }
}
