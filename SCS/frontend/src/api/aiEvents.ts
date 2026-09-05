import { apiRequest, postJson } from './http'
import type { AiEvent } from '@/types/ai'
import { mapAiEvent, mapAiEventPage, mapCameraInfo, type AiEventPageDto, type CameraInfo } from '@/adapters/aiEvent'

/**
 * AI 违规识别 API Client —— 严格对应后端 AiEventController（/api/v1/ai-events、/cameras）。
 * 写操作经 http.ts 自动携带 X-Trace-Id / Idempotency-Key；返回统一经 adapters/aiEvent 规范化。
 */

export interface AiListParams {
  keyword?: string | undefined
  type?: string | undefined
  area?: string | undefined
  camera?: string | undefined
  status?: string | undefined
  risk?: string | undefined
  confidence?: string | undefined
  timeBucket?: string | undefined
  page?: number | undefined
  pageSize?: number | undefined
}

function queryString(params: AiListParams): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '' || value === '全部') return
    search.set(key, String(value))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

/** 前端置信度筛选值 → 后端档位（high/mid/low） */
export function confidenceParam(value: string): string | undefined {
  if (value === '高（≥85%）') return 'high'
  if (value === '中（70-84%）') return 'mid'
  if (value === '低') return 'low'
  return undefined
}

/** 前端时间筛选值 → 后端时段（1h/2h/earlier） */
export function timeBucketParam(value: string): string | undefined {
  if (value === '近1小时') return '1h'
  if (value === '近2小时') return '2h'
  if (value === '更早') return 'earlier'
  return undefined
}

export const aiEventApi = {
  getAiEvents: async (params: AiListParams = {}): Promise<AiEventPageDto> =>
    mapAiEventPage(await apiRequest(`/ai-events${queryString(params)}`)),

  getAiEventDetail: async (id: string): Promise<AiEvent> =>
    mapAiEvent(await apiRequest(`/ai-events/${encodeURIComponent(id)}`)),

  confirmAiEvent: async (id: string, reviewer?: string): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/confirm`, { reviewer })),

  markFalsePositive: async (id: string, reason: string, reviewer?: string, note?: string): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/false-positive`, { reason, reviewer, note })),

  markUncertain: async (id: string, reviewer?: string): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/uncertain`, { reviewer })),

  assignAiEvent: async (
    id: string,
    payload: { assignee: string; priority: '普通' | '紧急'; note?: string; reviewer?: string },
  ): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/assign`, payload)),

  processAiEvent: async (id: string, operator?: string): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/process`, { operator })),

  closeAiEvent: async (id: string, operator?: string): Promise<AiEvent> =>
    mapAiEvent(await postJson(`/ai-events/${encodeURIComponent(id)}/close`, { operator })),

  simulate: async (kind: 'new' | 'low-confidence' | 'camera-fault' = 'new'): Promise<AiEvent> =>
    mapAiEvent(await postJson('/ai-events/simulate', { kind })),

  getCameras: async (): Promise<CameraInfo[]> =>
    (await apiRequest<unknown[]>('/cameras')).map(mapCameraInfo),
}
