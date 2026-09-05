import { apiRequest, postJson } from './http'
import type { AnalyticsDataset, AnalyticsEventItem, AnalyticsPeriod } from '@/types/analytics'
import { mapDataset, mapEventPage, type AnalyticsEventPage } from '@/adapters/analytics'

/** 统计分析 API Client —— 对应后端 AnalyticsController（/api/v1/analytics）。 */
export interface AnalyticsQuery {
  period?: AnalyticsPeriod | string | undefined
  from?: string | undefined
  to?: string | undefined
  area?: string | undefined
  team?: string | undefined
  type?: string | undefined
  level?: string | undefined
  deviceId?: string | undefined
  person?: string | undefined
  drillType?: string | undefined
  page?: number | undefined
  pageSize?: number | undefined
}

function qs<T extends object>(params: T): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    search.set(k, String(v))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export interface SurgeResult {
  active: boolean
  area: string
  dataset: AnalyticsDataset
}

export const analyticsApi = {
  dataset: async (query: AnalyticsQuery = {}): Promise<AnalyticsDataset> =>
    mapDataset(await apiRequest(`/analytics/dataset${qs(query)}`)),

  events: async (query: AnalyticsQuery = {}): Promise<AnalyticsEventPage> =>
    mapEventPage(await apiRequest(`/analytics/events${qs(query)}`)),

  deviceDetail: async (id: string): Promise<{ detail: unknown; events: AnalyticsEventItem[] }> =>
    apiRequest(`/analytics/devices/${encodeURIComponent(id)}`),

  personDetail: async (id: string): Promise<{ detail: unknown; events: AnalyticsEventItem[] }> =>
    apiRequest(`/analytics/persons/${encodeURIComponent(id)}`),

  simulateSurge: async (body: { area?: string; active: boolean }): Promise<SurgeResult> => {
    const dto = await postJson<{ active: boolean; area: string; dataset: unknown }>(
      '/analytics/simulate-surge',
      body,
    )
    return { active: dto.active, area: dto.area, dataset: mapDataset(dto.dataset) }
  },
}
