/**
 * S5 双模式数据服务（严格遵守“只监不控”只读边界）：
 * - 模式 1（realtime_readonly）：优先只读轮询 S1~S4 数据库网关 REST API（/api/v1/dts/*）与原生 WS；
 *   若内网/离线不可达则自动降级到本地推演并显式标注数据质量码。
 * - 模式 2（offline_sim）：大连线下离线演示模式，按两份 .eddx 工艺逻辑确定性推演。
 */
import type { AlertItem, useAlertStore } from '@/stores/alert'
import { buildDefaultDeviceSnapshot, type DeviceStatusCode, type DeviceTelemetry, type useDeviceStore } from '@/stores/device'
import { WS_URL } from '@/config/runtime'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'
import { restClient } from './restClient'
import { wsClient } from './wsClient'

export type DataSourceMode = 'offline_sim' | 'realtime_readonly'
export type DataQualityTag = 'GOOD' | 'SIM' | 'FALLBACK' | 'OFFLINE'
export type RegionCode = 'SHEN_TIE' | 'XIZANG_TIE' | 'WUHAN_TIE'

export const REGION_OPTIONS: Array<{ id: RegionCode; label: string; station: string }> = [
  { id: 'SHEN_TIE', label: '沈铁示范库区（联调基线）', station: '中沙坝示范联调场站' },
  { id: 'XIZANG_TIE', label: '青藏铁路局集团', station: '林芝米林中沙坝转运站' },
  { id: 'WUHAN_TIE', label: '武汉铁路局集团', station: '华中铁水联运中转站' },
]

export const DEFAULT_DATA_MODE: DataSourceMode =
  import.meta.env.VITE_DEFAULT_DATA_MODE === 'realtime_readonly' ? 'realtime_readonly' : 'offline_sim'

export interface DtsFeedStatus {
  mode: DataSourceMode
  quality: DataQualityTag
  sourceLabel: string
  lastSyncAt: string
  errorNote: string
  regionId?: RegionCode
  upstreamCount?: number
  inboxEventsCount?: number
}

export interface CrossSystemLink {
  key: 'S1' | 'S2' | 'S3' | 'S4'
  name: string
  url: string
  badge: string
}

export interface VendorControlSystem {
  id: 'CTRL-BULK' | 'CTRL-CRANE' | 'CTRL-TIPPER'
  name: string
  category: '外购厂家控制系统' | '合研装备控制系统'
  protocol: string
  boundDevices: string[]
  interlockSummary: string
  plcSignals: Array<{ tag: string; label: string; value: string; status: 'ok' | 'warn' }>
}

