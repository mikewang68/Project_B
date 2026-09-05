// 安全大屏 + 移动端告警处置 共享类型（由后端 Alert 投影而来，地图坐标/距离/照片仍为 Demo）

import type { AlertEvidence, AlertRisk, AlertStatus, TimelineNode } from './alert'

/** 移动端现场处置状态机（展示态，由 Alert 主状态 + mobileStage 投影得到） */
export type IncidentStatus = '待接单' | '已接单' | '已到场' | '处理中' | '待复核' | '已关闭'

export type IncidentSource = '人员安全' | '设备防碰撞' | 'AI违规' | '设备异常' | '系统异常'

export type IncidentPhoto = {
  id: string
  name: string
  /** Mock 图片 dataURL 或占位标识 */
  thumb: string
  time: string
}

export interface MobileIncident {
  /** 与管理端 / 大屏共享的同一 Alert 主键（ALM-20260904-xxx） */
  id: string
  title: string
  risk: AlertRisk
  source: IncidentSource
  area: string
  target: string
  /** 距安全员当前位置（米，Demo 派生：无真实定位） */
  distanceM: number
  time: string
  status: IncidentStatus
  /** 后端 Alert 主状态（管理端状态机原文） */
  alertStatus: AlertStatus
  /** 后端移动端现场阶段：PENDING / ACCEPTED / ARRIVED / PROCESSING */
  mobileStage?: string | undefined
  /** 事件在简化地图上的位置（百分比坐标，Demo 派生：无真实坐标服务） */
  pos: { x: number; y: number }
  evidence: AlertEvidence
  timeline: TimelineNode[]
  assignee: string
  acceptTime?: string | undefined
  arriveTime?: string | undefined
  handleTime?: string | undefined
  submitTime?: string | undefined
  measures: string[]
  siteNote: string
  riskCleared: boolean | null
  photos: IncidentPhoto[]
  note: string
  upgradedFrom?: AlertRisk | undefined
  /** 演示模式动态生成的事件，恢复时移除（Mock fallback 才会出现） */
  demo?: boolean | undefined
  occurredAt?: string | undefined
  /** 紧急联动状态（由后端 Alert.linkage 投影） */
  linkage?: {
    soundLight: 'idle' | 'running' | 'done' | 'failed'
    shutdown: 'idle' | 'running' | 'done' | 'failed'
    plc: 'idle' | 'running' | 'done' | 'failed'
    requested?: boolean | undefined
  } | undefined
}

export const INCIDENT_STATUSES: IncidentStatus[] = ['待接单', '已接单', '已到场', '处理中', '待复核', '已关闭']

export const MOBILE_MEASURES = ['人员已撤离', '设备已停止', '现场已设置警戒', '已通知司机', '现场确认无遗留风险'] as const

export const ESCALATE_REASONS = ['现场情况比预判严重', '设备无法停止', '出现人员受伤', '需要多班组协同'] as const
