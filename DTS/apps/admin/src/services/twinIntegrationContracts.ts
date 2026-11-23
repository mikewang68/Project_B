/**
 * S5 数字孪生可视化系统 (DTS V6) 跨系统标准化只读数据契约（Contract v1.0）
 *
 * 严格遵循“只监不控、零改三维代码直连”设计原则：
 * - S2 (WMS 仓储 · 周绪敬)：40 座筒仓 + 13 跨钢筋库实测料位/库存/垛层契约
 * - S3 (EHMS 健康 · 闫鑫钰)：36 台主动特种设备健康分/RUL/传感器与告警扩展契约
 * - S4 (SCS 安全 · 敬昌隆)：立体电子围栏、6 路安防监控流地址与防碰撞测距契约
 *
 * 当上游子系统交付真实数据时，dts_readonly_server.py 自动将 dataSource 由 FALLBACK_SIM 切换为
 * LIVE_S2_WMS / LIVE_S3_EHMS / LIVE_S4_SCS，前端三维覆盖层零代码修改即可点亮实测数据。
 */
import { SITE_LAYOUT_V4 } from '@/twin3d/siteLayoutV4'

export type DataProvenance =
  | 'LIVE_S2_WMS'
  | 'LIVE_S3_EHMS'
  | 'LIVE_S4_SCS'
  | 'PUBLIC_INBOX_SIM'
  | 'FALLBACK_SIM'
  | 'REGION_ISOLATED'
  | 'PHASE2_RESERVED_EMPTY'

export type PhysicalLevelSource =
  | 'LIVE_X09_RADAR'
  | 'UNBOUND_NO_SENSOR'
  | 'UNBOUND_PHASE2_RESERVED'
  | 'REGION_ISOLATED'

/** 冻结版《给大连大.dwg》实测 32t C型卷材钩桥吊跨位：03 / 06 / 09 / 13 跨 */
export const CHOOK_32T_BAY_INDICES = [3, 6, 9, 13] as const

export function is32tChookBay(bayIndex: number): boolean {
  return (CHOOK_32T_BAY_INDICES as readonly number[]).includes(bayIndex)
}

/** 100% 对齐 siteLayoutV4.json 2排×20列编码：SILO-01=A01, SILO-02=B01 ... SILO-40=B20 */
export function getSiloCodeForIndex(idx: number): string {
  const rowLetter = idx % 2 === 1 ? 'A' : 'B'
  const colNum = String(Math.ceil(idx / 2)).padStart(2, '0')
  return `${rowLetter}${colNum}`
}

export interface StorageLocationStateV6 {
  locationId: string // 'SILO-01'..'SILO-40' | 'REBAR-BAY-01'..'REBAR-BAY-13' | 'SILO-41'..'SILO-108'
  locationCode: string // 'A01'..'B20' | 'STEEL-BAY-01'..'STEEL-BAY-13' | 'P2-A21'..'P2-B54'
  phase?: 'PHASE1_ACTIVE' | 'PHASE2_RESERVED'
  category: 'CEMENT_SILO' | 'FLYASH_SILO' | 'STEEL_BAY' | 'PHASE2_RESERVED_SILO'
  materialName: string
  batchNo: string | null
  availableTon: number
  bookStockTons?: number | null
  capacityTon: number
  fillRatio: number // 0..1 (账面满仓比)
  levelMeters: number | null // 严禁无传感器时按满仓比冒充，缺失置 null
  physicalLevelM?: number | null
  bookDataSource?: DataProvenance
  physicalLevelSource?: PhysicalLevelSource
  stackLayers?: number
  highLevelWarning: boolean
  lowLevelWarning?: boolean
  sourceSystem: string
  dataSource: DataProvenance
  qualityCode: 'GOOD' | 'UNCERTAIN' | 'FALLBACK' | 'SIM' | 'ISOLATED' | 'RESERVED'
  updatedAt: string
}

export interface ElectronicFenceStateV6 {
  fenceId: string
  name: string
  zoneCode: 'REBAR_HANDOVER' | 'TIPPER_WORKZONE' | 'SILO_LOADING' | 'EAST_GATE' | string
  polygonCad: Array<[number, number]>
  heightM: number
  bufferDistanceM: number
  status: 'ARMED' | 'WARN' | 'BREACHED' | 'BYPASSED'
  breachTarget?: string | null
  dataSource: DataProvenance
  qualityCode: string
  updatedAt: string
}

export interface CameraAiEventV6 {
  eventId: string
  type: string
  confidence: number
  riskCode: 'HIGH' | 'MEDIUM' | 'LOW' | string
  detectedAt: string
}