export const VENDOR_CONTROL_SYSTEMS: VendorControlSystem[] = [
  {
    id: 'CTRL-BULK',
    name: '散料智能入库 / 库底定量装车控制系统',
    category: '外购厂家控制系统',
    protocol: 'OPC UA / Profinet 只读镜像',
    boundDevices: ['ELV-01~04', 'SILO-01~40', 'LOADER-01~04'],
    interlockSummary: '42m³料斗低料位联锁 + 仓顶脉冲除尘压差联锁 + 库底伸缩溜管静电接地联锁',
    plcSignals: [
      { tag: 'DB101.ELV01_AMP', label: '1# 40m斗提机主电机电流', value: '312.4 A (正常)', status: 'ok' },
      { tag: 'DB101.SLIDE_AIR_KPA', label: '仓顶空气斜槽罗茨风机母管气压', value: '94.2 kPa', status: 'ok' },
      { tag: 'DB102.SILO01_RADAR', label: 'SILO-01 雷达料位计/高料位联锁', value: '20.8 m / 未触发高限', status: 'ok' },
      { tag: 'DB102.DUST_DP_KPA', label: '仓顶脉冲袋式除尘器压差', value: '1.22 kPa (反吹正常)', status: 'ok' },
      { tag: 'DB103.LOADER01_SPOUT', label: 'LOADER-01 散装头伸缩行程/底阀', value: '3.40 m 对接锁紧 / 闭环定量', status: 'ok' },
      { tag: 'DB103.TRUCK_GND_LOCK', label: '罐车静电接地夹与电子围栏回执', value: '接地电阻 1.8Ω · 围栏闭锁', status: 'ok' },
    ],
  },
  {
    id: 'CTRL-CRANE',
    name: '门式起重机 / 室内桥式起重机控制系统',
    category: '外购厂家控制系统',
    protocol: 'Modbus TCP / 工业以太网只读采集',
    boundDevices: ['CR-01~03', 'CR-BULK-01~03', 'BC-01~13', 'BC-MECH-01~02'],
    interlockSummary: '同轨激光防撞测距 (预警15m/停机6m) + 大风防爬铁鞋联锁 + 跨间转接区防撞驻车',
    plcSignals: [
      { tag: 'PLC_CR02.GANTRY_X', label: 'CR-02 钢材门吊大车绝对编码器', value: 'X = 3737.0 m (转接位)', status: 'ok' },
      { tag: 'PLC_CR02.LASER_GAP', label: '同轨激光防撞测距仪 (CR-01↔CR-02)', value: '28.5 m (>15m 安全)', status: 'ok' },
      { tag: 'PLC_CR02.WIND_LOCK', label: '场站风速仪与防风铁鞋联锁', value: '4.2 m/s · 夹轨器释放', status: 'ok' },
      { tag: 'PLC_BC07.MAGNET_CUR', label: 'BC-07 20t电磁吸盘励磁电流/保磁', value: '38.6 A · 断电保磁使能', status: 'ok' },
      { tag: 'PLC_BC03.CHOOK_LOCK', label: 'BC-03 32t C型卷材吊具平衡梁锁销', value: '锁止到位 · 防摇开启', status: 'ok' },
      { tag: 'PLC_HANDOVER.BAY07', label: '钢筋库07跨跨间交接区光电联锁', value: '门吊撤离确认 · 允许桥吊进入', status: 'ok' },
    ],
  },
  {
    id: 'CTRL-TIPPER',
    name: '移动式集装箱翻箱机控制系统',
    category: '合研装备控制系统',
    protocol: 'S7-1500 Profinet 只读数据块',
    boundDevices: ['TIPPER-01~04', 'CONT-BULK-01'],
    interlockSummary: '门吊吊具离空闭锁 + 四角旋锁锁紧 + 0°~135°双液压缸同步倾角 + 卸料口关闭复核',
    plcSignals: [
      { tag: 'TIP01.TWIST_LOCK', label: '集装箱四角液压旋锁状态', value: '4/4 锁紧闭锁 (允许翻转)', status: 'ok' },
      { tag: 'TIP01.TILT_ANGLE', label: '翻转架双缸同步倾角传感器', value: '0.0° ~ 135.0° (同步误差 <0.15°)', status: 'ok' },
      { tag: 'TIP01.HYD_PRESS', label: '主液压站系统压力 / 油温', value: '16.8 MPa / 41.5 ℃', status: 'ok' },
      { tag: 'TIP01.VIBRATOR', label: '高频附着式振动拍打清箱电机', value: '间歇拍打模式 · 达交箱标准', status: 'ok' },
      { tag: 'TIP01.HATCH_CHECK', label: '集装箱尾门/卸料口关闭光电复核', value: '已关闭确认 · 允许翻转架回0°', status: 'ok' },
      { tag: 'TIP01.CRANE_CLEAR', label: 'CR-BULK-01 门吊吊具避让联锁', value: '吊具高 >16m · 翻箱区域净空', status: 'ok' },
    ],
  },
]

interface RemoteDeviceRow {
  device_id?: string
  id?: string
  device_name?: string
  name?: string
  status?: DeviceStatusCode
  health_score?: number
  risk_subsystem?: string
  predicted_rul_days?: number
  risk_reason?: string
  quality_code?: string
  source_system?: string
  telemetry?: Partial<DeviceTelemetry>
}

interface RemoteAlertRow {
  id?: string
  level?: AlertItem['level']
  target_id?: string
  target_name?: string
  message?: string
  safety_distance_m?: number
  source_system?: 'S4-SCS' | 'S3-EHMS' | 'S1-PSMS' | 'S2-WMS'
}

