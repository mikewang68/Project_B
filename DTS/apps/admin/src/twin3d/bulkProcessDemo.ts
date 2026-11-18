import type { AlertItem } from '@/stores/alert'
import type { EquipmentDef } from './constants'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'
import {
  lerp,
  lerpVehicle,
  type InterlockChecklistItem,
  type ProcessControllerHandle,
  type ProcessDemoState,
  type UnifiedProcessRuntime,
} from './processTypes'
import type { CranePose, SiloLoaderPose, TipperPose } from './models/buildEquipment'
import type { VehiclePose } from './vehicleAnimator'
import type { CameraPresetId } from './cameraPresets'

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

interface BulkStep {
  id: string
  phaseIndex: number
  title: string
  narration: string
  durationMs: number
  camera: CameraPresetId
  craneStatus: EquipmentDef['status']
  tipperStatus: EquipmentDef['status']
  elevatorStatus: EquipmentDef['status']
  loaderStatus: EquipmentDef['status']
  opProgress: [number, number]
  opStatus: 'running' | 'queued' | 'done' | 'warning'
  siloTons: [number, number]
  crane: { from: CranePose; to: CranePose }
  tipper: { from: TipperPose; to: TipperPose }
  loader: { from: SiloLoaderPose; to: SiloLoaderPose }
  wagon: { from: VehiclePose; to: VehiclePose }
  tanker: { from: VehiclePose; to: VehiclePose }
  containerState: 'wagon' | 'crane' | 'tipper' | 'tipping' | 'returning'
  flowFlags: { elevator: boolean; airSlide: boolean; spout: boolean }
  safetyDistanceM: [number, number]
  interlocks: InterlockChecklistItem[]
  alert?: Omit<AlertItem, 'time' | 'handled'>
}

const COMPLEX1 = SITE_LAYOUT_V4.ash.unloadingComplexes[0]
const BULK_CR01 = SITE_LAYOUT_V4.ash.bulkGantries[0]
const SILO01 = SITE_LAYOUT_V4.ash.silos[0]
const TRACK_H2_Y = SITE_LAYOUT_V4.ash.tracks[1].cadY
const WB_IN = SITE_LAYOUT_V4.weighbridges[1]
const WB_OUT = SITE_LAYOUT_V4.weighbridges[0]

const CR_HOME: CranePose = {
  gantryX: COMPLEX1.tipperParkX,
  gantryY: BULK_CR01.cadY,
  trolleyOffset: 0,
  hoistHeight: 15.0,
  payloadTons: 0,
}

const CR_TRACK_OFFSET = TRACK_H2_Y - BULK_CR01.cadY
const CR_TIPPER_OFFSET = COMPLEX1.tipperRailY - BULK_CR01.cadY

const TIPPER_HOME: TipperPose = { cadX: COMPLEX1.tipperParkX, cadY: COMPLEX1.tipperRailY, tiltDeg: 0, vibrating: false }
const TIPPER_UNLOAD: TipperPose = { cadX: COMPLEX1.tipperUnloadX, cadY: COMPLEX1.tipperRailY, tiltDeg: 0, vibrating: false }

const LOADER_HOME: SiloLoaderPose = { cadX: SILO01.cadX, cadY: SILO01.cadY, spoutHeight: 5.8, active: false, loadedTons: 0, targetTons: 32.0 }

const WAGON_POS: VehiclePose = { cadX: COMPLEX1.tipperParkX, cadY: TRACK_H2_Y, headingDeg: 0 }
const TANKER_GATE: VehiclePose = { cadX: WB_IN.cadX + 18, cadY: WB_IN.cadY, headingDeg: 180 }
const TANKER_STANDBY_STAGING: VehiclePose = { cadX: 4085.0, cadY: 1587.0, headingDeg: 180 }
const FLATBED_STANDBY_STAGING: VehiclePose = { cadX: 3860.0, cadY: SITE_LAYOUT_V4.rebar.truckLoadingY, headingDeg: 180 }
const TANKER_ROAD_Y = 1587.0
const TANKER_DOCKED: VehiclePose = { cadX: SILO01.cadX + 1.65, cadY: SILO01.cadY, headingDeg: 0 }
const TANKER_EXIT: VehiclePose = { cadX: WB_OUT.cadX, cadY: WB_OUT.cadY, headingDeg: 0 }

