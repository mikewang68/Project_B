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
const TRUCK_GATE: VehiclePose = { cadX: WB_IN.cadX - 180, cadY: TRUCK_LOAD_Y, headingDeg: 180 }
const TRUCK_LOAD_POS: VehiclePose = { cadX: BAY07.cadX, cadY: TRUCK_LOAD_Y, headingDeg: 180 }
const TRUCK_CORRIDOR_EAST: VehiclePose = { cadX: 4385.0, cadY: TRUCK_LOAD_Y, headingDeg: 0 }
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
    title: '4. 公路货车过磅与交接等待→防碰撞拣货装车',
    narration: '公路平板车经 100t 汽车衡初磅驶入装车工位完成交接确认，开启电子围栏防碰撞监测，门吊协同拣选装车（实时测距预警阈值 15m）。',
    durationMs: 4600,
    camera: 'gantry',
    craneStatus: 'ok',
    bridgeStatus: 'ok',
    opProgress: [74, 96],
    opStatus: 'running',
    bayTons: [14950, 14950],
    crane: {
      from: CR_HOME,
      to: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: TRUCK_LOAD_Y - CR02.cadY, hoistHeight: 5.0, payloadTons: 22.0 },
    },
    bridge: {
      from: { cadX: BAY07.cadX, bridgeY: BC_STACK_Y, trolleyOffsetX: 2.0, hoistHeight: 8.5, payloadTons: 0 },
      to: BC_HOME,
    },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_GATE, to: TRUCK_LOAD_POS },
    cargoMode: 'crane',
    safetyDistanceM: [18.5, 11.4],
    antiCollisionActive: true,
    interlocks: [
      { label: 'WB-TRUCK-02 皮重初检：16.4 t', passed: true },
      { label: '装车交接等待核验 + 电子围栏司机离车确认', passed: true },
      { label: '装车防碰撞距离 11.4m（<15m降速保护）', passed: true },
    ],
  },
  {
    id: 'steel-weigh-verify',
    phaseIndex: 4,
    title: '5. 满车沿北侧通道复磅出厂与防撞/库位联锁告警',
    narration: '平板挂车载钢材沿北侧通道绕开股道驶向 WB-TRUCK-01 完成 100t 复磅；跨间交接防撞测距 11.4m 暨 07 跨高库存触发 S4/S1 联锁告警。',
    durationMs: 4400,
    camera: 'steel',
    craneStatus: 'standby',
    bridgeStatus: 'standby',
    opProgress: [96, 100],
    opStatus: 'done',
    bayTons: [14950, 14950],
    crane: {
      from: { gantryX: BAY07.cadX, gantryY: CR02.cadY, trolleyOffset: TRUCK_LOAD_Y - CR02.cadY, hoistHeight: 16.0, payloadTons: 0 },
      to: CR_HOME,
    },
    bridge: { from: BC_HOME, to: BC_HOME },
    wagon: { from: WAGON_SPOTTED, to: WAGON_SPOTTED },
    truck: { from: TRUCK_LOAD_POS, to: TRUCK_EXIT_POS },
    cargoMode: 'truck',
    safetyDistanceM: [12.4, 11.4],
    antiCollisionActive: true,
    interlocks: [
      { label: 'WB-TRUCK-01 满车复磅：38.4 t（净重 22.0 t）', passed: true },
      { label: '跨间门吊-桥吊交接安全间距 11.4m（触发 <15m 联锁）', passed: true },
      { label: '07 跨高库存预警（82.5%）同步 S1/S2/S4', passed: true },
    ],
    alert: {
      id: 'DEMO-REBAR-BAY-07',
      level: 'critical',
      targetId: 'CR-02',
      targetName: '2# 40t 钢材门吊 · 07跨交接区',
      message: '跨间联合吊装交接等待：CR-02 与室内桥吊防撞间距 11.4m（低于 15m 阈值）且 07 跨库存达 82.5%，请核查厂家 PLC 状态及关联视频后转 S1 装卸工艺管理系统处置。',
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

  constructor(runtime: UnifiedProcessRuntime, onChange: (state: ProcessDemoState) => void, subMode: SteelSubMode = 'rebar_magnet') {
    this.runtime = runtime
    this.onChange = onChange
    this.subMode = subMode
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
    this.running = true
    this.lastTick = performance.now()
    this.runtime.setCamera(STEEL_UNLOAD_STEPS[this.stepIndex].camera)
    this.emitState(0)
    this.timer = setInterval(() => this.tick(), 80)
  }

  pause() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
    this.running = false
    this.emitState()
  }

  reset() {
    this.pause()
    this.stepIndex = 0
    this.elapsedMs = 0
    this.completed = false
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
    this.runtime.setVehiclePose('TRUCK-FLAT-01', TRUCK_GATE)
    this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: WAGON_START.cadX, cadY: WAGON_START.cadY, headingDeg: 0 }, 2.1)
    this.runtime.setCranePose('CR-02', { ...CR_HOME, gantryX: bayX })
    this.runtime.setBridgeCranePose('BC-03', BC03_HOME)
    this.runtime.setBridgeCranePose('BC-07', BC_HOME)
    this.runtime.setProcessHighlight({ antiCollisionActive: false, elevatorActive: false, airSlideActive: false, spoutActive: false })
    this.runtime.requestRender()
    this.emitState(0)
  }

  destroy() {
    this.reset()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 160)
    this.lastTick = now
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    const t = Math.min(this.elapsedMs / step.durationMs, 1)
    this.apply(step, t)

    if (t >= 1) {
      if (step.alert) this.runtime.addAlert(step.alert)
      if (this.stepIndex === STEEL_UNLOAD_STEPS.length - 1) {
        this.pause()
        this.completed = true
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

    this.runtime.setDeviceStatus('CR-02', step.craneStatus)
    this.runtime.setDeviceStatus(activeBcId, step.bridgeStatus)
    this.runtime.setDeviceStatus(inactiveBcId, 'standby')
    this.runtime.setStockyardQuantity('CARGO-01', Math.round(lerp(step.bayTons[0], step.bayTons[1], t)))
    this.runtime.setOperationState('OP-240801', Math.round(lerp(step.opProgress[0], step.opProgress[1], t)), step.opStatus)

    const crPose: CranePose = {
      gantryX: lerp(step.crane.from.gantryX, step.crane.to.gantryX, t) + bayDeltaX,
      gantryY: lerp(step.crane.from.gantryY, step.crane.to.gantryY, t),
      trolleyOffset: lerp(step.crane.from.trolleyOffset, step.crane.to.trolleyOffset, t),
      hoistHeight: lerp(step.crane.from.hoistHeight, step.crane.to.hoistHeight, t),
      payloadTons: this.subMode === 'coil_chook' && (step.crane.to.payloadTons ?? 0) > 0 ? 31.2 : step.crane.to.payloadTons,
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

    // Route flatbed truck along North loading road (cadY = 1655.0) before turning to exit weighbridge, avoiding rail tracks & gantry legs
    let truckPose: VehiclePose
    if (step.id === 'steel-weigh-verify') {
      const startLoadPos: VehiclePose = { ...TRUCK_LOAD_POS, cadX: activeBay.cadX, headingDeg: 0 }
      if (t < 0.72) {
        truckPose = lerpVehicle(startLoadPos, TRUCK_CORRIDOR_EAST, t / 0.72)
      } else {
        truckPose = lerpVehicle(TRUCK_CORRIDOR_EAST, TRUCK_EXIT_POS, (t - 0.72) / 0.28)
      }
    } else {
      const truckTo: VehiclePose =
        step.truck.to.cadX === TRUCK_LOAD_POS.cadX ? { ...step.truck.to, cadX: activeBay.cadX } : step.truck.to
      truckPose = lerpVehicle(step.truck.from, truckTo, t)
    }
    this.runtime.setVehiclePose('WAGON-FLAT-01', wagonPose)
    this.runtime.setVehiclePose('TRUCK-FLAT-01', truckPose)

    // Move detached active steel bundle/coil cleanly across handoff chain (no teleportation back to bay on step 5)
    if (step.cargoMode === 'wagon') {
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
      this.runtime.setVehiclePose(
        'CARGO-STEEL-ACTIVE',
        { cadX: truckPose.cadX, cadY: truckPose.cadY, headingDeg: truckPose.headingDeg },
        1.85,
      )
    } else {
      this.runtime.setVehiclePose('CARGO-STEEL-ACTIVE', { cadX: activeBay.cadX, cadY: BC_STACK_Y, headingDeg: 0 }, 1.6)
    }

    const gapDist = lerp(step.safetyDistanceM[0], step.safetyDistanceM[1], t)
    this.runtime.setProcessHighlight({
      antiCollisionActive: step.antiCollisionActive,
      gapLabelPos: { cadX: activeBay.cadX + 12, cadY: APRON_TARGET_Y },
      gapDistanceM: gapDist,
    })
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
