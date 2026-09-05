export type FenceStatus = '草稿' | '待评审' | '待发布' | '已生效' | '已停用' | '版本不一致'
export type FenceKind = '危险区域' | '设备区域' | '临时围栏' | '预警区域' | '授权区域'
export type FenceTone = 'danger' | 'warning' | 'normal' | 'temporary'

export interface FencePoint { x: number; y: number }

export interface EdgeNodeStatus {
  id: string
  state: 'pending' | 'syncing' | 'success' | 'failed'
}

export interface FenceRecord {
  id: string
  name: string
  kind: FenceKind
  tone: FenceTone
  area: string
  version: string
  status: FenceStatus
  effectiveAt: string
  expiresAt: string
  teams: string
  approver: string
  riskLevel?: string | undefined
  edgeSynced: number
  edgeTotal: number
  polygon: FencePoint[]
  nodes: EdgeNodeStatus[]
}

export interface FenceDraft {
  name: string
  kind: FenceKind
  riskLevel: '一般' | '严重' | '紧急'
  teams: string
  startsAt: string
  endsAt: string
}