export const BULK_PROCESS_STEPS: BulkStep[] = [
  {
    id: 'bulk-crane-transfer',
    phaseIndex: 0,
    title: '1. 重车进货2线与门吊吊装 35t 散料箱',
    narration: '集装箱列车进货2股道完成 RFID 核验，1# 散料门吊（CR-BULK-01）吊起 35t 水泥集装箱落入移动翻箱机工位。',
    durationMs: 4200,
    camera: 'tipper',
    craneStatus: 'ok',
    tipperStatus: 'standby',
    elevatorStatus: 'standby',
    loaderStatus: 'standby',
    opProgress: [0, 22],
    opStatus: 'running',
    siloTons: [15600, 15600],
    crane: {
      from: { gantryX: COMPLEX1.tipperParkX, gantryY: BULK_CR01.cadY, trolleyOffset: CR_TRACK_OFFSET, hoistHeight: 4.5, payloadTons: 35.0 },
      to: { gantryX: COMPLEX1.tipperParkX, gantryY: BULK_CR01.cadY, trolleyOffset: CR_TIPPER_OFFSET, hoistHeight: 6.2, payloadTons: 35.0 },
    },
    tipper: { from: TIPPER_HOME, to: TIPPER_HOME },
    loader: { from: LOADER_HOME, to: LOADER_HOME },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_GATE, to: TANKER_GATE },
    containerState: 'crane',
    flowFlags: { elevator: false, airSlide: false, spout: false },
    safetyDistanceM: [31.5, 24.8],
    interlocks: [
      { label: '货2 股道集装箱车号核验通过', passed: true },
      { label: '翻箱机安全待命与空位确认', passed: true },
      { label: '门吊与相邻天车防碰撞间距 24.8m', passed: true },
    ],
  },
  {
    id: 'bulk-tipper-travel-interlock',
    phaseIndex: 1,
    title: '2. 移动翻箱机载箱移位与入室联锁校验',
    narration: 'TIPPER-01 沿轨水平移至半封闭卸料室（42m³料斗），顺序校验“到位识别 → 液压锁紧 → 静电接地 → 脉冲除尘开启”。',
    durationMs: 3600,
    camera: 'tipper',
    craneStatus: 'standby',
    tipperStatus: 'ok',
    elevatorStatus: 'ok',
    loaderStatus: 'standby',
    opProgress: [22, 42],
    opStatus: 'running',
    siloTons: [15600, 15600],
    crane: {
      from: { gantryX: COMPLEX1.tipperParkX, gantryY: BULK_CR01.cadY, trolleyOffset: CR_TIPPER_OFFSET, hoistHeight: 15.0, payloadTons: 0 },
      to: CR_HOME,
    },
    tipper: { from: TIPPER_HOME, to: TIPPER_UNLOAD },
    loader: { from: LOADER_HOME, to: LOADER_HOME },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_GATE, to: TANKER_GATE },
    containerState: 'tipper',
    flowFlags: { elevator: true, airSlide: false, spout: false },
    safetyDistanceM: [19.0, 16.5],
    interlocks: [
      { label: '半封闭卸料室翻箱工位限位到位', passed: true },
      { label: '集装箱液压锁紧 + 静电接地检测 < 4Ω', passed: true },
      { label: '卸料室 2×18.5kW 脉冲除尘器启动', passed: true },
    ],
  },
  {
    id: 'bulk-tip-and-elevate',
    phaseIndex: 2,
    title: '3. 135°翻转振动卸料→40m提升机→空气斜槽入仓',
    narration: '翻箱架翻转 0°→135° 并开启振动拍打，物料落入 42m³ 料斗经 40m 斗式提升机及仓顶空气斜槽连续输入 SILO-01 水泥筒仓。',
    durationMs: 5200,
    camera: 'ash',
    craneStatus: 'standby',
    tipperStatus: 'ok',
    elevatorStatus: 'ok',
    loaderStatus: 'standby',
    opProgress: [42, 68],
    opStatus: 'running',
    siloTons: [15600, 15635],
    crane: { from: CR_HOME, to: CR_HOME },
    tipper: {
      from: { ...TIPPER_UNLOAD, tiltDeg: 0, vibrating: true },
      to: { ...TIPPER_UNLOAD, tiltDeg: 135, vibrating: true },
    },
    loader: { from: LOADER_HOME, to: LOADER_HOME },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_GATE, to: TANKER_GATE },
    containerState: 'tipping',
    flowFlags: { elevator: true, airSlide: true, spout: false },
    safetyDistanceM: [26.0, 26.0],
    interlocks: [
      { label: '42m³ 料斗低料位顺控启动 ELV-01', passed: true },
      { label: '仓顶空气斜槽高压离心风机压差正常', passed: true },
      { label: 'SILO-01 高料位雷达预警监测正常（满仓自动联锁切 SILO-02）', passed: true },
    ],
  },
  {
    id: 'bulk-return-and-tanker-weigh',
    phaseIndex: 3,
    title: '4. 罐车初磅/库底围栏对位（同步振动清箱复位）',
    narration: '罐车驶上 WB-TRUCK-02 完成 14.2t 初磅，沿北侧专用通道绕至 SILO-01 西侧驶入库底通道开启电子围栏与静电接地闭锁；同步完成翻箱复位与门吊回吊空箱。',
    durationMs: 12000,
    camera: 'weighbridge',
    craneStatus: 'ok',
    tipperStatus: 'ok',
    elevatorStatus: 'standby',
    loaderStatus: 'standby',
    opProgress: [68, 84],
    opStatus: 'running',
    siloTons: [15635, 15635],
    crane: {
      from: { gantryX: COMPLEX1.tipperParkX, gantryY: BULK_CR01.cadY, trolleyOffset: CR_TIPPER_OFFSET, hoistHeight: 6.2, payloadTons: 3.8 },
      to: { gantryX: COMPLEX1.tipperParkX, gantryY: BULK_CR01.cadY, trolleyOffset: CR_TRACK_OFFSET, hoistHeight: 4.5, payloadTons: 3.8 },
    },
    tipper: {
      from: { ...TIPPER_UNLOAD, tiltDeg: 135, vibrating: false },
      to: TIPPER_HOME,
    },
    loader: { from: LOADER_HOME, to: LOADER_HOME },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_GATE, to: TANKER_DOCKED },
    containerState: 'returning',
    flowFlags: { elevator: false, airSlide: false, spout: false },
    safetyDistanceM: [22.0, 25.0],
    interlocks: [
      { label: 'WB-TRUCK-02 初磅皮重 14.2 t（已放行入场）', passed: true },
      { label: '振动清箱复位 + CR-BULK-01 空箱回吊货2平车', passed: true },
      { label: 'SILO-01 库底电子围栏闭锁 + 静电接地 < 4Ω', passed: true },
    ],
  },
  {
    id: 'bulk-silo-quantitative-load',
    phaseIndex: 4,
    title: '5. 库底定量散装（32.0t）与满车复磅出厂',
    narration: '西北视角直击 SILO-01 库底：LOADER-01 伸缩溜管下落对接罐口定量放料（0→32.0t），满料回升后罐车沿 A 排柱间通道东向穿出并驶上 WB-TRUCK-01 复磅 46.2t。',
    durationMs: 13000,
    camera: 'loader',
    craneStatus: 'standby',
    tipperStatus: 'standby',
    elevatorStatus: 'standby',
    loaderStatus: 'ok',
    opProgress: [84, 100],
    opStatus: 'done',
    siloTons: [15635, 15603],
    crane: { from: CR_HOME, to: CR_HOME },
    tipper: { from: TIPPER_HOME, to: TIPPER_HOME },
    loader: {
      from: { cadX: SILO01.cadX, cadY: SILO01.cadY, spoutHeight: 5.8, active: true, loadedTons: 0, targetTons: 32.0 },
      to: { cadX: SILO01.cadX, cadY: SILO01.cadY, spoutHeight: 4.35, active: true, loadedTons: 32.0, targetTons: 32.0 },
    },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_DOCKED, to: TANKER_EXIT },
    containerState: 'wagon',
    flowFlags: { elevator: false, airSlide: false, spout: true },
    safetyDistanceM: [30.0, 32.0],
    interlocks: [
      { label: '定量设定 32.0 t 到达自动关断底阀', passed: true },
      { label: '伸缩溜管完全回升复位 + 粉尘负压回收完成', passed: true },
      { label: 'WB-TRUCK-01 满车复磅：46.2 t（净装 32.0 t · 误差 ±0.1%）', passed: true },
    ],
    alert: {
      id: 'DEMO-SILO-01-BATCH',
      level: 'critical',
      targetId: 'LOADER-01',
      targetName: '1# 水泥筒仓与定量散装机 #01',
      message: 'SILO-01 批次发运达联锁核验节点（余量预警 + 罐车 青B·88219 满载 32.0t 完成 46.2t 复磅待签发出厂），请核查厂家 PLC 状态与关联视频后进入 S1 处置。',
      interlockReceipt: {
        sourceSystem: 'S1-PSMS',
        safetyDistanceM: 14.6,
        actions: ['声光报警', '核查厂家PLC与视频', '转S1装卸工艺管理确认'],
      },
    },
  },
]

