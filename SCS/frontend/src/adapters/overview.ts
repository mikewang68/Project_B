import type {
  EquipmentPoint,
  OverviewAlert,
  OverviewDistributionDto,
  OverviewMapDto,
  OverviewSummaryDto,
  OverviewTrendDto,
  PersonnelPoint,
} from '@/types/overview'
import type { FenceRecord } from '@/types/fence'

/**
 * 安全态势首页前后端适配层（唯一允许做字段/枚举兼容的地方）。
 * 告警口径来自后端真实聚合；人员/设备/围栏坐标为 Demo 底图，风险覆盖来自真实未关闭 Alert。
 */

const MARKER_STATES = ['normal', 'warning', 'danger', 'offline'] as const
const PERSON_RISKS = ['正常', '关注', '高风险'] as const
const EQUIP_TYPES = ['crane', 'tipper', 'vehicle', 'camera'] as const

function hhmmss(iso: unknown): string {
  if (typeof iso !== 'string' || !iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return typeof iso === 'string' && iso.length <= 8 ? iso : ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function mapSummary(dto: unknown): OverviewSummaryDto {
  const d = (dto ?? {}) as Record<string, number | boolean>
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
  return {
    onDuty: num(d.onDuty),
    deviceOnline: num(d.deviceOnline),
    deviceTotal: num(d.deviceTotal),
    activeAlerts: num(d.activeAlerts),
    processingAlerts: num(d.processingAlerts),
    urgentAlerts: num(d.urgentAlerts),
    severeAlerts: num(d.severeAlerts),
    pendingAi: num(d.pendingAi),
    riskyDevices: num(d.riskyDevices),
    onDutyDemo: Boolean(d.onDutyDemo),
    deviceDemo: Boolean(d.deviceDemo),
    riskyDevicesDemo: Boolean(d.riskyDevicesDemo),
  }
}

export function mapPerson(input: unknown): PersonnelPoint {
  const d = (input ?? {}) as Record<string, unknown>
  const state = (typeof d.state === 'string' && (MARKER_STATES as readonly string[]).includes(d.state)
    ? d.state : 'normal') as PersonnelPoint['state']
  const risk = (typeof d.risk === 'string' && (PERSON_RISKS as readonly string[]).includes(d.risk)
    ? d.risk : '正常') as PersonnelPoint['risk']
  return {
    id: String(d.id ?? ''),
    name: String(d.name ?? ''),
    team: String(d.team ?? ''),
    status: d.status === '离线' ? '离线' : '在线',
    battery: Number(d.battery ?? 0),
    area: String(d.area ?? ''),
    risk,
    state,
    x: Number(d.x ?? 0),
    y: Number(d.y ?? 0),
    liveOverlay: Boolean(d.liveOverlay),
  }
}

export function mapEquipment(input: unknown): EquipmentPoint {
  const d = (input ?? {}) as Record<string, unknown>
  const type = (typeof d.type === 'string' && (EQUIP_TYPES as readonly string[]).includes(d.type)
    ? d.type : 'vehicle') as EquipmentPoint['type']
  const state = (typeof d.state === 'string' && (MARKER_STATES as readonly string[]).includes(d.state)
    ? d.state : 'normal') as EquipmentPoint['state']
  return {
    id: String(d.id ?? ''),
    name: String(d.name ?? ''),
    type,
    state,
    x: Number(d.x ?? 0),
    y: Number(d.y ?? 0),
    liveOverlay: Boolean(d.liveOverlay),
  }
}

/** 后端围栏投影 → SafetyMapCanvas 需要的 FenceRecord（补齐前端展示字段） */
export function mapFences(dto: unknown): FenceRecord[] {
  const d = (dto ?? {}) as OverviewMapDto
  if (!Array.isArray(d.fences)) return []
  return d.fences.map((f) => ({
    id: f.id,
    name: f.name,
    kind: f.tone === 'danger' ? '危险区域' : '临时围栏',
    tone: (f.tone === 'danger' ? 'danger' : 'temporary') as FenceRecord['tone'],
    area: f.area,
    version: '—',
    status: '已生效',
    effectiveAt: '',
    expiresAt: '',
    teams: '',
    approver: '',
    edgeSynced: 4,
    edgeTotal: 4,
    polygon: (f.polygon ?? []).map((p) => ({ x: Number(p.x), y: Number(p.y) })),
    nodes: [],
  }))
}

export function mapOverviewMap(dto: unknown): OverviewMapDto {
  const d = (dto ?? {}) as OverviewMapDto
  return {
    baseDemo: Boolean(d.baseDemo),
    liveOverlay: Boolean(d.liveOverlay),
    people: Array.isArray(d.people) ? d.people.map(mapPerson) : [],
    equipment: Array.isArray(d.equipment) ? d.equipment.map(mapEquipment) : [],
    fences: Array.isArray(d.fences) ? d.fences : [],
  }
}

export function mapFeedItem(input: unknown): OverviewAlert {
  const d = (input ?? {}) as Record<string, unknown>
  const level = d.level === '紧急' ? '紧急' : d.level === '严重' ? '严重' : '一般'
  const timeline = Array.isArray(d.timeline)
    ? d.timeline.map((n) => {
        const node = (n ?? {}) as Record<string, unknown>
        return { time: String(node.time ?? ''), title: String(node.title ?? ''), detail: String(node.detail ?? '') }
      })
    : []
  return {
    id: String(d.id ?? ''),
    title: String(d.title ?? ''),
    level,
    summary: String(d.summary ?? ''),
    time: hhmmss(d.time),
    area: String(d.area ?? ''),
    objectName: String(d.objectName ?? ''),
    status: String(d.status ?? ''),
    timeline,
  }
}

export function mapFeed(dto: unknown): OverviewAlert[] {
  const d = (dto ?? {}) as { list?: unknown[] }
  return Array.isArray(d.list) ? d.list.map(mapFeedItem) : []
}

export function mapTrend(dto: unknown): OverviewTrendDto {
  const d = (dto ?? {}) as OverviewTrendDto
  return {
    demo: Boolean(d.demo),
    points: Array.isArray(d.points)
      ? d.points.map((p) => ({ label: String(p.label), total: Number(p.total), high: Number(p.high) }))
      : [],
  }
}

export function mapDistribution(dto: unknown): OverviewDistributionDto {
  const d = (dto ?? {}) as OverviewDistributionDto
  return {
    items: Array.isArray(d.items)
      ? d.items.map((i) => ({ type: String(i.type), count: Number(i.count) }))
      : [],
  }
}
