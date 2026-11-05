import { postgresFilter, type AuthorizedFilter } from '@bproject/iam-context-client';
import { connectClient } from '../db/openGaussClient.js';
import { env } from '../config/env.js';
import { AppError } from '../middleware/errorHandler.js';

/** Resource, projection and owner are fixed here, never supplied as SQL by a consumer. */
export const sharedResources = {
  plans: { key: 'PSMS:shared:plans', table: 'plans',
    columns: 'id,plan_batch_no,train_no,cargo_type,arrive_time,work_area,status,priority,project_id,region_id,subsystem_code,updated_at' },
  'work-orders': { key: 'PSMS:shared:work-orders', table: 'work_orders',
    columns: 'id,plan_id,plan_batch_no,work_area,equipment_id,equipment_name,status,order_type,priority,project_id,region_id,subsystem_code,updated_at' },
} as const;
export type SharedResource = keyof typeof sharedResources;
export const sharedMenus = { wms: 'WMS:stockin', ehms: 'EHMS:workorders', scs: 'SCS:rules' } as const;

export function sharedQuery(resource: SharedResource, filter: AuthorizedFilter, id?: string) {
  const descriptor = sharedResources[resource];
  if (!descriptor || !/^[a-zA-Z_][a-zA-Z_0-9]{0,62}$/.test(env.OPENGAUSS_SCHEMA))
    throw new AppError(400, 'INVALID_SHARED_RESOURCE', '共享资源无效');
  if (id !== undefined && !/^[-_a-zA-Z0-9]{1,128}$/.test(id))
    throw new AppError(400, 'INVALID_SHARED_ID', '共享记录编号无效');
  const authorized = postgresFilter(filter);
  const values: unknown[] = [...authorized.values];
  // Shared filters omit the consumer's subsystem, while the provider still fixes its own owner.
  let where = " WHERE (subsystem_code = 'PSMS')" + authorized.text;
  if (id !== undefined) { values.push(id); where += ` AND id=$${values.length}`; }
  return { descriptor, table: `"${env.OPENGAUSS_SCHEMA}"."${descriptor.table}"`, where, values };
}

export async function readShared(resource: SharedResource, filter: AuthorizedFilter, page: number, pageSize: number, id?: string) {
  const q = sharedQuery(resource, filter, id);
  const client = await connectClient();
  try {
    await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const count = await client.query(`SELECT count(*)::int AS total FROM ${q.table}${q.where}`, q.values);
    const rows = await client.query(`SELECT ${q.descriptor.columns} FROM ${q.table}${q.where}
      ORDER BY id LIMIT $${q.values.length + 1} OFFSET $${q.values.length + 2}`,
    [...q.values, pageSize, (page - 1) * pageSize]);
    if (id !== undefined && rows.rowCount !== 1)
      throw new AppError(404, 'SHARED_RECORD_NOT_FOUND', '记录不存在或未获共享授权');
    await client.query('COMMIT');
    return { sourceApp: 'PSMS', resourceKey: q.descriptor.key, total: Number(count.rows[0].total),
      page, pageSize, items: rows.rows };
  } catch (error) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally { client.release(); }
}