export interface SurveillanceCameraStateV6 {
  cameraId: string // 'CAM-01'..'CAM-06'
  name: string
  cadX: number
  cadY: number
  cadZ: number
  headingDeg: number
  pitchDeg: number
  fovDeg: number
  rangeM: number
  zoneCode?: string
  status: 'ONLINE' | 'ALERT' | 'OFFLINE'
  streamProtocol: 'HLS' | 'MP4' | 'MJPEG' | 'SNAPSHOT' | 'IFRAME' | 'UNBOUND'
  streamUrl: string | null
  latestAiEvent?: CameraAiEventV6 | null
  dataSource: DataProvenance
}

export interface AntiCollisionInterlockV6 {
  pairId: string
  deviceA: string
  deviceB: string
  distanceM: number
  warnThresholdM: number
  stopThresholdM: number
  state: 'SAFE' | 'WARN' | 'STOP'
  dataSource: DataProvenance
}

export interface SafetyTwinSnapshotV6 {
  regionId: string
  regionIsolated: boolean
  fences: ElectronicFenceStateV6[]
  cameras: SurveillanceCameraStateV6[]
  interlocks: AntiCollisionInterlockV6[]
  source: string
  updatedAt: string
}

export interface DeviceHealthOverlayItemV6 {
  deviceId: string
  deviceName: string
  status: 'ok' | 'standby' | 'fault' | 'offline' | 'maintenance'
  healthScore: number
  healthLevel: 'healthy' | 'attention' | 'risk' | 'critical'
  activeAlarmCount: number
  dataSource: DataProvenance
}

export function formatProvenanceBadge(source?: DataProvenance | string): { label: string; tone: 'live' | 'sim' | 'isolated' } {
  switch (source) {
    case 'LIVE_S2_WMS':
      return { label: 'S2 实测', tone: 'live' }
    case 'LIVE_S3_EHMS':
      return { label: 'S3 实测', tone: 'live' }
    case 'LIVE_S4_SCS':
      return { label: 'S4 实测', tone: 'live' }
    case 'PUBLIC_INBOX_SIM':
      return { label: '公共接收箱', tone: 'sim' }
    case 'REGION_ISOLATED':
      return { label: '区域隔离', tone: 'isolated' }
    case 'PHASE2_RESERVED_EMPTY':
      return { label: '二期预留', tone: 'isolated' }
    default:
      return { label: '兜底推演', tone: 'sim' }
  }
}

export function formatPhysicalLevelText(item?: Pick<StorageLocationStateV6, 'physicalLevelM' | 'levelMeters' | 'physicalLevelSource'> | null): string {
  if (!item) return '—'
  const val = item.physicalLevelM ?? item.levelMeters
  if (typeof val === 'number' && Number.isFinite(val)) {
    return `${val.toFixed(1)} m（X09 实测）`
  }
  if (item.physicalLevelSource === 'REGION_ISOLATED') return '—（区域隔离）'
  if (item.physicalLevelSource === 'UNBOUND_PHASE2_RESERVED') return '—（二期预留空点位）'
  return '未接入物理料位计（X09）'
}

export function buildPhase2ReservedSilosV6(regionId = 'SHEN_TIE'): StorageLocationStateV6[] {
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const isolated = regionId !== 'SHEN_TIE'
  const slots: StorageLocationStateV6[] = []
  for (let i = 41; i <= 108; i++) {
    const code = `P2-${getSiloCodeForIndex(i)}`
    slots.push({
      locationId: `SILO-${String(i).padStart(2, '0')}`,
      locationCode: code,
      phase: 'PHASE2_RESERVED',
      category: 'PHASE2_RESERVED_SILO',
      materialName: '二期预留散料筒仓（场地留白/空点位）',
      batchNo: null,
      availableTon: 0,
      bookStockTons: null,
      capacityTon: 3000,
      fillRatio: 0,
      levelMeters: null,
      physicalLevelM: null,
      bookDataSource: isolated ? 'REGION_ISOLATED' : 'PHASE2_RESERVED_EMPTY',
      physicalLevelSource: isolated ? 'REGION_ISOLATED' : 'UNBOUND_PHASE2_RESERVED',
      highLevelWarning: false,
      lowLevelWarning: false,
      sourceSystem: 'DTS_PHASE2_RESERVED_SLOT',
      dataSource: isolated ? 'REGION_ISOLATED' : 'PHASE2_RESERVED_EMPTY',
      qualityCode: isolated ? 'ISOLATED' : 'RESERVED',
      updatedAt: now,
    })
  }
  return slots
}

