import type { AlertItem } from '@/stores/alert'
import type { EquipmentDef } from './constants'
import type { CameraPresetId } from './cameraPresets'
import type { OperationStatus } from './phase6'
import type { VehiclePose } from './vehicleAnimator'
import type { BridgeCranePose, CranePose, SiloLoaderPose, TipperPose } from './models/buildEquipment'

export type ProcessKind = 'steel' | 'bulk'

export interface InterlockChecklistItem {
  label: string
  passed: boolean
}

export interface ProcessDemoState {
  processKind: ProcessKind
  running: boolean
  completed: boolean
  stepIndex: number
  /** Slide 40 顶部 5 步工序栏当前高亮序号 (0..4) */
  phaseIndex: number
  stepCount: number
  title: string
  narration: string
  progress: number
  /** 当前核心设备实时负载或定量装车重量（吨） */
  payloadTons: number
  /** 当前 SCS 防碰撞实时测距（米） */
  safetyDistanceM: number
  /** 当前安全联锁回执清单（门吊/翻箱/库底接地/电子围栏） */
  interlockChecks: InterlockChecklistItem[]
}

export interface UnifiedProcessRuntime {
  setCamera: (id: CameraPresetId) => void
  setDeviceStatus: (id: string, status: EquipmentDef['status']) => void
  setEquipmentLabel: (id: string, text: string) => void
  setStockyardQuantity: (cargoId: string, quantity: number) => void
  setOperationState: (taskId: string, progress: number, status: OperationStatus) => void
  setVehiclePose: (id: string, pose: VehiclePose, height?: number, pitchDeg?: number, rollDeg?: number) => void
  setActorVisible: (id: string, visible: boolean) => void
  setCranePose: (id: string, pose: CranePose) => void
  setBridgeCranePose: (id: string, pose: BridgeCranePose) => void
  setTipperPose: (id: string, pose: TipperPose) => void
  setLoaderPose: (id: string, pose: SiloLoaderPose) => void
  setProcessHighlight: (patch: {
    elevatorActive?: boolean
    airSlideActive?: boolean
    spoutActive?: boolean
    spoutX?: number
    spoutY?: number
    antiCollisionActive?: boolean
    gapLabelPos?: { cadX: number; cadY: number }
    gapDistanceM?: number
  }) => void
  addAlert: (alert: Omit<AlertItem, 'time' | 'handled'>) => void
  clearAlerts: () => void
  requestRender: () => void
}

export interface ProcessControllerHandle {
  readonly kind: ProcessKind
  start: () => void
  pause: () => void
  reset: () => void
  jumpToStep: (stepIndex: number, autoPlay?: boolean) => void
  prevStep: (autoPlay?: boolean) => void
  nextStep: (autoPlay?: boolean) => void
  setSingleStep: (enabled: boolean) => void
  destroy: () => void
}

export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}

export function lerpVehicle(from: VehiclePose, to: VehiclePose, t: number): VehiclePose {
  const dx = to.cadX - from.cadX
  const dy = to.cadY - from.cadY
  const dist = Math.hypot(dx, dy)
  const travelHeadingDeg = dist > 0.2 ? (Math.atan2(-dy, dx) * 180) / Math.PI : lerp(from.headingDeg, to.headingDeg, t)
  return {
    cadX: lerp(from.cadX, to.cadX, t),
    cadY: lerp(from.cadY, to.cadY, t),
    headingDeg: travelHeadingDeg,
  }
}

export const STEEL_FIVE_PHASES = [
  '1. 列车到达/RFID核验',
  '2. 40t门吊卸至转接区',
  '3. 20t桥吊接力入库',
  '4. 汽车过磅/防碰撞装车',
  '5. 复磅出厂与库位预警',
] as const

export const BULK_FIVE_PHASES = [
  '1. 重车入线/门吊取箱',
  '2. 翻箱机移位/安全联锁',
  '3. 135°倾卸/提升入筒仓',
  '4. 罐车初磅/库底围栏对位',
  '5. 定量散装/满车复磅',
] as const
