import type { AlertItem } from '@/stores/alert'
import type { EquipmentDef } from './constants'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'
import {
  lerp,
  type InterlockChecklistItem,
  type ProcessControllerHandle,
  type ProcessDemoState,
  type UnifiedProcessRuntime,
} from './processTypes'
import type { BridgeCranePose, CranePose } from './models/buildEquipment'
import type { VehiclePose } from './vehicleAnimator'
import type { CameraPresetId } from './cameraPresets'

export type { ProcessDemoState as DemoViewState }

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v))
}

function easeInOut(u: number): number {
  const c = clamp01(u)
  return c * c * (3 - 2 * c)
}

function easeOutCubic(u: number): number {
  const c = clamp01(u)
  return 1 - (1 - c) ** 3
}

function followPolyline(points: Array<[number, number]>, u: number, fallbackHeading = 0): VehiclePose {
  if (points.length === 0) return { cadX: 0, cadY: 0, headingDeg: fallbackHeading }
  if (points.length === 1) return { cadX: points[0][0], cadY: points[0][1], headingDeg: fallbackHeading }
  const t = clamp01(u)
  const lengths: number[] = []
  let total = 0
  for (let i = 0; i < points.length - 1; i++) {
    const d = Math.hypot(points[i + 1][0] - points[i][0], points[i + 1][1] - points[i][1])
    lengths.push(d)
    total += d
  }
  if (total < 1e-4) {
    const last = points[points.length - 1]
    return { cadX: last[0], cadY: last[1], headingDeg: fallbackHeading }
  }
  let targetDist = t * total
  for (let i = 0; i < lengths.length; i++) {
    const segLen = lengths[i]
    if (targetDist <= segLen || i === lengths.length - 1) {
      const localU = segLen > 1e-4 ? clamp01(targetDist / segLen) : 1
      const [ax, ay] = points[i]
      const [bx, by] = points[i + 1]
      const dx = bx - ax
      const dy = by - ay
      return {
        cadX: lerp(ax, bx, localU),
        cadY: lerp(ay, by, localU),
        headingDeg: Math.hypot(dx, dy) > 0.05 ? (Math.atan2(-dy, dx) * 180) / Math.PI : fallbackHeading,
      }
    }
    targetDist -= segLen
  }
  const last = points[points.length - 1]
  return { cadX: last[0], cadY: last[1], headingDeg: fallbackHeading }
}

/** Smooth vertical elevation matching V5.3 Blender assets: CAD asphalt (-0.14m) -> ramps -> weighbridge (-0.04m) / indoor warehouse road (+0.06m) */
function computeFlatbedElevation(x: number, y: number): number {
  const wh = SITE_LAYOUT_V4.rebar.warehouse
  // Inside rebar warehouse north drive-through road (Z = +0.06m) + West/East 9m ramps bridging -0.14m CAD asphalt
  if (y >= wh.minY && y <= wh.maxY + 4 && x >= wh.minX - 11 && x <= wh.maxX + 11) {
    if (x >= wh.minX && x <= wh.maxX) return 0.06
    const distOut = x < wh.minX ? wh.minX - x : x - wh.maxX
    return lerp(0.06, -0.14, clamp01(distOut / 9.0))
  }
  // Angled CAD truck weighbridges (WB-TRUCK-01 / WB-TRUCK-02, deck at -0.04m + 7.5m ramps)
  for (const wb of [SITE_LAYOUT_V4.weighbridges[0], SITE_LAYOUT_V4.weighbridges[1]]) {
    const rad = ((wb.angleDeg ?? 0) * Math.PI) / 180
    const dx = x - wb.cadX
    const dy = y - wb.cadY
    const along = dx * Math.cos(rad) + dy * Math.sin(rad)
    const across = -dx * Math.sin(rad) + dy * Math.cos(rad)
    const halfLen = (wb.lengthM ?? 20.0) / 2
    if (Math.abs(across) <= 4.5 && Math.abs(along) <= halfLen + 8.0) {
      if (Math.abs(along) <= halfLen + 0.2) return -0.04
      return lerp(-0.04, -0.14, clamp01((Math.abs(along) - halfLen - 0.2) / 7.5))
    }
  }
  return -0.14
}

interface SteelStep {
  id:
    | 'steel-arrival'
    | 'steel-gantry-to-apron'
    | 'steel-bridge-inbound'
    | 'steel-truck-enter-wide'
    | 'steel-load-and-exit-wide'
  phaseIndex: number
  title: string
  narration: string
  durationMs: number
  camera: CameraPresetId
  craneStatus: EquipmentDef['status']
  bridgeStatus: EquipmentDef['status']
  opProgress: [number, number]
  opStatus: 'running' | 'queued' | 'done' | 'warning'
  bayTons: [number, number]
  safetyDistanceM: [number, number]
  interlocks: InterlockChecklistItem[]
  alert?: Omit<AlertItem, 'time' | 'handled'>
}

const CR02 = SITE_LAYOUT_V4.rebar.gantries[1]
const BAY03 = SITE_LAYOUT_V4.rebar.bays[2]
const BAY07 = SITE_LAYOUT_V4.rebar.bays[6]
const TRACK_H4_Y = SITE_LAYOUT_V4.rebar.tracks[0].cadY
const APRON_TARGET_Y = SITE_LAYOUT_V4.rebar.transferApronY[0] + 5.2 // 1668.245
const TRUCK_LOAD_Y = SITE_LAYOUT_V4.rebar.truckLoadingY // 1725.0 (exact CAD internal road centerline)
const WB_IN = SITE_LAYOUT_V4.weighbridges[1]
const WB_OUT = SITE_LAYOUT_V4.weighbridges[0]

// Dedicated Empty Demo Drop Slot inside Bay 07 / Bay 03 matching exact warehouse stack grid (2-col sx=±6.5, 4-row Y=1682.5..1709.5)
const STACK_OFFSET_X = 6.5
const BC_STACK_Y = 1709.5
const CARGO_ON_WAGON_Z = 2.02 // flush on open rail flatcar timber bolsters (1.64 + 0.38)
const CARGO_ON_APRON_Z = 0.50 // flush on handover pad (+0.09 + 0.38)
const CARGO_ON_SLOT_Z = 0.62 // flush on warehouse dunnage sleepers (+0.24 + 0.38), identical to all other warehouse stacks!

const WB_IN_HEADING = 180 - (WB_IN.angleDeg ?? 10.95)
const WB_OUT_HEADING = -(WB_OUT.angleDeg ?? 59.0)
const WB_IN_COS = Math.cos(((WB_IN.angleDeg ?? 10.95) * Math.PI) / 180)
const WB_IN_SIN = Math.sin(((WB_IN.angleDeg ?? 10.95) * Math.PI) / 180)
const WB_IN_EAST_PT: [number, number] = [WB_IN.cadX + 14.0 * WB_IN_COS, WB_IN.cadY + 14.0 * WB_IN_SIN]
const WB_IN_WEST_PT: [number, number] = [WB_IN.cadX - 14.0 * WB_IN_COS, WB_IN.cadY - 14.0 * WB_IN_SIN]

