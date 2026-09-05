import type { PersonnelPoint } from './overview'

export interface PersonnelRecord extends PersonnelPoint {
  jobNo: string
  bracelet: string
  braceletId?: string | undefined
  braceletStatus: '在线' | '离线' | '低电量'
  positioningQuality: '优秀' | '良好' | '较低' | '无信号'
  lastUpdated: string
  coordinate: string
  distanceToday: number
  alertsToday: number
  activeAlertIds?: string[] | undefined
}

export interface TrackPoint {
  x: number
  y: number
  time: string
}

/** 人员列表顶部统计（GET /personnel → stats） */
export interface PersonnelStats {
  online: number
  abnormal: number
  bandOffline: number
  lowBattery: number
}

export interface PersonnelListResponse {
  stats: PersonnelStats
  list: PersonnelRecord[]
}

export interface LivePosition {
  id: string
  x: number
  y: number
  area: string
  battery: number
  risk: string
  state: string
  ts: string
}
