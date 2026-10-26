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
const row = z.object({ id: z.string().min(1), status: z.string().min(1), updated_at: z.string().min(1),row_version:z.string().min(1) }).catchall(z.unknown());
const pageSchema = z.object({ collection: z.enum(['work-orders','tasks','equipment','appointments','exceptions']),
  items: z.array(row), total: z.number().int().nonnegative(), page: z.number().int().positive(),
  pageSize: z.number().int().positive(), generatedAt: z.string().min(1) });
export type BusinessPage = z.infer<typeof pageSchema>;
export type BusinessRow = BusinessPage['items'][number];
export type BusinessAction = {code:string;label:string;permission:string;equipment?:boolean};
export function businessActions(collection:LiveCollection,status:string):BusinessAction[]{
  const entries:Record<string,Array<[string,string,string[],string,boolean?]>>={
    'work-orders':[['assign','分配设备',['DRAFT','READY'],'write',true],['accept','接单',['ASSIGNED'],'execute'],
      ['start','开始作业',['ACCEPTED','PAUSED'],'execute'],['pause','暂停',['IN_PROGRESS'],'execute'],
      ['complete','完成工单',['IN_PROGRESS'],'execute'],['cancel','取消工单',['DRAFT','READY','ASSIGNED','ACCEPTED','PAUSED'],'execute']],
    appointments:[['approve','审核通过',['PENDING'],'approve'],['checkin','签到排队',['APPROVED','CHECKED_IN'],'write'],
      ['call','叫号',['QUEUED'],'execute'],['enter','确认入场',['CALLED'],'execute'],['complete','完成离场',['ON_SITE'],'execute']],
    exceptions:[['acknowledge','认领异常',['OPEN'],'write'],['resolve','提交解决结果',['ACKNOWLEDGED','IN_PROGRESS'],'write'],
      ['close','关闭异常',['RESOLVED'],'write']],
  };
  return(entries[collection]??[]).filter(([, ,from])=>from.includes(status))
    .map(([code,label,,operation,equipment])=>({code,label,permission:`pps:api:${collection}:${operation}`,equipment}));
}
export function canRunBusinessAction(session:SessionContext,action:BusinessAction):boolean{
  return !session.readOnly && (session.fullAdmin===true || session.permissions?.includes(action.permission)===true);
}
export async function runBusinessAction(collection:LiveCollection,row:BusinessRow,action:BusinessAction,values:{reason:string;equipmentId?:string}){
  return liveApi('/business/'+collection+'/'+encodeURIComponent(row.id)+'/actions',{
    method:'POST',body:JSON.stringify({...values,action:action.code,expectedVersion:row.row_version}),
  });
}
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