const WB_OUT_COS = Math.cos(((WB_OUT.angleDeg ?? 59.0) * Math.PI) / 180)
const WB_OUT_SIN = Math.sin(((WB_OUT.angleDeg ?? 59.0) * Math.PI) / 180)
const WB_OUT_SW_PT: [number, number] = [WB_OUT.cadX - 14.0 * WB_OUT_COS, WB_OUT.cadY - 14.0 * WB_OUT_SIN]
const WB_OUT_NE_PT: [number, number] = [WB_OUT.cadX + 14.0 * WB_OUT_COS, WB_OUT.cadY + 14.0 * WB_OUT_SIN]

export type SteelSubMode = 'rebar_magnet' | 'coil_chook'

const CR_HOME: CranePose = {
  gantryX: CR02.cadX,
  gantryY: CR02.cadY,
  trolleyOffset: 0,
  hoistHeight: 16.0,
  payloadTons: 0,
}

const CR_TROLLEY_TRACK_OFFSET = TRACK_H4_Y - CR02.cadY
const CR_TROLLEY_APRON_OFFSET = APRON_TARGET_Y - CR02.cadY

const BC_HOME: BridgeCranePose = {
  cadX: BAY07.cadX,
  bridgeY: BAY07.bridgeParkY,
  trolleyOffsetX: 0,
  hoistHeight: 9.5,
  payloadTons: 0,
}

const BC03_HOME: BridgeCranePose = {
  cadX: BAY03.cadX,
  bridgeY: BAY03.bridgeParkY,
  trolleyOffsetX: 0,
  hoistHeight: 9.5,
  payloadTons: 0,
}

const BC_APRON_Y = APRON_TARGET_Y
const WAGON_START_OFFSET_X = -92
const TRUCK_STANDBY_STAGING: VehiclePose = { cadX: 4032.0, cadY: 1704.0, headingDeg: 148 }
const TANKER_STANDBY_STAGING: VehiclePose = { cadX: 4095.0, cadY: 1588.0, headingDeg: 180 }

export const STEEL_UNLOAD_STEPS: SteelStep[] = [
  {
    id: 'steel-arrival',
    phaseIndex: 0,
    title: '1. 露天板车钢材专列平稳进场（零穿模）· 40t 门吊电磁吊具直抓对位',
    narration:
      '取消集装箱中转，3 节联挂露天铁路平车直接满载 9.2m 捆扎钢材沿货4股道整体缓速进站停稳（股道完全净空、零静态滞留车穿模），完成 RFID 车号核验；CR-02 40t 门吊直接纯垂向下放磁吸吊具对准中间露天板车钢材准备起吊。',
    durationMs: 20000,
    camera: 'steel',
    craneStatus: 'ok',
    bridgeStatus: 'standby',
    opProgress: [0, 18],
    opStatus: 'running',
    bayTons: [14200, 14200],
    safetyDistanceM: [36.0, 29.4],
    interlocks: [
      { label: '3 节联挂露天铁路板车同步进站停稳（货4股道净空零穿模）', passed: true },
      { label: '取消集装箱中转：钢材直置露天平车枕木上，CR-02 磁吸直抓', passed: true },
      { label: '跨间激光防碰撞测距系统在线联锁就绪', passed: true },
    ],
  },
  {
    id: 'steel-gantry-to-apron',
    phaseIndex: 1,
    title: '2. 40t 门吊高位起升越梁 · 缓缓垂直下放月台卸货区 · 空钩升顶退回',
    narration:
      '严格遵循 CAD《钢筋钢材仓库与门式起重机联合吊装位置关系示意图》：CR-02 先纯+Z 将钢材垂直吊至最高安全净空（19.4m，高出 13.0m 仓库外伸桥吊横梁与端柱近 5m）→ 高位纯+Y 水平越梁至月台正上方（Y=1668.25m）→ 纯-Z 缓缓垂直下放落稳月台（±0.000）→ 脱磁后空钩先纯+Z 升回 19.4m 最高位再纯-Y 退回铁路安全域，全程零撞梁、零穿模；室内桥吊 BC-07 保持在库内安全位待命！',
    durationMs: 26000,
    camera: 'gantry',
    craneStatus: 'ok',
    bridgeStatus: 'standby',
    opProgress: [18, 42],
    opStatus: 'running',
    bayTons: [14200, 14200],
    safetyDistanceM: [26.0, 24.5],
    interlocks: [
      { label: '纯+Z 起吊至 19.4m 最高净空 → 高位纯+Y 安全越过 13m 仓库外伸横梁', passed: true },
      { label: '对准 ±0.000 月台卸货区 → 纯-Z 缓缓垂直下放落稳垫木', passed: true },
      { label: '空钩先纯+Z 升回 19.4m 再纯-Y 退回安全域 + BC-07 库内联锁待命', passed: true },
    ],
  },
  {
    id: 'steel-bridge-inbound',
    phaseIndex: 2,
    title: '3. 联锁解除 · 20t 磁吸桥吊沿月台外伸轨道梁接驳 · 精准落入 07 跨空位',
    narration:
      '门吊升顶退回释放联锁后，BC-07 沿 CAD 实测月台外伸桥吊轨道梁（Y: 1694→1668.25m）平稳驶出仓库南门至月台正上方 → 纯-Z 下放电磁吸盘激磁锁定 → 纯+Z 起升至 6.4m 越垛安全高度 → 纯+Y 沿轨道平稳入库至第 4 排 → 小车纯+X 横移对准 07 跨专用演示空位 → 纯-Z 精准落位枕木，摆放方向与标高和全库钢材 100% 一致！',
    durationMs: 26000,
    camera: 'rebar',
    craneStatus: 'standby',
    bridgeStatus: 'ok',
    opProgress: [42, 66],
    opStatus: 'running',
    bayTons: [14200, 14950],
    safetyDistanceM: [24.0, 28.5],
    interlocks: [
      { label: 'BC-07 沿新建月台外伸轨道梁顺畅驶出月台接货（零脱轨、零撞梁）', passed: true },
      { label: '高位越垛（6.4m）一维入库 → 精准落入 07 跨专用演示空位同向对齐', passed: true },
      { label: 'RFID 自动绑定 07 跨专用库位并实时同步 S2-WMS 库存台账', passed: true },
    ],
  },
  {
    id: 'steel-truck-enter-wide',
    phaseIndex: 3,
    title: '4. 大广角锁定跟拍平板车进站 · 过初磅 · 沿东斜道直入 07 跨（自动推进特写）',
    narration:
      '高空大广角镜头始终居中锁定六轴重型平板车（藏A·63052），完整展现全站交通走向：穿过 40m 东大门雨棚 → 慢速驶上 +10.95° 斜交入口地磅 WB-TRUCK-02 停稳完成 16.4t 初磅 → 沿主干道（Y=1616.5）西行经东斜道直穿钢筋库东门驶向 07 跨，入位停稳后镜头平滑放大切换装货特写！',
    durationMs: 30000,
    camera: 'weighbridge',
    craneStatus: 'standby',
    bridgeStatus: 'standby',
    opProgress: [66, 80],
    opStatus: 'running',
    bayTons: [14950, 14950],
    safetyDistanceM: [26.0, 24.5],
    interlocks: [
      { label: '高空大广角锁定货车 藏A·63052：全站建筑与进站路线一目了然', passed: true },
      { label: 'WB-TRUCK-02 慢速停稳初磅 16.4t → 沿CAD东斜道直入钢筋库东门', passed: true },
      { label: '07 跨装车位停稳 + 电子围栏闭锁 → 镜头平滑放大准备装货特写', passed: true },
    ],
  },
  {
    id: 'steel-load-and-exit-wide',
    phaseIndex: 4,
    title: '5. 装货高清特写（BC-07 落位车厢）· 平滑拉远大广角锁定西出绕圈复磅离场',
    narration:
      '前半段高清特写聚焦 07 跨装车位：BC-07 从专用空位吸起 22.0t 钢材纯+Y 平移精准落位平板车鞍座并抬盘退让；装车完毕后镜头平滑拉远切回大广角居中锁定满载平板车，俯瞰货车直出西门、沿 CAD 西外环弯道绕行一圈汇入主干道东行、南绕停车场经 +59° 出口地磅 WB-TRUCK-01 完成 38.4t 复磅出厂！',
    durationMs: 44000,
    camera: 'rebar',
    craneStatus: 'standby',
    bridgeStatus: 'ok',
    opProgress: [80, 100],
    opStatus: 'done',
    bayTons: [14950, 16500],
    safetyDistanceM: [12.8, 11.4],
    interlocks: [
      { label: '装货阶段镜头放大高清特写：BC-07 精准装车落稳鞍座零穿模', passed: true },
      { label: '出站平滑拉远大广角锁定：直出西门 → 西外环绕一圈 → 主干道东行', passed: true },
      { label: 'WB-TRUCK-01 慢速停稳复磅 38.4 t（净重 22.0 t）→ 穿东大门离场', passed: true },
    ],
    alert: {
      id: 'DEMO-REBAR-BAY-07',
      level: 'critical',
      targetId: 'CR-02',
      targetName: '2# 40t 钢材门吊 · 07跨交接区',
      message:
        '跨间联合吊装交接等待：CR-02 与室内桥吊防撞间距 11.4m（低于 15m 阈值）且 07 跨库存达 82.5%（16,500t），请核查厂家 PLC 状态及关联视频后转 S1 装卸工艺管理系统处置。',
      interlockReceipt: {
        sourceSystem: 'S4-SCS',
        safetyDistanceM: 11.4,
        actions: ['声光报警', '大屏弹窗', '查看厂家状态及视频', '转S1装卸工艺管理处置'],
      },
    },
  },
]

