/**
 * S5 双模式数据服务（严格遵守“只监不控”只读边界）：
 * - 模式 1（realtime_readonly）：优先只读轮询 S1~S4 数据库网关 REST API（/api/v1/dts/*）与原生 WS；
 *   若内网/离线不可达则自动降级到本地推演并显式标注数据质量码。
 * - 模式 2（offline_sim）：大连线下离线演示模式，按两份 .eddx 工艺逻辑确定性推演。
 *
 * 全面对齐组长《系统需求功能工作簿 v2.0/v2.1》：
 * - R-D01 ~ R-D05 业务需求 ↔ F-D01 ~ F-D08 系统功能多对多双向挂载
 * - R-D03 显式拆分为 F-D06（固定工艺演示播放 SIM）与 F-D07（S1 真实任务节点接入与展示 X04）
 * - F-D04（S2 仓储库位与 40 座筒仓料位映射 X08）及 X03/X04/X05/X08~X11 跨系统带参联查
 */
import type { AlertItem, useAlertStore } from '@/stores/alert'
import { buildDefaultDeviceSnapshot, type DeviceStatusCode, type DeviceTelemetry, type useDeviceStore } from '@/stores/device'
import { WS_URL } from '@/config/runtime'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'
import { restClient } from './restClient'
import { wsClient } from './wsClient'

export type DataSourceMode = 'offline_sim' | 'realtime_readonly'
export type ProcessTrackMode = 'f_d06_sim' | 'f_d07_live'
export type DataQualityTag = 'GOOD' | 'SIM' | 'FALLBACK' | 'OFFLINE'
export type RegionCode = 'SHEN_TIE' | 'XIZANG_TIE' | 'WUHAN_TIE'

export const REGION_OPTIONS: Array<{ id: RegionCode; label: string; station: string }> = [
  { id: 'SHEN_TIE', label: '沈铁示范库区（联调基线）', station: '中沙坝示范联调场站' },
  { id: 'XIZANG_TIE', label: '青藏铁路局集团', station: '林芝米林中沙坝转运站' },
  { id: 'WUHAN_TIE', label: '武汉铁路局集团', station: '华中铁水联运中转站' },
]

export const DEFAULT_DATA_MODE: DataSourceMode =
  import.meta.env.VITE_DEFAULT_DATA_MODE === 'realtime_readonly' ? 'realtime_readonly' : 'offline_sim'

export interface LiveTaskNodeItem {
  taskId: string
  planNo: string
  cargoName: string
  tonnage: number
  processChain: 'BULK_CEMENT_FLYASH' | 'STEEL_REBAR_20T' | 'STEEL_COIL_32T'
  processKind: 'bulk' | 'steel'
  steelSubMode: 'rebar_magnet' | 'coil_chook'
  currentStepIndex: number
  stepStatus: 'PENDING' | 'RUNNING' | 'HANDOVER_WAIT' | 'COMPLETED' | 'EXCEPTION'
  handoverWaitReason: string
  assignedDevices: string[]
  focusDeviceId: string
  sourceSystem: string
  qualityCode: 'GOOD' | 'FALLBACK' | 'SIM'
  contractNote: string
  updatedAt: string
}

export interface StorageLocationSummary {
  locationId: string
  locationCode: string
  category: 'CEMENT_SILO' | 'FLYASH_SILO' | 'STEEL_BAY'
  materialName: string
  batchNo: string
  availableTon: number
  capacityTon: number
  levelMeters: number
  highLevelWarning: boolean
  sourceSystem: string
  qualityCode: 'GOOD' | 'FALLBACK' | 'SIM'
  updatedAt: string
}

export interface RequirementFunctionItem {
  fnId: 'F-D01' | 'F-D02' | 'F-D03' | 'F-D04' | 'F-D05' | 'F-D06' | 'F-D07' | 'F-D08'
  fnName: string
  reqIds: string
  collabCode: string
  partnerSystem: string
  roleInReq: string
  reuseRelation: string
  statusLabel: string
  actionLabel: string
}