export function buildDefaultStorageSnapshotV6(regionId = 'SHEN_TIE'): StorageLocationStateV6[] {
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const isolated = regionId !== 'SHEN_TIE'
  const items: StorageLocationStateV6[] = []

  for (let i = 1; i <= 40; i++) {
    const isCement = i <= 8
    const code = getSiloCodeForIndex(i)
    const tons = isolated ? 0 : isCement ? 2120 + ((i * 95) % 720) : 1680 + ((i * 89) % 1080)
    const ratio = Number((tons / 3000).toFixed(4))
    items.push({
      locationId: `SILO-${String(i).padStart(2, '0')}`,
      locationCode: code,
      phase: 'PHASE1_ACTIVE',
      category: isCement ? 'CEMENT_SILO' : 'FLYASH_SILO',
      materialName: isCement ? 'P·O 42.5 散装水泥' : 'Ⅰ级散装粉煤灰',
      batchNo: isolated ? `${regionId}-ISOLATED` : `SIM-20261123-${code}`,
      availableTon: tons,
      bookStockTons: tons,
      capacityTon: 3000,
      fillRatio: ratio,
      levelMeters: null,
      physicalLevelM: null,
      bookDataSource: isolated ? 'REGION_ISOLATED' : 'FALLBACK_SIM',
      physicalLevelSource: isolated ? 'REGION_ISOLATED' : 'UNBOUND_NO_SENSOR',
      highLevelWarning: !isolated && ratio >= 0.85,
      lowLevelWarning: !isolated && ratio <= 0.15,
      sourceSystem: isolated ? `REGION_ISOLATED_${regionId}` : 'S2-WMS (预留直连)',
      dataSource: isolated ? 'REGION_ISOLATED' : 'FALLBACK_SIM',
      qualityCode: isolated ? 'ISOLATED' : 'SIM',
      updatedAt: now,
    })
  }

  for (let b = 1; b <= 13; b++) {
    const isChook = is32tChookBay(b)
    const tons = isolated ? 0 : 920 + ((b * 110) % 760)
    const ratio = Number((tons / 1800).toFixed(4))
    items.push({
      locationId: `REBAR-BAY-${String(b).padStart(2, '0')}`,
      locationCode: `STEEL-BAY-${String(b).padStart(2, '0')}`,
      phase: 'PHASE1_ACTIVE',
      category: 'STEEL_BAY',
      materialName: isChook ? '盘螺/热轧卷钢 (32t C型钩跨)' : 'HRB400E 螺纹钢 (20t电磁吸盘跨)',
      batchNo: isolated ? `${regionId}-ISOLATED` : `SIM-20261123-B${String(b).padStart(2, '0')}`,
      availableTon: tons,
      bookStockTons: tons,
      capacityTon: 1800,
      fillRatio: ratio,
      levelMeters: null,
      physicalLevelM: null,
      bookDataSource: isolated ? 'REGION_ISOLATED' : 'FALLBACK_SIM',
      physicalLevelSource: isolated ? 'REGION_ISOLATED' : 'UNBOUND_NO_SENSOR',
      stackLayers: isolated ? 0 : Math.max(1, Math.round(ratio * 6)),
      highLevelWarning: !isolated && ratio >= 0.85,
      lowLevelWarning: !isolated && ratio <= 0.15,
      sourceSystem: isolated ? `REGION_ISOLATED_${regionId}` : 'S2-WMS (预留直连)',
      dataSource: isolated ? 'REGION_ISOLATED' : 'FALLBACK_SIM',
      qualityCode: isolated ? 'ISOLATED' : 'SIM',
      updatedAt: now,
    })
  }
  return items
}

