import type { AiEvent, CameraHealth, DetectionBox, ReviewStatus, RiskLevel, AiSceneKind, AiEventType } from '@/types/ai'

/**
 * AI 事件前后端适配层（唯一允许做字段/枚举兼容的地方，组件内禁止再写 if/switch 猜字段）。
 * 后端 DemoAiEvent 字段已与前端 AiEvent 对齐，这里负责：枚举白名单兜底、检测框/时间线规范化、缺省值。
 */

const REVIEW_STATUSES: ReviewStatus[] = ['待复核', '已确认违规', '误报', '不确定', '已派单', '处理中', '已关闭']
const RISKS: RiskLevel[] = ['高', '中', '低']
const HEALTHS: CameraHealth[] = ['正常', '画面质量下降', '离线']
const SCENES: AiSceneKind[] = ['helmet', 'fence', 'intrusion', 'linger', 'camera']
const EVENT_TYPES = ['未佩戴安全帽', '翻越护栏', '闯入危险区域', '人员滞留', '摄像头异常']

export function mapReviewStatus(value: unknown): ReviewStatus {
  return typeof value === 'string' && (REVIEW_STATUSES as string[]).includes(value)
    ? (value as ReviewStatus) : '待复核'
}

export function mapAiRisk(value: unknown): RiskLevel {
  return typeof value === 'string' && (RISKS as string[]).includes(value) ? (value as RiskLevel) : '中'
}

export function mapCameraHealth(value: unknown): CameraHealth {
  return typeof value === 'string' && (HEALTHS as string[]).includes(value) ? (value as CameraHealth) : '正常'
}

function mapScene(value: unknown, type?: unknown): AiSceneKind {
  if (typeof value === 'string' && (SCENES as string[]).includes(value)) return value as AiSceneKind
  if (type === '未佩戴安全帽') return 'helmet'
  if (type === '翻越护栏') return 'fence'
  if (type === '闯入危险区域') return 'intrusion'
  if (type === '人员滞留') return 'linger'
  return 'camera'
}

function mapBoxes(value: unknown): DetectionBox[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((b): b is Record<string, unknown> => !!b && typeof b === 'object')
    .map((b) => ({
      id: String(b.id ?? ''),
      label: String(b.label ?? ''),
      ...(typeof b.score === 'number' && Number.isFinite(b.score) ? { score: b.score } : {}),
      x: Number(b.x ?? 0),
      y: Number(b.y ?? 0),
      w: Number(b.w ?? 0),
      h: Number(b.h ?? 0),
      tone: b.tone === 'violation' ? 'violation' : b.tone === 'zone' ? 'zone' : 'person',
    }))
}

function mapTimeline(value: unknown): AiEvent['timeline'] {
  if (!Array.isArray(value)) return []
  return value
    .filter((n): n is Record<string, unknown> => !!n && typeof n === 'object')
    .map((n) => ({
      time: String(n.time ?? ''),
      text: String(n.text ?? ''),
      state: n.state === 'active' ? 'active' : n.state === 'pending' ? 'pending' : 'done',
    }))
}

/** 后端 AI 事件 DTO → 前端 AiEvent（结构对齐，仅集中规范化） */
export function mapAiEvent(input: unknown): AiEvent {
  const dto = (input ?? {}) as Record<string, unknown>
  const type = (typeof dto.type === 'string' && EVENT_TYPES.includes(dto.type)
    ? dto.type : '摄像头异常') as AiEventType
  const event: AiEvent = {
    id: String(dto.id ?? ''),
    type,
    camera: String(dto.camera ?? ''),
    cameraName: String(dto.cameraName ?? dto.camera ?? ''),
    area: String(dto.area ?? ''),
    confidence: Number(dto.confidence ?? 0),
    durationSec: Number(dto.durationSec ?? 0),
    model: String(dto.model ?? ''),
    threshold: Number(dto.threshold ?? 0),
    time: String(dto.time ?? ''),
    status: mapReviewStatus(dto.status),
    risk: mapAiRisk(dto.risk),
    health: mapCameraHealth(dto.health),
    scene: mapScene(dto.scene, dto.type),
    boxes: mapBoxes(dto.boxes),
    rule: String(dto.rule ?? ''),
    relatedPerson: String(dto.relatedPerson ?? '—'),
    relatedDevice: String(dto.relatedDevice ?? '—'),
    judgeText: String(dto.judgeText ?? ''),
    timeline: mapTimeline(dto.timeline),
  }
  const optional = ['reviewer', 'reviewTime', 'falseReason', 'assignee', 'assignmentPriority',
    'processStatus', 'assignmentNote', 'linkedAlertId', 'occurredAt', 'fresh'] as const
  optional.forEach((key) => {
    if (dto[key] !== undefined && dto[key] !== null) {
      ;(event as unknown as Record<string, unknown>)[key] = dto[key]
    }
  })
  return event
}

export interface AiMetricsDto {
  today: number
  pending: number
  confirmed: number
  falsePositive: number
  cameraFault: number
}

export function mapAiMetrics(dto: unknown): AiMetricsDto {
  const d = (dto ?? {}) as Record<string, number>
  const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
  return {
    today: num(d.today),
    pending: num(d.pending),
    confirmed: num(d.confirmed),
    falsePositive: num(d.falsePositive),
    cameraFault: num(d.cameraFault),
  }
}

export interface AiEventPageDto {
  page: number
  pageSize: number
  total: number
  metrics: AiMetricsDto
  facets: { areas: string[]; cameras: string[] }
  list: AiEvent[]
}

export function mapAiEventPage(dto: unknown): AiEventPageDto {
  const d = (dto ?? {}) as Record<string, unknown>
  const list = Array.isArray(d.list) ? d.list.map(mapAiEvent) : []
  const facets = (d.facets ?? {}) as Record<string, unknown>
  return {
    page: Number(d.page ?? 1),
    pageSize: Number(d.pageSize ?? list.length),
    total: Number(d.total ?? list.length),
    metrics: mapAiMetrics(d.metrics),
    facets: {
      areas: Array.isArray(facets.areas) ? facets.areas.map(String) : [],
      cameras: Array.isArray(facets.cameras) ? facets.cameras.map(String) : [],
    },
    list,
  }
}

export interface CameraInfo {
  cameraId: string
  name: string
  area: string
  online: boolean
  quality: number
  state: CameraHealth
  lastUpdated: string
}

export function mapCameraInfo(input: unknown): CameraInfo {
  const d = (input ?? {}) as Record<string, unknown>
  return {
    cameraId: String(d.cameraId ?? ''),
    name: String(d.name ?? ''),
    area: String(d.area ?? ''),
    online: Boolean(d.online),
    quality: Number(d.quality ?? 0),
    state: mapCameraHealth(d.state),
    lastUpdated: String(d.lastUpdated ?? ''),
  }
}