const VALID_STATUSES = new Set<DeviceStatusCode>(['ok', 'standby', 'fault', 'offline', 'maintenance'])

const V4_TO_EHMS_ASSET_CODE: Record<string, string> = {
  'CR-01': 'EQ-CRANE-01',
  'CR-02': 'EQ-CRANE-02',
  'CR-03': 'EQ-CRANE-03',
  'CR-BULK-01': 'EQ-CRANE-04',
  'CR-BULK-02': 'EQ-CRANE-05',
  'CR-BULK-03': 'EQ-CRANE-06',
  'TIPPER-01': 'EQ-CONVEYOR-01',
  'TIPPER-02': 'EQ-CONVEYOR-02',
  'TIPPER-03': 'EQ-CONVEYOR-03',
  'TIPPER-04': 'EQ-CONVEYOR-04',
  'ELV-01': 'EQ-ELEVATOR-01',
  'ELV-02': 'EQ-ELEVATOR-02',
  'ELV-03': 'EQ-ELEVATOR-03',
  'ELV-04': 'EQ-ELEVATOR-04',
  'LOADER-01': 'EQ-VALVE-01',
  'LOADER-02': 'EQ-VALVE-02',
  'LOADER-03': 'EQ-VALVE-03',
  'LOADER-04': 'EQ-VALVE-04',
}

export function getPortalUrl(): string {
  const envPortal = import.meta.env.VITE_URL_PORTAL
  if (envPortal) return String(envPortal)
  return 'https://bproject.de5.net/portal/'
}

export function getCrossSystemLinks(): CrossSystemLink[] {
  const env = import.meta.env
  return [
    {
      key: 'S1',
      name: 'S1 装卸工艺管理系统',
      url: String(env.VITE_URL_S1 || 'https://psms.bproject.de5.net/production-dispatch/'),
      badge: '工艺调度',
    },
    {
      key: 'S2',
      name: 'S2 装卸区仓储管理系统 (WMS)',
      url: String(env.VITE_URL_S2 || 'https://wms.bproject.de5.net/'),
      badge: '仓位台账',
    },
    {
      key: 'S3',
      name: 'S3 设备健康与维护系统 (EHMS)',
      url: String(env.VITE_URL_S3 || 'https://ehms.bproject.de5.net/ehm/'),
      badge: 'BOM健康',
    },
    {
      key: 'S4',
      name: 'S4 安全监控与联锁系统 (SCS)',
      url: String(env.VITE_URL_S4 || 'https://scs.bproject.de5.net/'),
      badge: '防撞联锁',
    },
  ]
}

export function buildEhmsAssetUrl(baseUrl: string, deviceId?: string): string {
  try {
    const u = new URL(baseUrl, window.location.origin)
    if (!u.pathname.includes('/ehm')) {
      u.pathname = `${u.pathname.replace(/\/+$/, '')}/ehm/`
    }
    if (deviceId) {
      const assetCode = V4_TO_EHMS_ASSET_CODE[deviceId] ?? deviceId
      u.searchParams.set('page', 'asset-profile')
      u.searchParams.set('assetCode', assetCode)
      u.searchParams.set('deviceId', deviceId)
    }
    return u.href
  } catch {
    return baseUrl
  }
}

export class DtsDataService {
  private timer: ReturnType<typeof setInterval> | null = null
  private mode: DataSourceMode = 'offline_sim'
  private regionId: RegionCode = 'SHEN_TIE'
  private wsRegistered = false
  private tickCount = 0

  private readonly deviceStore: ReturnType<typeof useDeviceStore>
  private readonly alertStore: ReturnType<typeof useAlertStore>
  private readonly onStatus?: (status: DtsFeedStatus) => void

  constructor(
    deviceStore: ReturnType<typeof useDeviceStore>,
    alertStore: ReturnType<typeof useAlertStore>,
    onStatus?: (status: DtsFeedStatus) => void,
    initialRegionId?: RegionCode,
  ) {
    this.deviceStore = deviceStore
    this.alertStore = alertStore
    this.onStatus = onStatus
    if (initialRegionId) {
      this.regionId = initialRegionId
    } else if (typeof window !== 'undefined') {
      const q = new URL(window.location.href).searchParams.get('regionId')
      if (q === 'SHEN_TIE' || q === 'XIZANG_TIE' || q === 'WUHAN_TIE') {
        this.regionId = q
      }
    }
  }