export const REQUIREMENT_FUNCTION_MATRIX: RequirementFunctionItem[] = [
  {
    fnId: 'F-D01',
    fnName: '装卸区域场景与图层展示',
    reqIds: 'R-D01',
    collabCode: '本系统核心',
    partnerSystem: '中铁二院《给大连大.dwg》实测底图',
    roleInReq: '展示散料区(40座筒仓/翻箱/提升/散装)与钢材区(13跨钢筋库/门吊/桥吊)三维场景与图层开关',
    reuseRelation: '支撑 R-D01 装卸工艺区场景可视化',
    statusLabel: '已上线 (V4.2)',
    actionLabel: '全景复位核验',
  },
  {
    fnId: 'F-D02',
    fnName: 'CAD与设备模型落位校核',
    reqIds: 'R-D01',
    collabCode: '本系统核心',
    partnerSystem: '《给大连大.dwg》+ 两份 11.14 .eddx',
    roleInReq: '核对 76 项三维对象(36台设备+40座筒仓)与 CAD 实测坐标 1:1 叠合，区分实测与远期缓建示意区',
    reuseRelation: '支撑 R-D01 图纸一致性核验（/dts/calibration）',
    statusLabel: '已上线 (76项台账)',
    actionLabel: '打开CAD校核页',
  },
  {
    fnId: 'F-D03',
    fnName: '设备对象映射与状态面板',
    reqIds: 'R-D02 / R-D04 / R-E08',
    collabCode: '协作 X03 / X09 / X10',
    partnerSystem: 'S3 EHMS(闫鑫钰) + 厂家控制系统(董博涵)',
    roleInReq: '单点拾取 36 台设备展示实时状态、差异化遥测、EHMS 健康分/BOM风险与厂家 PLC 只读信号',
    reuseRelation: '跨系统共享功能；同时支撑 R-D02、R-D04、R-E08、R-B01~03、R-C01~03',
    statusLabel: '已上线 (14/14 PASS)',
    actionLabel: '拾取CR-02核验',
  },
  {
    fnId: 'F-D04',
    fnName: '仓储库位与40座筒仓料位状态映射及联查',
    reqIds: 'R-D01 / R-D02 / R-W01/05/06',
    collabCode: '协作 X08 (新增补缺)',
    partnerSystem: 'S2 装卸区仓储 WMS(周绪敬)',
    roleInReq: '映射 40 座 3000T 筒仓(SILO-01..40)料位/吨数与 13 跨钢筋库(REBAR-BAY-01..13)批次库存并带参跳 S2',
    reuseRelation: '跨系统共享功能；补齐组长表空缺编号 F-D04，支撑 R-D01、R-D02 与 S2 库存联查',
    statusLabel: '已上线 (40仓+13跨)',
    actionLabel: '聚焦SILO-01核验',
  },
  {
    fnId: 'F-D05',
    fnName: '异常定位与业务详情联查',
    reqIds: 'R-D04 / R-E08 / R-S06',
    collabCode: '协作 X03 / X04 / X05',
    partnerSystem: 'S1(李润泽) / S3(闫鑫钰) / S4(敬昌隆)',
    roleInReq: '告警自动三维聚焦、打开 CAM-01..04 工位关联视频+厂家 PLC 核查浮窗，一键带参跳 S1~S4 处置',
    reuseRelation: '跨系统共享功能；同时支撑 R-D04、R-E08(EHMS)、R-S06(SCS)',
    statusLabel: '已上线 (S1~S4 200)',
    actionLabel: '触发视频+联查核验',
  },
  {
    fnId: 'F-D06',
    fnName: '固定工艺演示播放 (SIM)',
    reqIds: 'R-D03',
    collabCode: '本系统演示',
    partnerSystem: '两份 2026-11-14 .eddx 工艺细化图',
    roleInReq: '显式标 SIM，支持散料(135°翻箱复位+空箱回吊)与钢材(20t磁吸/32t C型钩) 1~5 步任意跳步与单步驻留',
    reuseRelation: '支撑 R-D03 模拟演示路径（与 F-D07 真实节点严格区分）',
    statusLabel: '已上线 (自由跳步)',
    actionLabel: '切换F-D06演示轨',
  },
  {
    fnId: 'F-D07',
    fnName: '真实任务节点接入与展示 (LIVE)',
    reqIds: 'R-D03 / R-P05',
    collabCode: '协作 X04',
    partnerSystem: 'S1 工艺管理系统 PSMS(李润泽)',
    roleInReq: '只读接入 S1 /api/plans 真实计划主账，映射 1~5 工序节点与交接等待原因，点击工单联动三维设备并跳 S1',
    reuseRelation: '跨系统共享功能；同时支撑 R-D03 与 PSMS R-P05(F-P13)',
    statusLabel: '已接通S1 (40条计划)',
    actionLabel: '切换F-D07真实轨',
  },
  {
    fnId: 'F-D08',
    fnName: '总体看板无框内嵌、多区域隔离与只读安全网关',
    reqIds: 'R-D05 / R-PUB01',
    collabCode: '协作 X11 (PPT第39页)',
    partnerSystem: '上位信息专业总体看板 + 统一门户(雷文)',
    roleInReq: '支持 ?embed=1 无框内嵌与 ?deviceId=/taskId= URL 定位，沈铁/西藏/武汉区域隔离，非GET写请求 405 拦截',
    reuseRelation: '支撑 R-D05 统一入口与全系统只监不控红线',
    statusLabel: '已上线 (405硬拦截)',
    actionLabel: '查看PLC/只读网关',
  },
]

