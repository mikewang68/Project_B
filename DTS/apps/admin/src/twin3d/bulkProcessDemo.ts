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

/** Smooth vertical elevation matching V5.3 Blender assets: CAD asphalt (-0.14m) -> ramps -> angled weighbridge (-0.04m) / silo apron (-0.015m) / under-silo (0.0m) */
function computeTankerElevation(x: number, y: number): number {
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
  // Under-silo Row-A concrete loading lane (Z = 0.00m)
  if (x >= 3746 && x <= 4050 && y >= 1552 && y <= 1576) {
    if (x >= 3754 && x <= 4042) return 0.0
    const edgeDist = x < 3754 ? 3754 - x : x - 4042
    return lerp(0.0, -0.015, clamp01(edgeDist / 8.0))
  }
  // Silo yard service apron lane (Y <= 1594, Z = -0.015m) with smooth ramp up from Main Trunk (-0.14m)
  if (x >= 3726 && x <= 4224 && y >= 1550 && y <= 1612) {
    if (y <= 1594) return -0.015
    return lerp(-0.015, -0.14, clamp01((y - 1594) / 14.0))
  }
  return -0.14
}

interface BulkStep {
  id:
    | 'bulk-1-train-and-crane-drop'
    | 'bulk-2-tipper-rail-and-clamp'
    | 'bulk-3-tip-bellows-belt-silo'
    | 'bulk-4-close-return-rail-crane'
    | 'bulk-5-tanker-weigh-in-and-dock'
    | 'bulk-6-silo-load-and-weigh-out'
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

// Exact CAD East-West parallel mobile tilter coordinates (parallel to railway tracks 货1/货2!)
const TIPPER_WAIT_X = COMPLEX1.tipperWaitX ?? 3799.2
const TIPPER_UNLOAD_X = COMPLEX1.tipperUnloadX ?? 3787.89
const TIPPER_SOUTH_Y = COMPLEX1.tipperSouthY ?? 1523.85
const TIPPER_NORTH_Y = COMPLEX1.tipperNorthY ?? 1529.55
const WORKROOM_X = COMPLEX1.workroomX ?? COMPLEX1.elevatorCadX ?? 3781.04

const CR_TRACK_OFFSET = TRACK_H2_Y - BULK_CR01.cadY // -6.50m (over 货2)
const CR_WAIT_OFFSET = TIPPER_SOUTH_Y - BULK_CR01.cadY // +2.556m (over East-West South tilter rail)

const WB_IN_HEADING = 180 - (WB_IN.angleDeg ?? 10.95) // ~169.05° (WSW inbound)
const WB_OUT_HEADING = -(WB_OUT.angleDeg ?? 59.0) // -59.0° (NNE outbound)

const CR_HOME: CranePose = {
  gantryX: TIPPER_WAIT_X,
  gantryY: BULK_CR01.cadY,
  trolleyOffset: CR_TRACK_OFFSET,
  hoistHeight: 14.5,
  payloadTons: 0,
}

const TIPPER_WAIT: TipperPose = {
  cadX: TIPPER_WAIT_X,
  cadY: TIPPER_SOUTH_Y,
  tiltDeg: 0,
  clampLocked: false,
  pinExtracted: false,
  leftDoorDeg: 0,
  rightDoorDeg: 0,
  bellowsDropM: 0,
  vibrating: false,
}

const TIPPER_NORTH_STANDBY: TipperPose = {
  cadX: TIPPER_WAIT_X,
  cadY: TIPPER_NORTH_Y,
  tiltDeg: 0,
  clampLocked: false,
  pinExtracted: false,
  leftDoorDeg: 0,
  rightDoorDeg: 0,
  bellowsDropM: 0,
  vibrating: false,
  stageLabel: '北侧平行东西轨待命接箱',
}

const LOADER_HOME: SiloLoaderPose = {
  cadX: SILO01.cadX,
  cadY: SILO01.cadY,
  spoutHeight: 5.8,
  active: false,
  loadedTons: 0,
  targetTons: 32.0,
}

const WAGON_STOP_POS: VehiclePose = { cadX: TIPPER_WAIT_X, cadY: TRACK_H2_Y, headingDeg: 0 }
const TANKER_STANDBY_STAGING: VehiclePose = { cadX: 4095.0, cadY: 1588.0, headingDeg: 180 }
const FLATBED_STANDBY_STAGING: VehiclePose = { cadX: 3860.0, cadY: SITE_LAYOUT_V4.rebar.truckLoadingY, headingDeg: 180 }
const TANKER_DOCKED: VehiclePose = { cadX: SILO01.cadX + 1.65, cadY: SILO01.cadY, headingDeg: 0 }

// Exact CAD road waypoints for Inbound Tanker (East Gate -> WB-TRUCK-02 -> Main Trunk Y=1616.5 -> Silo Apron X=4212/Y=1588 -> Row-A SILO-01)
const WB_IN_COS = Math.cos(((WB_IN.angleDeg ?? 10.95) * Math.PI) / 180)
const WB_IN_SIN = Math.sin(((WB_IN.angleDeg ?? 10.95) * Math.PI) / 180)
const WB_IN_EAST_PT: [number, number] = [WB_IN.cadX + 14.0 * WB_IN_COS, WB_IN.cadY + 14.0 * WB_IN_SIN]
const WB_IN_WEST_PT: [number, number] = [WB_IN.cadX - 14.0 * WB_IN_COS, WB_IN.cadY - 14.0 * WB_IN_SIN]

const WB_OUT_COS = Math.cos(((WB_OUT.angleDeg ?? 59.0) * Math.PI) / 180)
const WB_OUT_SIN = Math.sin(((WB_OUT.angleDeg ?? 59.0) * Math.PI) / 180)
const WB_OUT_SW_PT: [number, number] = [WB_OUT.cadX - 14.0 * WB_OUT_COS, WB_OUT.cadY - 14.0 * WB_OUT_SIN]
const WB_OUT_NE_PT: [number, number] = [WB_OUT.cadX + 14.0 * WB_OUT_COS, WB_OUT.cadY + 14.0 * WB_OUT_SIN]

export const BULK_PROCESS_STEPS: BulkStep[] = [
  {
    id: 'bulk-1-train-and-crane-drop',
    phaseIndex: 0,
    title: '1. 散料列车进线停稳 · 龙门吊落箱至平行东西轨移动翻箱机',
    narration:
      '散料专列平稳减速停靠货2股道；按 CAD 平面及剖面原图，移动翻箱机轨道严格平行于铁路股道呈东西向双线布置（南轨 Y=1523.85 / 北轨 Y=1529.55），TIPPER-01 与 TIPPER-05 在东侧露天等待位（X=3799.2）并列待命。CR-BULK-01 严格执行纯一维直线动作（Z→Y→Z），将 20ft 粉煤灰集装箱平稳落入 TIPPER-01 托架。',
    durationMs: 22000,
    camera: 'tipper',
    craneStatus: 'ok',
    tipperStatus: 'standby',
    elevatorStatus: 'standby',
    loaderStatus: 'standby',
    opProgress: [0, 18],
    opStatus: 'running',
    siloTons: [15600, 15600],
    safetyDistanceM: [31.5, 24.8],
    interlocks: [
      { label: '双列移动翻箱机轨道严格平行于货1/货2呈东西走向（轨距 3.5m）', passed: true },
      { label: '龙门吊一维直线（-Z→+Z→+Y→-Z）平稳落箱至东侧等待位', passed: true },
      { label: '门吊与相邻翻箱机防碰撞测距安全（>24m）', passed: true },
    ],
  },
  {
    id: 'bulk-2-tipper-rail-and-clamp',
    phaseIndex: 1,
    title: '2. 翻箱机沿东西轨道西行驶入卸料工位 · 抱臂锁紧与顶门拔销',
    narration:
      '吊具垂直升离后，TIPPER-01 载箱沿东西向双轨向西行驶（X: 3799.2 → 3787.89，Y 恒定 1523.85），精准驶入后方卸料工作室（X=3781.04）东侧紧邻的翻箱工位并对齐顶部吸灰波纹管；完成静电接地（<4Ω）、抱臂锁紧箱体，顶部工程蓝压门爪顶紧箱门并拔销预开 6° 排气微缝。',
    durationMs: 18000,
    camera: 'tipper',
    craneStatus: 'standby',
    tipperStatus: 'ok',
    elevatorStatus: 'ok',
    loaderStatus: 'standby',
    opProgress: [18, 34],
    opStatus: 'running',
    siloTons: [15600, 15600],
    safetyDistanceM: [24.8, 19.6],
    interlocks: [
      { label: '翻箱机沿平行东西轨西行入位（X: 3799.2 → 3787.89）', passed: true },
      { label: '红色抱臂锁紧 + 蓝爪顶紧端门 + 拔销预开 6° 排气缝', passed: true },
      { label: '静电接地 < 4Ω + 后方工作室除尘/负压抽灰风机就绪', passed: true },
    ],
  },
  {
    id: 'bulk-3-tip-bellows-belt-silo',
    phaseIndex: 2,
    title: '3. 90°翻转 · 顶部负压抽灰送入后方工作室 · 工作室内皮带输送入仓',
    narration:
      '镜头稳锁翻箱工位、架空负压抽灰管与玻璃幕墙后方工作室（卸料室）：0°→3°→90° 垂直翻转后掀起双扇顶门 92°，波纹伸缩吸灰管下放 1.2m 插入箱口，先经顶部架空负压管道将粉煤灰抽送至翻箱机后方（西侧）工作室旋风分离料斗，再由工作室内部的皮带输送机（X=3781.04）向北输送至 40m 斗式提升机及空气斜槽汇入 SILO-01。',
    durationMs: 28000,
    camera: 'tipper',
    craneStatus: 'standby',
    tipperStatus: 'ok',
    elevatorStatus: 'ok',
    loaderStatus: 'standby',
    opProgress: [34, 58],
    opStatus: 'running',
    siloTons: [15600, 15635],
    safetyDistanceM: [26.0, 26.0],
    interlocks: [
      { label: '① 90°翻转顶门开启 → 波纹管顶部负压抽灰送入后方工作室', passed: true },
      { label: '② 后方工作室内部皮带输送机（X=3781.04）向北接力输灰', passed: true },
      { label: '③ 40m 斗式提升机（ELV-01）→ 空气斜槽 → SILO-01 (+35t)', passed: true },
    ],
  },
  {
    id: 'bulk-4-close-return-rail-crane',
    phaseIndex: 3,
    title: '4. 关门翻回 0° · 沿东西轨东退回位 · 龙门吊一维吊回火车平车',
    narration:
      '吸灰清仓完成停震 → 顶部波纹伸缩管垂直收回 → 双扇箱门闭合锁紧 → 托架将空集装箱由 90° 平稳翻回 0° 水平位 → TIPPER-01 沿平行东西轨道向东退回露天接箱位（X: 3787.89 → 3799.2）→ 抱臂松开，CR-BULK-01 纯一维直行夹起空箱放回货2火车平车。',
    durationMs: 26000,
    camera: 'tipper',
    craneStatus: 'ok',
    tipperStatus: 'ok',
    elevatorStatus: 'standby',
    loaderStatus: 'standby',
    opProgress: [58, 72],
    opStatus: 'running',
    siloTons: [15635, 15635],
    safetyDistanceM: [25.0, 22.0],
    interlocks: [
      { label: '顶部波纹管收回 → 双门闭合锁紧 → 90°→0° 翻回水平位', passed: true },
      { label: '翻箱机沿东西双轨东退至露天接箱位（X=3799.2）并解锁抱臂', passed: true },
      { label: 'CR-BULK-01 一维直线（-Z→+Z→-Y→-Z）将空箱放回货2平车', passed: true },
    ],
  },
  {
    id: 'bulk-5-tanker-weigh-in-and-dock',
    phaseIndex: 4,
    title: '5. 大广角锁定跟拍罐车进站 · 过初磅 · 沿CAD道路驶入筒仓底（自动推近特写）',
    narration:
      '高空大广角镜头始终居中锁定散装罐车（青B·88219），完整呈现全站走行路线：穿 40m 东大门雨棚 → 慢速驶上 +10.95° 斜交入口地磅 WB-TRUCK-02 停稳完成 14.2t 空车初磅 → 沿主干道（Y=1616.5）西行转入筒仓北服务道（Y=1588）驶向 1# 仓，对位进仓底时镜头平滑放大准备接灰特写！',
    durationMs: 28000,
    camera: 'weighbridge',
    craneStatus: 'standby',
    tipperStatus: 'standby',
    elevatorStatus: 'standby',
    loaderStatus: 'standby',
    opProgress: [72, 85],
    opStatus: 'running',
    siloTons: [15635, 15635],
    safetyDistanceM: [28.0, 29.5],
    interlocks: [
      { label: '高空大广角居中锁定罐车 青B·88219：进站全貌路线一目了然', passed: true },
      { label: 'WB-TRUCK-02（CAD 10.95°斜交磅）慢速停稳初磅：14.2 t ✓', passed: true },
      { label: 'SILO-01 仓底对位停稳 + 电子围栏闭锁 → 平滑推近准备接灰特写', passed: true },
    ],
  },
  {
    id: 'bulk-6-silo-load-and-weigh-out',
    phaseIndex: 5,
    title: '6. 仓底定量接灰高清特写（32.0t）· 平滑拉远大广角锁定罐车复磅出站',
    narration:
      '前半段高清特写聚焦 SILO-01 仓底定量散装机（LOADER-01）下放对接罐口、定量放灰 32.0t 自动关断并复核底阀关闭；装灰完毕罐车东出仓底通道后，镜头平滑拉远切回大广角居中锁定罐车，俯瞰沿主干道/停车场南绕经 +59° 出口重车地磅 WB-TRUCK-01 停稳复磅 46.2t 并穿过 40m 东大门出厂！',
    durationMs: 32000,
    camera: 'loader',
    craneStatus: 'standby',
    tipperStatus: 'standby',
    elevatorStatus: 'standby',
    loaderStatus: 'ok',
    opProgress: [85, 100],
    opStatus: 'done',
    siloTons: [15635, 15603],
    safetyDistanceM: [30.0, 32.0],
    interlocks: [
      { label: '装灰阶段高清特写：定量放灰 32.0 t 停料 + 卸料底阀关闭复核 ✓', passed: true },
      { label: '出仓后镜头平滑拉远大广角锁定罐车，全站出站路径一目了然', passed: true },
      { label: 'WB-TRUCK-01（CAD 59°斜交磅）停稳复磅 46.2 t → 穿东大门出厂', passed: true },
    ],
    alert: {
      id: 'DEMO-SILO-01-BATCH',
      level: 'critical',
      targetId: 'LOADER-01',
      targetName: '1# 水泥/粉煤灰筒仓定量散装机 #01',
      message:
        'SILO-01 批次发运达联锁核验节点（罐车 青B·88219 满载 32.0t 完成 46.2t 出口复磅待签发出厂），请核查厂家 PLC 状态与关联视频后进入 S1 处置。',
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
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    this.emitState(step ? Math.min(1, this.elapsedMs / step.durationMs) : 0)
  }

  private resetSceneBaseline() {
    this.runtime.trackPoint?.(null)
    this.runtime.clearAlerts()
    this.runtime.setDeviceStatus('CR-BULK-01', 'standby')
    this.runtime.setDeviceStatus('TIPPER-01', 'standby')
    this.runtime.setDeviceStatus('TIPPER-05', 'standby')
    this.runtime.setDeviceStatus('ELV-01', 'standby')
    this.runtime.setDeviceStatus('LOADER-01', 'standby')
    this.runtime.setStockyardQuantity('CARGO-02', 15600)
    this.runtime.setOperationState('OP-240802', 0, 'queued')
    this.runtime.setCranePose('CR-BULK-01', CR_HOME)
    this.runtime.setTipperPose('TIPPER-01', TIPPER_WAIT)
    this.runtime.setTipperPose('TIPPER-05', TIPPER_NORTH_STANDBY)
    this.runtime.setLoaderPose('LOADER-01', LOADER_HOME)
    this.runtime.setVehiclePose('WAGON-BULK-01', { cadX: WAGON_STOP_POS.cadX - 17, cadY: TRACK_H2_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('WAGON-BULK-02', WAGON_STOP_POS)
    this.runtime.setVehiclePose('WAGON-BULK-03', { cadX: WAGON_STOP_POS.cadX + 17, cadY: TRACK_H2_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('CONT-BULK-01', WAGON_STOP_POS, 1.56, 0, 0)
    this.runtime.setVehiclePose('TRUCK-TANK-01', TANKER_STANDBY_STAGING, -0.015)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', FLATBED_STANDBY_STAGING, 0.06)
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
    const target = Math.max(0, Math.min(BULK_PROCESS_STEPS.length - 1, Math.floor(stepIndex)))
    this.stepIndex = target
    this.elapsedMs = 0
    this.completed = false
    this.resetSceneBaseline()
    const step = BULK_PROCESS_STEPS[target]
    if (autoPlay) {
      this.apply(step, 0)
      this.running = true
      this.lastTick = performance.now()
      this.emitState(0)
      this.timer = setInterval(() => this.tick(), 50)
    } else {
      const previewT =
        step.id === 'bulk-3-tip-bellows-belt-silo'
          ? 0.88
          : step.id === 'bulk-4-close-return-rail-crane'
            ? 0.56
            : step.id === 'bulk-5-tanker-weigh-in-and-dock'
              ? 0.22
              : step.id === 'bulk-6-silo-load-and-weigh-out'
                ? 0.24
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
    this.jumpToStep(Math.min(BULK_PROCESS_STEPS.length - 1, this.stepIndex + 1), autoPlay)
  }

  destroy() {
    this.reset()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 240)
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
        // Re-enable automatic step camera framing on step transition so tracking never stays permanently suppressed
        this.runtime.resetUserCameraHold?.()
      }
    }
    this.emitState(t)
  }

  private apply(step: BulkStep, t: number) {
    this.runtime.setDeviceStatus('CR-BULK-01', step.craneStatus)
    this.runtime.setDeviceStatus('TIPPER-01', step.tipperStatus)
    this.runtime.setDeviceStatus('TIPPER-05', 'standby')
    this.runtime.setDeviceStatus('ELV-01', step.elevatorStatus)
    this.runtime.setDeviceStatus('LOADER-01', step.loaderStatus)

    let siloRatio = t
    if (step.id === 'bulk-3-tip-bellows-belt-silo') {
      siloRatio = clamp01((t - 0.78) / 0.22)
    } else if (step.id === 'bulk-6-silo-load-and-weigh-out') {
      siloRatio = clamp01((t - 0.12) / 0.24)
    }
    this.runtime.setStockyardQuantity('CARGO-02', Math.round(lerp(step.siloTons[0], step.siloTons[1], siloRatio)))
    this.runtime.setOperationState(
      'OP-240802',
      Math.round(lerp(step.opProgress[0], step.opProgress[1], t)),
      t >= 1 ? step.opStatus : 'running',
    )

    let crPose: CranePose = CR_HOME
    let tpPose: TipperPose = { ...TIPPER_WAIT, stageLabel: '东侧东西轨待命接箱' }
    let wagonShiftX = 0
    let containerMode: 'wagon' | 'crane' | 'tipper' = 'wagon'
    let tankerPose: VehiclePose = TANKER_STANDBY_STAGING
    let spoutNorm = 0
    let spoutPouring = false
    let loadedTons = 0

    // Keep paired North East-West tilter TIPPER-05 visibly parked on parallel North rail (Y=1529.55)
    this.runtime.setTipperPose('TIPPER-05', TIPPER_NORTH_STANDBY)

    if (step.id === 'bulk-1-train-and-crane-drop') {
      // 0.00..0.34: Bulk train decelerates gently along 货2 to stop centered at TIPPER_WAIT_X (3799.20)
      // 0.34..0.46: CR-BULK-01 pure -Z descend onto container on flatcar
      // 0.46..0.60: CR-BULK-01 pure +Z lift container to clear height 8.6m
      // 0.60..0.76: CR-BULK-01 pure +Y traverse North from 货2 (1514.79) to South East-West tilter rail (1523.85)
      // 0.76..0.90: CR-BULK-01 pure -Z lower container smoothly into TIPPER-01 cradle
      // 0.90..1.00: CR-BULK-01 pure +Z release & raise spreader to safe height 14.5m
      if (t < 0.34) {
        const p = easeOutCubic(t / 0.34)
        wagonShiftX = lerp(-72, 0, p)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TRACK_OFFSET,
          hoistHeight: 14.5,
          payloadTons: 0,
        }
        containerMode = 'wagon'
        tpPose = { ...TIPPER_WAIT, stageLabel: '南侧东西平行轨东端等待接箱' }
      } else if (t < 0.46) {
        const p = easeInOut((t - 0.34) / 0.12)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TRACK_OFFSET,
          hoistHeight: lerp(14.5, 4.16, p),
          payloadTons: 0,
        }
        containerMode = 'wagon'
        tpPose = { ...TIPPER_WAIT, stageLabel: '门吊垂直下钩对位（纯Z轴）' }
      } else if (t < 0.60) {
        const p = easeInOut((t - 0.46) / 0.14)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TRACK_OFFSET,
          hoistHeight: lerp(4.16, 8.6, p),
          payloadTons: 35.0,
        }
        containerMode = 'crane'
        tpPose = { ...TIPPER_WAIT, stageLabel: '门吊纯Z向垂直提箱（35t）' }
      } else if (t < 0.76) {
        const p = easeInOut((t - 0.60) / 0.16)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: lerp(CR_TRACK_OFFSET, CR_WAIT_OFFSET, p),
          hoistHeight: 8.6,
          payloadTons: 35.0,
        }
        containerMode = 'crane'
        tpPose = { ...TIPPER_WAIT, stageLabel: '门吊纯Y向横移至东西轨翻箱机上方' }
      } else if (t < 0.90) {
        const p = easeInOut((t - 0.76) / 0.14)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_WAIT_OFFSET,
          hoistHeight: lerp(8.6, 4.4, p),
          payloadTons: 35.0,
        }
        containerMode = 'crane'
        tpPose = { ...TIPPER_WAIT, clampLocked: false, stageLabel: '纯Z向缓速垂直落箱入托架' }
      } else {
        const p = easeInOut((t - 0.90) / 0.10)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_WAIT_OFFSET,
          hoistHeight: lerp(4.4, 14.5, p),
          payloadTons: 0,
        }
        containerMode = 'tipper'
        tpPose = { ...TIPPER_WAIT, clampLocked: false, stageLabel: '落箱就位 · 吊具纯Z向升离避让' }
      }

      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: t < 0.34 ? 'bulk-1a-train-arrive' : 'bulk-1b-crane-drop-ew-rail',
        cadX: t < 0.34 ? TIPPER_WAIT_X - 4 : TIPPER_WAIT_X - 3.0,
        cadY: t < 0.34 ? 1519.0 : 1524.5,
        cadZ: 4.5,
        radius: 24,
        headingDeg: t < 0.34 ? 22 : 26,
        pitchDeg: t < 0.34 ? -24 : -22,
        range: t < 0.34 ? 78 : 54,
        durationSec: 0.95,
      })

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: false,
        antiCollisionActive: t >= 0.46 && t <= 0.90,
        gapLabelPos: { cadX: TIPPER_WAIT_X, cadY: TIPPER_NORTH_Y },
        gapDistanceM: lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t),
        laserFromPos: { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, cadZ: crPose.hoistHeight },
        laserTargetPos: { cadX: TIPPER_WAIT_X, cadY: TIPPER_NORTH_Y, cadZ: 3.5 },
        fenceActive: false,
        underSiloFenceActive: false,
        wbInText: '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: null,
      })
    } else if (step.id === 'bulk-2-tipper-rail-and-clamp') {
      // 0.00..0.16: Crane pure -Y retreats back over 货2 rail corridor
      // 0.12..0.58: TIPPER-01 rides WEST along parallel EAST-WEST rails (X: 3799.20 -> 3787.89, Y = 1523.85!)
      // 0.58..0.80: Red side arms lock container + blue hydraulic claws press doors tight
      // 0.80..1.00: Pin extracted & right door pre-cracked 6° micro-gap
      const craneRetreat = easeInOut(t / 0.16)
      crPose = {
        gantryX: TIPPER_WAIT_X,
        gantryY: BULK_CR01.cadY,
        trolleyOffset: lerp(CR_WAIT_OFFSET, CR_TRACK_OFFSET, craneRetreat),
        hoistHeight: 14.5,
        payloadTons: 0,
      }
      const railTravel = easeInOut((t - 0.12) / 0.46)
      const microGap = t < 0.80 ? 0 : easeInOut((t - 0.80) / 0.20)
      const curX = lerp(TIPPER_WAIT_X, TIPPER_UNLOAD_X, railTravel)
      tpPose = {
        cadX: curX,
        cadY: TIPPER_SOUTH_Y,
        tiltDeg: 0,
        clampLocked: t >= 0.58,
        pinExtracted: t >= 0.80,
        leftDoorDeg: 0,
        rightDoorDeg: lerp(0, 6, microGap),
        bellowsDropM: 0,
        vibrating: false,
        stageLabel:
          t < 0.12
            ? '门吊退让 · 翻箱机准备沿东西轨西行入工位'
            : t < 0.58
              ? `沿平行东西轨道西行驶入卸料工位（X=${curX.toFixed(1)}m）`
              : t < 0.80
                ? '到位静电接地(<4Ω) · 抱臂锁紧 · 蓝爪顶紧端门'
                : `拔出门锁插销 · 右门预开 ${lerp(0, 6, microGap).toFixed(1)}° 排气微缝`,
      }
      containerMode = 'tipper'

      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: 'bulk-34-tipper-lock',
        cadX: (WORKROOM_X + TIPPER_UNLOAD_X) / 2 + 1.2,
        cadY: 1526.2,
        cadZ: 4.8,
        radius: 18,
        headingDeg: 24,
        pitchDeg: -22,
        range: 44,
        durationSec: 1.0,
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
    } else if (step.id === 'bulk-3-tip-bellows-belt-silo') {
      // Camera stays ROCK-SOLID framing TIPPER-01 + Overhead Suction Pipe + Glass West Workroom + Workroom Internal Belt Gallery!
      crPose = CR_HOME
      let tiltDeg = 0
      let clampLocked = true
      let leftDoorDeg = 0
      let rightDoorDeg = 6
      let bellowsDropM = 0
      let vibrating = false
      let stageLabel = ''

      if (t < 0.08) {
        tiltDeg = lerp(0, 3, easeInOut(t / 0.08))
        stageLabel = `双主液压缸建压抬头 ${tiltDeg.toFixed(1)}°`
      } else if (t < 0.38) {
        tiltDeg = lerp(3, 90, (t - 0.08) / 0.30)
        stageLabel = `90°垂直翻转 ${Math.round(tiltDeg)}° · 蓝爪全程顶门严禁早开`
      } else if (t < 0.48) {
        tiltDeg = 90
        stageLabel = '90°完全竖立 · 液压保压与抱臂锁死核验'
      } else if (t < 0.62) {
        const doorP = easeInOut((t - 0.48) / 0.14)
        tiltDeg = 90
        clampLocked = false
        leftDoorDeg = lerp(0, 92, doorP)
        rightDoorDeg = lerp(6, 92, doorP)
        stageLabel = `蓝爪解锁 · 顶部双扇箱门掀开 ${Math.round(rightDoorDeg)}°/92°`
      } else if (t < 0.72) {
        const chuteP = easeInOut((t - 0.62) / 0.10)
        tiltDeg = 90
        clampLocked = false
        leftDoorDeg = 92
        rightDoorDeg = 92
        bellowsDropM = lerp(0, 1.2, chuteP)
        stageLabel = `顶部波纹吸灰管下放 ${bellowsDropM.toFixed(2)}m 对接箱口`
      } else if (t < 0.82) {
        tiltDeg = 90
        clampLocked = false
        leftDoorDeg = 92
        rightDoorDeg = 92
        bellowsDropM = 1.2
        vibrating = true
        stageLabel = '① 顶部负压抽灰 → 经架空管道送入后方工作室旋风料斗'
      } else {
        tiltDeg = 90
        clampLocked = false
        leftDoorDeg = 92
        rightDoorDeg = 92
        bellowsDropM = 1.2
        vibrating = true
        stageLabel = '② 工作室内部皮带向北输送 → 40m斗提机 → 空气斜槽入 SILO-01'
      }

      tpPose = {
        cadX: TIPPER_UNLOAD_X,
        cadY: TIPPER_SOUTH_Y,
        tiltDeg,
        clampLocked,
        pinExtracted: true,
        leftDoorDeg,
        rightDoorDeg,
        bellowsDropM,
        vibrating,
        stageLabel,
      }
      containerMode = 'tipper'

      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: 'bulk-34-tipper-lock',
        cadX: (WORKROOM_X + TIPPER_UNLOAD_X) / 2 + 1.2,
        cadY: 1526.2,
        cadZ: 4.8,
        radius: 18,
        headingDeg: 24,
        pitchDeg: -22,
        range: 44,
        durationSec: 0.75,
      })

      this.runtime.setProcessHighlight({
        suctionPipeActive: t >= 0.72,
        beltConveyorActive: t >= 0.77,
        elevatorActive: t >= 0.82,
        airSlideActive: t >= 0.87,
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
    } else if (step.id === 'bulk-4-close-return-rail-crane') {
      // COMPLETE REVERSE RESET LOOP ALONG EAST-WEST RAILS:
      // 0.00..0.12: Retract top bellows suction chute (1.2 -> 0m)
      // 0.12..0.24: Close double doors (92° -> 0°) & re-lock blue clamp claws
      // 0.24..0.46: Tilt empty container smoothly back from 90° -> 0° horizontal
      // 0.46..0.64: Ride EAST along parallel East-West rails (X: 3787.89 -> 3799.20, Y = 1523.85)
      // 0.64..0.72: Release clamps + Crane pure +Y moves over South East-West tilter rail
      // 0.72..0.79: Crane pure -Z descends to lock empty container
      // 0.79..0.86: Crane pure +Z lifts empty container straight out (4.4 -> 8.6m)
      // 0.86..0.93: Crane pure -Y traverses South back over 货2 railway flatcar
      // 0.93..1.00: Crane pure -Z sets empty container gently onto flatcar (8.6 -> 4.16m)
      const chuteRetract = t < 0.12 ? lerp(1.2, 0, easeInOut(t / 0.12)) : 0
      const doorAngle = t < 0.12 ? 92 : t < 0.24 ? lerp(92, 0, easeInOut((t - 0.12) / 0.12)) : 0
      const tiltBack = t < 0.24 ? 90 : t < 0.46 ? lerp(90, 0, easeInOut((t - 0.24) / 0.22)) : 0
      const railBackX =
        t < 0.46
          ? TIPPER_UNLOAD_X
          : t < 0.64
            ? lerp(TIPPER_UNLOAD_X, TIPPER_WAIT_X, easeInOut((t - 0.46) / 0.18))
            : TIPPER_WAIT_X
      const clampsEngaged = t >= 0.22 && t < 0.64

      let stageLabel = ''
      if (t < 0.12) stageLabel = '抽灰完毕停震 · 顶部波纹吸灰管垂直收回'
      else if (t < 0.24) stageLabel = `双扇箱门闭合复核（${Math.round(doorAngle)}°→0°）· 蓝爪锁紧`
      else if (t < 0.46) stageLabel = `翻箱机托架 ${Math.round(tiltBack)}°→0° 平稳翻回水平位`
      else if (t < 0.64) stageLabel = `沿东西轨道东退回露天接箱位（X=${railBackX.toFixed(1)}m）`
      else if (t < 0.79) stageLabel = '抱臂松开退让 · 门吊纯Z向下钩夹持空箱'
      else if (t < 0.93) stageLabel = '门吊一维直行（+Z起升→-Y横移）吊离空箱'
      else stageLabel = '空箱平稳放回货2火车平车 · 单箱闭环完成'

      tpPose = {
        cadX: railBackX,
        cadY: TIPPER_SOUTH_Y,
        tiltDeg: tiltBack,
        clampLocked: clampsEngaged,
        pinExtracted: t < 0.22,
        leftDoorDeg: doorAngle,
        rightDoorDeg: doorAngle,
        bellowsDropM: chuteRetract,
        vibrating: false,
        stageLabel,
      }

      if (t < 0.64) {
        crPose = CR_HOME
        containerMode = 'tipper'
      } else if (t < 0.72) {
        const p = easeInOut((t - 0.64) / 0.08)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: lerp(CR_TRACK_OFFSET, CR_WAIT_OFFSET, p),
          hoistHeight: 14.5,
          payloadTons: 0,
        }
        containerMode = 'tipper'
      } else if (t < 0.79) {
        const p = easeInOut((t - 0.72) / 0.07)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_WAIT_OFFSET,
          hoistHeight: lerp(14.5, 4.4, p),
          payloadTons: 0,
        }
        containerMode = 'tipper'
      } else if (t < 0.86) {
        const p = easeInOut((t - 0.79) / 0.07)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_WAIT_OFFSET,
          hoistHeight: lerp(4.4, 8.6, p),
          payloadTons: 3.8,
        }
        containerMode = 'crane'
      } else if (t < 0.93) {
        const p = easeInOut((t - 0.86) / 0.07)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: lerp(CR_WAIT_OFFSET, CR_TRACK_OFFSET, p),
          hoistHeight: 8.6,
          payloadTons: 3.8,
        }
        containerMode = 'crane'
      } else {
        const p = easeInOut((t - 0.93) / 0.07)
        crPose = {
          gantryX: TIPPER_WAIT_X,
          gantryY: BULK_CR01.cadY,
          trolleyOffset: CR_TRACK_OFFSET,
          hoistHeight: lerp(8.6, 4.16, p),
          payloadTons: 3.8,
        }
        containerMode = p >= 0.96 ? 'wagon' : 'crane'
      }

      this.runtime.trackPoint?.(null)
      this.runtime.focusPoint?.({
        tag: t < 0.64 ? 'bulk-34-tipper-lock' : 'bulk-4b-crane-return',
        cadX: t < 0.64 ? (WORKROOM_X + TIPPER_UNLOAD_X) / 2 + 1.2 : TIPPER_WAIT_X - 2.0,
        cadY: t < 0.64 ? 1526.2 : 1522.0,
        cadZ: 4.8,
        radius: 18,
        headingDeg: 24,
        pitchDeg: -22,
        range: t < 0.64 ? 44 : 54,
        durationSec: 0.85,
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
    } else if (step.id === 'bulk-5-tanker-weigh-in-and-dock') {
      // CENTERED WIDE-ANGLE TRACKING OF TANKER ALONG EXACT CAD ROADS (ZERO OBSTRUCTION, ALWAYS CENTERED IN VIEWPORT!)
      // 0.00..0.14: Slow climb from East Gate along CAD +10.95° road onto WB-TRUCK-02
      // 0.14..0.30: COMPLETE STOP on WB-TRUCK-02 (14.2t tare weight)
      // 0.30..0.54: Cruise West along CAD Main Trunk Y=1616.5 (4558 -> 4212)
      // 0.54..0.82: Turn South at X=4212 (West of Tire Crane Zone) -> Silo Service Lane Y=1588 -> X=3736
      // 0.82..0.94: Turn South into Row-A under-silo lane (Y=1568.05) -> dock precisely under SILO-01
      // 0.94..1.00: Docked stop + electronic fence & electrostatic grounding locked
      crPose = CR_HOME
      tpPose = { ...TIPPER_WAIT, stageLabel: '东侧东西轨待命' }
      containerMode = 'wagon'

      if (t < 0.14) {
        tankerPose = followPolyline(
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
        tankerPose = { cadX: WB_IN.cadX, cadY: WB_IN.cadY, headingDeg: WB_IN_HEADING }
      } else if (t < 0.54) {
        tankerPose = followPolyline(
          [
            [WB_IN.cadX, WB_IN.cadY],
            WB_IN_WEST_PT,
            [4521.8, 1616.9],
            [4518.4, 1616.5],
            [4212.0, 1616.5],
          ],
          easeInOut((t - 0.30) / 0.24),
          180,
        )
      } else if (t < 0.82) {
        tankerPose = followPolyline(
          [
            [4212.0, 1616.5],
            [4212.0, 1588.0],
            [3742.0, 1588.0],
          ],
          easeInOut((t - 0.54) / 0.28),
          180,
        )
      } else if (t < 0.94) {
        tankerPose = followPolyline(
          [
            [3742.0, 1588.0],
            [3736.0, 1584.0],
            [3736.0, SILO01.cadY],
            [TANKER_DOCKED.cadX, SILO01.cadY],
          ],
          easeInOut((t - 0.82) / 0.12),
          0,
        )
      } else {
        tankerPose = TANKER_DOCKED
      }

      const tankerElev = computeTankerElevation(tankerPose.cadX, tankerPose.cadY)
      // Cinematic Macro Wide-Angle Locked Follow (235~250m, pitch -44°) -> Smooth Zoom-In to SILO-01 Under-Silo Close-Up (46m)!
      // Shares continuous tag 'bulk-tanker-cinematic-follow' with Step 6 for seamless wide -> close-up -> wide transitions!
      let trackHeading = -22
      let trackPitch = -44
      let trackRange = 240
      if (t < 0.54) {
        // Panoramic macro wide-angle locked on tanker: shows 40m East Gate canopy, WB-TRUCK-02 stop, Office/Parking, Tire-Crane yard, 40 Silos & Main Trunk!
        trackHeading = -22
        trackPitch = -44
        trackRange = t < 0.30 ? 235 : 250
      } else if (t < 0.82) {
        // High macro wide-angle locked from North-West (+152°) looking SSE across all 40 silos & unloading rooms (ZERO silo occlusion!)
        const swingP = easeInOut((t - 0.54) / 0.12)
        trackHeading = t < 0.66 ? lerp(-22, 152, swingP) : 152
        trackPitch = -45
        trackRange = 245
      } else {
        // Smooth zoom-IN as tanker docks under SILO-01 Row-A ready for loading close-up!
        const dockP = easeInOut((t - 0.82) / 0.16)
        trackHeading = lerp(152, 110, dockP)
        trackPitch = lerp(-45, -13, dockP)
        trackRange = lerp(245, 48, dockP)
      }

      this.runtime.trackPoint?.({
        tag: 'bulk-tanker-cinematic-follow',
        cadX: tankerPose.cadX,
        cadY: tankerPose.cadY,
        cadZ: tankerElev + 2.6,
        headingDeg: trackHeading,
        pitchDeg: trackPitch,
        range: trackRange,
        smoothing: 0.2,
      })

      const badgeText =
        t < 0.14
          ? '散装罐车 青B·88219 · 慢速驶上入口初磅'
          : t < 0.30
            ? '散装罐车 青B·88219 · WB-TRUCK-02 初磅停稳 (14.2t)'
            : t < 0.54
              ? '散装罐车 青B·88219 · 沿CAD主干道西行'
              : t < 0.82
                ? '散装罐车 青B·88219 · 沿筒仓北服务道驶向1#仓'
                : '散装罐车 青B·88219 · 驶入 SILO-01 仓底对位'

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
        underSiloFenceActive: t >= 0.86,
        underSiloFencePos: { cadX: SILO01.cadX, cadY: SILO01.cadY },
        underSiloFenceText: t >= 0.94 ? 'SILO-01 库底电子围栏闭锁 · 静电接地 < 4Ω ✓' : '罐车慢速驶入 SILO-01 库底精准对位…',
        wbInText:
          t <= 0.34
            ? t < 0.14
              ? 'WB-TRUCK-02 罐车缓速上磅中…'
              : t < 0.26
                ? 'WB-TRUCK-02 停稳初磅计量中（皮重采集中）…'
                : 'WB-TRUCK-02 初磅完成 · 空车皮重 14.2 t ✓'
            : '',
        wbOutText: '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: { cadX: tankerPose.cadX, cadY: tankerPose.cadY, cadZ: tankerElev, text: badgeText },
      })
    } else if (step.id === 'bulk-6-silo-load-and-weigh-out') {
      // 0.00..0.12: Spout descends onto tank dome (5.8 -> 4.35m)
      // 0.12..0.36: Quantitative ash pouring 0 -> 32.0 t
      // 0.36..0.46: Auto-cutoff + bottom valve close check + spout retracts (4.35 -> 5.8m)
      // 0.46..0.62: Drive East through Row-A under-silo tunnel to X=4058
      // 0.62..0.80: Follow CAD roads: (4058, 1568) -> (4058, 1588) -> (4212, 1588) -> (4212, 1616.5) -> (4324.1, 1616.5) -> (4324.1, 1560.8) -> (4524.2, 1560.8)
      // 0.80..0.86: Climb +59° CAD exit branch onto WB-TRUCK-01 (4549.21, 1595.41)
      // 0.86..0.95: COMPLETE STOP on WB-TRUCK-01 for 46.2t re-weighing
      // 0.95..1.00: Drive off WB-TRUCK-01 out East Main Gate (4612, 1630.4)
      crPose = CR_HOME
      tpPose = { ...TIPPER_WAIT, stageLabel: '东侧东西轨待命' }
      containerMode = 'wagon'

      if (t < 0.12) {
        spoutNorm = easeInOut(t / 0.12)
        spoutPouring = false
        loadedTons = 0
        tankerPose = TANKER_DOCKED
      } else if (t < 0.36) {
        spoutNorm = 1
        spoutPouring = true
        loadedTons = easeInOut((t - 0.12) / 0.24) * 32.0
        tankerPose = TANKER_DOCKED
      } else if (t < 0.46) {
        spoutNorm = 1 - easeInOut((t - 0.36) / 0.10)
        spoutPouring = false
        loadedTons = 32.0
        tankerPose = TANKER_DOCKED
      } else if (t < 0.62) {
        loadedTons = 32.0
        tankerPose = followPolyline(
          [
            [TANKER_DOCKED.cadX, SILO01.cadY],
            [4058.0, SILO01.cadY],
          ],
          easeInOut((t - 0.46) / 0.16),
          0,
        )
      } else if (t < 0.80) {
        loadedTons = 32.0
        tankerPose = followPolyline(
          [
            [4058.0, SILO01.cadY],
            [4058.0, 1588.0],
            [4212.0, 1588.0],
            [4212.0, 1616.5],
            [4324.1, 1616.5],
            [4324.1, 1560.8],
            [4524.2, 1560.8],
          ],
          easeInOut((t - 0.62) / 0.18),
          0,
        )
      } else if (t < 0.86) {
        loadedTons = 32.0
        tankerPose = followPolyline(
          [
            [4524.2, 1560.8],
            WB_OUT_SW_PT,
            [WB_OUT.cadX, WB_OUT.cadY],
          ],
          easeInOut((t - 0.80) / 0.06),
          WB_OUT_HEADING,
        )
      } else if (t < 0.95) {
        loadedTons = 32.0
        tankerPose = { cadX: WB_OUT.cadX, cadY: WB_OUT.cadY, headingDeg: WB_OUT_HEADING }
      } else {
        loadedTons = 32.0
        tankerPose = followPolyline(
          [
            [WB_OUT.cadX, WB_OUT.cadY],
            WB_OUT_NE_PT,
            [4562.5, 1624.7],
            [4587.6, 1629.6],
            [4612.0, 1630.4],
          ],
          easeInOut((t - 0.95) / 0.05),
          0,
        )
      }

      const tankerElev = computeTankerElevation(tankerPose.cadX, tankerPose.cadY)
      if (t < 0.46) {
        // Under-silo quantitative ash loading Close-Up (shares continuous tag 'bulk-tanker-cinematic-follow'!)
        this.runtime.trackPoint?.({
          tag: 'bulk-tanker-cinematic-follow',
          cadX: SILO01.cadX - 1.0,
          cadY: SILO01.cadY,
          cadZ: 3.8,
          headingDeg: 110,
          pitchDeg: -12,
          range: 46,
          smoothing: 0.2,
        })
      } else {
        // Inside under-silo Row-A tunnel stay low (-10°, 42m) for unobstructed corridor view; the moment tanker emerges East of silos (t>=0.60), smoothly pull back to panoramic Macro Wide-Angle Locked Exit (240m, -43°)!
        let outHeading = -132
        let outPitch = -43
        let outRange = 238
        if (t < 0.60) {
          outHeading = -148
          outPitch = -10
          outRange = 42
        } else if (t < 0.68) {
          const p = easeInOut((t - 0.60) / 0.08)
          outHeading = lerp(-148, -134, p)
          outPitch = lerp(-10, -44, p)
          outRange = lerp(42, 245, p)
        } else {
          outHeading = -132
          outPitch = -43
          outRange = 238
        }
        this.runtime.trackPoint?.({
          tag: 'bulk-tanker-cinematic-follow',
          cadX: tankerPose.cadX,
          cadY: tankerPose.cadY,
          cadZ: tankerElev + 2.6,
          headingDeg: outHeading,
          pitchDeg: outPitch,
          range: outRange,
          smoothing: 0.18,
        })
      }

      const exitBadge =
        t < 0.46
          ? null
          : {
              cadX: tankerPose.cadX,
              cadY: tankerPose.cadY,
              cadZ: tankerElev,
              text:
                t < 0.62
                  ? '散装罐车 青B·88219 · 满载 32t 驶出仓底通道'
                  : t < 0.80
                    ? '散装罐车 青B·88219 · 沿CAD主道/复磅支道东行'
                    : t < 0.95
                      ? '散装罐车 青B·88219 · WB-TRUCK-01 复磅停稳 (46.2t)'
                      : '散装罐车 青B·88219 · 复磅核验通过驶出东门 ✓',
            }

      this.runtime.setProcessHighlight({
        suctionPipeActive: false,
        beltConveyorActive: false,
        elevatorActive: false,
        airSlideActive: false,
        spoutActive: spoutPouring,
        spoutX: SILO01.cadX,
        spoutY: SILO01.cadY,
        antiCollisionActive: false,
        laserFromPos: null,
        laserTargetPos: null,
        fenceActive: false,
        underSiloFenceActive: t < 0.48,
        underSiloFencePos: { cadX: SILO01.cadX, cadY: SILO01.cadY },
        underSiloFenceText:
          t < 0.12
            ? '散装头缓速下落密封对接罐口…'
            : t < 0.36
              ? `定量散装中：${loadedTons.toFixed(1)} / 32.0 t（除尘负压回收）`
              : '32.0 t 满料停机 · 卸料底阀关闭复核 ✓ · 散装头回升',
        wbInText: '',
        wbOutText:
          t >= 0.80
            ? t < 0.86
              ? 'WB-TRUCK-01 重车缓速上磅中…'
              : t < 0.92
                ? 'WB-TRUCK-01 停稳复磅计量中（毛重核验）…'
                : 'WB-TRUCK-01 复磅完成 · 46.2 t（净装 32.0 t ✓）'
            : '',
        bayWarningPos: null,
        bayWarningText: '',
        activeVehicleBadge: exitBadge,
      })
    }

    this.runtime.setCranePose('CR-BULK-01', crPose)
    this.runtime.setTipperPose('TIPPER-01', tpPose)
    this.runtime.setLoaderPose('LOADER-01', {
      cadX: SILO01.cadX,
      cadY: SILO01.cadY,
      spoutHeight: step.id === 'bulk-6-silo-load-and-weigh-out' ? lerp(5.8, 4.35, spoutNorm) : 5.8,
      active: spoutPouring,
      loadedTons,
      targetTons: 32.0,
    })

    // Sync 3-car bulk train along 货2 centered at TIPPER_WAIT_X (3799.20)
    this.runtime.setVehiclePose('WAGON-BULK-01', { cadX: WAGON_STOP_POS.cadX - 17 + wagonShiftX, cadY: TRACK_H2_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('WAGON-BULK-02', { cadX: WAGON_STOP_POS.cadX + wagonShiftX, cadY: TRACK_H2_Y, headingDeg: 0 })
    this.runtime.setVehiclePose('WAGON-BULK-03', { cadX: WAGON_STOP_POS.cadX + 17 + wagonShiftX, cadY: TRACK_H2_Y, headingDeg: 0 })

    // Sync tanker elevation smoothly onto CAD roads, angled weighbridge decks & silo lanes
    const tankerZ = computeTankerElevation(tankerPose.cadX, tankerPose.cadY)
    this.runtime.setVehiclePose('TRUCK-TANK-01', tankerPose, tankerZ)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', FLATBED_STANDBY_STAGING, 0.06)

    // Sync 20ft China Railway bulk container across flatcar / 1D crane / East-West mobile tilter (0°..90°..0°)
    if (containerMode === 'crane') {
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: crPose.gantryX, cadY: crPose.gantryY + crPose.trolleyOffset, headingDeg: 0 },
        crPose.hoistHeight - 2.6,
        0,
        0,
      )
    } else if (containerMode === 'tipper') {
      const rad = (tpPose.tiltDeg * Math.PI) / 180
      const vibJitterX = tpPose.vibrating ? Math.sin(performance.now() * 0.055) * 0.04 : 0
      const vibJitterZ = tpPose.vibrating ? Math.cos(performance.now() * 0.07) * 0.03 : 0
      const contCadX = tpPose.cadX - 0.43 * (1 - Math.cos(rad)) + 1.4 * Math.sin(rad) + vibJitterX
      const contCadY = tpPose.cadY
      const contZ = 3.2 + 0.43 * Math.sin(rad) - 1.4 * Math.cos(rad) + vibJitterZ
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: contCadX, cadY: contCadY, headingDeg: 0 },
        contZ,
        0,
        tpPose.tiltDeg,
      )
    } else {
      this.runtime.setVehiclePose(
        'CONT-BULK-01',
        { cadX: WAGON_STOP_POS.cadX + wagonShiftX, cadY: TRACK_H2_Y, headingDeg: 0 },
        1.56,
        0,
        0,
      )
    }

    this.runtime.requestRender()
  }

  private emitState(localProgress: number) {
    const step = BULK_PROCESS_STEPS[this.stepIndex]
    const total = BULK_PROCESS_STEPS.length
    const progress = this.completed
      ? 100
      : Math.round(((this.stepIndex + localProgress) / total) * 100)
    const payloadTons =
      step.id === 'bulk-1-train-and-crane-drop'
        ? localProgress >= 0.46 && localProgress <= 0.90
          ? 35.0
          : 0
        : step.id === 'bulk-2-tipper-rail-and-clamp' || step.id === 'bulk-3-tip-bellows-belt-silo'
          ? 35.0
          : step.id === 'bulk-4-close-return-rail-crane'
            ? 3.8
            : step.id === 'bulk-6-silo-load-and-weigh-out'
              ? Number((clamp01((localProgress - 0.12) / 0.24) * 32.0).toFixed(1))
              : 0

    this.onChange({
      processKind: 'bulk',
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