export class BulkProcessDemoController implements ProcessControllerHandle {
  readonly kind = 'bulk' as const
  private readonly runtime: UnifiedProcessRuntime
  private readonly onChange: (state: ProcessDemoState) => void
  private timer: ReturnType<typeof setInterval> | null = null
  private stepIndex = 0
  private elapsedMs = 0
  private lastTick = 0
  private running = false
  private completed = false
  private singleStep = false

  constructor(runtime: UnifiedProcessRuntime, onChange: (state: ProcessDemoState) => void) {
    this.runtime = runtime
    this.onChange = onChange
  }

  setSingleStep(enabled: boolean) {
    this.singleStep = enabled
  }

  start() {
    if (this.completed) this.reset()
    if (this.running) return
    const currentStep = BULK_PROCESS_STEPS[this.stepIndex]
    if (this.elapsedMs >= currentStep.durationMs) {
      if (this.stepIndex < BULK_PROCESS_STEPS.length - 1) {
        this.stepIndex += 1
        this.elapsedMs = 0
      } else {
        this.elapsedMs = 0
      }
    }
    const resuming = this.elapsedMs > 0
    this.running = true
    this.lastTick = performance.now()
    const step = BULK_PROCESS_STEPS[this.stepIndex]
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
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    this.emitState(step ? Math.min(1, this.elapsedMs / step.durationMs) : 0)
  }