export const DEFAULT_LIVE_TASKS: LiveTaskNodeItem[] = [
  {
    taskId: 'PSMS-20261117-01',
    planNo: 'PLN-ASH-20261117-001',
    cargoName: '散装粉煤灰专列（货1线 20t集装箱×16）',
    tonnage: 512.0,
    processChain: 'BULK_CEMENT_FLYASH',
    processKind: 'bulk',
    steelSubMode: 'rebar_magnet',
    currentStepIndex: 1,
    stepStatus: 'RUNNING',
    handoverWaitReason: '',
    assignedDevices: ['CR-BULK-01', 'TIPPER-01', 'ELV-01', 'SILO-09', 'LOADER-02'],
    focusDeviceId: 'TIPPER-01',
    sourceSystem: 'S1-PSMS (/api/plans)',
    qualityCode: 'GOOD',
    contractNote: '计划主账来自S1；当前处于第2步[135°翻箱倾卸与振动清箱]',
    updatedAt: '10:42:15',
  },
  {
    taskId: 'PSMS-20261117-02',
    planNo: 'PLN-REB-20261117-002',
    cargoName: 'HRB400E 螺纹钢专列（货3线 07跨入库）',
    tonnage: 240.0,
    processChain: 'STEEL_REBAR_20T',
    processKind: 'steel',
    steelSubMode: 'rebar_magnet',
    currentStepIndex: 1,
    stepStatus: 'HANDOVER_WAIT',
    handoverWaitReason: '跨间转接区B：CR-02 门吊与 BC-07 室内桥吊防撞安全净空复核（待S1调度确认放行）',
    assignedDevices: ['CR-02', 'BC-07', 'WB-TRUCK-01'],
    focusDeviceId: 'CR-02',
    sourceSystem: 'S1-PSMS (/api/plans)',
    qualityCode: 'GOOD',
    contractNote: '对应 PPT 第40页“交接等待”典型工单；可一键跳 S1 处置',
    updatedAt: '10:43:02',
  },
  {
    taskId: 'PSMS-20261117-03',
    planNo: 'PLN-COIL-20261117-003',
    cargoName: '盘螺/卷材入库工单（货4线 03跨32t C型钩）',
    tonnage: 192.0,
    processChain: 'STEEL_COIL_32T',
    processKind: 'steel',
    steelSubMode: 'coil_chook',
    currentStepIndex: 2,
    stepStatus: 'RUNNING',
    handoverWaitReason: '',
    assignedDevices: ['CR-01', 'BC-03', 'WB-TRUCK-02'],
    focusDeviceId: 'BC-03',
    sourceSystem: 'S1-PSMS (/api/plans)',
    qualityCode: 'GOOD',
    contractNote: '32t C型卷材钩桥吊 BC-03 跨内转运落位中',
    updatedAt: '10:41:50',
  },
  {
    taskId: 'PSMS-20261117-04',
    planNo: 'PLN-CEM-20261117-004',
    cargoName: 'P·O 42.5 水泥库底定量装车（SILO-01→青B·88219）',
    tonnage: 48.5,
    processChain: 'BULK_CEMENT_FLYASH',
    processKind: 'bulk',
    steelSubMode: 'rebar_magnet',
    currentStepIndex: 3,
    stepStatus: 'RUNNING',
    handoverWaitReason: '',
    assignedDevices: ['SILO-01', 'LOADER-01', 'WB-TRUCK-01'],
    focusDeviceId: 'LOADER-01',
    sourceSystem: 'S1-PSMS + S2-WMS',
    qualityCode: 'GOOD',
    contractNote: '关联 X07/X08 库底定量装车与 100t 地磅复核',
    updatedAt: '10:43:18',
  },
]

