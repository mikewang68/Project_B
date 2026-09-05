// 告警中心类型定义（Mock 与 Backend Demo 共用，字段与后端 DemoAlert 对齐）

import type { AiSceneKind, DetectionBox } from './ai'

export type AlertRisk = '一般' | '预警' | '严重' | '紧急'

export type AlertStatus = '待确认' | '已确认' | '待派单' | '待处理' | '处理中' | '待复核' | '已关闭' | '已升级'

export type AlertSource = '人员安全' | '设备防碰撞' | 'AI违规' | '设备异常' | '系统异常'

export type LinkageStepState = 'wait' | 'running' | 'success' | 'failed'

export interface LinkageStep {
  id: string
  label: string
  state: LinkageStepState
  detail: string
  time?: string | undefined
}

export interface TimelineNode {
  time: string
  text: string
  state: 'done' | 'active' | 'pending'
}

/** 人员类证据 */
export interface PersonnelEvidence {
  kind: 'personnel'
  track: Array<{ x: number; y: number }>
  currentPosition: string
  fence: string
  band: string
  bandState: string
  heartRate: string
}

/** 设备防碰撞证据 */
export interface CollisionEvidence {
  kind: 'collision'
  distance: number
  relSpeed: number
  trend: number[]
  radar: string
  brakeDistance: string
}

/** AI 证据（复用 AIDetectionImage 所需结构） */
export interface AiEvidence {
  kind: 'ai'
  scene: AiSceneKind
  boxes: DetectionBox[]
  confidence: number
  model: string
  camera: string
  time: string
}

/** 设备异常 / 系统异常证据 */
export interface MetricEvidence {
  kind: 'device-metric' | 'system-metric'
  metrics: Array<{ label: string; value: string; tone?: 'ok' | 'warn' | 'danger' }>
  description: string
}

export type AlertEvidence = PersonnelEvidence | CollisionEvidence | AiEvidence | MetricEvidence

export interface TreatmentResult {
  measures: string[]
  result: string
  attachment: string
  note: string
  submitTime: string
  handler: string
}

export interface AlertEvent {
  id: string
  title: string
  risk: AlertRisk
  eventType: string
  time: string
  area: string
  target: string
  source: AlertSource
  status: AlertStatus
  assignee: string
  /** SLA 剩余秒数；负数表示已超时；已关闭事件为 undefined */
  slaRemainingSec?: number | undefined
  ruleId: string
  ruleVersion: string
  durationSec: number
  evidence: AlertEvidence
  linkageAvailable: boolean
  linkage: LinkageStep[]
  linkageFailed?: boolean | undefined
  linkageFinished?: boolean | undefined
  takeover?: boolean | undefined
  timeline: TimelineNode[]
  confirmUser?: string | undefined
  confirmTime?: string | undefined
  acceptTime?: string | undefined
  priority?: '普通' | '紧急' | undefined
  slaLimitMin?: number | undefined
  treatment?: TreatmentResult | undefined
  reviewUser?: string | undefined
  reviewTime?: string | undefined
  reviewNote?: string | undefined
  upgradedFrom?: AlertRisk | undefined
  /** 移动端现场阶段（Backend Demo 过渡设计）：PENDING/ACCEPTED/ARRIVED/PROCESSING */
  mobileStage?: string | undefined
  /** 移动端接单/到场时间（ISO-8601） */
  acceptedAt?: string | undefined
  arrivedAt?: string | undefined
  /** 后端规范时间戳（ISO-8601 带时区），联调后由 Backend 返回，Mock 数据可缺省 */
  occurredAt?: string | undefined
  slaDeadline?: string | null | undefined
  updatedAt?: string | undefined
}

/** 顶部指标（GET /api/v1/alerts/metrics） */
export interface AlertMetrics {
  total: number
  pending: number
  active: number
  severe: number
  urgent: number
  closed: number
}

/** 后端分页响应（GET /api/v1/alerts） */
export interface AlertPageResult {
  page: number
  pageSize: number
  total: number
  list: AlertEvent[]
}

export const ALERT_RISKS: AlertRisk[] = ['一般', '预警', '严重', '紧急']
export const ALERT_STATUSES: AlertStatus[] = ['待确认', '已确认', '待派单', '待处理', '处理中', '待复核', '已关闭', '已升级']
export const ALERT_SOURCES: AlertSource[] = ['人员安全', '设备防碰撞', 'AI违规', '设备异常', '系统异常']
export const ALERT_ASSIGNEES = ['安全员 王建国', '安全员 李娜', '班长 刘志明', '值班员 陈晓', '设备管理员 周海'] as const
export const TREATMENT_MEASURES = ['人员已撤离危险区域', '设备已停止运行', '现场已设置警戒', '现场确认无遗留风险'] as const

/** 紧急/严重事件的标准联动步骤模板 */
export function buildLinkageTemplate(): LinkageStep[] {
  return [
    { id: 'sound-light', label: '现场声光提醒', state: 'wait', detail: '等待执行' },
    { id: 'band', label: '人员手环提醒', state: 'wait', detail: '等待发送' },
    { id: 'safety-officer', label: '安全员通知', state: 'wait', detail: '等待送达' },
    { id: 'dispatcher', label: '调度员通知', state: 'wait', detail: '等待送达' },
    { id: 'slowdown', label: '减速请求', state: 'wait', detail: '等待发送' },
    { id: 'shutdown', label: '设备停机', state: 'wait', detail: '等待执行' },
    { id: 'plc', label: 'PLC 回执', state: 'wait', detail: '等待回执' },
  ]
}
