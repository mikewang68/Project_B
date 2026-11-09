import { AsyncLocalStorage } from 'node:async_hooks';
import { postgresFilter, requireAction, validateContext, type Action,
  type UserContext } from '@bproject/iam-context-client';
import { AppError } from './errorHandler.js';

const current = new AsyncLocalStorage<UserContext>();
const regional = new Set(['plans', 'work_orders', 'tasks', 'equipment', 'exceptions',
  'interlocks', 'interlock_requests', 'appointments', 'offline_packets', 'config_versions', 'audit_logs']);
export const centralContext = () => current.getStore();
/** A server-generated audit may be written for a read-only operation. */
export function centralAuditScope(): { projectId: string; regionId: string; subsystemCode: string } {
  const c=current.getStore();
  return c?.scopeMode==='SCOPED' && c.activeScope.length===1
    ? {...c.activeScope[0]}
    : {projectId:'LEGACY',regionId:'UNASSIGNED',subsystemCode:'PSMS'};
}
export function runWithCentralScope<T>(context: UserContext, work: () => T): T {
  return current.run(context, work);
}
/** Only call with data returned by the authenticated IAM service request. */
export function centralAssertion(value: unknown, subject: string): UserContext | undefined {
  if (value === undefined || value === null) return undefined; // Unactivated legacy account.
  try { return validateContext(value, subject, 'psms'); }
  catch { throw new AppError(403, 'INVALID_CENTRAL_CONTEXT', '中央上下文无效'); }
}
/** Explicit predicates, never parse/rewrite caller SQL. Existing AREA-* rules remain in force. */
export function centralPredicate(table: string, offset = 0): { text: string; values: unknown[] } {
  const c = centralContext();
  if (!c) return { text: '', values: [] };
  if (table === 'users') return { text: '', values: [] }; // Global identity; only self-info route is adapted.
  if (!regional.has(table)) throw new AppError(403, 'CENTRAL_RESOURCE_NOT_ADAPTED', '该资源尚未接入中央数据范围');
  const f = postgresFilter({ condition: c.my_info, template: c.my_info_template, parameters: c.scope_params });
  return { text: f.text.replace(/\$(\d+)/g, (_, n: string) => '$' + (Number(n) + offset)),
    values: [...f.values] };
}
export function scopedInsert(table: string, doc: Record<string, unknown>): Record<string, unknown> {
  const c = centralContext();
  if (!c) return doc;
  if (!regional.has(table)) throw new AppError(403, 'CENTRAL_RESOURCE_NOT_ADAPTED', '该资源尚未接入中央数据范围');
  if (c.readOnly || c.scopeMode !== 'SCOPED' || c.activeScope.length !== 1)
    throw new AppError(403, 'CONCRETE_WRITE_SCOPE_REQUIRED', '写入前须选择一个明确授权范围');
  const t = c.activeScope[0], expected = { projectId: t.projectId, regionId: t.regionId, subsystemCode: t.subsystemCode };
  for (const [field, value] of Object.entries(expected))
    if (doc[field] !== undefined && doc[field] !== value)
      throw new AppError(403, 'CENTRAL_SCOPE_TAMPERING', '不能修改中央归属');
  return { ...doc, ...expected };
}
export function checkScopeUpdate(doc: Record<string, unknown>): void {
  if (!centralContext()) return;
  if (['projectId', 'regionId', 'subsystemCode', 'project_id', 'region_id', 'subsystem_code']
    .some(k => Object.hasOwn(doc, k)))
    throw new AppError(403, 'CENTRAL_SCOPE_IMMUTABLE', '业务更新不能迁移归属');
}
export function requireCentralRow(row: { project_id?: unknown; region_id?: unknown; subsystem_code?: unknown }): void {
  const c=centralContext();
  if(!c||c.scopeMode==='GLOBAL')return;
  if(c.scopeMode!=='SCOPED'||!c.activeScope.some(scope=>scope.projectId===row.project_id
      &&scope.regionId===row.region_id&&scope.subsystemCode===row.subsystem_code))
    throw new AppError(404,'OBJECT_NOT_FOUND','对象不存在或不在授权范围');
}
/** Route and action mapping uses the existing PSMS menu catalog. Unsupported paths fail closed. */
export function centralRoute(context: UserContext, method: string, path: string, action?: unknown): void {
  if (path === '/api/auth/me') return;
  const read = ['GET', 'HEAD'].includes(method);
  // Nested plan tools touch more than one resource; both grants are required.
  const nested = /^\/api\/plans\/[^/]+\/(tasks|recommendation)$/.exec(path)?.[1];
  if (nested) {
    try {
      requireAction(context, 'PSMS:ui-002', 'VIEW');
      requireAction(context, 'PSMS:ui-007', 'VIEW');
      if (nested === 'tasks' && method === 'POST') {
        requireAction(context, 'PSMS:ui-007', 'ADD');
        requireAction(context, 'PSMS:ui-007', 'EDIT');
      } else if (!(nested === 'tasks' && read || nested === 'recommendation' && method === 'POST')) {
        throw new Error('Unsupported method');
      }
      return;
    } catch { throw new AppError(403, 'CENTRAL_ACTION_DENIED', '中央权限未授予关联计划和任务动作'); }
  }
  let menu: string | undefined;
  if (path === '/api/overview') menu = 'PSMS:ui-001';
  else if (/^\/api\/governance\/reports(?:\/export)?\/?$/.test(path)) menu = 'PSMS:ui-011';
  else if (/^\/api\/governance\/(interlocks|interlock-requests|offline|settings|audit-logs)(?:\/[^/]+)?\/?$/.test(path)) {
    const kind=path.split('/')[3];
    menu=({'interlocks':'PSMS:ui-009','interlock-requests':'PSMS:ui-009',offline:'PSMS:ui-010',
      settings:'PSMS:ui-012','audit-logs':'PSMS:ui-013'} as Record<string,string>)[kind];
  }
  else if (/^\/api\/plans(?:\/[^/]+)?\/?$/.test(path)) menu = 'PSMS:ui-002';
  else if (/^\/api\/plans\/[^/]+\/confirm$/.test(path)) menu = 'PSMS:ui-002';
  else if (/^\/api\/business\/work-orders\/[^/]+\/wms-inbound(?:\/actions)?$/.test(path)) menu = 'PSMS:ui-005';
  else if (/^\/api\/business\/work-orders\/ai-draft\/?$/.test(path)) menu = 'PSMS:ui-005';
  else if (/^\/api\/business\/work-orders\/[^/]+\/confirm\/?$/.test(path)) menu = 'PSMS:ui-005';
  else if (/^\/api\/business\/work-orders\/[^/]+\/?$/.test(path)) menu = 'PSMS:ui-005';
  else {
    const collection = /^\/api\/business\/(work-orders|tasks|equipment|exceptions|appointments)(?:\/[^/]+\/actions)?$/.exec(path)?.[1];
    menu = collection && ({ 'work-orders': 'PSMS:ui-005', tasks: 'PSMS:ui-007',
      equipment: 'PSMS:ui-007', exceptions: 'PSMS:ui-008', appointments: 'PSMS:ui-006' } as Record<string, string>)[collection];
  }
  if (!menu) throw new AppError(403, 'CENTRAL_ROUTE_NOT_ADAPTED', '该接口尚未完成中央权限适配');
  let operation: Action = read ? 'VIEW' : method === 'DELETE' ? 'DELETE' : method === 'POST' ? 'ADD' : 'EDIT';
  if (path.endsWith('/reports/export')) operation = 'EXPORT';
  else if (/^\/api\/business\/work-orders\/[^/]+\/wms-inbound\/actions$/.test(path) && !read)
    operation = action === 'verify' ? 'CONFIRM' : 'EDIT';
  else if(path.startsWith('/api/governance/')&&!read) {
    const kind=path.split('/')[3];
    if(kind==='offline')operation=action==='sync'?'UPLOAD':'CONFIRM';
    else if(kind==='settings')operation=path==='/api/governance/settings'?'ADD':action==='edit'?'EDIT':'CONFIRM';
    else if(kind==='interlocks'||kind==='interlock-requests')
      operation=['requestOverride','requestReset'].includes(String(action))?'ADD':action==='approve'?'CONFIRM':'EDIT';
  }
  else if (path === '/api/plans/import' || path === '/api/plans/import-file') operation = 'UPLOAD';
  else if (path.endsWith('/confirm') || ['approve', 'complete', 'close'].includes(String(action))) operation = 'CONFIRM';
  else if (path.endsWith('/actions')) operation = 'EDIT';
  try { requireAction(context, menu, operation); }
  catch { throw new AppError(403, 'CENTRAL_ACTION_DENIED', '中央权限未授予该动作'); }
}
