import { apiRequest, postJson, putJson } from './http'
import type { FenceDraft, FencePoint, FenceRecord } from '@/types/fence'
import { mapFence, mapFenceList } from '@/adapters/fence'

/** 电子围栏 API Client —— 对应后端 FenceController（/api/v1/fences）。 */
export interface FenceQuery {
  [key: string]: unknown
  keyword?: string
  status?: string
  kind?: string
}

function qs(params: Record<string, unknown>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    search.set(k, String(v))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export interface FenceSavePayload {
  name: string
  kind: FenceRecord['kind']
  riskLevel?: string
  teams?: string
  startsAt?: string
  endsAt?: string
  polygon: FencePoint[]
  submitReview?: boolean
}

export function draftToPayload(draft: FenceDraft & { polygon: FencePoint[] }): FenceSavePayload {
  return {
    name: draft.name,
    kind: draft.kind,
    riskLevel: draft.riskLevel,
    teams: draft.teams,
    startsAt: draft.startsAt,
    endsAt: draft.endsAt,
    polygon: draft.polygon,
  }
}

export const fenceApi = {
  list: async (query: FenceQuery = {}): Promise<FenceRecord[]> =>
    mapFenceList(await apiRequest(`/fences${qs(query)}`)),

  detail: async (id: string): Promise<FenceRecord> =>
    mapFence(await apiRequest(`/fences/${encodeURIComponent(id)}`)),

  create: async (payload: FenceSavePayload): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences`, payload)),

  update: async (id: string, payload: Partial<FenceSavePayload>): Promise<FenceRecord> =>
    mapFence(await putJson(`/fences/${encodeURIComponent(id)}`, payload)),

  submitReview: async (id: string): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences/${encodeURIComponent(id)}/submit-review`, {})),

  publish: async (id: string, nodeIds?: string[]): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences/${encodeURIComponent(id)}/publish`, { nodeIds })),

  redeliver: async (id: string, nodeId?: string): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences/${encodeURIComponent(id)}/redeliver`, { nodeId })),

  disable: async (id: string): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences/${encodeURIComponent(id)}/disable`, {})),

  simulateMismatch: async (id: string): Promise<FenceRecord> =>
    mapFence(await postJson(`/fences/${encodeURIComponent(id)}/simulate-mismatch`, {})),
}
