import { apiRequest, postJson } from './http'
import {
  mapDistribution,
  mapFeed,
  mapFeedItem,
  mapOverviewMap,
  mapSummary,
  mapTrend,
} from '@/adapters/overview'
import type {
  OverviewAlert,
  OverviewDistributionDto,
  OverviewMapDto,
  OverviewSummaryDto,
  OverviewTrendDto,
} from '@/types/overview'

/**
 * 安全态势首页 API Client —— 对应后端 OverviewController（/api/v1/overview）。
 * 首页是聚合 Projection，不建独立业务库；告警口径来自 Alert / AiEvent 权威源。
 */
export const overviewApi = {
  getSummary: async (): Promise<OverviewSummaryDto> =>
    mapSummary(await apiRequest('/overview/summary')),

  getMap: async (): Promise<OverviewMapDto> =>
    mapOverviewMap(await apiRequest('/overview/map')),

  getFeed: async (limit = 10): Promise<OverviewAlert[]> =>
    mapFeed(await apiRequest(`/overview/alerts/feed?limit=${limit}`)),

  getAlertDetail: async (id: string): Promise<OverviewAlert> =>
    mapFeedItem(await apiRequest(`/overview/alerts/${encodeURIComponent(id)}`)),

  getTrend: async (range: '7d' | '24h' = '7d'): Promise<OverviewTrendDto> =>
    mapTrend(await apiRequest(`/overview/risk-trend?range=${range}`)),

  getDistribution: async (): Promise<OverviewDistributionDto> =>
    mapDistribution(await apiRequest('/overview/risk-distribution')),

  /** SIMULATED 风险演示开关：后端创建/关闭固定演示告警，五端经 WS 同步 */
  simulateRisk: (active: boolean) =>
    postJson<{ active: boolean }>('/overview/simulate-risk', { active }),
}