export class SteelUnloadDemoController implements ProcessControllerHandle {
  readonly kind = 'steel' as const
  private readonly runtime: UnifiedProcessRuntime
  private readonly onChange: (state: ProcessDemoState) => void
  private timer: ReturnType<typeof setInterval> | null = null
  private stepIndex = 0
  private elapsedMs = 0
  private lastTick = 0
  private running = false
  private completed = false
  private subMode: SteelSubMode = 'rebar_magnet'
  private singleStep = false

  constructor(
    runtime: UnifiedProcessRuntime,
    onChange: (state: ProcessDemoState) => void,
    subMode: SteelSubMode = 'rebar_magnet',
  ) {
    this.runtime = runtime
    this.onChange = onChange
    this.subMode = subMode
  }

  setSingleStep(enabled: boolean) {
    this.singleStep = enabled
  }

  setSubMode(mode: SteelSubMode) {
    if (this.subMode === mode) return
    this.subMode = mode
    this.reset()
  }

  getSubMode(): SteelSubMode {
    return this.subMode
  }

  start() {
    if (this.completed) this.reset()
    if (this.running) return
    const currentStep = STEEL_UNLOAD_STEPS[this.stepIndex]
    if (this.elapsedMs >= currentStep.durationMs) {
      if (this.stepIndex < STEEL_UNLOAD_STEPS.length - 1) {
        this.stepIndex += 1
        this.elapsedMs = 0
      } else {
        this.elapsedMs = 0
      }
    }
    const resuming = this.elapsedMs > 0
    this.running = true
    this.lastTick = performance.now()
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    this.runtime.resetUserCameraHold?.()
    if (!resuming) {
      this.runtime.trackPoint?.(null)
      this.apply(step, 0)
    } else {
      this.apply(step, Math.min(1, this.elapsedMs / step.durationMs))
    }
    this.emitState(Math.min(1, this.elapsedMs / step.durationMs))
    this.timer = setInterval(() => this.tick(), 50)
  }

