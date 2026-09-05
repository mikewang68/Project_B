import { apiRequest } from './http'

/** 安全大屏只读投影（聚合自 Alert 权威源；设备/在岗为后端 Demo 聚合并带 demo 标记） */
export interface ScreenDeviceHealth {
  name: string
  online: number
  total: number
  demo: boolean
}
export interface ScreenRiskTypeCount {
  type: string
  count: number
}
export interface ScreenTrendPoint {
  label: string
  total: number
  highRisk: number
  demo: boolean
}
export interface ScreenOverview {
  onDuty: number
  deviceOnline: number
  deviceTotal: number
  todayAlerts: number
  processingAlerts: number
  urgentAlerts: number
  severeAlerts: number
  onDutyDemo: boolean
  deviceDemo: boolean
  deviceHealth: ScreenDeviceHealth[]
  riskTypeDistribution: ScreenRiskTypeCount[]
  riskTrend: ScreenTrendPoint[]
}
export interface ScreenFeedItem {
  id: string
  title: string
  level: string
  area: string
  objectName: string
  status: string
  eventType: string
  source: string
  occurredAt: string | null
  summary: string
}
export interface ScreenCritical {
  id: string
  title: string
  level: string
  area: string
  objectName: string
  status: string
  assignee: string
  durationSec: number | null
  occurredAt: string | null
  summary: string
}

export const screenApi = {
  getOverview: () => apiRequest<ScreenOverview>('/screen/overview'),
  getFeed: (limit = 12) => apiRequest<ScreenFeedItem[]>(`/screen/alerts/feed?limit=${limit}`),
  getCritical: () => apiRequest<ScreenCritical | null>('/screen/critical'),
}
