import { z } from 'zod';
import { do001Schema } from '../contracts';
import { withAppBasePath } from '../runtime/appBasePath';

let csrf = '';
export class LiveApiError extends Error {
  constructor(message: string, public readonly status: number) { super(message); }
}
export async function liveApi(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers); headers.set('Accept', 'application/json');
  if (init.body) headers.set('Content-Type', 'application/json');
  if (init.method && !['GET', 'HEAD', 'OPTIONS'].includes(init.method.toUpperCase())) {
    if (!csrf) { const result = await liveApi('/auth/sso/csrf'); csrf = z.string().min(1).parse(result.data.csrf); }
    headers.set('X-PSMS-CSRF', csrf);
  }
  const response = await fetch(withAppBasePath('/api' + path), { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  if (response.status === 401) {
    csrf = ''; window.location.replace('/sso/start?page=PSMS%3Aui-002'); throw new Error('正在恢复统一登录');
  }
  const result = await response.json();
  if (!response.ok || result.ok !== true) throw new LiveApiError(result.message || `业务接口返回 ${response.status}`, response.status);
  return result;
}

export function planProjection(raw: Record<string, unknown>) {
  const supplements = (raw.supplements ?? {}) as Record<string, unknown>;
  const instant = new Date(String(raw.arriveTime));
  if (!Number.isFinite(instant.getTime())) throw new Error('计划到达时间格式无效');
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',
    day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(instant).map(p=>[p.type,p.value]));
  const arrival = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`;
  return do001Schema.parse({ id: raw._id, planBatchNo: raw.planBatchNo, trainNo: raw.trainNo,
    arrivalDepartureTime: arrival, workArea: z.string().min(1).parse(raw.workArea),
    trackNo: raw.trackNo, cargoType: raw.cargoType, status: raw.status,
    sourceSystem: supplements.sourceSystem ?? '', sourceTime: raw.createdAt, missingFields: [], conflicts: [],
    version: new Date(String(raw.updatedAt)).getTime(), createdAt: raw.createdAt, updatedAt: raw.updatedAt });
}

export async function loadLivePlans() {
  const plans: ReturnType<typeof planProjection>[] = [];
  const seen = new Set<string>();
  for (let page = 1; ; page++) {
    const result = await liveApi(`/plans?pageSize=200&page=${page}`);
    const items = z.array(z.record(z.string(), z.unknown())).parse(result.data.items).map(planProjection);
    const total = z.number().int().nonnegative().parse(result.data.total);
    for (const item of items) {
      if (seen.has(item.id)) throw new Error('台账在分页期间发生变化，请刷新后重试');
      seen.add(item.id); plans.push(item);
    }
    if (plans.length === total) break;
    if (!items.length || plans.length > total || plans.length >= 20000)
      throw new Error('台账分页结果不完整，请缩小查询范围后重试');
  }
  return plans;
}

const metric = z.number().nonnegative();
const overviewSchema = z.object({ workArea: z.string(), date: z.string(), generatedAt: z.string(),
  kpis: z.object({ totalPlans: metric, pendingConfirmPlans: metric, confirmedPlans: metric, blockedPlans: metric,
    completedWorkOrders: metric, waitingVehicles: metric.nullable(), availableResourceRate: metric.nullable(), openExceptions: metric }),
  availability: z.object({ waitingVehicles: z.string(), availableResourceRate: z.string(), yardLayout: z.string() }),
  planRange: z.object({ earliest: z.string().nullable(), latest: z.string().nullable(), total: metric }),
  risks: z.array(z.object({id:z.string(),source:z.enum(['EXCEPTION','INTERLOCK','OFFLINE']),type:z.string(),status:z.string(),level:z.string()})),
  waitingVehicleScope: z.string().nullable() });
export async function loadLiveOverview(date: string, workArea: string) {
  return overviewSchema.parse((await liveApi('/overview?' + new URLSearchParams({date, workArea}))).data);
}
