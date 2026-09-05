import type {
  CollisionEquipment,
  CollisionPair,
  CollisionRisk,
  CollisionSimulateResult,
  DistancePoint,
  LinkageStep,
  LinkageState,
} from '@/types/collision'

function num(v: unknown, fallback = 0): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}

const RISK_FALLBACK: CollisionRisk = '安全'

export function mapDevice(dto: unknown): CollisionEquipment {
  const d = (dto ?? {}) as Partial<CollisionEquipment>
  return {
    id: d.id ?? '',
    name: d.name ?? '',
    type: d.type ?? '转运车辆',
    area: d.area ?? '',
    status: d.status ?? '',
    speed: num(d.speed),
    direction: d.direction ?? '',
    controlStatus: d.controlStatus ?? '',
    communication: d.communication ?? '',
    radarStatus: d.radarStatus ?? '正常',
    lastUpdated: d.lastUpdated ?? '刚刚',
    risk: (d.risk ?? RISK_FALLBACK) as CollisionRisk,
    relatedEquipment: d.relatedEquipment ?? '',
    relatedEquipmentId: d.relatedEquipmentId,
    latestAlert: d.latestAlert ?? '',
    latestAlertId: d.latestAlertId ?? undefined,
    x: typeof d.x === 'number' ? d.x : undefined,
    y: typeof d.y === 'number' ? d.y : undefined,
  }
}

export function mapDeviceList(dto: unknown): CollisionEquipment[] {
  const d = (dto ?? {}) as { list?: unknown[] }
  return Array.isArray(d.list) ? d.list.map(mapDevice) : []
}

function mapSteps(raw: unknown): LinkageStep[] {
  if (!Array.isArray(raw)) return []
  return raw.map((s) => {
    const q = (s ?? {}) as { id: string; label: string; state: LinkageState; detail: string }
    return { id: q.id, label: q.label, state: q.state, detail: q.detail }
  })
}

export function mapPair(dto: unknown): CollisionPair {
  const d = (dto ?? {}) as Partial<CollisionPair>
  return {
    distance: num(d.distance, 12.6),
    relSpeed: num(d.relSpeed),
    direction: d.direction ?? '接近',
    radarQuality: num(d.radarQuality, 97),
    risk: (d.risk ?? RISK_FALLBACK) as CollisionRisk,
    radarDown: !!d.radarDown,
    ts: d.ts,
    alertId: d.alertId,
    steps: mapSteps(d.steps),
  }
}

export function mapTrend(dto: unknown): DistancePoint[] {
  const d = (dto ?? {}) as { points?: unknown[] }
  return Array.isArray(d.points)
    ? d.points.map((p) => {
        const q = (p ?? {}) as DistancePoint
        return { label: q.label ?? '', value: num(q.value) }
      })
    : []
}

export function mapSimulate(dto: unknown): CollisionSimulateResult {
  const d = (dto ?? {}) as Partial<CollisionSimulateResult>
  const pair = mapPair(dto)
  return {
    ...pair,
    deviceStopped: !!d.deviceStopped,
    controlFailure: !!d.controlFailure,
    plcStatus: d.plcStatus ?? '待命',
    device: mapDevice(d.device),
    related: mapDevice(d.related),
  }
}

export function mapStepsResponse(dto: unknown): LinkageStep[] {
  const d = (dto ?? {}) as { steps?: unknown }
  return mapSteps(d.steps)
}