export function buildDefaultSafetySnapshotV6(regionId = 'SHEN_TIE'): SafetyTwinSnapshotV6 {
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  const isolated = regionId !== 'SHEN_TIE'
  const src: DataProvenance = isolated ? 'REGION_ISOLATED' : 'FALLBACK_SIM'
  return {
    regionId,
    regionIsolated: true,
    source: isolated ? `区域隔离(${regionId})` : 'S4-SCS 预留直连基线 (给大连大.dwg)',
    updatedAt: now,
    fences: [
      {
        fenceId: 'FENCE-REBAR-HANDOVER',
        name: '钢筋库 07 跨交接区防撞电子围栏',
        zoneCode: 'REBAR_HANDOVER',
        polygonCad: [
          [3716, 1656],
          [3786, 1656],
          [3786, 1676],
          [3716, 1676],
        ],
        heightM: 3.8,
        bufferDistanceM: 15,
        status: isolated ? 'ARMED' : 'WARN',
        breachTarget: null,
        dataSource: src,
        qualityCode: isolated ? 'ISOLATED' : 'SIM',
        updatedAt: now,
      },
      {
        fenceId: 'FENCE-TIPPER-ZONE',
        name: '1# 移动翻箱卸料协同电子围栏',
        zoneCode: 'TIPPER_WORKZONE',
        polygonCad: [
          [3768, 1518],
          [3806, 1518],
          [3806, 1539],
          [3768, 1539],
        ],
        heightM: 3.6,
        bufferDistanceM: 12,
        status: 'ARMED',
        breachTarget: null,
        dataSource: src,
        qualityCode: isolated ? 'ISOLATED' : 'SIM',
        updatedAt: now,
      },
      {
        fenceId: 'FENCE-SILO-LOADING',
        name: '水泥筒仓库底定量散装安全围栏',
        zoneCode: 'SILO_LOADING',
        polygonCad: [
          [3754, 1560],
          [3810, 1560],
          [3810, 1592],
          [3754, 1592],
        ],
        heightM: 3.5,
        bufferDistanceM: 10,
        status: 'ARMED',
        breachTarget: null,
        dataSource: src,
        qualityCode: isolated ? 'ISOLATED' : 'SIM',
        updatedAt: now,
      },
      {
        fenceId: 'FENCE-EAST-GATE',
        name: '东大门进出厂与汽车衡防闯入围栏',
        zoneCode: 'EAST_GATE',
        polygonCad: [
          [4495, 1612],
          [4578, 1612],
          [4578, 1656],
          [4495, 1656],
        ],
        heightM: 3.4,
        bufferDistanceM: 8,
        status: 'ARMED',
        breachTarget: null,
        dataSource: src,
        qualityCode: isolated ? 'ISOLATED' : 'SIM',
        updatedAt: now,
      },
    ],
    cameras: [
      { cameraId: 'CAM-01', name: '东大门进出通道全景球机', cadX: 4570, cadY: 1644, cadZ: 9.2, headingDeg: 195, pitchDeg: -16, fovDeg: 58, rangeM: 72, zoneCode: 'EAST_GATE', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
      { cameraId: 'CAM-02', name: '1# 移动翻箱卸料室 AI 监控', cadX: 3798, cadY: 1515, cadZ: 10.5, headingDeg: 128, pitchDeg: -20, fovDeg: 52, rangeM: 48, zoneCode: 'TIPPER_WORKZONE', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
      { cameraId: 'CAM-03', name: '水泥筒仓库底散装通道枪机', cadX: 3748, cadY: 1568, cadZ: 8.8, headingDeg: 4, pitchDeg: -14, fovDeg: 48, rangeM: 64, zoneCode: 'SILO_LOADING', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
      { cameraId: 'CAM-04', name: '钢筋库 07 跨装车通道高清监控', cadX: 3726, cadY: 1720, cadZ: 11.2, headingDeg: 22, pitchDeg: -18, fovDeg: 56, rangeM: 58, zoneCode: 'REBAR_HANDOVER', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
      { cameraId: 'CAM-05', name: '货4线轨道衡与钢材门吊高位球机', cadX: 3705, cadY: 1624, cadZ: 16.5, headingDeg: 32, pitchDeg: -20, fovDeg: 60, rangeM: 80, zoneCode: 'REBAR_HANDOVER', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
      { cameraId: 'CAM-06', name: '综合办公楼广场与汽车衡全景球机', cadX: 4458, cadY: 1650, cadZ: 12.8, headingDeg: -42, pitchDeg: -16, fovDeg: 62, rangeM: 76, zoneCode: 'EAST_GATE', status: isolated ? 'OFFLINE' : 'ONLINE', streamProtocol: 'UNBOUND', streamUrl: null, latestAiEvent: null, dataSource: src },
    ],
    interlocks: [
      { pairId: 'ILK-CR02-BC07', deviceA: 'CR-02', deviceB: 'BC-07', distanceM: 24.6, warnThresholdM: 15, stopThresholdM: 6, state: 'SAFE', dataSource: src },
      { pairId: 'ILK-CRBULK01-TIP01', deviceA: 'CR-BULK-01', deviceB: 'TIPPER-01', distanceM: 18.2, warnThresholdM: 15, stopThresholdM: 6, state: 'SAFE', dataSource: src },
      { pairId: 'ILK-CR01-CR02', deviceA: 'CR-01', deviceB: 'CR-02', distanceM: 42.0, warnThresholdM: 15, stopThresholdM: 6, state: 'SAFE', dataSource: src },
    ],
  }
}

export function normalizeStorageListV6(raw: unknown, regionId = 'SHEN_TIE'): StorageLocationStateV6[] {
  const defaults = buildDefaultStorageSnapshotV6(regionId)
  if (!Array.isArray(raw) || raw.length === 0) return defaults
  const byId = new Map<string, StorageLocationStateV6>(defaults.map((item) => [item.locationId, item]))
  for (const r of raw) {
    if (!r || typeof r !== 'object') continue
    const obj = r as Record<string, unknown>
    const locId = String(obj.locationId ?? '')
    if (!locId) continue
    const base = byId.get(locId)
    const cap = Number(obj.capacityTon ?? base?.capacityTon ?? 3000) || 3000
    const avail = Number(obj.availableTon ?? base?.availableTon ?? 0)
    const ratio =
      obj.fillRatio != null
        ? Math.max(0, Math.min(1, Number(obj.fillRatio)))
        : Math.max(0, Math.min(1, avail / Math.max(1, cap)))
    const rawPhys = obj.physicalLevelM !== undefined ? obj.physicalLevelM : obj.levelMeters
    const parsedPhys = typeof rawPhys === 'number' && Number.isFinite(rawPhys) ? rawPhys : null
    const physSource =
      (obj.physicalLevelSource as PhysicalLevelSource | undefined) ??
      (parsedPhys != null ? 'LIVE_X09_RADAR' : base?.physicalLevelSource ?? 'UNBOUND_NO_SENSOR')
    byId.set(locId, {
      locationId: locId,
      locationCode: String(obj.locationCode ?? base?.locationCode ?? locId),
      phase: (obj.phase as StorageLocationStateV6['phase']) ?? base?.phase ?? 'PHASE1_ACTIVE',
      category: (obj.category as StorageLocationStateV6['category']) ?? base?.category ?? 'CEMENT_SILO',
      materialName: String(obj.materialName ?? base?.materialName ?? ''),
      batchNo: obj.batchNo === null ? null : String(obj.batchNo ?? base?.batchNo ?? ''),
      availableTon: avail,
      bookStockTons: typeof obj.bookStockTons === 'number' ? obj.bookStockTons : avail,
      capacityTon: cap,
      fillRatio: Number(ratio.toFixed(4)),
      levelMeters: parsedPhys,
      physicalLevelM: parsedPhys,
      bookDataSource: (obj.bookDataSource as DataProvenance) ?? (obj.dataSource as DataProvenance) ?? base?.bookDataSource ?? 'FALLBACK_SIM',
      physicalLevelSource: physSource,
      stackLayers: obj.stackLayers != null ? Number(obj.stackLayers) : base?.stackLayers,
      highLevelWarning: Boolean(obj.highLevelWarning ?? ratio >= 0.85),
      lowLevelWarning: Boolean(obj.lowLevelWarning ?? ratio <= 0.15),
      sourceSystem: String(obj.sourceSystem ?? base?.sourceSystem ?? 'S2-WMS'),
      dataSource: (obj.dataSource as DataProvenance) ?? base?.dataSource ?? 'FALLBACK_SIM',
      qualityCode: (obj.qualityCode as StorageLocationStateV6['qualityCode']) ?? base?.qualityCode ?? 'FALLBACK',
      updatedAt: String(obj.updatedAt ?? base?.updatedAt ?? ''),
    })
  }
  return Array.from(byId.values())
}

export function normalizeSafetySnapshotV6(raw: unknown, regionId = 'SHEN_TIE'): SafetyTwinSnapshotV6 {
  const base = buildDefaultSafetySnapshotV6(regionId)
  if (!raw || typeof raw !== 'object') return base
  const obj = raw as Record<string, unknown>
  return {
    regionId: String(obj.regionId ?? regionId),
    regionIsolated: Boolean(obj.regionIsolated ?? true),
    fences: Array.isArray(obj.fences) && obj.fences.length > 0 ? (obj.fences as ElectronicFenceStateV6[]) : base.fences,
    cameras: Array.isArray(obj.cameras) && obj.cameras.length > 0 ? (obj.cameras as SurveillanceCameraStateV6[]) : base.cameras,
    interlocks:
      Array.isArray(obj.interlocks) && obj.interlocks.length > 0
        ? (obj.interlocks as AntiCollisionInterlockV6[])
        : base.interlocks,
    source: String(obj.source ?? base.source),
    updatedAt: String(obj.updatedAt ?? base.updatedAt),
  }
}

export function getSiloCadById(locationId: string) {
  return SITE_LAYOUT_V4.ash.silos.find((s) => s.id === locationId) ?? null
}

export function getRebarBayCadById(locationId: string) {
  return SITE_LAYOUT_V4.rebar.bays.find((b) => b.id === locationId) ?? null
}
