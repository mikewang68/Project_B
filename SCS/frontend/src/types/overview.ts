export type MarkerState = 'normal' | 'warning' | 'danger' | 'offline'
export type AlertLevel = '紧急' | '严重' | '一般'

export interface PersonnelPoint {
  id: string
  name: string
  team: string
  status: '在线' | '离线'
  battery: number
  area: string
  risk: '正常' | '关注' | '高风险'
  state: MarkerState
  x: number
  y: number
  /** 风险状态是否来自真实未关闭 Alert（LIVE RISK OVERLAY） */
  liveOverlay?: boolean
}

export interface EquipmentPoint {
  id: string
  name: string
  type: 'crane' | 'tipper' | 'vehicle' | 'camera' | 'device'
  state: MarkerState
  x: number
  y: number
  /** 风险状态是否来自真实未关闭 Alert（LIVE RISK OVERLAY） */
  liveOverlay?: boolean
}

export interface OverviewAlert {
  id: string
  title: string
  level: AlertLevel
  summary: string
  time: string
  area: string
  objectName: string
  status: string
  timeline: Array<{ time: string; title: string; detail: string }>
}

// ---------- 后端 Overview DTO（adapter 转换前的原始结构） ----------

export interface OverviewSummaryDto {
  onDuty: number
  deviceOnline: number
  deviceTotal: number
  activeAlerts: number
  processingAlerts: number
  urgentAlerts: number
  severeAlerts: number
  pendingAi: number
  riskyDevices: number
  onDutyDemo: boolean
  deviceDemo: boolean
  riskyDevicesDemo: boolean
}

export interface OverviewMapDto {
  baseDemo: boolean
  liveOverlay: boolean
  people: PersonnelPoint[]
  equipment: EquipmentPoint[]
  fences: Array<{
    id: string
    name: string
    tone: string
    area: string
    polygon: Array<{ x: number; y: number }>
  }>
}

export interface OverviewTrendDto {
  demo: boolean
  points: Array<{ label: string; total: number; high: number }>
}

export interface OverviewDistributionDto {
  items: Array<{ type: string; count: number }>
}
