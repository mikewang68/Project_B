export type CollisionRisk = '安全' | '预警' | '严重' | '紧急' | '待确认'
export type LinkageState = 'waiting' | 'running' | 'success' | 'failed'

export interface CollisionEquipment {
  id: string
  name: string
  type: '转运车辆' | '翻箱机' | '龙门吊'
  area: string
  status: string
  speed: number
  direction: string
  controlStatus: string
  communication: string
  radarStatus: string
  lastUpdated: string
  risk: CollisionRisk
  relatedEquipment: string
  relatedEquipmentId?: string | undefined
  latestAlert: string
  latestAlertId?: string | undefined
  x?: number | undefined
  y?: number | undefined
}

/** 设备配对实时状态（GET /collision/pair） */
export interface CollisionPair {
  distance: number
  relSpeed: number
  direction: string
  radarQuality: number
  risk: CollisionRisk
  radarDown: boolean
  ts?: string | undefined
  alertId?: string | null | undefined
  steps: LinkageStep[]
}

/** 模拟 / 联动推进结果（POST /collision/simulate） */
export interface CollisionSimulateResult extends CollisionPair {
  deviceStopped: boolean
  controlFailure: boolean
  plcStatus: string
  device: CollisionEquipment
  related: CollisionEquipment
}

export interface LinkageStep {
  id: string
  label: string
  state: LinkageState
  detail: string
}

export interface DistancePoint {
  label: string
  value: number
}
