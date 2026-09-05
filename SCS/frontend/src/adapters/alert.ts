import type {
  AlertEvent,
  AlertEvidence,
  AlertMetrics,
  AlertPageResult,
  AlertRisk,
  AlertStatus,
  LinkageStep,
  TimelineNode,
} from '@/types/alert'

/**
 * 告警前后端适配层（唯一允许做字段/枚举兼容的地方，组件内禁止再写 if/switch 猜字段）。
 * 当前 Backend Demo 字段已与前端类型对齐，这里负责：枚举白名单兜底、缺省值、
 * SLA 以绝对 deadline 重算、分页结构规范化。
 */

const RISKS: AlertRisk[] = ['一般', '预警', '严重', '紧急']
const STATUSES: AlertStatus[] = ['待确认', '已确认', '待派单', '待处理', '处理中', '待复核', '已关闭', '已升级']
const EVIDENCE_KINDS = ['personnel', 'collision', 'ai', 'device-metric', 'system-metric'] as const

/** 风险等级：未知值兜底为“一般”，避免 Badge 渲染空白 */
export function mapAlertLevel(value: unknown): AlertRisk {
  return typeof value === 'string' && (RISKS as string[]).includes(value) ? (value as AlertRisk) : '一般'
}

/** 状态：未知值兜底为“待确认” */
export function mapAlertStatus(value: unknown): AlertStatus {
  return typeof value === 'string' && (STATUSES as string[]).includes(value) ? (value as AlertStatus) : '待确认'
}

/** 证据：kind 不在五类联合内时收敛为系统指标占位，保证视图不崩 */
export function mapAlertEvidence(value: unknown): AlertEvent['evidence'] | undefined {
  if (!value || typeof value !== 'object') return undefined
  const kind = (value as { kind?: unknown }).kind
  if (typeof kind !== 'string' || !(EVIDENCE_KINDS as readonly string[]).includes(kind)) return undefined
  return value as AlertEvidence
}

function mapTimeline(value: unknown): TimelineNode[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((n): n is Record<string, unknown> => !!n && typeof n === 'object')
    .map((n) => ({
      time: String(n.time ?? ''),
      text: String(n.text ?? ''),
      state: n.state === 'active' ? 'active' : n.state === 'pending' ? 'pending' : 'done',
    }))
}

function mapLinkage(value: unknown): LinkageStep[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((s): s is Record<string, unknown> => !!s && typeof s === 'object')
    .map((s) => ({
      id: String(s.id ?? ''),
      label: String(s.label ?? ''),
      state: ['wait', 'running', 'success', 'failed'].includes(String(s.state))
        ? (String(s.state) as LinkageStep['state'])
        : 'wait',
      detail: String(s.detail ?? ''),
      ...(s.time ? { time: String(s.time) } : {}),
    }))
}

/** 以 ISO-8601 绝对截止时间计算 SLA 剩余秒数（负数=已超时；无 deadline 返回 undefined） */
export function remainingSecByDeadline(deadline?: string | null, now: Date = new Date()): number | undefined {
  if (!deadline) return undefined
  const end = Date.parse(deadline)
  if (Number.isNaN(end)) return undefined
  return Math.round((end - now.getTime()) / 1000)
}

/** 后端告警 DTO → 前端 AlertEvent（结构对齐，仅做集中规范化；SLA 以 slaDeadline 为准） */
export function mapAlertEvent(input: unknown): AlertEvent {
  const dto = (input ?? {}) as Record<string, unknown>
  const deadline = (dto.slaDeadline as string | null | undefined) ?? null
  const byDeadline = remainingSecByDeadline(deadline)
  const rawRemaining = typeof dto.slaRemainingSec === 'number' ? dto.slaRemainingSec : undefined
  const mapped: AlertEvent = {
    id: String(dto.id ?? ''),
    title: String(dto.title ?? ''),
    risk: mapAlertLevel(dto.risk),
    eventType: String(dto.eventType ?? ''),
    time: String(dto.time ?? ''),
    area: String(dto.area ?? ''),
    target: String(dto.target ?? ''),
    source: String(dto.source ?? '') as AlertEvent['source'],
    status: mapAlertStatus(dto.status),
    assignee: String(dto.assignee ?? '待分配'),
    // 有绝对 deadline 时以其为准，后端 slaRemainingSec 仅作校验兜底
    slaRemainingSec: byDeadline ?? rawRemaining,
    ruleId: String(dto.ruleId ?? ''),
    ruleVersion: String(dto.ruleVersion ?? ''),
    durationSec: Number(dto.durationSec ?? 0),
    evidence: mapAlertEvidence(dto.evidence) as AlertEvidence,
    linkageAvailable: Boolean(dto.linkageAvailable),
    linkage: mapLinkage(dto.linkage),
    linkageFailed: Boolean(dto.linkageFailed),
    linkageFinished: Boolean(dto.linkageFinished),
    takeover: Boolean(dto.takeover),
    timeline: mapTimeline(dto.timeline),
    occurredAt: typeof dto.occurredAt === 'string' ? dto.occurredAt : undefined,
    slaDeadline: deadline,
    updatedAt: typeof dto.updatedAt === 'string' ? dto.updatedAt : undefined,
  }
  const target = mapped as unknown as Record<string, unknown>
  const optional = ['confirmUser', 'confirmTime', 'acceptTime', 'priority', 'slaLimitMin', 'treatment', 'reviewUser', 'reviewTime', 'reviewNote', 'upgradedFrom', 'mobileStage', 'acceptedAt', 'arrivedAt'] as const
  optional.forEach((key) => {
    if (dto[key] !== undefined && dto[key] !== null) target[key] = dto[key]
  })
  return mapped
}

/** metrics DTO 集中映射（字段同名，保留独立入口以便后端字段调整时只改一处） */
export function mapAlertMetricsDto(dto: unknown): AlertMetrics {
  const d = (dto ?? {}) as Record<string, number>
  const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
  return {
    total: num(d.total),
    pending: num(d.pending),
    active: num(d.active),
    severe: num(d.severe),
    urgent: num(d.urgent),
    closed: num(d.closed),
  }
}

export function mapAlertPage(dto: unknown): AlertPageResult {
  const d = (dto ?? {}) as Record<string, unknown>
  const list = Array.isArray(d.list) ? d.list.map((item) => mapAlertEvent(item as Record<string, unknown>)) : []
  return {
    page: Number(d.page ?? 1),
    pageSize: Number(d.pageSize ?? list.length),
    total: Number(d.total ?? list.length),
    list,
  }
}

/** 升级时取比当前等级高一级（紧急保持紧急） */
export function nextRiskLevel(current: AlertRisk): AlertRisk {
  const order: AlertRisk[] = ['一般', '预警', '严重', '紧急']
  const idx = Math.max(0, order.indexOf(current))
  return order[Math.min(idx + 1, order.length - 1)] ?? '紧急'
}