  getRegionId(): RegionCode {
    return this.regionId
  }

  setRegionId(nextRegion: RegionCode) {
    if (this.regionId === nextRegion) return
    this.regionId = nextRegion
    this.alertStore.clear()
    if (this.mode === 'realtime_readonly') {
      void this.pollReadOnlyDatabase()
    } else {
      this.emitStatus(this.mode, 'SIM', `离线工艺仿真推演（${this.regionId}）`)
    }
  }

  seedInitial() {
    this.deviceStore.batchUpdate(
      MOCK_EQUIPMENT.map((item) => buildDefaultDeviceSnapshot(item.id, item.name, item.status)),
    )
    this.emitStatus(this.mode, this.mode === 'offline_sim' ? 'SIM' : 'FALLBACK', '本地 V4 台账已就绪')
  }

  setMode(nextMode: DataSourceMode) {
    this.mode = nextMode
    if (this.timer) {
      this.stop()
      this.start(nextMode)
    } else {
      this.emitStatus(nextMode, nextMode === 'offline_sim' ? 'SIM' : 'FALLBACK', '数据源已切换')
    }
  }

  start(mode: DataSourceMode = this.mode, intervalMs = 4500) {
    this.stop()
    this.mode = mode
    this.seedInitial()

    if (mode === 'realtime_readonly') {
      void this.pollReadOnlyDatabase()
      if (WS_URL) {
        wsClient.connect()
        if (!this.wsRegistered) {
          this.wsRegistered = true
          wsClient.on<RemoteDeviceRow>('device.status', (msg) => {
            if (this.mode !== 'realtime_readonly') return
            const payload = msg.data
            const id = payload?.device_id ?? payload?.id
            if (id && payload?.status && VALID_STATUSES.has(payload.status)) {
              this.deviceStore.updateDevice(id, payload.status)
              this.emitStatus('realtime_readonly', 'GOOD', 'WS 实时推流（只读）')
            }
          })
        }
      }
      this.timer = setInterval(() => void this.pollReadOnlyDatabase(), intervalMs)
      return
    }

    this.emitStatus('offline_sim', 'SIM', '离线工艺仿真推演（给大连大.dwg）')
    this.timer = setInterval(() => this.stepOfflineSimulation(), intervalMs)
  }