  pause() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    this.running = false
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    this.emitState(step ? Math.min(1, this.elapsedMs / step.durationMs) : 0)
  }

  private syncThreeCarSteelTrain(centerX: number) {
    // Move ALL 3 coupled open flatcars together on 货4 (WAGON-FLAT-02 center car is empty deck carrying active demo cargo!) -> ZERO clipping!
    this.runtime.setVehiclePose('WAGON-FLAT-01', { cadX: centerX - 15.5, cadY: TRACK_H4_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('WAGON-FLAT-02', { cadX: centerX, cadY: TRACK_H4_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('WAGON-FLAT-03', { cadX: centerX + 15.5, cadY: TRACK_H4_Y, headingDeg: 0 })
  }

  private resetSceneBaseline() {
    this.runtime.trackPoint?.(null)
    this.runtime.clearAlerts()
    this.runtime.setDeviceStatus('CR-01', 'standby')
    this.runtime.setDeviceStatus('CR-02', 'standby')
    this.runtime.setDeviceStatus('CR-03', 'standby')
    this.runtime.setDeviceStatus('BC-03', 'standby')
    this.runtime.setDeviceStatus('BC-07', 'standby')
    this.runtime.setStockyardQuantity('CARGO-01', 14200)
    this.runtime.setOperationState('OP-240801', 0, 'queued')
    const bayX = this.subMode === 'coil_chook' ? BAY03.cadX : BAY07.cadX
    this.syncThreeCarSteelTrain(bayX + WAGON_START_OFFSET_X)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', TRUCK_STANDBY_STAGING, -0.14)
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING, -0.015)
    this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: bayX + WAGON_START_OFFSET_X, cadY: TRACK_H4_Y, headingDeg: 0 }, CARGO_ON_WAGON_Z)
    this.runtime.setCranePose('CR-02', { ...CR_HOME, gantryX: bayX })
    this.runtime.setBridgeCranePose('BC-03', BC03_HOME)
    this.runtime.setBridgeCranePose('BC-07', BC_HOME)
    this.runtime.setProcessHighlight({
      suctionPipeActive: false,
      beltConveyorActive: false,
      antiCollisionActive: false,
      elevatorActive: false,
      airSlideActive: false,
      spoutActive: false,
      laserFromPos: null,
      laserTargetPos: null,
      fenceActive: false,
      underSiloFenceActive: false,
      wbInText: '',
      wbOutText: '',
      bayWarningPos: null,
      bayWarningText: '',
      activeVehicleBadge: null,
    })
  }

  reset() {
    this.pause()
    this.stepIndex = 0
    this.elapsedMs = 0
    this.completed = false
    this.resetSceneBaseline()
    this.runtime.requestRender()
    this.emitState(0)
  }

  jumpToStep(stepIndex: number, autoPlay = true) {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    this.running = false
    this.runtime.resetUserCameraHold?.()
    const target = Math.max(0, Math.min(STEEL_UNLOAD_STEPS.length - 1, Math.floor(stepIndex)))
    this.stepIndex = target
    this.elapsedMs = 0
    this.completed = false
    this.resetSceneBaseline()
    const step = STEEL_UNLOAD_STEPS[target]
    if (autoPlay) {
      this.apply(step, 0)
      this.running = true
      this.lastTick = performance.now()
      this.emitState(0)
      this.timer = setInterval(() => this.tick(), 50)
    } else {
      const previewT =
        step.id === 'steel-bridge-inbound'
          ? 0.88
          : step.id === 'steel-truck-enter-wide'
            ? 0.22
            : step.id === 'steel-load-and-exit-wide'
              ? 0.34
              : 0.50
      this.elapsedMs = Math.round(step.durationMs * previewT)
      this.apply(step, previewT)
      this.emitState(previewT)
    }
  }

  prevStep(autoPlay = true) {
    this.jumpToStep(Math.max(0, this.stepIndex - 1), autoPlay)
  }

  nextStep(autoPlay = true) {
    this.jumpToStep(Math.min(STEEL_UNLOAD_STEPS.length - 1, this.stepIndex + 1), autoPlay)
  }

  destroy() {
    this.reset()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 240)
    this.lastTick = now
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    const t = Math.min(this.elapsedMs / step.durationMs, 1)
    this.apply(step, t)

    if (t >= 1) {
      if (step.alert) this.runtime.addAlert(step.alert)
      if (this.stepIndex === STEEL_UNLOAD_STEPS.length - 1) {
        this.pause()
        this.completed = true
      } else if (this.singleStep) {
        this.pause()
      } else {
        this.stepIndex += 1
        this.elapsedMs = 0
        this.runtime.resetUserCameraHold?.()
      }
    }
    this.emitState(t)
  }

  private apply(step: SteelStep, t: number) {
    const activeBay = this.subMode === 'coil_chook' ? BAY03 : BAY07
    const activeBcId = this.subMode === 'coil_chook' ? 'BC-03' : 'BC-07'
    const inactiveBcId = this.subMode === 'coil_chook' ? 'BC-07' : 'BC-03'
    const activePayload = this.subMode === 'coil_chook' ? 31.2 : 22.0
    const inboundPayload = this.subMode === 'coil_chook' ? 29.6 : 19.2

    // Dedicated Bay 07 / Bay 03 empty slot coordinates & aligned westbound truck park X
    const stackCadX = activeBay.cadX + STACK_OFFSET_X
    // Truck faces WEST (headingDeg = 180°), flatbed center is at truckX + 1.9 => truckParkX = stackCadX - 1.9!
    const truckParkX = stackCadX - 1.9
    const truckDockedPose: VehiclePose = { cadX: truckParkX, cadY: TRUCK_LOAD_Y, headingDeg: 180 }

    this.runtime.setDeviceStatus('CR-02', step.craneStatus)
    this.runtime.setDeviceStatus(activeBcId, step.bridgeStatus)
    this.runtime.setDeviceStatus(inactiveBcId, 'standby')
    this.runtime.setStockyardQuantity('CARGO-01', Math.round(lerp(step.bayTons[0], step.bayTons[1], t)))
    this.runtime.setOperationState(
      'OP-240801',
      Math.round(lerp(step.opProgress[0], step.opProgress[1], t)),
      t >= 1 ? step.opStatus : 'running',
    )

    let trainCenterX = activeBay.cadX
    let crPose: CranePose = { ...CR_HOME, gantryX: activeBay.cadX }
    let bcPose: BridgeCranePose = {
      cadX: activeBay.cadX,
      bridgeY: activeBay.bridgeParkY,
      trolleyOffsetX: 0,
      hoistHeight: 9.0,
      payloadTons: 0,
    }
    let truckPose: VehiclePose = TRUCK_STANDBY_STAGING
    let cargoPose: VehiclePose = { cadX: activeBay.cadX, cadY: TRACK_H4_Y, headingDeg: 0 }
    let cargoZ = CARGO_ON_WAGON_Z

    const gapDist = lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t)

    if (step.id === 'steel-arrival') {
      // NO CONTAINER! 3 coupled open flatcars carry bundled rebar directly on open timber bolsters (ZERO static flatcar clipping!)
      // 0.00..0.64: Smooth slow deceleration of 3-car open steel train along 货4 (-92m -> 0)
      // 0.64..0.88: CR-02 pure -Z lowers magnet spreader straight onto open steel bundle on WAGON-FLAT-02
      // 0.88..1.00: Energize magnet & lock check ready to lift
      if (t < 0.64) {
        const p = easeOutCubic(t / 0.64)
        trainCenterX = activeBay.cadX + lerp(WAGON_START_OFFSET_X, 0, p)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_TRACK_OFFSET,
          hoistHeight: 13.5,
          payloadTons: 0,
        }
      } else if (t < 0.88) {
        const p = easeInOut((t - 0.64) / 0.24)
        trainCenterX = activeBay.cadX
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_TRACK_OFFSET,
          hoistHeight: lerp(13.5, 3.12, p),
          payloadTons: 0,
        }
      } else {
        trainCenterX = activeBay.cadX
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_TRACK_OFFSET,
          hoistHeight: 3.12,
          payloadTons: 28.5,
        }
      }

      cargoPose = { cadX: trainCenterX, cadY: TRACK_H4_Y, headingDeg: 0 }
      cargoZ = CARGO_ON_WAGON_Z

      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: t < 0.64 ? 'steel-1a-open-train-in' : 'steel-1b-gantry-direct-grab',
        cadX: t < 0.64 ? activeBay.cadX - 20 : activeBay.cadX,
        cadY: 1650,
        cadZ: 4.5,
        radius: 34,
        headingDeg: t < 0.64 ? -28 : -36,
        pitchDeg: -24,
        range: t < 0.64 ? 115 : 74,
        durationSec: 0.95,
      })

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: false,
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: null,
      })
    } else if (step.id === 'steel-gantry-to-apron') {
      // Strictly matching CAD《钢筋钢材仓库与门式起重机联合吊装位置关系示意图》:
      // 0.00..0.24: Pure +Z HIGH LIFT straight to 19.4m (cargo bottom 17.9m, clearing 13.0m extended warehouse bridge-crane beam by +4.9m!)
      // 0.24..0.50: Pure +Y horizontal traverse at HIGH CLEARANCE (19.4m) cleanly OVER the 13.0m outdoor bridge-crane beam & outer columns to APRON_TARGET_Y (1668.25)
      // 0.50..0.72: Pure -Z SLOW vertical descent straight down into ±0.000 handover platform (19.4m -> 1.60m, cargoZ = 0.50m)
      // 0.72..0.76: Demagnetize & stable release pause on platform
      // 0.76..0.90: Pure +Z empty spreader MUST hoist all the way back UP to 19.4m before retracting (ZERO beam collision!)
      // 0.90..1.00: Pure -Y trolley retraction at 19.4m back over railway safe zone (trolleyOffset -> 0)
      const HIGH_CLEAR_Z = 19.4
      if (t < 0.24) {
        const p = easeInOut(t / 0.24)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_TRACK_OFFSET,
          hoistHeight: lerp(3.12, HIGH_CLEAR_Z, p),
          payloadTons: 28.5,
        }
        cargoPose = { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 }
        cargoZ = Math.max(CARGO_ON_APRON_Z, crPose.hoistHeight - 1.1)
      } else if (t < 0.50) {
        const p = easeInOut((t - 0.24) / 0.26)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: lerp(CR_TROLLEY_TRACK_OFFSET, CR_TROLLEY_APRON_OFFSET, p),
          hoistHeight: HIGH_CLEAR_Z,
          payloadTons: 28.5,
        }
        cargoPose = { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 }
        cargoZ = HIGH_CLEAR_Z - 1.1
      } else if (t < 0.72) {
        const p = easeInOut((t - 0.50) / 0.22)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_APRON_OFFSET,
          hoistHeight: lerp(HIGH_CLEAR_Z, 1.6, p),
          payloadTons: 28.5,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = Math.max(CARGO_ON_APRON_Z, crPose.hoistHeight - 1.1)
      } else if (t < 0.76) {
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_APRON_OFFSET,
          hoistHeight: 1.6,
          payloadTons: 0,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      } else if (t < 0.90) {
        const p = easeInOut((t - 0.76) / 0.14)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_APRON_OFFSET,
          hoistHeight: lerp(1.6, HIGH_CLEAR_Z, p),
          payloadTons: 0,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      } else {
        const p = easeInOut((t - 0.90) / 0.10)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: lerp(CR_TROLLEY_APRON_OFFSET, 0, p),
          hoistHeight: HIGH_CLEAR_Z,
          payloadTons: 0,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      }

      // True SCS tandem safety interlock: BC-07 stays safely parked INSIDE the warehouse (Y=1694.0m, >25m clear!) until CR-02 clears completely!
      bcPose = {
        cadX: activeBay.cadX,
        bridgeY: 1694.0,
        trolleyOffsetX: 0,
        hoistHeight: 9.5,
        payloadTons: 0,
      }

      // Side-oblique CAD-section angle clearly revealing the 19.4m high-lift crossing OVER the 13.0m extended warehouse bridge-crane beam!
      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: 'steel-2-cad-highlift-section',
        cadX: activeBay.cadX,
        cadY: 1656.5,
        cadZ: 10.5,
        radius: 28,
        headingDeg: -56,
        pitchDeg: -15,
        range: 112,
        durationSec: 0.9,
      })

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: true,
        gapLabelPos: { cadX: activeBay.cadX + 12, cadY: APRON_TARGET_Y },
        gapDistanceM: gapDist,
        laserFromPos: { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, cadZ: crPose.hoistHeight },
        laserTargetPos: { cadX: bcPose.cadX, cadY: bcPose.bridgeY, cadZ: 13.0 },
        fenceActive: t >= 0.50,
        fencePos: { cadX: activeBay.cadX, cadY: APRON_TARGET_Y },
        fenceLabel:
          t < 0.24
            ? 'CR-02 纯+Z 起吊至 19.4m 最高安全净空（避让 13m 仓库横梁）…'
            : t < 0.50
              ? 'CR-02 保持 19.4m 高位纯+Y 安全越过 13m 仓库外伸桥吊横梁 ✓'
              : t < 0.72
                ? '对准 ±0.000 月台卸货区 · 纯-Z 缓缓垂直下放落稳…'
                : t < 0.90
                  ? '落稳脱磁 · 门吊空钩先纯+Z 升回 19.4m 最高净空…'
                  : '门吊高位安全退回铁路侧 · 联锁释放准备切室内桥吊 ✓',
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: null,
      })
    } else if (step.id === 'steel-bridge-inbound') {
      // STRICTLY 1D LINEAR TRANSFER ALONG EXTENDED OUTDOOR PLATFORM RUNWAY BEAMS INTO BAY 07 DEDICATED EMPTY SLOT:
      // CR-02 smoothly glides west (no sudden teleport!) at 19.4m high clearance so view is 100% unobstructed.
      // 0.00..0.18: BC-07 pure -Y glides out along the newly built outdoor extended runway beams (Y: 1694.0 -> 1668.25)
      // 0.18..0.30: Pure -Z lower magnet onto steel bundle on ±0.000 platform (9.5 -> 1.50m)
      // 0.30..0.36: Magnetize hold & anti-sway lock confirmation
      // 0.36..0.48: Pure +Z lift steel smoothly to 6.4m (cargoZ = 5.4m clearing indoor stacks!)
      // 0.48..0.70: Pure +Y bridge travel North back into Bay 07 Row 4 (1668.25 -> 1709.5)
      // 0.70..0.82: Pure +X trolley traverse East over dedicated empty demo slot (0 -> +6.5m)
      // 0.82..0.93: Pure -Z lower steel FLUSH onto timber sleepers (cargoZ = 0.62m)
      // 0.93..1.00: Demagnetize & pure +Z retract magnet clear (1.62 -> 7.2m)
      const crClearP = easeInOut(Math.min(1, t / 0.14))
      crPose = {
        ...CR_HOME,
        gantryX: lerp(activeBay.cadX, activeBay.cadX - 24.0, crClearP),
        trolleyOffset: 0,
        hoistHeight: 19.4,
      }

      if (t < 0.18) {
        const p = easeInOut(t / 0.18)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: lerp(1694.0, BC_APRON_Y, p),
          trolleyOffsetX: 0,
          hoistHeight: 9.5,
          payloadTons: 0,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      } else if (t < 0.30) {
        const p = easeInOut((t - 0.18) / 0.12)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_APRON_Y,
          trolleyOffsetX: 0,
          hoistHeight: lerp(9.5, 1.5, p),
          payloadTons: 0,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      } else if (t < 0.36) {
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_APRON_Y,
          trolleyOffsetX: 0,
          hoistHeight: 1.5,
          payloadTons: inboundPayload,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_APRON_Z
      } else if (t < 0.48) {
        const p = easeInOut((t - 0.36) / 0.12)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_APRON_Y,
          trolleyOffsetX: 0,
          hoistHeight: lerp(1.5, 6.4, p),
          payloadTons: inboundPayload,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: BC_APRON_Y, headingDeg: 0 }
        cargoZ = Math.max(CARGO_ON_APRON_Z, bcPose.hoistHeight - 1.0)
      } else if (t < 0.70) {
        const p = easeInOut((t - 0.48) / 0.22)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: lerp(BC_APRON_Y, BC_STACK_Y, p),
          trolleyOffsetX: 0,
          hoistHeight: 6.4,
          payloadTons: inboundPayload,
        }
        cargoPose = { cadX: activeBay.cadX, cadY: bcPose.bridgeY, headingDeg: 0 }
        cargoZ = 5.4
      } else if (t < 0.82) {
        const p = easeInOut((t - 0.70) / 0.12)
        const offX = lerp(0, STACK_OFFSET_X, p)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_STACK_Y,
          trolleyOffsetX: offX,
          hoistHeight: 6.4,
          payloadTons: inboundPayload,
        }
        cargoPose = { cadX: activeBay.cadX + offX, cadY: BC_STACK_Y, headingDeg: 0 }
        cargoZ = 5.4
      } else if (t < 0.93) {
        const p = easeInOut((t - 0.82) / 0.11)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_STACK_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(6.4, 1.62, p),
          payloadTons: inboundPayload,
        }
        cargoPose = { cadX: stackCadX, cadY: BC_STACK_Y, headingDeg: 0 }
        cargoZ = Math.max(CARGO_ON_SLOT_Z, bcPose.hoistHeight - 1.0)
      } else {
        const p = easeInOut((t - 0.93) / 0.07)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_STACK_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(1.62, 7.2, p),
          payloadTons: 0,
        }
        cargoPose = { cadX: stackCadX, cadY: BC_STACK_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_SLOT_Z
      }

      // First clearly frame BC-07 gliding out on the outdoor extended platform runway beams, then smoothly track inside Bay 07!
      const inBayCam = t >= 0.45
      this.runtime.trackPoint?.({
        tag: 'steel-3-bridge-closeup',
        cadX: activeBay.cadX + bcPose.trolleyOffsetX,
        cadY: bcPose.bridgeY,
        cadZ: inBayCam ? Math.max(2.6, bcPose.hoistHeight * 0.65) : 7.5,
        headingDeg: inBayCam ? -26 : -44,
        pitchDeg: inBayCam ? -28 : -22,
        range: inBayCam ? 38 : 66,
        smoothing: 0.18,
      })

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: true,
        fencePos: { cadX: activeBay.cadX + bcPose.trolleyOffsetX, cadY: bcPose.bridgeY },
        fenceLabel:
          t < 0.18
            ? `${activeBcId} 沿月台外伸轨道梁平稳驶出至交接区…`
            : t < 0.30
              ? `${activeBcId} 纯-Z 下放电磁吸盘对准月台钢材…`
              : t < 0.36
                ? `${activeBcId} 电磁吸盘激磁锁定 (${inboundPayload}t) ✓`
                : t < 0.82
                  ? `${activeBcId} 升至 6.4m 高位越垛入库 → 对准 07 跨空位`
                  : `${activeBcId} 精准落入专用空位 · 与全库钢材同向对齐 ✓`,
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: null,
      })
    } else if (step.id === 'steel-truck-enter-wide') {
      // REAL CAD ENTRY ORDER: East Gate -> WB-TRUCK-02 (full stop) -> Main Trunk Y=1616.5 -> CAD East Diagonal -> DIRECTLY INTO REBAR EAST PORTAL (Y=1725.0) -> Park at Bay 07 facing West!
      // 0.00..0.14: Slow ramp climb from East Gate along +10.95° CAD road onto WB-TRUCK-02
      // 0.14..0.30: COMPLETE STOP on WB-TRUCK-02 (16.4t empty tare weight)
      // 0.30..0.54: Cruise West along CAD Main Trunk Y=1616.5 (4558 -> 4119.8)
      // 0.54..0.74: Follow REAL CAD East Diagonal (4119.8, 1616.5 -> 4088.8, 1666.6 -> 4075.9, 1676.8 -> 3998.5, 1725.04)
      // 0.74..0.95: Drive STRAIGHT WEST into Rebar Warehouse East Portal (3947.6, 1725.04) -> Bay 07 loading spot (truckParkX, 1725.0)
      // 0.95..1.00: Docked stop + electronic fence lock
      crPose = { ...CR_HOME, gantryX: activeBay.cadX }
      bcPose = {
        cadX: activeBay.cadX,
        bridgeY: BC_STACK_Y,
        trolleyOffsetX: STACK_OFFSET_X,
        hoistHeight: 6.8,
        payloadTons: 0,
      }
      cargoPose = { cadX: stackCadX, cadY: BC_STACK_Y, headingDeg: 0 }
      cargoZ = CARGO_ON_SLOT_Z

      if (t < 0.14) {
        truckPose = followPolyline(
          [
            [4610.0, 1630.4],
            [4587.6, 1629.6],
            WB_IN_EAST_PT,
            [WB_IN.cadX, WB_IN.cadY],
          ],
          easeInOut(t / 0.14),
          WB_IN_HEADING,
        )
      } else if (t < 0.30) {
        truckPose = { cadX: WB_IN.cadX, cadY: WB_IN.cadY, headingDeg: WB_IN_HEADING }
      } else if (t < 0.54) {
        truckPose = followPolyline(
          [
            [WB_IN.cadX, WB_IN.cadY],
            WB_IN_WEST_PT,
            [4521.8, 1616.86],
            [4518.37, 1616.54],
            [4119.8, 1616.54],
          ],
          easeInOut((t - 0.30) / 0.24),
          180,
        )
      } else if (t < 0.74) {
        truckPose = followPolyline(
          [
            [4119.8, 1616.54],
            [4088.81, 1666.62],
            [4087.63, 1668.31],
            [4086.25, 1669.86],
            [4084.71, 1671.24],
            [4083.02, 1672.43],
            [4075.92, 1676.85],
            [3998.51, 1725.04],
          ],
          easeInOut((t - 0.54) / 0.20),
          148,
        )
      } else if (t < 0.95) {
        truckPose = followPolyline(
          [
            [3998.51, 1725.04],
            [3947.61, 1725.04],
            [truckParkX, TRUCK_LOAD_Y],
          ],
          easeInOut((t - 0.74) / 0.21),
          180,
        )
      } else {
        truckPose = truckDockedPose
      }

      const truckElev = computeFlatbedElevation(truckPose.cadX, truckPose.cadY)
      // Cinematic Macro Wide-Angle Locked Follow (240~255m, pitch -44°) -> Smooth Zoom-In Ready for Bay 07 Loading Close-Up (28m)!
      // Shares continuous tag 'steel-truck-cinematic-follow' with Step 5 so wide entry -> zoom-in loading -> wide exit is 100% seamless!
      let followHeading = -22
      let followPitch = -44
      let followRange = 245
      if (t < 0.74) {
        // Panoramic macro wide-angle locked on truck: shows East Gate canopy, WB-TRUCK-02 stop, Office/Dorm, Parking, 40 Silos, Main Trunk & East Diagonal!
        followHeading = -22
        followPitch = -44
        followRange = t < 0.30 ? 235 : 255
      } else if (t < 0.91) {
        // High wide overhead follow watching truck enter East Portal & drive straight along indoor road Y=1725 through cutaway roof
        const p = easeInOut((t - 0.74) / 0.17)
        followHeading = lerp(-22, -16, p)
        followPitch = lerp(-44, -48, p)
        followRange = lerp(255, 185, p)
      } else {
        // Truck parks at Bay 07 -> smoothly zoom IN to crisp loading close-up!
        const p = easeInOut((t - 0.91) / 0.09)
        followHeading = lerp(-16, 22, p)
        followPitch = lerp(-48, -22, p)
        followRange = lerp(185, 32, p)
      }

      this.runtime.trackPoint?.({
        tag: 'steel-truck-cinematic-follow',
        cadX: truckPose.cadX,
        cadY: truckPose.cadY,
        cadZ: truckElev + 2.4,
        headingDeg: followHeading,
        pitchDeg: followPitch,
        range: followRange,
        smoothing: 0.22,
      })

      const enterBadge =
        t < 0.14
          ? '重型平板车 藏A·63052 · 慢速驶上入口初磅'
          : t < 0.30
            ? '重型平板车 藏A·63052 · WB-TRUCK-02 初磅停稳 (16.4t)'
            : t < 0.54
              ? '重型平板车 藏A·63052 · 沿CAD主干道西行'
              : t < 0.74
                ? '重型平板车 藏A·63052 · 沿CAD东斜道直趋东门'
                : t < 0.95
                  ? '重型平板车 藏A·63052 · 穿入钢材库东门驶向07跨'
                  : '重型平板车 藏A·63052 · 07跨装车位停稳 ✓'

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: t >= 0.88,
        fencePos: { cadX: stackCadX, cadY: TRUCK_LOAD_Y },
        fenceLabel: t >= 0.95 ? '装车电子围栏闭锁 · 司机离车确认 · 准备桥吊装车 ✓' : '平板车沿CAD道路直入东门对位07跨…',
        underSiloFenceActive: false,
        wbInText:
          t <= 0.34
            ? t < 0.14
              ? 'WB-TRUCK-02 货车缓速上磅中…'
              : t < 0.26
                ? 'WB-TRUCK-02 停稳初磅计量中（皮重采集）…'
                : 'WB-TRUCK-02 初磅完成 · 空车皮重 16.4 t ✓'
            : '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: { cadX: truckPose.cadX, cadY: truckPose.cadY, cadZ: truckElev, text: enterBadge },
      })
    } else {
      // STEP 5 ('steel-load-and-exit-wide', 42s):
      // PART A (0.00..0.40): Close-up precision BC-07 loading from Bay 07 dedicated slot (stackCadX, 1709.5) onto westbound flatbed truck (center at stackCadX, 1725.0)
      //   0.00..0.08: Pure -Z lower magnet onto stack (6.8 -> 1.62m) & energize magnet
      //   0.08..0.15: Pure +Z lift 22.0t steel bundle (1.62 -> 4.8m)
      //   0.15..0.26: Pure +Y glide North over truck flatbed (BC_STACK_Y 1709.5 -> TRUCK_LOAD_Y 1725.0)
      //   0.26..0.34: Pure -Z slow precision drop onto flatbed bolsters (cargoZ = 2.08m, hoistHeight = 3.08m)
      //   0.34..0.40: Demagnetize & Pure +Z hoist retracts clear (3.08 -> 8.8m)
      // PART B (0.40..1.00): Loaded truck drives STRAIGHT WEST out Rebar West Portal -> REAL CAD West Perimeter Loop -> Main Trunk EAST -> South -> East -> WB-TRUCK-01 (+59°) full stop -> East Gate!
      crPose = { ...CR_HOME, gantryX: activeBay.cadX }

      if (t < 0.08) {
        const p = easeInOut(t / 0.08)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_STACK_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(6.8, 1.62, p),
          payloadTons: 0,
        }
        truckPose = truckDockedPose
        cargoPose = { cadX: stackCadX, cadY: BC_STACK_Y, headingDeg: 0 }
        cargoZ = CARGO_ON_SLOT_Z
      } else if (t < 0.15) {
        const p = easeInOut((t - 0.08) / 0.07)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: BC_STACK_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(1.62, 4.8, p),
          payloadTons: activePayload,
        }
        truckPose = truckDockedPose
        cargoPose = { cadX: stackCadX, cadY: BC_STACK_Y, headingDeg: 0 }
        cargoZ = Math.max(CARGO_ON_SLOT_Z, bcPose.hoistHeight - 1.0)
      } else if (t < 0.26) {
        const p = easeInOut((t - 0.15) / 0.11)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: lerp(BC_STACK_Y, TRUCK_LOAD_Y, p),
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: 4.8,
          payloadTons: activePayload,
        }
        truckPose = truckDockedPose
        cargoPose = { cadX: stackCadX, cadY: bcPose.bridgeY, headingDeg: 0 }
        cargoZ = 3.8
      } else if (t < 0.34) {
        const p = easeInOut((t - 0.26) / 0.08)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: TRUCK_LOAD_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(4.8, 3.08, p),
          payloadTons: activePayload,
        }
        truckPose = truckDockedPose
        cargoPose = { cadX: stackCadX, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
        cargoZ = Math.max(2.08, bcPose.hoistHeight - 1.0)
      } else if (t < 0.40) {
        const p = easeInOut((t - 0.34) / 0.06)
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: TRUCK_LOAD_Y,
          trolleyOffsetX: STACK_OFFSET_X,
          hoistHeight: lerp(3.08, 8.8, p),
          payloadTons: 0,
        }
        truckPose = truckDockedPose
        cargoPose = { cadX: stackCadX, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
        cargoZ = 2.08
      } else {
        const retP = easeInOut(Math.min(1, (t - 0.40) / 0.12))
        bcPose = {
          cadX: activeBay.cadX,
          bridgeY: lerp(TRUCK_LOAD_Y, activeBay.bridgeParkY, retP),
          trolleyOffsetX: lerp(STACK_OFFSET_X, 0, retP),
          hoistHeight: lerp(8.8, 9.5, retP),
          payloadTons: 0,
        }
        if (t < 0.51) {
          // Straight WEST out Rebar Warehouse West Portal (Y=1725.04)
          truckPose = followPolyline(
            [
              [truckParkX, TRUCK_LOAD_Y],
              [3555.01, 1725.04],
              [3477.74, 1725.05],
            ],
            easeInOut((t - 0.40) / 0.11),
            180,
          )
        } else if (t < 0.65) {
          // Exact REAL CAD West Perimeter Loop Curve (#32 -> #11 -> #27 -> #26 -> Main Trunk Y=1616.55)
          truckPose = followPolyline(
            [
              [3477.74, 1725.05],
              [3468.95, 1720.37],
              [3329.24, 1646.1],
              [3326.89, 1644.61],
              [3324.8, 1642.77],
              [3323.02, 1640.64],
              [3321.59, 1638.26],
              [3320.54, 1635.68],
              [3319.9, 1632.98],
              [3319.68, 1630.2],
              [3319.68, 1616.55],
            ],
            easeInOut((t - 0.51) / 0.14),
            152,
          )
        } else if (t < 0.80) {
          // Drive EAST along Main Central Trunk (Y=1616.54) to X=4324.11
          truckPose = followPolyline(
            [
              [3319.68, 1616.55],
              [4324.11, 1616.54],
            ],
            easeInOut((t - 0.65) / 0.15),
            0,
          )
        } else if (t < 0.88) {
          // Turn SOUTH to Y=1560.79 -> EAST to 4524.24 -> climb +59° CAD exit diagonal onto WB-TRUCK-01
          truckPose = followPolyline(
            [
              [4324.11, 1616.54],
              [4324.11, 1560.79],
              [4524.24, 1560.79],
              WB_OUT_SW_PT,
              [WB_OUT.cadX, WB_OUT.cadY],
            ],
            easeInOut((t - 0.80) / 0.08),
            WB_OUT_HEADING,
          )
        } else if (t < 0.95) {
          truckPose = { cadX: WB_OUT.cadX, cadY: WB_OUT.cadY, headingDeg: WB_OUT_HEADING }
        } else {
          truckPose = followPolyline(
            [
              [WB_OUT.cadX, WB_OUT.cadY],
              WB_OUT_NE_PT,
              [4562.46, 1624.73],
              [4587.56, 1629.58],
              [4612.0, 1630.4],
            ],
            easeInOut((t - 0.95) / 0.05),
            0,
          )
        }
        const truckRad = (truckPose.headingDeg * Math.PI) / 180
        cargoPose = {
          cadX: truckPose.cadX - 1.9 * Math.cos(truckRad),
          cadY: truckPose.cadY + 1.9 * Math.sin(truckRad),
          headingDeg: truckPose.headingDeg,
        }
        cargoZ = computeFlatbedElevation(truckPose.cadX, truckPose.cadY) + 2.02
      }

      const truckElev = computeFlatbedElevation(truckPose.cadX, truckPose.cadY)
      if (t < 0.40) {
        // Loading Close-Up (shares tag 'steel-truck-cinematic-follow' for seamless zoom-in from Step 4 -> zoom-out to Step 5 Part B!)
        this.runtime.trackPoint?.({
          tag: 'steel-truck-cinematic-follow',
          cadX: stackCadX - 1.2,
          cadY: lerp(BC_STACK_Y + 3, TRUCK_LOAD_Y - 1, clamp01((t - 0.10) / 0.22)),
          cadZ: 3.0,
          headingDeg: 22,
          pitchDeg: -22,
          range: 28,
          smoothing: 0.2,
        })
      } else {
        // Loading finished -> smooth zoom-OUT to panoramic Macro Wide-Angle Locked Follow (240~260m) watching truck exit West Portal -> West Loop -> Main Trunk -> WB-TRUCK-01 -> East Gate!
        let exitHeading = -20
        let exitPitch = -46
        let exitRange = 255
        if (t < 0.47) {
          // Smooth cinematic pull-back from 28m loading close-up to 245m high panoramic wide-angle above roof trusses & cranes!
          const p = easeInOut((t - 0.40) / 0.07)
          exitHeading = lerp(22, -20, p)
          exitPitch = lerp(-22, -48, p)
          exitRange = lerp(28, 245, p)
        } else if (t < 0.80) {
          // Panoramic macro wide-angle over West Portal, CAD West Perimeter Loop (past Water Station / Open Storage / 35kV Sub) & Main Trunk East (Z≈180m, 100% unobstructed!)
          exitHeading = -20
          exitPitch = -45
          exitRange = 260
        } else {
          // Macro wide-angle locked on East exit loop (Parking south bypass, Mech Warehouse, Sewage Plant, +59° WB-TRUCK-01 re-weighing stop & 40m East Gate canopy exit!)
          exitHeading = -132
          exitPitch = -43
          exitRange = 235
        }
        this.runtime.trackPoint?.({
          tag: 'steel-truck-cinematic-follow',
          cadX: truckPose.cadX,
          cadY: truckPose.cadY,
          cadZ: truckElev + 2.4,
          headingDeg: exitHeading,
          pitchDeg: exitPitch,
          range: exitRange,
          smoothing: 0.18,
        })
      }

      const exitBadge =
        t < 0.40
          ? null
          : {
              cadX: truckPose.cadX,
              cadY: truckPose.cadY,
              cadZ: truckElev,
              text:
                t < 0.51
                  ? '重型平板车 藏A·63052 · 满载 22t 直出钢材库西门'
                  : t < 0.65
                    ? '重型平板车 藏A·63052 · 沿CAD西外环道路绕行一圈'
                    : t < 0.80
                      ? '重型平板车 藏A·63052 · 沿CAD主干道东行前往出口磅'
                      : t < 0.95
                        ? '重型平板车 藏A·63052 · WB-TRUCK-01 复磅停稳 (38.4t)'
                        : '重型平板车 藏A·63052 · 复磅放行离场 ✓',
            }

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: t < 0.36,
        gapLabelPos: { cadX: stackCadX + 6, cadY: TRUCK_LOAD_Y },
        gapDistanceM: gapDist,
        laserFromPos: { cadX: bcPose.cadX + bcPose.trolleyOffsetX, cadY: bcPose.bridgeY, cadZ: bcPose.hoistHeight },
        laserTargetPos: { cadX: stackCadX, cadY: TRUCK_LOAD_Y, cadZ: 2.8 },
        fenceActive: t < 0.40,
        fencePos: { cadX: stackCadX, cadY: TRUCK_LOAD_Y },
        fenceLabel:
          t < 0.15
            ? `${activeBcId} 磁吸抓起 07 跨专用空位钢材 (${activePayload}t)…`
            : t < 0.26
              ? `${activeBcId} 纯+Y 平移对准平板车中心`
              : t < 0.34
                ? `${activeBcId} 慢速精准落位汽车鞍座…`
                : '装车落稳 · 释磁抬盘 · 准备直出西门绕圈离场 ✓',
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText:
          t >= 0.82
            ? t < 0.88
              ? 'WB-TRUCK-01 满载货车缓速上磅中…'
              : t < 0.93
                ? 'WB-TRUCK-01 停稳复磅计量中（毛重核验）…'
                : 'WB-TRUCK-01 复磅完成 · 38.4 t（净重 22.0 t ✓）'
            : '',
        bayWarningPos: t >= 0.90 ? { cadX: activeBay.cadX, cadY: activeBay.cadY } : null,
        bayWarningText: t >= 0.90 ? '07跨 高库存预警 82.5% (16,500t)' : '',
        activeVehicleBadge: exitBadge,
      })
    }

    this.runtime.setCranePose('CR-02', crPose)
    this.runtime.setBridgeCranePose(activeBcId, bcPose)
    this.syncThreeCarSteelTrain(trainCenterX)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', truckPose, computeFlatbedElevation(truckPose.cadX, truckPose.cadY))
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING, -0.015)
    this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', cargoPose, cargoZ)

    this.runtime.requestRender()
  }

  private emitState(localProgress: number) {
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    const total = STEEL_UNLOAD_STEPS.length
    const progress = this.completed
      ? 100
      : Math.round(((this.stepIndex + localProgress) / total) * 100)
    const activePayload = this.subMode === 'coil_chook' ? 31.2 : 22.0
    const payloadTons =
      step.id === 'steel-arrival'
        ? localProgress >= 0.88
          ? 28.5
          : 0
        : step.id === 'steel-gantry-to-apron'
          ? localProgress < 0.72
            ? 28.5
            : 0
          : step.id === 'steel-bridge-inbound'
            ? localProgress >= 0.30 && localProgress <= 0.93
              ? this.subMode === 'coil_chook'
                ? 29.6
                : 19.2
              : 0
            : step.id === 'steel-load-and-exit-wide' && localProgress >= 0.08 && localProgress <= 0.34
              ? activePayload
              : 0

    this.onChange({
      processKind: 'steel',
      running: this.running,
      completed: this.completed,
      stepIndex: this.stepIndex,
      phaseIndex: step.phaseIndex,
      stepCount: total,
      title: step.title,
      narration: step.narration,
      progress,
      payloadTons,
      safetyDistanceM: Number(lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], localProgress).toFixed(1)),
      interlockChecks: step.interlocks,
    })
  }
}