export interface DtsFeedStatus {
  mode: DataSourceMode
  quality: DataQualityTag
  sourceLabel: string
  lastSyncAt: string
  errorNote: string
  regionId?: RegionCode
  upstreamCount?: number
  inboxEventsCount?: number
  liveTasks?: LiveTaskNodeItem[]
  storageLocations?: StorageLocationSummary[]
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

export function buildPsmsTaskUrl(
  baseUrl: string,
  opts?: { taskId?: string; planNo?: string; deviceId?: string; step?: number },
): string {
  try {
    const u = new URL(baseUrl, window.location.origin)
    if (opts?.taskId) u.searchParams.set('taskId', opts.taskId)
    if (opts?.planNo) u.searchParams.set('planNo', opts.planNo)
    if (opts?.deviceId) u.searchParams.set('deviceId', opts.deviceId)
    if (opts?.step != null) u.searchParams.set('step', String(opts.step))
    u.searchParams.set('from', 'dts')
    return u.href
  } catch {
    return baseUrl
  }
}

export function buildWmsLocationUrl(baseUrl: string, targetId?: string, batchNo?: string): string {
  try {
    const u = new URL(baseUrl, window.location.origin)
    if (targetId) {
      const locCode = targetId.startsWith('BC-') ? targetId.replace('BC-', 'REBAR-BAY-') : targetId
      u.searchParams.set('locationCode', locCode)
    }
    if (batchNo) u.searchParams.set('batchNo', batchNo)
    u.searchParams.set('from', 'dts')
    return u.href
  } catch {
    return baseUrl
  }
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

export function buildScsAlertUrl(
  baseUrl: string,
  opts?: { alertId?: string; deviceId?: string; zoneId?: string },
): string {
  try {
    const u = new URL(baseUrl, window.location.origin)
    if (opts?.alertId) u.searchParams.set('alertId', opts.alertId)
    if (opts?.deviceId) u.searchParams.set('deviceId', opts.deviceId)
    if (opts?.zoneId) u.searchParams.set('zoneId', opts.zoneId)
    u.searchParams.set('from', 'dts')
    return u.href
  } catch {
    return baseUrl
  }
}

export function getStorageSummaryForTarget(
  targetId: string,
  remoteList?: StorageLocationSummary[],
): StorageLocationSummary | null {
  if (!targetId) return null
  const normalizedId = targetId.startsWith('BC-') && !targetId.startsWith('BC-MECH')
    ? targetId.replace('BC-', 'REBAR-BAY-')
    : targetId === 'LOADER-01'
      ? 'SILO-01'
      : targetId === 'LOADER-02'
        ? 'SILO-09'
        : targetId === 'LOADER-03'
          ? 'SILO-17'
          : targetId === 'LOADER-04'
            ? 'SILO-25'
            : targetId

  const found = remoteList?.find((item) => item.locationId === normalizedId)
  if (found) return found

  if (normalizedId.startsWith('SILO-')) {
    const num = Number.parseInt(normalizedId.replace('SILO-', ''), 10) || 1
    const isCement = num <= 8
    const tons = isCement ? 2180 + (num * 95) % 680 : 1940 + (num * 83) % 820
    return {
      locationId: normalizedId,
      locationCode: isCement ? `C${String(num).padStart(2, '0')}` : `F${String(num - 8).padStart(2, '0')}`,
      category: isCement ? 'CEMENT_SILO' : 'FLYASH_SILO',
      materialName: isCement ? 'P·O 42.5 散装水泥' : 'Ⅰ级散装粉煤灰',
      batchNo: 'SIM-IDENTITY-20260928',
      availableTon: tons,
      capacityTon: 3000,
      levelMeters: Number(((tons / 3000) * 31.5).toFixed(1)),
      highLevelWarning: tons >= 2650,
      sourceSystem: 'S2-WMS (协作X08)',
      qualityCode: 'GOOD',
      updatedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    }
  }

  if (normalizedId.startsWith('REBAR-BAY-')) {
    const num = Number.parseInt(normalizedId.replace('REBAR-BAY-', ''), 10) || 1
    const isChook = [3, 4, 9, 12].includes(num)
    const tons = 920 + (num * 110) % 760
    return {
      locationId: normalizedId,
      locationCode: `STEEL-BAY-${String(num).padStart(2, '0')}`,
      category: 'STEEL_BAY',
      materialName: isChook ? '盘螺/冷轧卷钢 (32t C型钩跨)' : 'HRB400E 螺纹钢 (20t电磁吸盘跨)',
      batchNo: `SIM-IDENTITY-20260928-B${String(num).padStart(2, '0')}`,
      availableTon: tons,
      capacityTon: 1800,
      levelMeters: Number((1.2 + (tons / 1800) * 3.4).toFixed(2)),
      highLevelWarning: tons >= 1550,
      sourceSystem: 'S2-WMS (协作X08)',
      qualityCode: 'GOOD',
      updatedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
    }
  }

  return null
}

export class DtsDataService {
  private timer: ReturnType<typeof setInterval> | null = null
  private mode: DataSourceMode = 'offline_sim'
  private regionId: RegionCode = 'SHEN_TIE'
  private wsRegistered = false
  private tickCount = 0
  private latestLiveTasks: LiveTaskNodeItem[] = structuredClone(DEFAULT_LIVE_TASKS)
  private latestStorageLocations: StorageLocationSummary[] = []

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
          .get<{
            inbox_events_count?: number
            upstream?: Record<string, unknown>
            region_note?: string
            tasks?: LiveTaskNodeItem[]
            storage?: StorageLocationSummary[]
          }>(`/dts/overview${qs}`, { timeout: 3500 })
          .catch(() => null),
        restClient.get<{ items?: RemoteDeviceRow[]; source?: string; region_note?: string } | RemoteDeviceRow[]>(`/dts/devices${qs}`, { timeout: 3500 }),
        restClient.get<{ items?: RemoteAlertRow[]; source?: string } | RemoteAlertRow[]>(`/dts/alerts${qs}`, { timeout: 3500 }),
      ])
      const devRows = Array.isArray(devRes) ? devRes : (devRes.items ?? [])
      const alertRows = Array.isArray(alertRes) ? alertRes : (alertRes.items ?? [])
      if (overviewRes?.tasks && Array.isArray(overviewRes.tasks) && overviewRes.tasks.length > 0) {
        this.latestLiveTasks = overviewRes.tasks
      } else if (this.regionId !== 'SHEN_TIE') {
        this.latestLiveTasks = []
      } else {
        this.latestLiveTasks = structuredClone(DEFAULT_LIVE_TASKS)
      }
      if (overviewRes?.storage && Array.isArray(overviewRes.storage)) {
        this.latestStorageLocations = overviewRes.storage
      }
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
      liveTasks: this.latestLiveTasks,
      storageLocations: this.latestStorageLocations,
    })
  }
}
