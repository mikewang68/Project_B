import type { AlertItem } from '@/stores/alert'
import type { DeviceState } from '@/stores/device'
import type { CameraPresetId } from './cameraPresets'
import type { CargoItem, OperationStatus, PersonnelItem } from './phase6'
import type { VehiclePose } from './vehicleAnimator'

type DeviceStatus = DeviceState['status']

export interface DemoCranePose {
  /** Crane travel position along the steel railway line (CAD X). */
  gantryX: number
  /** Shared centreline of the two steel tracks (CAD Y). */
  gantryY: number
  /** Trolley movement across the portal, along CAD Y. */
  trolleyOffset: number
  hoistHeight: number
}

export interface DemoStep {
  id: string
  title: string
  narration: string
  durationMs: number
  camera: CameraPresetId
  deviceStatus: DeviceStatus
  operation: { from: number; to: number; status: OperationStatus }
  stockyardTons: { from: number; to: number }
  vehicle: { from: VehiclePose; to: VehiclePose }
  crane: { from: DemoCranePose; to: DemoCranePose }
  alert?: Omit<AlertItem, 'time' | 'handled'>
}

export interface DemoViewState {
  running: boolean
  completed: boolean
  stepIndex: number
  stepCount: number
  title: string
  narration: string
  progress: number
}

export interface SteelUnloadDemoRuntime {
  setCamera(id: CameraPresetId): void
  setDeviceStatus(id: string, status: DeviceStatus): void
  setStockyardQuantity(quantity: number): void
  setUnloadOperation(progress: number, status: OperationStatus): void
  setVehiclePose(id: string, pose: VehiclePose): void
  setCranePose(pose: DemoCranePose): void
  addAlert(alert: Omit<AlertItem, 'time' | 'handled'>): void
  clearAlerts(): void
  requestRender(): void
}

const INITIAL_CRANE: DemoCranePose = { gantryX: 3727.235, gantryY: 1673.434, trolleyOffset: 0, hoistHeight: 16 }
const INITIAL_WAGON: VehiclePose = { cadX: 3656, cadY: 1664.806, headingDeg: 0 }
const WAGON_UNLOAD: VehiclePose = { cadX: 3727.235, cadY: 1664.806, headingDeg: 0 }
const REBAR_BAY_07_TROLLEY_OFFSET = 36.585

/** A deterministic acceptance story; positions, quantities, and durations remain simulated. */
export const STEEL_UNLOAD_STEPS: DemoStep[] = [
  {
    id: 'wagon-arrival', title: '1. 铁路平车进入钢材两轨',
    narration: '钢材铁路平车沿钢材两轨驶入 CR-02 作业区；CR-01 与 CR-03 保持待机。',
    durationMs: 4200, camera: 'route', deviceStatus: 'standby',
    operation: { from: 0, to: 12, status: 'running' }, stockyardTons: { from: 12500, to: 12500 },
    vehicle: { from: INITIAL_WAGON, to: WAGON_UNLOAD },
    crane: { from: INITIAL_CRANE, to: INITIAL_CRANE },
  },
  {
    id: 'crane-positioning', title: '2. CR-02 定位与落钩',
    narration: 'CR-02 小车沿龙门架横向移动至铁路平车上方，吊具下降至待吊高度。',
    durationMs: 4600, camera: 'gantry', deviceStatus: 'ok',
    operation: { from: 12, to: 35, status: 'running' }, stockyardTons: { from: 12500, to: 12500 },
    vehicle: { from: WAGON_UNLOAD, to: WAGON_UNLOAD },
    crane: { from: INITIAL_CRANE, to: { gantryX: 3727.235, gantryY: 1673.434, trolleyOffset: -8.6, hoistHeight: 5.2 } },
  },
  {
    id: 'lifting', title: '3. 起吊并横移至仓位',
    narration: '吊具起升后，小车向钢筋露天仓位侧横移；钢材从铁路平车转运至 REBAR-BAY-07。',
    durationMs: 5600, camera: 'gantry', deviceStatus: 'ok',
    operation: { from: 35, to: 72, status: 'running' }, stockyardTons: { from: 12500, to: 12700 },
    vehicle: { from: WAGON_UNLOAD, to: WAGON_UNLOAD },
    crane: {
      from: { gantryX: 3727.235, gantryY: 1673.434, trolleyOffset: -8.6, hoistHeight: 5.2 },
      to: { gantryX: 3741.1065, gantryY: 1673.434, trolleyOffset: REBAR_BAY_07_TROLLEY_OFFSET, hoistHeight: 16 },
    },
  },
  {
    id: 'stocking', title: '4. 钢筋仓位入库',
    narration: 'CR-02 在 REBAR-BAY-07 上方落料，钢筋仓位库存与卸车任务进度同步更新。',
    durationMs: 5200, camera: 'steel', deviceStatus: 'ok',
    operation: { from: 72, to: 100, status: 'done' }, stockyardTons: { from: 12700, to: 13200 },
    vehicle: { from: WAGON_UNLOAD, to: { cadX: 3690, cadY: 1664.806, headingDeg: 0 } },
    crane: {
      from: { gantryX: 3741.1065, gantryY: 1673.434, trolleyOffset: REBAR_BAY_07_TROLLEY_OFFSET, hoistHeight: 16 },
      to: { gantryX: 3741.1065, gantryY: 1673.434, trolleyOffset: REBAR_BAY_07_TROLLEY_OFFSET, hoistHeight: 4.8 },
    },
  },
  {
    id: 'verification', title: '5. 仓位预警核验',
    narration: '作业完成后，系统提示 REBAR-BAY-07 接近演示预警关注线，供调度人员复核。',
    durationMs: 4800, camera: 'steel', deviceStatus: 'standby',
    operation: { from: 100, to: 100, status: 'done' }, stockyardTons: { from: 13200, to: 13200 },
    vehicle: { from: { cadX: 3690, cadY: 1664.806, headingDeg: 0 }, to: { cadX: 3690, cadY: 1664.806, headingDeg: 0 } },
    crane: { from: { gantryX: 3741.1065, gantryY: 1673.434, trolleyOffset: REBAR_BAY_07_TROLLEY_OFFSET, hoistHeight: 4.8 }, to: INITIAL_CRANE },
    alert: {
      id: 'DEMO-REBAR-BAY-07', level: 'warning', targetId: 'REBAR-BAY-07', targetName: '钢筋仓位 07',
      message: '钢筋仓位 07 达到演示预警关注线（82.5%）。',
    },
  },
]

