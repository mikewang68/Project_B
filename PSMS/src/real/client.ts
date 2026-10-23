import { z } from 'zod';
import { do001Schema } from '../contracts';
import { withAppBasePath } from '../runtime/appBasePath';

let csrf = '';
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
  if (!response.ok || result.ok !== true) throw new Error(result.message || `业务接口返回 ${response.status}`);
  return result;
}

export function planProjection(raw: Record<string, unknown>) {
  const supplements = (raw.supplements ?? {}) as Record<string, unknown>;
  return do001Schema.parse({ id: raw._id, planBatchNo: raw.planBatchNo, trainNo: raw.trainNo,
    arrivalDepartureTime: raw.arriveTime, workArea: z.string().min(1).parse(raw.workArea),
    trackNo: raw.trackNo, cargoType: raw.cargoType, status: raw.status,
    sourceSystem: supplements.sourceSystem ?? '', sourceTime: raw.createdAt, missingFields: [], conflicts: [],
    version: new Date(String(raw.updatedAt)).getTime(), createdAt: raw.createdAt, updatedAt: raw.updatedAt });
}

export async function loadLivePlans() {
  const result = await liveApi('/plans?pageSize=200');
  const plans = (result.data.items as Record<string, unknown>[]).map(planProjection);
  if (result.data.total > plans.length) throw new Error('当前台账超过单页上限，请接入服务端分页后继续；未截断展示全部数据');
  return plans;
}
