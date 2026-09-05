import type { LivePosition, PersonnelListResponse, PersonnelRecord, PersonnelStats, TrackPoint } from '@/types/personnel'

/** 后端人员 DTO（字段与 DemoPersonnel 对齐，braceletId 对应前端 bracelet） */
export interface PersonnelDto {
  id: string
  jobNo: string
  name: string
  team: string
  area: string
  braceletId?: string
  bracelet?: string
  battery: number
  status: string
  braceletStatus: PersonnelRecord['braceletStatus']
  positioningQuality: PersonnelRecord['positioningQuality']
  risk: string
  state: string
  x: number
  y: number
  coordinate?: string
  distanceToday: number
  alertsToday: number
  lastUpdated: string
  activeAlertIds?: string[]
}

function num(v: unknown, fallback = 0): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}

/** 后端人员 → 前端 PersonnelRecord（集中兜底，禁止组件各写一套映射） */
export function mapPerson(dto: unknown): PersonnelRecord {
  const d = (dto ?? {}) as PersonnelDto
  const risk = d.risk ?? '正常'
  const state = d.state ?? 'normal'
  return {
    id: d.id,
    jobNo: d.jobNo ?? '',
    name: d.name ?? '',
    team: d.team ?? '',
    area: d.area ?? '',
    bracelet: d.bracelet ?? d.braceletId ?? '',
    braceletId: d.braceletId ?? d.bracelet,
    battery: num(d.battery),
    status: (d.status ?? '在线') as PersonnelRecord['status'],
    braceletStatus: (d.braceletStatus ?? '在线') as PersonnelRecord['braceletStatus'],
    positioningQuality: (d.positioningQuality ?? '良好') as PersonnelRecord['positioningQuality'],
    risk: risk as PersonnelRecord['risk'],
    state: state as PersonnelRecord['state'],
    x: num(d.x),
    y: num(d.y),
    coordinate: d.coordinate ?? '',
    distanceToday: num(d.distanceToday),
    alertsToday: num(d.alertsToday),
    lastUpdated: d.lastUpdated ?? '',
    activeAlertIds: Array.isArray(d.activeAlertIds) ? d.activeAlertIds : undefined,
  }
}

export function mapPersonnelList(dto: unknown): PersonnelListResponse {
  const d = (dto ?? {}) as { stats?: Partial<PersonnelStats>; list?: unknown[] }
  const stats: PersonnelStats = {
    online: d.stats?.online ?? 0,
    abnormal: d.stats?.abnormal ?? 0,
    bandOffline: d.stats?.bandOffline ?? 0,
    lowBattery: d.stats?.lowBattery ?? 0,
  }
  return { stats, list: Array.isArray(d.list) ? d.list.map(mapPerson) : [] }
}

export function mapTrack(dto: unknown): TrackPoint[] {
  const d = (dto ?? {}) as { points?: unknown[] }
  return Array.isArray(d.points)
    ? d.points.map((p) => {
        const q = (p ?? {}) as { x: number; y: number; time: string }
        return { x: num(q.x), y: num(q.y), time: q.time ?? '' }
      })
    : []
}

export function mapLivePositions(dto: unknown): LivePosition[] {
  const d = (dto ?? {}) as { list?: unknown[] }
  return Array.isArray(d.list)
    ? d.list.map((p) => {
        const q = (p ?? {}) as LivePosition
        return { ...q, x: num(q.x), y: num(q.y), battery: num(q.battery) }
      })
    : []
}