function lerp(a: number, b: number, t: number) {
  return a + (b - a) * t
}

function lerpPose(from: VehiclePose, to: VehiclePose, t: number): VehiclePose {
  return { cadX: lerp(from.cadX, to.cadX, t), cadY: lerp(from.cadY, to.cadY, t), headingDeg: lerp(from.headingDeg, to.headingDeg, t) }
}

function lerpCrane(from: DemoCranePose, to: DemoCranePose, t: number): DemoCranePose {
  return {
    gantryX: lerp(from.gantryX, to.gantryX, t), gantryY: lerp(from.gantryY, to.gantryY, t),
    trolleyOffset: lerp(from.trolleyOffset, to.trolleyOffset, t), hoistHeight: lerp(from.hoistHeight, to.hoistHeight, t),
  }
}

export class SteelUnloadDemoController {
  private readonly runtime: SteelUnloadDemoRuntime
  private readonly onChange: (state: DemoViewState) => void
  private timer: ReturnType<typeof setInterval> | null = null
  private stepIndex = 0
  private elapsedMs = 0
  private lastTick = 0
  private running = false
  private completed = false

  constructor(runtime: SteelUnloadDemoRuntime, onChange: (state: DemoViewState) => void) {
    this.runtime = runtime
    this.onChange = onChange
  }

  start() {
    if (this.completed) this.reset()
    if (this.running) return
    this.running = true
    this.lastTick = performance.now()
    this.runtime.setCamera(STEEL_UNLOAD_STEPS[this.stepIndex].camera)
    this.emitState()
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
    this.runtime.setStockyardQuantity(12500)
    this.runtime.setUnloadOperation(0, 'queued')
    this.runtime.setVehiclePose('WAGON-FLAT-01', INITIAL_WAGON)
    this.runtime.setCranePose(INITIAL_CRANE)
    this.runtime.setCamera('overview')
    this.runtime.requestRender()
    this.emitState()
  }

  destroy() {
    this.pause()
  }

  private tick() {
    const now = performance.now()
    this.elapsedMs += Math.min(now - this.lastTick, 160)
    this.lastTick = now
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    const progress = Math.min(this.elapsedMs / step.durationMs, 1)
    this.apply(step, progress)
    if (progress >= 1) {
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
    this.emitState()
  }

  private apply(step: DemoStep, progress: number) {
    this.runtime.setDeviceStatus('CR-02', step.deviceStatus)
    this.runtime.setStockyardQuantity(Math.round(lerp(step.stockyardTons.from, step.stockyardTons.to, progress)))
    this.runtime.setUnloadOperation(Math.round(lerp(step.operation.from, step.operation.to, progress)), step.operation.status)
    this.runtime.setVehiclePose('WAGON-FLAT-01', lerpPose(step.vehicle.from, step.vehicle.to, progress))
    this.runtime.setCranePose(lerpCrane(step.crane.from, step.crane.to, progress))
    this.runtime.requestRender()
  }

  private emitState() {
    const step = STEEL_UNLOAD_STEPS[this.stepIndex]
    this.onChange({
      running: this.running,
      completed: this.completed,
      stepIndex: this.stepIndex,
      stepCount: STEEL_UNLOAD_STEPS.length,
      title: this.completed ? '演示完成：钢筋入库与预警已核验' : step.title,
      narration: this.completed ? '可点击重置，重新开始同一条钢材卸车验收流程。' : step.narration,
      progress: this.completed ? 100 : Math.round(((this.stepIndex + this.elapsedMs / step.durationMs) / STEEL_UNLOAD_STEPS.length) * 100),
    })
  }
}

export function resetDemoData(cargo: CargoItem[], personnel: PersonnelItem[]) {
  const nextCargo = structuredClone(cargo)
  const stockyard = nextCargo.find((item) => item.id === 'CARGO-01')
  if (stockyard) {
    stockyard.quantity = 12500
    stockyard.warning = false
  }
  return { cargo: nextCargo, personnel: structuredClone(personnel) }
}