  stop() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    if (this.mode === 'realtime_readonly') wsClient.close()
  }

  private async pollReadOnlyDatabase() {
    try {
      const qs = `?regionId=${encodeURIComponent(this.regionId)}`
      const [overviewRes, devRes, alertRes] = await Promise.all([
        restClient
          .get<{ inbox_events_count?: number; upstream?: Record<string, unknown>; region_note?: string }>(`/dts/overview${qs}`, { timeout: 3500 })
          .catch(() => null),
        restClient.get<{ items?: RemoteDeviceRow[]; source?: string; region_note?: string } | RemoteDeviceRow[]>(`/dts/devices${qs}`, { timeout: 3500 }),
        restClient.get<{ items?: RemoteAlertRow[]; source?: string } | RemoteAlertRow[]>(`/dts/alerts${qs}`, { timeout: 3500 }),
      ])
      const devRows = Array.isArray(devRes) ? devRes : (devRes.items ?? [])
      const alertRows = Array.isArray(alertRes) ? alertRes : (alertRes.items ?? [])
      if (devRows.length > 0) {
        this.deviceStore.batchUpdate(
          devRows
            .filter((r) => Boolean(r.device_id ?? r.id))
            .map((r) => {
              const id = String(r.device_id ?? r.id)
              const name = String(r.device_name ?? r.name ?? id)
              const status = r.status && VALID_STATUSES.has(r.status) ? r.status : 'ok'
              const base = buildDefaultDeviceSnapshot(id, name, status)
              return {
                ...base,
                telemetry: r.telemetry ? { ...base.telemetry!, ...r.telemetry } : base.telemetry,
                ehms:
                  r.health_score != null
                    ? {
                        ...base.ehms!,
                        healthScore: r.health_score,
                        healthLevel: r.health_score < 75 ? 'risk' : r.health_score < 86 ? 'attention' : 'healthy',
                        riskSubsystem: r.risk_subsystem ?? base.ehms!.riskSubsystem,
                        predictedRulDays: r.predicted_rul_days ?? base.ehms!.predictedRulDays,
                        riskReason: r.risk_reason ?? base.ehms!.riskReason,
                      }
                    : base.ehms,
              }
            }),
        )
      }
      if (alertRows.length === 0 && this.regionId !== 'SHEN_TIE') {
        this.alertStore.clear()
      }
      for (const a of alertRows.slice(0, 5)) {
        if (a.message) {
          this.alertStore.addAlert({
            id: a.id ?? `DB-${Date.now()}`,
            level: a.level ?? 'warning',
            targetId: a.target_id ?? 'CR-02',
            targetName: a.target_name ?? '现场设备',
            message: a.message,
            interlockReceipt: {
              sourceSystem: a.source_system ?? 'S4-SCS',
              safetyDistanceM: a.safety_distance_m,
              actions: a.level === 'critical' ? ['声光报警', '大屏弹窗', '联锁停机'] : ['声光报警', '大屏弹窗'],
            },
          })
        }
      }
      const sourceMeta = !Array.isArray(devRes) && devRes.source ? ` · ${devRes.source}` : ''
      const regionNote = !Array.isArray(devRes) && devRes.region_note ? ` (${devRes.region_note})` : ''
      const upstreamCount = overviewRes?.upstream ? Object.keys(overviewRes.upstream).length : undefined
      const inboxCount = overviewRes?.inbox_events_count
      this.emitStatus(
        'realtime_readonly',
        'GOOD',
        `只读底座 (/api/v1/dts · ${this.regionId})${sourceMeta}${regionNote}`,
        '',
        upstreamCount,
        inboxCount,
      )
    } catch (err) {
      // Explicit transparent fallback when unified openGauss backend is offline
      this.stepOfflineSimulation()
      const reason = err instanceof Error ? err.message : '后端网关离线'
      this.emitStatus('realtime_readonly', 'FALLBACK', `库接口不可达 (${reason}) · 已降级本地推演`, reason)
    }
  }

  private stepOfflineSimulation() {
    this.tickCount += 1
    const allIds = MOCK_EQUIPMENT.map((e) => e.id)
    const idx = this.tickCount % allIds.length
    const targetId = allIds[idx]
    const nextStatus: DeviceStatusCode = this.tickCount % 7 === 0 ? 'maintenance' : this.tickCount % 5 === 0 ? 'standby' : 'ok'
    this.deviceStore.updateDevice(targetId, nextStatus, {
      windSpeedMps: Number((3.4 + ((this.tickCount * 7) % 15) * 0.25).toFixed(1)),
    })

    if (this.tickCount % 3 === 1) {
      this.alertStore.addAlert({
        id: `SCS-SIM-${this.tickCount}`,
        level: 'warning',
        targetId: 'TIPPER-01',
        targetName: '1# 移动翻箱机 / 门吊防碰撞',
        message: 'CR-BULK-01 与 TIPPER-01 协同作业区安全距离 13.6m（<15m 预警线，正常避让中）',
        interlockReceipt: {
          sourceSystem: 'S4-SCS',
          safetyDistanceM: 13.6,
          actions: ['声光报警', '大屏弹窗', '信息通知'],
        },
      })
    }
    if (this.mode === 'offline_sim') {
      this.emitStatus('offline_sim', 'SIM', '本地工艺仿真推演（给大连大.dwg）')
    }
  }

  private emitStatus(
    mode: DataSourceMode,
    quality: DataQualityTag,
    sourceLabel: string,
    errorNote = '',
    upstreamCount?: number,
    inboxEventsCount?: number,
  ) {
    this.onStatus?.({
      mode,
      quality,
      sourceLabel,
      lastSyncAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      errorNote,
      regionId: this.regionId,
      upstreamCount,
      inboxEventsCount,
    })
  }
}
