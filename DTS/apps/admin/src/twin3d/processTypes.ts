import type { AlertItem } from '@/stores/alert'
import type { EquipmentDef } from './constants'
import type { CameraFocusTarget, CameraPresetId, CameraTrackTarget } from './cameraPresets'
import type { OperationStatus } from './phase6'
import type { VehiclePose } from './vehicleAnimator'
import type { BridgeCranePose, CranePose, SiloLoaderPose, TipperPose } from './models/buildEquipment'

export type ProcessKind = 'steel' | 'bulk'

export interface InterlockChecklistItem {
  label: string
  passed: boolean
}

export interface ProcessHighlightState {
  suctionPipeActive: boolean
  beltConveyorActive: boolean
  elevatorActive: boolean
  airSlideActive: boolean
  spoutActive: boolean
  spoutX: number
  spoutY: number
  antiCollisionActive: boolean
  gapLabelPos: { cadX: number; cadY: number }
  gapDistanceM: number
  laserFromPos: { cadX: number; cadY: number; cadZ?: number } | null
  laserTargetPos: { cadX: number; cadY: number; cadZ?: number } | null
  fenceActive: boolean
  fencePos: { cadX: number; cadY: number }
  fenceLabel: string
  underSiloFenceActive: boolean
  underSiloFencePos: { cadX: number; cadY: number }
  underSiloFenceText: string
  wbInText: string
  wbOutText: string
  bayWarningPos: { cadX: number; cadY: number } | null
  bayWarningText: string
  activeVehicleBadge: { cadX: number; cadY: number; cadZ?: number; text: string } | null
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
  focusPoint?: (target: CameraFocusTarget) => void
  trackPoint?: (target: CameraTrackTarget | null) => void
  resetUserCameraHold?: () => void
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
  setProcessHighlight: (patch: Partial<ProcessHighlightState>) => void
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
  '1. 露天板车专列进线/门吊直抓对位',
  '2. 门吊一维低空横移/交接区落位停顿',
  '3. 20t磁吸桥吊接驳/精准落入专用空位',
  '4. 聚焦跟拍过初磅/沿CAD道路直入东门',
  '5. 桥吊精细装车/西出绕一圈复磅离场',
] as const

export const BULK_FIVE_PHASES = [
  '1. 列车进线/落箱至平行东西轨翻箱机',
  '2. 翻箱机沿东西轨西行入工位/顶门拔销',
  '3. 90°翻转顶部抽灰→工作室→皮带入仓',
  '4. 关门翻回0°/沿东西轨东退/吊回平车',
  '5. 聚焦广角跟拍罐车过初磅/驶入筒仓底',
  '6. 仓底定量接灰(32t)/聚焦跟拍复磅出厂',
] as const

export const BULK_SIX_PHASES = BULK_FIVE_PHASES
