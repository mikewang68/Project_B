import type { AlertItem } from '@/stores/alert'
import type { EquipmentDef } from './constants'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'
import type { CargoItem, PersonnelItem } from './phase6'
import {
  lerp,
  lerpVehicle,
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

interface SteelStep {
  id: string
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
  crane: { from: CranePose; to: CranePose }
  bridge: { from: BridgeCranePose; to: BridgeCranePose }
  wagon: { from: VehiclePose; to: VehiclePose }
  truck: { from: VehiclePose; to: VehiclePose }
  cargoMode: 'wagon' | 'crane' | 'apron' | 'bridge' | 'bay' | 'truck'
  safetyDistanceM: [number, number]
  antiCollisionActive: boolean
  interlocks: InterlockChecklistItem[]
  alert?: Omit<AlertItem, 'time' | 'handled'>
}

const CR02 = SITE_LAYOUT_V4.rebar.gantries[1]
const BAY03 = SITE_LAYOUT_V4.rebar.bays[2]
const BAY07 = SITE_LAYOUT_V4.rebar.bays[6]
const TRACK_H4_Y = SITE_LAYOUT_V4.rebar.tracks[0].cadY
const APRON_TARGET_Y = SITE_LAYOUT_V4.rebar.transferApronY[0] + 5.2
const TRUCK_LOAD_Y = SITE_LAYOUT_V4.rebar.truckLoadingY
const WB_IN = SITE_LAYOUT_V4.weighbridges[1]
const WB_OUT = SITE_LAYOUT_V4.weighbridges[0]

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

const BC_APRON_Y = BAY07.minY + 3.2
const BC_STACK_Y = BAY07.cadY + 8.0

const WAGON_START: VehiclePose = { cadX: 3660, cadY: TRACK_H4_Y, headingDeg: 0 }
const WAGON_SPOTTED: VehiclePose = { cadX: BAY07.cadX, cadY: TRACK_H4_Y, headingDeg: 0 }
const TRUCK_GATE: VehiclePose = { cadX: WB_IN.cadX + 20, cadY: WB_IN.cadY, headingDeg: 180 }
const TRUCK_STANDBY_STAGING: VehiclePose = { cadX: 3860.0, cadY: TRUCK_LOAD_Y, headingDeg: 180 }
const TANKER_STANDBY_STAGING: VehiclePose = { cadX: 4085.0, cadY: 1587.0, headingDeg: 180 }
const TRUCK_LOAD_POS: VehiclePose = { cadX: BAY07.cadX + 1.9, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
const TRUCK_EXIT_POS: VehiclePose = { cadX: WB_OUT.cadX, cadY: WB_OUT.cadY, headingDeg: 0 }

export const STEEL_UNLOAD_STEPS: SteelStep[] = [
  {
    id: 'steel-arrival',
    phaseIndex: 0,
    title: '1. 重车进货4线与目标跨位分配',
    narration: '钢材专列停靠货4股道（564m），S1工艺系统绑定 REBAR-BAY-07（20t磁吸跨），CR-02 40t门吊对位就绪。',
    durationMs: 3600,
    camera: 'steel',
    craneStatus: 'standby',
    bridgeStatus: 'standby',
    opProgress: [0, 18],
    opStatus: 'running',
    bayTons: [14200, 14200],
    crane: {
      from: CR_HOME,
      to: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: CR_TROLLEY_TRACK_OFFSET, hoistHeight: 15.0, payloadTons: 0 },
    },
    bridge: { from: BC_HOME, to: BC_HOME },
    wagon: { from: WAGON_START, to: WAGON_SPOTTED },
    truck: { from: TRUCK_GATE, to: TRUCK_GATE },
    cargoMode: 'wagon',
    safetyDistanceM: [36.0, 29.4],
    antiCollisionActive: false,
    interlocks: [
      { label: '货4股道车号 RFID 自动识别', passed: true },
      { label: '07跨库位（20t磁吸）任务下发', passed: true },
      { label: '门吊行车防碰撞激光就绪', passed: true },
    ],
  },
  {
    id: 'steel-gantry-to-apron',
    phaseIndex: 1,
    title: '2. 一级卸车（40t门吊→库外交接区B）',
    narration: 'CR-02（40t/30m）从货4敞车起吊 28.5t 钢筋束，穿过防碰撞警戒区将钢材精确放至钢筋库南侧转接区 B。',
    durationMs: 4600,
    camera: 'gantry',
    craneStatus: 'ok',
    bridgeStatus: 'standby',
    opProgress: [18, 46],
    opStatus: 'running',
    bayTons: [14200, 14200],
    crane: {
      from: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: CR_TROLLEY_TRACK_OFFSET, hoistHeight: 4.2, payloadTons: 28.5 },
      to: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: CR_TROLLEY_APRON_OFFSET, hoistHeight: 4.5, payloadTons: 28.5 },
    },
    bridge: {
      from: BC_HOME,
      to: { cadX: BAY07.cadX, bridgeY: BC_APRON_Y, trolleyOffsetX: 0, hoistHeight: 9.0, payloadTons: 0 },
    },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_GATE, to: TRUCK_GATE },
    cargoMode: 'crane',
    safetyDistanceM: [24.0, 12.8],
    antiCollisionActive: true,
    interlocks: [
      { label: '40t 门吊载荷采集：28.5 t（正常）', passed: true },
      { label: '门吊-相邻行车安全间距 > 6m', passed: true },
      { label: '联合吊装联锁：禁止上下双钩同时落差', passed: true },
    ],
  },
  {
    id: 'steel-bridge-inbound',
    phaseIndex: 2,
    title: '3. 二级接力入库（20t磁吸桥吊→07跨）',
    narration: 'CR-02 抬钩退避安全域后，室内 BC-07 桥吊（轨顶13m，20t电磁吸盘）从转接区 B 接驳钢筋入 07 跨垛位，同步 WMS 库存。',
    durationMs: 4800,
    camera: 'rebar',
    craneStatus: 'ok',
    bridgeStatus: 'ok',
    opProgress: [46, 74],
    opStatus: 'running',
    bayTons: [14200, 14950],
    crane: {
      from: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: CR_TROLLEY_APRON_OFFSET, hoistHeight: 16.5, payloadTons: 0 },
      to: CR_HOME,
    },
    bridge: {
      from: { cadX: BAY07.cadX, bridgeY: BC_APRON_Y, trolleyOffsetX: 0, hoistHeight: 3.2, payloadTons: 19.2 },
      to: { cadX: BAY07.cadX, bridgeY: BC_STACK_Y, trolleyOffsetX: 2.0, hoistHeight: 3.4, payloadTons: 19.2 },
    },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_GATE, to: TRUCK_GATE },
    cargoMode: 'bridge',
    safetyDistanceM: [22.0, 26.5],
    antiCollisionActive: false,
    interlocks: [
      { label: '转接区红外占区交接确认', passed: true },
      { label: '20t 电磁吸盘充磁保持检测', passed: true },
      { label: 'RFID 批次入库核对同步 S2', passed: true },
    ],
  },
  {
    id: 'steel-truck-load',
    phaseIndex: 3,
    title: '4. 汽车衡初磅/电子围栏防碰撞装车',
    narration: '平板挂车先上 WB-TRUCK-02 完成 16.4t 皮重初磅，沿主干道驶入装车位并开启电子围栏与红外防碰撞激光监测，CR-02 慢速稳钩装车。',
    durationMs: 12000,
    camera: 'weighbridge',
    craneStatus: 'ok',
    bridgeStatus: 'ok',
    opProgress: [74, 94],
    opStatus: 'running',
    bayTons: [14950, 14950],
    crane: {
      from: CR_HOME,
      to: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: TRUCK_LOAD_Y - CR02.cadY, hoistHeight: 4.1, payloadTons: 22.0 },
    },
    bridge: {
      from: { cadX: BAY07.cadX, bridgeY: BC_STACK_Y, trolleyOffsetX: 2.0, hoistHeight: 8.5, payloadTons: 0 },
      to: BC_HOME,
    },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_GATE, to: TRUCK_LOAD_POS },
    cargoMode: 'crane',
    safetyDistanceM: [22.0, 11.4],
    antiCollisionActive: true,
    interlocks: [
      { label: 'WB-TRUCK-02 皮重初磅：16.4 t（已核验放行）', passed: true },
      { label: '装车电子围栏闭锁 + 司机离车交接确认', passed: true },
      { label: 'SCS 激光防碰撞 11.4m（<15m 降速稳钩保护）', passed: true },
    ],
  },
  {
    id: 'steel-weigh-verify',
    phaseIndex: 4,
    title: '5. 满车沿安全通道复磅出厂与高库存预警',
    narration: 'CR-02 抬钩退避后平板挂车载钢材沿北侧通道驶上 WB-TRUCK-01 完成 38.4t 复磅出厂，同步回焦 07 跨高库存预警（82.5%）与跨间防撞联锁。',
    durationMs: 14000,
    camera: 'gantry',
    craneStatus: 'standby',
    bridgeStatus: 'standby',
    opProgress: [94, 100],
    opStatus: 'done',
    bayTons: [14950, 16500],
    crane: {
      from: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: TRUCK_LOAD_Y - CR02.cadY, hoistHeight: 4.1, payloadTons: 0 },
      to: CR_HOME,
    },
    bridge: { from: BC_HOME, to: BC_HOME },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_LOAD_POS, to: TRUCK_EXIT_POS },
    cargoMode: 'truck',
    safetyDistanceM: [12.8, 11.4],
    antiCollisionActive: true,
    interlocks: [
      { label: 'WB-TRUCK-01 满车复磅：38.4 t（净重 22.0 t）', passed: true },
      { label: '跨间门吊-桥吊交接安全间距 11.4m（触发 <15m 联锁）', passed: true },
      { label: '07 跨高库存预警（82.5% · 16,500t）同步 S1/S2/S4', passed: true },
    ],
    alert: {
      id: 'DEMO-REBAR-BAY-07',
      level: 'critical',
      targetId: 'CR-02',
      targetName: '2# 40t 钢材门吊 · 07跨交接区',
      message: '跨间联合吊装交接等待：CR-02 与室内桥吊防撞间距 11.4m（低于 15m 阈值）且 07 跨库存达 82.5%（16,500t），请核查厂家 PLC 状态及关联视频后转 S1 装卸工艺管理系统处置。',
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

  constructor(runtime: UnifiedProcessRuntime, onChange: (state: ProcessDemoState) => void, subMode: SteelSubMode = 'rebar_magnet') {
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
    if (!resuming) {
      this.runtime.setCamera(step.camera)
    } else {
      this.apply(step, Math.min(1, this.elapsedMs / step.durationMs))
    }
    this.emitState(Math.min(1, this.elapsedMs / step.durationMs))
    this.timer = setInterval(() => this.tick(), 80)
  }

  pause() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    this.running = false
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    this.emitState(step ? Math.min(1, this.elapsedMs / step.durationMs) : 0)
  }

  private resetSceneBaseline() {
    this.runtime.clearAlerts()
    this.runtime.setDeviceStatus('CR-01', 'standby')
    this.runtime.setDeviceStatus('CR-02', 'standby')
    this.runtime.setDeviceStatus('CR-03', 'standby')
    this.runtime.setDeviceStatus('BC-03', 'standby')
    this.runtime.setDeviceStatus('BC-07', 'standby')
    this.runtime.setStockyardQuantity('CARGO-01', 14200)
    this.runtime.setOperationState('OP-240801', 0, 'queued')
    const bayX = this.subMode === 'coil_chook' ? BAY03.cadX : BAY07.cadX
    this.runtime.setVehiclePose('WAGON-FLAT-01', WAGON_START)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', TRUCK_STANDBY_STAGING)
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING)
    this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: WAGON_START.cadX, cadY: WAGON_START.cadY, headingDeg: 0 }, 2.1)
    this.runtime.setCranePose('CR-02', { ...CR_HOME, gantryX: bayX })
    this.runtime.setBridgeCranePose('BC-03', BC03_HOME)
    this.runtime.setBridgeCranePose('BC-07', BC_HOME)
    this.runtime.setProcessHighlight({
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
    const target = Math.max(0, Math.min(STEEL_UNLOAD_STEPS.length - 1, Math.floor(stepIndex)))
    this.stepIndex = target
    this.elapsedMs = 0
    this.completed = false
    this.resetSceneBaseline()
    const step = STEEL_UNLOAD_STEPS[target]
    this.runtime.setCamera(step.camera)
    if (autoPlay) {
      this.apply(step, 0)
      this.running = true
      this.lastTick = performance.now()
      this.emitState(0)
      this.timer = setInterval(() => this.tick(), 80)
    } else {
      const previewT = step.id === 'steel-truck-load' ? 0.72 : step.id === 'steel-weigh-verify' ? 0.85 : 0.45
      this.elapsedMs = Math.round(step.durationMs * previewT)
      this.apply(step, previewT)
      this.emitState(previewT)
    }
  }

  prevStep(autoPlay = true) {
    const prevIdx = Math.max(0, this.stepIndex - 1)
    this.jumpToStep(prevIdx, autoPlay)
  }

  nextStep(autoPlay = true) {
    const nextIdx = Math.min(STEEL_UNLOAD_STEPS.length - 1, this.stepIndex + 1)
    this.jumpToStep(nextIdx, autoPlay)
  }

  destroy() {
    this.reset()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 360)
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
        this.runtime.setCamera(STEEL_UNLOAD_STEPS[this.stepIndex].camera)
      }
    }
    this.emitState(t)
  }

  private apply(step: SteelStep, t: number) {
    const activeBay = this.subMode === 'coil_chook' ? BAY03 : BAY07
    const activeBcId = this.subMode === 'coil_chook' ? 'BC-03' : 'BC-07'
    const inactiveBcId = this.subMode === 'coil_chook' ? 'BC-07' : 'BC-03'
    const bayDeltaX = activeBay.cadX - BAY07.cadX
    const activePayload = this.subMode === 'coil_chook' ? 31.2 : 22.0

    this.runtime.setDeviceStatus('CR-02', step.craneStatus)
    this.runtime.setDeviceStatus(activeBcId, step.bridgeStatus)
    this.runtime.setDeviceStatus(inactiveBcId, 'standby')
    this.runtime.setStockyardQuantity('CARGO-01', Math.round(lerp(step.bayTons[0], step.bayTons[1], t)))
    this.runtime.setOperationState('OP-240801', Math.round(lerp(step.opProgress[0], step.opProgress[1], t)), t >= 1 ? step.opStatus : 'running')

    let crPose: CranePose
    if (step.id === 'steel-truck-load') {
      // Staged Step 4 CR-02 kinematics:
      // 0.00..0.24: move empty to apron & lower to hook steel bundle
      // 0.24..0.44: hoist bundle at apron
      // 0.44..0.63: hold at apron waiting for truck fence lock
      // 0.63..0.85: slow trolley traverse APRON_TARGET_Y -> TRUCK_LOAD_Y (~5 m/s) with SCS laser active
      // 0.85..1.00: slow hoist down 13.8m -> 4.1m onto truck flatbed
      if (t < 0.24) {
        const u = easeInOut(t / 0.24)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: lerp(0, CR_TROLLEY_APRON_OFFSET, Math.min(1, u * 1.25)),
          hoistHeight: lerp(16.0, 4.6, u),
          payloadTons: 0,
        }
      } else if (t < 0.44) {
        const u = easeInOut((t - 0.24) / 0.20)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_APRON_OFFSET,
          hoistHeight: lerp(4.6, 13.8, u),
          payloadTons: activePayload,
        }
      } else if (t < 0.63) {
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: CR_TROLLEY_APRON_OFFSET,
          hoistHeight: 13.8,
          payloadTons: activePayload,
        }
      } else if (t < 0.85) {
        const u = easeInOut((t - 0.63) / 0.22)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: lerp(CR_TROLLEY_APRON_OFFSET, TRUCK_LOAD_Y - CR02.cadY, u),
          hoistHeight: 13.8,
          payloadTons: activePayload,
        }
      } else {
        const u = easeInOut((t - 0.85) / 0.15)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: TRUCK_LOAD_Y - CR02.cadY,
          hoistHeight: lerp(13.8, 4.1, u),
          payloadTons: t > 0.97 ? 0 : activePayload,
        }
      }
    } else if (step.id === 'steel-weigh-verify') {
      // Staged Step 5 CR-02 kinematics: 0..0.16 lift hook clear above loaded truck, 0.16..0.38 retract trolley slowly to CR_HOME
      if (t < 0.16) {
        const u = easeInOut(t / 0.16)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: TRUCK_LOAD_Y - CR02.cadY,
          hoistHeight: lerp(4.1, 15.8, u),
          payloadTons: 0,
        }
      } else {
        const u = easeInOut((t - 0.16) / 0.26)
        crPose = {
          gantryX: activeBay.cadX,
          gantryY: CR02.cadY,
          trolleyOffset: lerp(TRUCK_LOAD_Y - CR02.cadY, 0, u),
          hoistHeight: lerp(15.8, 16.0, u),
          payloadTons: 0,
        }
      }
    } else {
      crPose = {
        gantryX: lerp(step.crane.from.gantryX, step.crane.to.gantryX, t) + bayDeltaX,
        gantryY: lerp(step.crane.from.gantryY, step.crane.to.gantryY, t),
        trolleyOffset: lerp(step.crane.from.trolleyOffset, step.crane.to.trolleyOffset, t),
        hoistHeight: lerp(step.crane.from.hoistHeight, step.crane.to.hoistHeight, t),
        payloadTons: this.subMode === 'coil_chook' && (step.crane.to.payloadTons ?? 0) > 0 ? 31.2 : step.crane.to.payloadTons,
      }
    }
    this.runtime.setCranePose('CR-02', crPose)

    const bcPose: BridgeCranePose = {
      cadX: activeBay.cadX,
      bridgeY: lerp(step.bridge.from.bridgeY, step.bridge.to.bridgeY, t),
      trolleyOffsetX: lerp(step.bridge.from.trolleyOffsetX, step.bridge.to.trolleyOffsetX, t),
      hoistHeight: lerp(step.bridge.from.hoistHeight, step.bridge.to.hoistHeight, t),
      payloadTons: this.subMode === 'coil_chook' && (step.bridge.to.payloadTons ?? 0) > 0 ? 29.6 : step.bridge.to.payloadTons,
    }
    this.runtime.setBridgeCranePose(activeBcId, bcPose)

    const wagonFrom: VehiclePose = { ...step.wagon.from, cadX: step.wagon.from.cadX + bayDeltaX }
    const wagonTo: VehiclePose = { ...step.wagon.to, cadX: step.wagon.to.cadX + bayDeltaX }
    const wagonPose = lerpVehicle(wagonFrom, wagonTo, t)

    // Route flatbed truck along safe paved roads (WB-TRUCK-02 <-> Y=1616.5 <-> X=4120 <-> Y=1655 loading lane <-> WB-TRUCK-01)
    let truckPose: VehiclePose
    if (step.id === 'steel-truck-load') {
      if (t < 0.10) {
        truckPose = followPolyline([[TRUCK_GATE.cadX, TRUCK_GATE.cadY], [WB_IN.cadX, WB_IN.cadY]], easeInOut(t / 0.10), 180)
      } else if (t < 0.28) {
        truckPose = { cadX: WB_IN.cadX, cadY: WB_IN.cadY, headingDeg: 180 }
      } else if (t < 0.49) {
        truckPose = followPolyline(
          [
            [WB_IN.cadX, WB_IN.cadY],
            [4525.0, WB_IN.cadY],
            [4525.0, 1616.5],
            [4120.0, 1616.5],
            [4120.0, TRUCK_LOAD_Y],
            [activeBay.cadX + 46, TRUCK_LOAD_Y],
          ],
          easeInOut((t - 0.28) / 0.21),
          180,
        )
      } else if (t < 0.63) {
        // Gentle forward U-loop in loading apron so truck finishes aligned East (heading 0) with flatbed centered at activeBay.cadX
        truckPose = followPolyline(
          [
            [activeBay.cadX + 46, TRUCK_LOAD_Y],
            [activeBay.cadX + 20, 1651.0],
            [activeBay.cadX - 10, 1651.0],
            [activeBay.cadX - 12, 1654.0],
            [activeBay.cadX - 5, TRUCK_LOAD_Y],
            [activeBay.cadX + 1.9, TRUCK_LOAD_Y],
          ],
          easeInOut((t - 0.49) / 0.14),
          0,
        )
      } else {
        truckPose = { cadX: activeBay.cadX + 1.9, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
      }
    } else if (step.id === 'steel-weigh-verify') {
      if (t < 0.14) {
        truckPose = { cadX: activeBay.cadX + 1.9, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
      } else if (t < 0.24) {
        truckPose = followPolyline(
          [
            [activeBay.cadX + 1.9, TRUCK_LOAD_Y],
            [activeBay.cadX + 44, TRUCK_LOAD_Y],
          ],
          easeInOut((t - 0.14) / 0.10),
          0,
        )
      } else if (t < 0.40) {
        truckPose = followPolyline(
          [
            [activeBay.cadX + 44, TRUCK_LOAD_Y],
            [4120.0, TRUCK_LOAD_Y],
            [4120.0, 1616.5],
            [4516.0, 1616.5],
          ],
          easeInOut((t - 0.24) / 0.16),
          0,
        )
      } else if (t < 0.50) {
        truckPose = followPolyline(
          [
            [4516.0, 1616.5],
            [4525.0, 1616.5],
            [4525.0, WB_OUT.cadY],
            [WB_OUT.cadX, WB_OUT.cadY],
          ],
          easeInOut((t - 0.40) / 0.10),
          0,
        )
      } else if (t < 0.74) {
        truckPose = { cadX: WB_OUT.cadX, cadY: WB_OUT.cadY, headingDeg: 0 }
      } else {
        truckPose = followPolyline(
          [
            [WB_OUT.cadX, WB_OUT.cadY],
            [WB_OUT.cadX + 18, WB_OUT.cadY],
          ],
          easeInOut((t - 0.74) / 0.26),
          0,
        )
      }
    } else {
      truckPose = TRUCK_STANDBY_STAGING
    }
    this.runtime.setVehiclePose('WAGON-FLAT-01', wagonPose)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', truckPose)
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING)

    // Move detached active steel bundle/coil cleanly across handoff chain with heading-aware flatbed offset (-1.9m)
    const truckRad = (truckPose.headingDeg * Math.PI) / 180
    const flatbedCargoPose: VehiclePose = {
      cadX: truckPose.cadX - 1.9 * Math.cos(truckRad),
      cadY: truckPose.cadY + 1.9 * Math.sin(truckRad),
      headingDeg: truckPose.headingDeg,
    }

    if (step.id === 'steel-truck-load') {
      if (t < 0.22) {
        this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }, 1.5)
      } else if (t < 0.97) {
        this.runtime.setVehiclePose(
          'CARGO-STEEL-ACTIVE',
          { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 },
          crPose.hoistHeight - 1.1,
        )
      } else {
        this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', flatbedCargoPose, 1.88)
      }
    } else if (step.cargoMode === 'wagon') {
      this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: wagonPose.cadX, cadY: wagonPose.cadY, headingDeg: 0 }, 2.1)
    } else if (step.cargoMode === 'crane') {
      this.runtime.setVehiclePose(
        'CARGO-STEEL-ACTIVE',
        { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 },
        crPose.hoistHeight - 1.1,
      )
    } else if (step.cargoMode === 'apron') {
      this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: activeBay.cadX, cadY: APRON_TARGET_Y, headingDeg: 0 }, 1.5)
    } else if (step.cargoMode === 'bridge') {
      this.runtime.setVehiclePose(
        'CARGO-STEEL-ACTIVE',
        { cadX: bcPose.cadX + bcPose.trolleyOffsetX, cadY: bcPose.bridgeY, headingDeg: 0 },
        bcPose.hoistHeight - 1.0,
      )
    } else if (step.cargoMode === 'truck') {
      this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', flatbedCargoPose, 1.88)
    } else {
      this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: activeBay.cadX, cadY: BC_STACK_Y, headingDeg: 0 }, 1.6)
    }

    // Dynamic sub-phase camera focus tracking & rich 3D visual highlights for Steps 4 & 5
    const gapDist = lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t)
    if (step.id === 'steel-truck-load') {
      if (t < 0.34) {
        this.runtime.focusPoint?.({
          tag: 'steel-4a-wb-in',
          cadX: WB_IN.cadX,
          cadY: WB_IN.cadY,
          cadZ: 2.2,
          radius: 18,
          headingDeg: -38,
          pitchDeg: -22,
          range: 92,
          durationSec: 0.65,
        })
      } else if (t < 0.63) {
        this.runtime.focusPoint?.({
          tag: 'steel-4b-dock',
          cadX: activeBay.cadX + 16,
          cadY: 1656,
          cadZ: 5.5,
          radius: 26,
          headingDeg: -34,
          pitchDeg: -24,
          range: 132,
          durationSec: 1.15,
        })
      } else {
        this.runtime.focusPoint?.({
          tag: 'steel-4c-load',
          cadX: activeBay.cadX + 4,
          cadY: 1658,
          cadZ: 5.0,
          radius: 18,
          headingDeg: -38,
          pitchDeg: -22,
          range: 96,
          durationSec: 0.85,
        })
      }

      const fenceReady = t >= 0.56
      const laserActive = t >= 0.62
      this.runtime.setProcessHighlight({
        antiCollisionActive: laserActive,
        gapLabelPos: { cadX: activeBay.cadX + 10, cadY: TRUCK_LOAD_Y + 4 },
        gapDistanceM: gapDist,
        laserFromPos: laserActive
          ? { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, cadZ: crPose.hoistHeight }
          : null,
        laserTargetPos: laserActive ? { cadX: truckPose.cadX, cadY: truckPose.cadY, cadZ: 3.2 } : null,
        fenceActive: fenceReady,
        fencePos: { cadX: activeBay.cadX, cadY: TRUCK_LOAD_Y },
        fenceLabel: t >= 0.82 ? '电子围栏闭锁 · 门吊慢速落钩装车' : '装车电子围栏闭锁 · 司机离车确认',
        underSiloFenceActive: false,
        wbInText: t <= 0.32 ? (t >= 0.10 ? 'WB-TRUCK-02 初磅皮重 16.4 t ✓' : 'WB-TRUCK-02 初磅过磅中…') : '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
      })
    } else if (step.id === 'steel-weigh-verify') {
      if (t < 0.24) {
        this.runtime.focusPoint?.({
          tag: 'steel-5a-depart',
          cadX: activeBay.cadX + 14,
          cadY: 1656,
          cadZ: 4.5,
          radius: 22,
          headingDeg: -32,
          pitchDeg: -22,
          range: 112,
          durationSec: 0.7,
        })
      } else if (t < 0.75) {
        this.runtime.focusPoint?.({
          tag: 'steel-5b-wb-out',
          cadX: WB_OUT.cadX - 4,
          cadY: WB_OUT.cadY + 2,
          cadZ: 2.5,
          radius: 20,
          headingDeg: -30,
          pitchDeg: -24,
          range: 105,
          durationSec: 1.15,
        })
      } else {
        this.runtime.focusPoint?.({
          tag: 'steel-5c-warning',
          cadX: activeBay.cadX,
          cadY: 1684,
          cadZ: 8.0,
          radius: 34,
          headingDeg: -38,
          pitchDeg: -27,
          range: 148,
          durationSec: 1.0,
        })
      }

      const showBayWarn = t >= 0.75
      this.runtime.setProcessHighlight({
        antiCollisionActive: true,
        gapLabelPos: { cadX: activeBay.cadX + 12, cadY: APRON_TARGET_Y },
        gapDistanceM: gapDist,
        laserFromPos: { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, cadZ: crPose.hoistHeight },
        laserTargetPos: { cadX: bcPose.cadX, cadY: bcPose.bridgeY, cadZ: 12.0 },
        fenceActive: false,
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: t >= 0.46 && t < 0.75 ? (t >= 0.56 ? 'WB-TRUCK-01 复磅 38.4 t · 净重 22.0 t ✓' : 'WB-TRUCK-01 满车复磅中…') : '',
        bayWarningPos: showBayWarn ? { cadX: activeBay.cadX, cadY: activeBay.cadY } : null,
        bayWarningText: showBayWarn ? `${activeBay.id} 高库存预警 82.5% (16,500t) · 防撞联锁 11.4m` : '',
      })
    } else {
      this.runtime.setProcessHighlight({
        antiCollisionActive: step.antiCollisionActive,
        gapLabelPos: { cadX: activeBay.cadX + 12, cadY: APRON_TARGET_Y },
        gapDistanceM: gapDist,
        laserFromPos: step.antiCollisionActive
          ? { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, cadZ: crPose.hoistHeight }
          : null,
        laserTargetPos: step.antiCollisionActive ? { cadX: bcPose.cadX, cadY: bcPose.bridgeY, cadZ: 11.0 } : null,
        fenceActive: false,
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
      })
    }
    this.runtime.requestRender()
  }

  private emitState(t = 0) {
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    const subLabel = this.subMode === 'coil_chook' ? '【卷材·32t C型钩 BC-03】' : '【螺纹钢·20t磁吸 BC-07】'
    this.onChange({
      processKind: 'steel',
      running: this.running,
      completed: this.completed,
      stepIndex: this.stepIndex,
      phaseIndex: step.phaseIndex,
      stepCount: STEEL_UNLOAD_STEPS.length,
      title: this.completed ? `演示完成：${subLabel}双级联合吊装与复磅闭环` : `${subLabel}${step.title}`,
      narration: this.completed
        ? `货4卸车→CR-02门吊→转接区B→${this.subMode === 'coil_chook' ? 'BC-03(32t C型吊具)入03跨' : 'BC-07(20t电磁吸盘)入07跨'}→货车防碰撞装车已全流程打通。`
        : step.narration,
      progress: this.completed
        ? 100
        : Math.round(((this.stepIndex + this.elapsedMs / step.durationMs) / STEEL_UNLOAD_STEPS.length) * 100),
      payloadTons: Math.max(step.crane.to.payloadTons ?? 0, step.bridge.to.payloadTons ?? 0),
      safetyDistanceM: Number(lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t).toFixed(1)),
      interlockChecks: step.interlocks,
    })
  }
}

export function resetDemoData(cargo: CargoItem[], personnel: PersonnelItem[]) {
  const nextCargo = structuredClone(cargo)
  const stockyard = nextCargo.find((item) => item.id === 'CARGO-01')
  if (stockyard) {
    stockyard.quantity = 14200
    stockyard.warning = false
  }
  return { cargo: nextCargo, personnel: structuredClone(personnel) }
}
