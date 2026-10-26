import { z } from 'zod';
import { liveApi } from './client';
import type { SessionContext } from '../auth';

export const liveBusinessPages = {
  'UI-005': { title: '派工看板', collections: ['work-orders', 'tasks'] },
  'UI-006': { title: '公路预约与叫号', collections: ['appointments'] },
  'UI-007': { title: '全流程监控', collections: ['equipment', 'work-orders', 'tasks'] },
  'UI-008': { title: '异常处置', collections: ['exceptions'] },
} as const;
export type LiveBusinessPageId = keyof typeof liveBusinessPages;
export type LiveCollection = (typeof liveBusinessPages)[LiveBusinessPageId]['collections'][number];
export function canReadCollection(session: SessionContext, collection: LiveCollection) {
  const resource = collection === 'tasks' || collection === 'equipment' ? 'monitor' : collection;
  return (session.fullAdmin && !session.readOnly) || session.permissions?.includes(`pps:api:${resource}:read`) === true;
}
const row = z.object({ id: z.string().min(1), status: z.string().min(1), updated_at: z.string().min(1) }).catchall(z.unknown());
const pageSchema = z.object({ collection: z.enum(['work-orders','tasks','equipment','appointments','exceptions']),
  items: z.array(row), total: z.number().int().nonnegative(), page: z.number().int().positive(),
  pageSize: z.number().int().positive(), generatedAt: z.string().min(1) });
export type BusinessPage = z.infer<typeof pageSchema>;
export type BusinessRow = BusinessPage['items'][number];
export async function loadBusinessPage(collection: LiveCollection, filters: Record<string, string>, signal?: AbortSignal) {
  const result = pageSchema.parse((await liveApi('/business/' + collection + '?' + new URLSearchParams(filters), { signal })).data);
  if (result.collection !== collection || new Set(result.items.map(r => r.id)).size !== result.items.length
      || result.items.length > result.pageSize || result.items.length > result.total
      || result.page !== Number(filters.page ?? 1)) throw new Error('业务列表响应与当前查询不一致');
  return result;
}
export function equipmentFreshness(row: BusinessRow, generatedAt: string): 'FRESH' | 'STALE' | 'UNKNOWN' {
  const values = [row.last_heartbeat, row.last_telemetry_at].filter((v): v is string => typeof v === 'string')
    .map(Date.parse).filter(Number.isFinite);
  if (!values.length) return 'UNKNOWN';
  const age = Date.parse(generatedAt) - Math.max(...values);
  return Number.isFinite(age) && age >= -30000 && age <= 120000 ? 'FRESH' : 'STALE';
}
