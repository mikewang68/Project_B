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
const TANKER_GATE: VehiclePose = { cadX: WB_IN.cadX, cadY: WB_IN.cadY, headingDeg: 180 }
const TANKER_ROAD_Y = 1586.5
const TANKER_APPROACH: VehiclePose = { cadX: SILO01.cadX + 26, cadY: TANKER_ROAD_Y, headingDeg: 180 }
const TANKER_DOCKED: VehiclePose = { cadX: SILO01.cadX, cadY: SILO01.cadY, headingDeg: 180 }
const TANKER_EXIT_CORRIDOR: VehiclePose = { cadX: SILO01.cadX + 26, cadY: TANKER_ROAD_Y, headingDeg: 0 }
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
    tanker: { from: TANKER_GATE, to: TANKER_APPROACH },
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
    tanker: { from: TANKER_APPROACH, to: TANKER_APPROACH },
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
    title: '4. 振动清箱→135°复位退回→门吊吊回空箱 + 罐车入库底',
    narration: '振动电机拍打清箱达交箱标准并复核卸料口关闭，翻箱架 135°→0° 复位退回装卸位由门吊将空箱吊回铁路平车；罐车沿北侧通道驶入 SILO-01 库底。',
    durationMs: 4800,
    camera: 'tipper',
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
    tanker: { from: TANKER_APPROACH, to: TANKER_DOCKED },
    containerState: 'returning',
    flowFlags: { elevator: false, airSlide: false, spout: false },
    safetyDistanceM: [22.0, 25.0],
    interlocks: [
      { label: '振动拍打清箱完成 + 集装箱卸料口关闭复核', passed: true },
      { label: '翻转架 135°→0° 落平解锁 + 退回装卸工位回吊空箱', passed: true },
      { label: 'WB-TRUCK-02 初磅皮重 14.2t + 库底电子围栏与静电接地', passed: true },
    ],
  },
  {
    id: 'bulk-silo-quantitative-load',
    phaseIndex: 4,
    title: '5. 库底伸缩散装机定量装车（32.0t）与满车复磅',
    narration: 'LOADER-01 散装溜管垂直下降 2.3m 对接罐口开启定量装车（0→32.0t 自动停料回升），随后罐车沿北侧通道过 100t 地磅复磅出厂。',
    durationMs: 5200,
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
      to: { cadX: SILO01.cadX, cadY: SILO01.cadY, spoutHeight: 3.5, active: true, loadedTons: 32.0, targetTons: 32.0 },
    },
    wagon: { from: WAGON_POS, to: WAGON_POS },
    tanker: { from: TANKER_DOCKED, to: TANKER_EXIT },
    containerState: 'wagon',
    flowFlags: { elevator: false, airSlide: false, spout: true },
    safetyDistanceM: [30.0, 32.0],
    interlocks: [
      { label: '定量设定 32.0 t 到达自动关断底阀', passed: true },
      { label: '伸缩溜管回升复位 + 粉尘负压回收完成', passed: true },
      { label: 'WB-TRUCK-01 满车复磅：46.2 t（误差 ±0.1%）', passed: true },
    ],
    alert: {
      id: 'DEMO-SILO-01-BATCH',
      level: 'critical',
      targetId: 'LOADER-01',
      targetName: '1# 水泥筒仓与定量散装机 #01',
      message: 'SILO-01 批次发运达联锁核验节点（余量预警 + 罐车 青B·88219 满载 32.0t 待调度签发交接出厂），请核查厂家 PLC 状态与关联视频后进入 S1 处置。',
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
    this.running = true
    this.lastTick = performance.now()
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    this.runtime.setCamera(step.camera)
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
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_GATE)
    this.runtime.setProcessHighlight({ elevatorActive: false, airSlideActive: false, spoutActive: false, antiCollisionActive: false })
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
      const previewT = 0.45
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
    this.elapsedMs += Math.min(now - this.lastTick, 160)
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

    this.runtime.setStockyardQuantity('CARGO-02', Math.round(lerp(step.siloTons[0], step.siloTons[1], t)))
    this.runtime.setOperationState('OP-240802', Math.round(lerp(step.opProgress[0], step.opProgress[1], t)), step.opStatus)

    // Step 4 staged kinematics: 0..0.42 reset tilt 135°->0°, 0.42..0.72 travel back to TIPPER_HOME, 0.72..1.0 crane hoists empty container to flatcar
    let crPose: CranePose
    let tpPose: TipperPose
    if (step.id === 'bulk-return-and-tanker-weigh') {
      const tiltProgress = Math.min(1, t / 0.42)
      const travelProgress = t < 0.42 ? 0 : Math.min(1, (t - 0.42) / 0.30)
      const hoistProgress = t < 0.72 ? 0 : Math.min(1, (t - 0.72) / 0.28)
      tpPose = {
        cadX: lerp(TIPPER_UNLOAD.cadX, TIPPER_HOME.cadX, travelProgress),
        cadY: TIPPER_HOME.cadY,
        tiltDeg: lerp(135, 0, tiltProgress),
        vibrating: t < 0.15,
      }
      crPose = {
        gantryX: COMPLEX1.tipperParkX,
        gantryY: BULK_CR01.cadY,
        trolleyOffset: t < 0.72 ? CR_TIPPER_OFFSET : lerp(CR_TIPPER_OFFSET, CR_TRACK_OFFSET, hoistProgress),
        hoistHeight: t < 0.72 ? lerp(15.0, 6.2, Math.min(1, t / 0.72)) : lerp(6.2, 4.5, hoistProgress),
        payloadTons: t < 0.72 ? 0 : 3.8,
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

    // First 72% of step 5: truck stays docked under silo while spout descends and fills 32.0t; last 28% retracts & drives out via North road
    const spoutCurve = step.id === 'bulk-silo-quantitative-load' ? (t < 0.72 ? Math.min(1, t / 0.25) : Math.max(0, 1 - (t - 0.72) / 0.15)) : 0
    const loaderPose: SiloLoaderPose = {
      cadX: SILO01.cadX,
      cadY: SILO01.cadY,
      spoutHeight: step.id === 'bulk-silo-quantitative-load' ? lerp(5.8, 3.4, spoutCurve) : 5.8,
      active: step.flowFlags.spout && t < 0.72,
      loadedTons: step.id === 'bulk-silo-quantitative-load' ? Math.min(32.0, (t / 0.72) * 32.0) : 0,
      targetTons: 32.0,
    }
    this.runtime.setLoaderPose('LOADER-01', loaderPose)

    // Synchronize tanker travel along North road corridor (cadY = 1586.5) so it never clips through Row-A silos
    let tankerPose = lerpVehicle(step.tanker.from, step.tanker.to, t)
    if (step.id === 'bulk-silo-quantitative-load') {
      if (t < 0.72) {
        tankerPose = TANKER_DOCKED
      } else if (t < 0.80) {
        tankerPose = lerpVehicle(TANKER_DOCKED, TANKER_EXIT_CORRIDOR, (t - 0.72) / 0.08)
      } else {
        tankerPose = lerpVehicle(TANKER_EXIT_CORRIDOR, TANKER_EXIT, (t - 0.80) / 0.20)
      }
    }
    this.runtime.setVehiclePose('TRUCK-TANK-01', tankerPose)
    this.runtime.setVehiclePose('WAGON-BULK-01', lerpVehicle(step.wagon.from, step.wagon.to, t))

    // Synchronize 35t bulk container across gantry / mobile tipper / 135 deg pivot tilt / empty container return
    if (step.containerState === 'crane' || (step.containerState === 'returning' && t >= 0.72)) {
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 },
        crPose.hoistHeight - 2.6,
      )
    } else if (step.containerState === 'tipper' || step.containerState === 'tipping' || step.containerState === 'returning') {
      // Exact rigid-body hinge rotation around (cadY + 1.85, Z = 3.2) for container bottom at (dy0 = -1.85, dz0 = -1.55)
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

    this.runtime.setProcessHighlight({
      elevatorActive: step.flowFlags.elevator,
      airSlideActive: step.flowFlags.airSlide,
      spoutActive: step.flowFlags.spout && t < 0.72,
      spoutX: SILO01.cadX,
      spoutY: SILO01.cadY,
      antiCollisionActive: false,
    })
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
      payloadTons: step.id === 'bulk-silo-quantitative-load' ? Number(Math.min(32.0, (t / 0.72) * 32.0).toFixed(1)) : step.crane.to.payloadTons ?? 0,
      safetyDistanceM: Number(lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t).toFixed(1)),
      interlockChecks: step.interlocks,
    })
  }
}