  private resetSceneBaseline() {
    this.runtime.clearAlerts()
    this.runtime.setDeviceStatus('CR-BULK-01', 'standby')
    this.runtime.setDeviceStatus('TIPPER-01', 'standby')
    this.runtime.setDeviceStatus('ELV-01', 'standby')
    this.runtime.setDeviceStatus('LOADER-01', 'standby')
    this.runtime.setStockyardQuantity('CARGO-02', 15600)
    this.runtime.setOperationState('OP-240802', 0, 'queued')
    this.runtime.setCranePose('CR-BULK-01', CR_HOME)
    this.runtime.setTipperPose('TIPPER-01', TIPPER_HOME)
    this.runtime.setLoaderPose('LOADER-01', LOADER_HOME)
    this.runtime.setVehiclePose('WAGON-BULK-01', WAGON_POS)
    this.runtime.setVehiclePose('CONT-BULK-01', WAGON_POS, 1.65)
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', FLATBED_STANDBY_STAGING)
    this.runtime.setProcessHighlight({
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
    const target = Math.max(0, Math.min(BULK_PROCESS_STEPS.length - 1, Math.floor(stepIndex)))
    this.stepIndex = target
    this.elapsedMs = 0
    this.completed = false
    this.resetSceneBaseline()
    const step = BULK_PROCESS_STEPS[target]
    this.runtime.setCamera(step.camera)
    if (autoPlay) {
      this.apply(step, 0)
      this.running = true
      this.lastTick = performance.now()
      this.emitState(0)
      this.timer = setInterval(() => this.tick(), 80)
    } else {
      const previewT = step.id === 'bulk-return-and-tanker-weigh' ? 0.85 : step.id === 'bulk-silo-quantitative-load' ? 0.28 : 0.45
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
    const nextIdx = Math.min(BULK_PROCESS_STEPS.length - 1, this.stepIndex + 1)
    this.jumpToStep(nextIdx, autoPlay)
  }

  destroy() {
    this.reset()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 360)
    this.lastTick = now
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    const t = Math.min(this.elapsedMs / step.durationMs, 1)
    this.apply(step, t)

    if (t >= 1) {
      if (step.alert) this.runtime.addAlert(step.alert)
      if (this.stepIndex === BULK_PROCESS_STEPS.length - 1) {
        this.pause()
        this.completed = true
      } else if (this.singleStep) {
        this.pause()
      } else {
        this.stepIndex += 1
        this.elapsedMs = 0
        this.runtime.setCamera(BULK_PROCESS_STEPS[this.stepIndex].camera)
      }
    }
    this.emitState(t)
  }

  private apply(step: BulkStep, t: number) {
    this.runtime.setDeviceStatus('CR-BULK-01', step.craneStatus)
    this.runtime.setDeviceStatus('TIPPER-01', step.tipperStatus)
    this.runtime.setDeviceStatus('ELV-01', step.elevatorStatus)
    this.runtime.setDeviceStatus('LOADER-01', step.loaderStatus)

    // For Step 5, only decrement silo tonnage during actual spout flow (t in 0.12..0.40)
    const siloRatio = step.id === 'bulk-silo-quantitative-load' ? clamp01((t - 0.12) / 0.28) : t
    this.runtime.setStockyardQuantity('CARGO-02', Math.round(lerp(step.siloTons[0], step.siloTons[1], siloRatio)))
    this.runtime.setOperationState('OP-240802', Math.round(lerp(step.opProgress[0], step.opProgress[1], t)), t >= 1 ? step.opStatus : 'running')

    // Step 4 staged kinematics:
    // - 0.00..0.28: vibrate cleanout & reset tilt 135°->0°
    // - 0.28..0.52: travel back to TIPPER_HOME
    // - 0.52..0.78: CR-BULK-01 lowers, lifts empty container across to flatcar WAGON-BULK-01
    // - 0.78..1.00: CR-BULK-01 retracts hook clear
    let crPose: CranePose
    let tpPose: TipperPose
    if (step.id === 'bulk-return-and-tanker-weigh') {
      const tiltProgress = easeInOut(t / 0.28)
      const travelProgress = easeInOut((t - 0.28) / 0.24)
      const hoistProgress = easeInOut((t - 0.52) / 0.26)
      tpPose = {
        cadX: lerp(TIPPER_UNLOAD.cadX, TIPPER_HOME.cadX, travelProgress),
        cadY: TIPPER_HOME.cadY,
        tiltDeg: lerp(135, 0, tiltProgress),
        vibrating: t < 0.10,
      }
      if (t < 0.52) {
        crPose = {
          gantryX: COMPLEX1.tipperParkX,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TIPPER_OFFSET,
          hoistHeight: lerp(15.0, 6.2, easeInOut(t / 0.52)),
          payloadTons: 0,
        }
      } else if (t < 0.78) {
        crPose = {
          gantryX: COMPLEX1.tipperParkX,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: lerp(CR_TIPPER_OFFSET, CR_TRACK_OFFSET, hoistProgress),
          hoistHeight: lerp(6.2, 4.5, hoistProgress),
          payloadTons: 3.8,
        }
      } else {
        crPose = {
          gantryX: COMPLEX1.tipperParkX,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TRACK_OFFSET,
          hoistHeight: lerp(4.5, 15.0, easeInOut((t - 0.78) / 0.22)),
          payloadTons: 0,
        }
      }
    } else {
      crPose = {
        gantryX: lerp(step.crane.from.gantryX, step.crane.to.gantryX, t),
        gantryY: lerp(step.crane.from.gantryY, step.crane.to.gantryY, t),
        trolleyOffset: lerp(step.crane.from.trolleyOffset, step.crane.to.trolleyOffset, t),
        hoistHeight: lerp(step.crane.from.hoistHeight, step.crane.to.hoistHeight, t),
        payloadTons: step.crane.to.payloadTons,
      }
      tpPose = {
        cadX: lerp(step.tipper.from.cadX, step.tipper.to.cadX, t),
        cadY: step.tipper.from.cadY,
        tiltDeg: lerp(step.tipper.from.tiltDeg, step.tipper.to.tiltDeg, t),
        vibrating: step.tipper.to.vibrating,
      }
    }
    this.runtime.setCranePose('CR-BULK-01', crPose)
    this.runtime.setTipperPose('TIPPER-01', tpPose)

    // Step 5 loader kinematics: 0..0.12 descend onto dome (5.8 -> 4.35), 0.12..0.40 fill 0->32.0t, 0.40..0.50 retract cleanly BEFORE truck moves!
    let spoutNorm = 0
    let spoutPouring = false
    let loadedTons = 0
    if (step.id === 'bulk-silo-quantitative-load') {
      if (t < 0.12) {
        spoutNorm = easeInOut(t / 0.12)
        spoutPouring = false
        loadedTons = 0
      } else if (t < 0.40) {
        spoutNorm = 1
        spoutPouring = true
        loadedTons = easeInOut((t - 0.12) / 0.28) * 32.0
      } else if (t < 0.50) {
        spoutNorm = 1 - easeInOut((t - 0.40) / 0.10)
        spoutPouring = false
        loadedTons = 32.0
      } else {
        spoutNorm = 0
        spoutPouring = false
        loadedTons = 32.0
      }
    }
    const loaderPose: SiloLoaderPose = {
      cadX: SILO01.cadX,
      cadY: SILO01.cadY,
      spoutHeight: step.id === 'bulk-silo-quantitative-load' ? lerp(5.8, 4.35, spoutNorm) : 5.8,
      active: spoutPouring,
      loadedTons,
      targetTons: 32.0,
    }
    this.runtime.setLoaderPose('LOADER-01', loaderPose)

    // Synchronize tanker travel along collision-free paved roads & coaxial Row-A drive-through tunnel (Y = 1568.049)
    let tankerPose: VehiclePose = TANKER_STANDBY_STAGING
    if (step.id === 'bulk-return-and-tanker-weigh') {
      if (t < 0.10) {
        tankerPose = followPolyline([[TANKER_GATE.cadX, TANKER_GATE.cadY], [WB_IN.cadX, WB_IN.cadY]], easeInOut(t / 0.10), 180)
      } else if (t < 0.28) {
        tankerPose = { cadX: WB_IN.cadX, cadY: WB_IN.cadY, headingDeg: 180 }
      } else if (t < 0.54) {
        tankerPose = followPolyline(
          [
            [WB_IN.cadX, WB_IN.cadY],
            [4525.0, WB_IN.cadY],
            [4525.0, 1616.5],
            [4120.0, 1616.5],
            [4120.0, TANKER_ROAD_Y],
            [3770.0, TANKER_ROAD_Y],
          ],
          easeInOut((t - 0.28) / 0.26),
          180,
        )
      } else if (t < 0.84) {
        // Enter through West X-portal so truck finishes facing East (headingDeg = 0) with center dome right under LOADER-01
        tankerPose = followPolyline(
          [
            [3770.0, TANKER_ROAD_Y],
            [3736.0, TANKER_ROAD_Y],
            [3736.0, SILO01.cadY],
            [TANKER_DOCKED.cadX, SILO01.cadY],
          ],
          easeInOut((t - 0.54) / 0.30),
          0,
        )
      } else {
        tankerPose = TANKER_DOCKED
      }
    } else if (step.id === 'bulk-silo-quantitative-load') {
      if (t < 0.50) {
        tankerPose = TANKER_DOCKED
      } else if (t < 0.74) {
        tankerPose = followPolyline(
          [
            [TANKER_DOCKED.cadX, SILO01.cadY],
            [4044.0, SILO01.cadY],
            [4044.0, TANKER_ROAD_Y],
            [4120.0, TANKER_ROAD_Y],
            [4120.0, 1616.5],
            [4505.0, 1616.5],
          ],
          easeInOut((t - 0.50) / 0.24),
          0,
        )
      } else if (t < 0.88) {
        tankerPose = followPolyline(
          [
            [4505.0, 1616.5],
            [4525.0, 1616.5],
            [4525.0, WB_OUT.cadY],
            [WB_OUT.cadX, WB_OUT.cadY],
          ],
          easeInOut((t - 0.74) / 0.14),
          0,
        )
      } else {
        tankerPose = TANKER_EXIT
      }
    } else {
      tankerPose = TANKER_STANDBY_STAGING
    }
    this.runtime.setVehiclePose('TRUCK-TANK-01', tankerPose)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', FLATBED_STANDBY_STAGING)
    this.runtime.setVehiclePose('WAGON-BULK-01', lerpVehicle(step.wagon.from, step.wagon.to, t))

    // Synchronize 35t bulk container across gantry / mobile tipper / 135 deg pivot tilt / empty container return
    if (step.containerState === 'crane' || (step.containerState === 'returning' && t >= 0.52 && t < 0.78)) {
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 },
        crPose.hoistHeight - 2.6,
      )
    } else if (step.containerState === 'returning' && t >= 0.78) {
      this.runtime.setVehiclePose('CONT-BULK-01', WAGON_POS, 1.65)
    } else if (step.containerState === 'tipper' || step.containerState === 'tipping' || step.containerState === 'returning') {
      const rad = (tpPose.tiltDeg * Math.PI) / 180
      const dy = -1.85 * Math.cos(rad) - 1.55 * Math.sin(rad)
      const dz = 1.85 * Math.sin(rad) - 1.55 * Math.cos(rad)
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: tpPose.cadX, cadY: tpPose.cadY + 1.85 + dy, headingDeg: 0 },
        3.2 + dz,
        0,
        -tpPose.tiltDeg,
      )
    } else {
      this.runtime.setVehiclePose('CONT-BULK-01', WAGON_POS, 1.65)
    }

    // Dynamic sub-phase camera focus & 3D visual indicators for Steps 4 & 5
    if (step.id === 'bulk-return-and-tanker-weigh') {
      if (t < 0.32) {
        this.runtime.focusPoint?.({
          tag: 'bulk-4a-wb-in',
          cadX: WB_IN.cadX,
          cadY: WB_IN.cadY,
          cadZ: 2.2,
          radius: 18,
          headingDeg: -38,
          pitchDeg: -22,
          range: 92,
          durationSec: 0.65,
        })
      } else if (t < 0.54) {
        this.runtime.focusPoint?.({
          tag: 'bulk-4b-tipper',
          cadX: 3782,
          cadY: 1545,
          cadZ: 5.0,
          radius: 42,
          headingDeg: -24,
          pitchDeg: -26,
          range: 175,
          durationSec: 1.15,
        })
      } else {
        this.runtime.focusPoint?.({
          tag: 'bulk-4c-silo-dock',
          cadX: SILO01.cadX - 4,
          cadY: SILO01.cadY,
          cadZ: 3.6,
          radius: 20,
          headingDeg: 110,
          pitchDeg: -12,
          range: 64,
          durationSec: 1.1,
        })
      }

      this.runtime.setProcessHighlight({
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        spoutX: SILO01.cadX,
        spoutY: SILO01.cadY,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: false,
        underSiloFenceActive: t >= 0.72,
        underSiloFencePos: { cadX: SILO01.cadX, cadY: SILO01.cadY },
        underSiloFenceText: t >= 0.84 ? 'SILO-01 库底电子围栏闭锁 · 静电接地 < 4Ω ✓' : '库底电子围栏对位检测中…',
        wbInText: t <= 0.30 ? (t >= 0.10 ? 'WB-TRUCK-02 初磅皮重 14.2 t ✓' : 'WB-TRUCK-02 罐车初磅中…') : '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
      })
    } else if (step.id === 'bulk-silo-quantitative-load') {
      if (t < 0.50) {
        this.runtime.focusPoint?.({
          tag: 'bulk-5a-silo-load',
          cadX: SILO01.cadX - 1,
          cadY: SILO01.cadY,
          cadZ: 3.8,
          radius: 18,
          headingDeg: 110,
          pitchDeg: -11,
          range: 58,
          durationSec: 0.7,
        })
      } else if (t < 0.74) {
        this.runtime.focusPoint?.({
          tag: 'bulk-5b-exit-corridor',
          cadX: 4040,
          cadY: 1582,
          cadZ: 8.0,
          radius: 55,
          headingDeg: -128,
          pitchDeg: -22,
          range: 210,
          durationSec: 1.2,
        })
      } else {
        this.runtime.focusPoint?.({
          tag: 'bulk-5c-wb-out',
          cadX: WB_OUT.cadX - 4,
          cadY: WB_OUT.cadY + 2,
          cadZ: 2.5,
          radius: 20,
          headingDeg: -30,
          pitchDeg: -24,
          range: 105,
          durationSec: 1.1,
        })
      }

      const fenceMsg =
        t < 0.12
          ? '伸缩散装头下落密封对接中…'
          : t < 0.40
            ? `定量散装中 ${loadedTons.toFixed(1)} / 32.0 t`
            : '32.0 t 定量停料 · 溜管回升复位完成 ✓'
      this.runtime.setProcessHighlight({
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: spoutPouring,
        spoutX: SILO01.cadX,
        spoutY: SILO01.cadY,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: false,
        underSiloFenceActive: t <= 0.52,
        underSiloFencePos: { cadX: SILO01.cadX, cadY: SILO01.cadY },
        underSiloFenceText: fenceMsg,
        wbInText: '',
        wbOutText: t >= 0.74 ? (t >= 0.88 ? 'WB-TRUCK-01 满车复磅 46.2 t · 净装 32.0 t ✓' : 'WB-TRUCK-01 罐车复磅计量中…') : '',
        bayWarningPos: null,
        bayWarningText: '',
      })
    } else {
      this.runtime.setProcessHighlight({
        elevatorActive: step.flowFlags.elevator,
        airSlideActive: step.flowFlags.airSlide,
        spoutActive: false,
        spoutX: SILO01.cadX,
        spoutY: SILO01.cadY,
        antiCollisionActive: false,
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
    this.runtime.requestRender()
  }

  private emitState(t = 0) {
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    this.onChange({
      processKind: 'bulk',
      running: this.running,
      completed: this.completed,
      stepIndex: this.stepIndex,
      phaseIndex: step.phaseIndex,
      stepCount: BULK_PROCESS_STEPS.length,
      title: this.completed ? '演示完成：散料翻箱入仓与库底定量装车闭环' : step.title,
      narration: this.completed ? '货2集装箱→CR-BULK-01→TIPPER-01(135°)→42m³料斗→提升机/斜槽→40筒仓→LOADER-01定量装车已完成。' : step.narration,
      progress: this.completed
        ? 100
        : Math.round(((this.stepIndex + this.elapsedMs / step.durationMs) / BULK_PROCESS_STEPS.length) * 100),
      payloadTons:
        step.id === 'bulk-silo-quantitative-load'
          ? Number(Math.min(32.0, clamp01((t - 0.12) / 0.28) * 32.0).toFixed(1))
          : step.crane.to.payloadTons ?? 0,
      safetyDistanceM: Number(lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t).toFixed(1)),
      interlockChecks: step.interlocks,
    })
  }
}
