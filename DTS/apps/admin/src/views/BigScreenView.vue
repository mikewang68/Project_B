<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { filterByCategory } from '@/components/cesium/layerManager'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import {
  DEFAULT_DATA_MODE,
  DEFAULT_LIVE_TASKS,
  DtsDataService,
  REGION_OPTIONS,
  REQUIREMENT_FUNCTION_MATRIX,
  VENDOR_CONTROL_SYSTEMS,
  buildEhmsAssetUrl,
  buildPsmsTaskUrl,
  buildScsAlertUrl,
  buildWmsLocationUrl,
  getCrossSystemLinks,
  getPortalUrl,
  getStorageSummaryForTarget,
  type DataSourceMode,
  type DtsFeedStatus,
  type LiveTaskNodeItem,
  type ProcessTrackMode,
  type RegionCode,
  type RequirementFunctionItem,
} from '@/services/dtsDataService'
import { useAlertStore, type AlertItem } from '@/stores/alert'
import { buildDefaultDeviceSnapshot, useDeviceStore } from '@/stores/device'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { BulkProcessDemoController } from '@/twin3d/bulkProcessDemo'
import {
  flyToCameraPreset,
  focusCadPoint,
  releaseCameraTrack,
  trackCadPoint,
  type CameraFocusTarget,
  type CameraPresetId,
  type CameraTrackTarget,
} from '@/twin3d/cameraPresets'
import { cadGeoJsonToWgs84 } from '@/twin3d/geo'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'
import { MOCK_CARGO, MOCK_OPERATIONS, MOCK_PERSONNEL, Phase6LayerController } from '@/twin3d/phase6'
import {
  BULK_FIVE_PHASES,
  STEEL_FIVE_PHASES,
  type ProcessControllerHandle,
  type ProcessDemoState,
  type ProcessKind,
  type UnifiedProcessRuntime,
} from '@/twin3d/processTypes'
import { SITE_LAYOUT_V4 } from '@/twin3d/siteLayoutV4'
import { SteelUnloadDemoController, type SteelSubMode } from '@/twin3d/steelUnloadDemo'

type ThemeId = 'overview' | 'bulk' | 'steel' | 'safety' | 'ehms' | 'weigh'

interface ThemeMeta {
  id: ThemeId
  label: string
  title: string
  note: string
  camera: CameraPresetId
  chartTitle: string
}

const THEMES: ThemeMeta[] = [
  { id: 'overview', label: '全站总览', title: '中沙坝转运站数字孪生综合指挥大屏', note: '给大连大.dwg V4 实测基准 · 40座3000T筒仓 + 13跨钢筋库双线协同', camera: 'overview', chartTitle: '全站装卸吞吐小时趋势（吨/小时）' },
  { id: 'bulk', label: '散料入仓与定量装车', title: '水泥/粉煤灰翻箱入仓与库底定量装车态势', note: '货1/货2 → 集装箱门吊 → 平行东西轨移动翻箱机(90°) → 顶部抽灰→工作室皮带 → 40m提升机 → 40筒仓', camera: 'tipper', chartTitle: '散料入仓与定量装车小时流量（t/h）' },
  { id: 'steel', label: '钢材联合吊装', title: '钢材 40t 门吊 + 13 跨室内桥吊联合吊装态势', note: '货3/货4 → CR-01~03 40t门吊 → 转接区B → 9×20t磁吸 + 4×32t C型桥吊', camera: 'gantry', chartTitle: '钢材入库与货车装车作业效率（%）' },
  { id: 'safety', label: '安全防撞 (SCS)', title: '安全防碰撞监测与四步联锁监控态势（S4）', note: '门吊/翻箱机动态测距预警(15m)/停机(6m) + 库底电子围栏 + 静电接地联锁', camera: 'loader', chartTitle: '防碰撞预警与联锁处置响应分布' },
  { id: 'ehms', label: '设备健康 (EHMS)', title: '核心装卸装备 EHMS 健康评分与 BOM 风险（S3）', note: 'CR-01~03 / TIPPER-01~04 / BC-01~13 / ELV-01~04 机构级预测性维护上屏', camera: 'steel', chartTitle: '核心装卸装备群组健康指数（EHMS 分值）' },
  { id: 'weigh', label: '地磅计量与车流', title: '100t 汽车衡/轨道衡计量与场内车流态势', note: 'WB-TRUCK-01/02 空车初检与满车复磅 · 轨道衡自动采集', camera: 'weighbridge', chartTitle: '公路/铁路计量过磅车次统计' },
]

interface VideoChannelMeta {
  id: string
  title: string
  camera: CameraPresetId
  status: string
  controlSystemId: 'CTRL-BULK' | 'CTRL-CRANE' | 'CTRL-TIPPER'
  targetDeviceId: string
  aiOverlayTag: string
}

const VIDEO_CHANNELS: VideoChannelMeta[] = [
  {
    id: 'CAM-01',
    title: '1# 卸料室与翻箱机监控',
    camera: 'tipper',
    status: '在线 · 1080P · PLC联锁',
    controlSystemId: 'CTRL-TIPPER',
    targetDeviceId: 'TIPPER-01',
    aiOverlayTag: 'AI 识别：集装箱四角旋锁闭锁 · 倾角 0~135° 轨行净空正常',
  },
  {
    id: 'CAM-02',
    title: '2# 门吊-钢筋库跨间转接区',
    camera: 'gantry',
    status: '在线 · AI防撞 · 交接复核',
    controlSystemId: 'CTRL-CRANE',
    targetDeviceId: 'CR-02',
    aiOverlayTag: 'AI 识别：转接区B交接等待复核 · 门吊撤离净空 >18.5m',
  },
  {
    id: 'CAM-03',
    title: '钢筋库 07/03 跨室内桥吊',
    camera: 'rebar',
    status: '在线 · 1080P · 光电测距',
    controlSystemId: 'CTRL-CRANE',
    targetDeviceId: 'BC-07',
    aiOverlayTag: 'AI 识别：室内跨间光电防撞正常 · 20t磁吸/32t C型吊具稳钩',
  },
  {
    id: 'CAM-04',
    title: 'SILO-01 库底定量散装车道',
    camera: 'loader',
    status: '在线 · 电子围栏 · 接地',
    controlSystemId: 'CTRL-BULK',
    targetDeviceId: 'LOADER-01',
    aiOverlayTag: 'AI 识别：罐车青B·88219 静电接地闭锁 · 伸缩散装头对接密封',
  },
]

const router = useRouter()
const deviceStore = useDeviceStore()
const alertStore = useAlertStore()
const crossLinks = getCrossSystemLinks()
const portalUrl = getPortalUrl()
const s1DispatchUrl = crossLinks.find((c) => c.key === 'S1')?.url ?? 'https://psms.bproject.de5.net/production-dispatch/'

const activeTheme = ref<ThemeId>('overview')
const activeProcess = ref<ProcessKind>('bulk')
const steelSubMode = ref<SteelSubMode>('rebar_magnet')
const processTrackMode = ref<ProcessTrackMode>('f_d06_sim')
const activeLiveTaskId = ref(DEFAULT_LIVE_TASKS[0].taskId)
const singleStepMode = ref(false)
const activeRegion = ref<RegionCode>('SHEN_TIE')
const vendorModalOpen = ref(false)
const reqMatrixOpen = ref(false)
const activeVideoChannel = ref<VideoChannelMeta | null>(null)
const videoSnapshotDataUrl = ref('')
const dataMode = ref<DataSourceMode>(DEFAULT_DATA_MODE)
const feedStatus = reactive<DtsFeedStatus>({
  mode: DEFAULT_DATA_MODE,
  quality: DEFAULT_DATA_MODE === 'offline_sim' ? 'SIM' : 'GOOD',
  sourceLabel: DEFAULT_DATA_MODE === 'offline_sim' ? '本地工艺仿真推演（给大连大.dwg）' : '正在连接只读底座 (/api/v1/dts)',
  lastSyncAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
  errorNote: '',
  liveTasks: structuredClone(DEFAULT_LIVE_TASKS),
  storageLocations: [],
})

const carouselPaused = ref(false)
const sceneStatus = ref<'loading' | 'ready' | 'error'>('loading')
const sceneError = ref('')
const selectedInfo = ref<SelectedInfo | null>(null)
const cargoItems = ref(structuredClone(MOCK_CARGO))
const personnelItems = ref(structuredClone(MOCK_PERSONNEL))
const operationTasks = ref(structuredClone(MOCK_OPERATIONS))

const demoState = reactive<ProcessDemoState>({
  processKind: 'bulk',
  running: false,
  completed: false,
  stepIndex: 0,
  phaseIndex: 0,
  stepCount: 5,
  title: '就绪：一键启动散料翻箱或钢材联合吊装全流程演示',
  narration: '基于 2026.11.14 两份工艺细化图与给大连大.dwg 施工图驱动',
  progress: 0,
  payloadTons: 0,
  safetyDistanceM: 28.5,
  interlockChecks: [
    { label: '门吊与翻箱机防碰撞测距正常（>15m）', passed: true },
    { label: '库底装车电子围栏与接地待命', passed: true },
    { label: '42m³ 料斗与提升机联锁就绪', passed: true },
  ],
})

const chartRef = ref<HTMLElement | null>(null)
let viewer: Cesium.Viewer | null = null
let yardDataSource: Cesium.GeoJsonDataSource | null = null
let twin3d: Twin3dController | null = null
let phase6Layers: Phase6LayerController | null = null
let demoController: ProcessControllerHandle | null = null
let dataService: DtsDataService | null = null
let chart: echarts.ECharts | null = null
let chartResizeObserver: ResizeObserver | null = null
let carouselTimer: ReturnType<typeof setInterval> | null = null
let carouselResumeTimer: ReturnType<typeof setTimeout> | null = null
let alertShrinkTimer: ReturnType<typeof setTimeout> | null = null
let lastFocusTag = ''
let userCameraHold = false
let smoothedTrackState: (Required<Pick<CameraTrackTarget, 'cadX' | 'cadY' | 'cadZ' | 'headingDeg' | 'pitchDeg' | 'range'>> & {
  tag: string
}) | null = null
let removeCanvasInteractionListeners: (() => void) | null = null

const alertOverlay = ref(false)
const alertMinimized = ref(false)
const activeModalAlert = ref<AlertItem | null>(null)
let lastPromptedAlertId = ''

const activeThemeInfo = computed(() => THEMES.find((t) => t.id === activeTheme.value) ?? THEMES[0])
const fivePhases = computed(() => (activeProcess.value === 'bulk' ? BULK_FIVE_PHASES : STEEL_FIVE_PHASES))
const liveTaskList = computed(() =>
  feedStatus.liveTasks && feedStatus.liveTasks.length > 0 ? feedStatus.liveTasks : DEFAULT_LIVE_TASKS,
)
const activeLiveTask = computed(
  () => liveTaskList.value.find((t) => t.taskId === activeLiveTaskId.value) ?? liveTaskList.value[0],
)
const selectedDeviceState = computed(() => (selectedInfo.value ? deviceStore.devices[selectedInfo.value.id] : undefined))
const selectedStorageSummary = computed(() =>
  getStorageSummaryForTarget(selectedInfo.value?.id ?? '', feedStatus.storageLocations),
)
const selectedPsmsUrl = computed(() =>
  buildPsmsTaskUrl(s1DispatchUrl, {
    taskId: activeLiveTask.value?.taskId,
    planNo: activeLiveTask.value?.planNo,
    deviceId: selectedInfo.value?.id || undefined,
    step: demoState.stepIndex + 1,
  }),
)
const selectedWmsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S2')?.url ?? 'https://wms.bproject.de5.net/'
  return buildWmsLocationUrl(base, selectedInfo.value?.id || undefined, selectedStorageSummary.value?.batchNo)
})
const ehmsUrlForSelected = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S3')?.url ?? 'https://ehms.bproject.de5.net/ehm/'
  return buildEhmsAssetUrl(base, selectedInfo.value?.id || undefined)
})
const selectedScsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S4')?.url ?? 'https://scs.bproject.de5.net/'
  return buildScsAlertUrl(base, { deviceId: selectedInfo.value?.id || undefined })
})

const unreadAlerts = computed(() => alertStore.unhandledCount)
const criticalAlert = computed(
  () => activeModalAlert.value ?? alertStore.alerts.find((a) => !a.handled && (a.level === 'critical' || a.level === 'warning')) ?? null,
)
const activeVideoControlSystem = computed(() =>
  activeVideoChannel.value
    ? VENDOR_CONTROL_SYSTEMS.find((s) => s.id === activeVideoChannel.value?.controlSystemId) ?? VENDOR_CONTROL_SYSTEMS[0]
    : null,
)
const deviceSummary = computed(() => deviceStore.getSummary())
const ehmsTopRisks = computed(() => deviceStore.ehmsTopRisks)
const ehmsAvgScore = computed(() => {
  const list = Object.values(deviceStore.devices).filter((d) => d.ehms)
  if (!list.length) return 93.4
  return Number((list.reduce((s, d) => s + (d.ehms?.healthScore ?? 92), 0) / list.length).toFixed(1))
})

const bulkTotalTons = computed(() => {
  const c = cargoItems.value.find((i) => i.id === 'CARGO-02')?.quantity ?? 15600
  const f = cargoItems.value.find((i) => i.id === 'CARGO-04')?.quantity ?? 58400
  return c + f
})
const rebarStockTons = computed(() => cargoItems.value.find((i) => i.id === 'CARGO-01')?.quantity ?? 14200)
const activeTask = computed(() =>
  operationTasks.value.find((i) => i.id === (activeProcess.value === 'bulk' ? 'OP-240802' : 'OP-240801')),
)

const materialFlowNodes = computed(() =>
  activeProcess.value === 'bulk'
    ? ['货1/货2 列车', '40t集装箱门吊', '东西轨翻箱机(90°)', '顶部抽灰→工作室', '工作室内皮带输送', '40m斗式提升机', '40×3000T筒仓', '库底散装→100t磅']
    : ['货3/货4 露天板车', 'CR-01~03 门吊(40t)', '库外交接转接区 B', '20t磁吸 / 32t C型桥吊', '07跨专用空位对齐', 'CAD东斜道直入东门', '库内桥吊装车', '西出绕圈→100t复磅'],
)

const kpis = computed(() => [
  {
    label: '40座×3000T 散料总储 (F-D04)',
    value: `${bulkTotalTons.value.toLocaleString()} t`,
    delta: `总仓容 120,000t · ${Math.round((bulkTotalTons.value / 120000) * 100)}%`,
    tone: 'cyan',
  },
  {
    label: '钢筋仓库 13跨 (协作 X08)',
    value: `${rebarStockTons.value.toLocaleString()} t`,
    delta: '13跨 · 9磁吸 + 4 C型吊具',
    tone: 'blue',
  },
  {
    label: '核心装备健康 (F-D03·X03)',
    value: `${ehmsAvgScore.value} 分`,
    delta: `在线 ${deviceSummary.value.counts.ok ?? 0}/${deviceSummary.value.total} 台`,
    tone: 'green',
  },
  {
    label: '防碰撞测距 (F-D05·X05)',
    value: `${demoState.safetyDistanceM.toFixed(1)} m`,
    delta: demoState.safetyDistanceM <= 15 ? '预警减速 (<15m)' : '安全域 (预警15m/停机6m)',
    tone: demoState.safetyDistanceM <= 15 ? 'amber' : 'green',
  },
  {
    label: processTrackMode.value === 'f_d07_live' ? 'F-D07 真实任务节点 (X04)' : 'F-D06 模拟推演进度 (SIM)',
    value: `${demoState.progress}%`,
    delta: demoState.payloadTons > 0 ? `当前吊/装载 ${demoState.payloadTons.toFixed(1)} t` : activeProcess.value === 'bulk' ? '翻箱/提升/定量链' : '门吊/转接/桥吊链',
    tone: 'violet',
  },
])

function seedDevices() {
  deviceStore.batchUpdate(
    MOCK_EQUIPMENT.map((item) => buildDefaultDeviceSnapshot(item.id, item.name, item.status)),
  )
}

function setCamera(preset: CameraPresetId) {
  lastFocusTag = ''
  userCameraHold = false
  smoothedTrackState = null
  if (viewer) flyToCameraPreset(viewer, preset)
}

function setCategoryVisible(category: string, visible: boolean) {
  if (yardDataSource) void filterByCategory(yardDataSource, category, visible)
}

function syncOperationalLayers() {
  phase6Layers?.syncCargo(cargoItems.value)
  phase6Layers?.syncPersonnel(personnelItems.value)
  phase6Layers?.setCargoVisible(activeTheme.value === 'overview' || activeTheme.value === 'steel' || activeTheme.value === 'bulk')
  phase6Layers?.setPersonnelVisible(false)
}

function createRuntime(): UnifiedProcessRuntime {
  return {
    setCamera,
    focusPoint: (target: CameraFocusTarget) => {
      if (!viewer || userCameraHold) return
      if (target.tag && target.tag === lastFocusTag) return
      lastFocusTag = target.tag ?? ''
      smoothedTrackState = null
      focusCadPoint(viewer, target)
    },
    trackPoint: (target: CameraTrackTarget | null) => {
      if (!viewer) return
      if (!target) {
        smoothedTrackState = null
        releaseCameraTrack(viewer)
        return
      }
      if (userCameraHold) return
      const nextTag = target.tag ?? ''
      lastFocusTag = ''
      if (!smoothedTrackState || smoothedTrackState.tag !== nextTag) {
        smoothedTrackState = {
          cadX: target.cadX,
          cadY: target.cadY,
          cadZ: target.cadZ ?? 2.2,
          headingDeg: target.headingDeg,
          pitchDeg: target.pitchDeg,
          range: target.range,
          tag: nextTag,
        }
      } else {
        const alpha = target.smoothing ?? 0.24
        smoothedTrackState.cadX += (target.cadX - smoothedTrackState.cadX) * alpha
        smoothedTrackState.cadY += (target.cadY - smoothedTrackState.cadY) * alpha
        smoothedTrackState.cadZ += ((target.cadZ ?? 2.2) - smoothedTrackState.cadZ) * alpha
        const dh = ((target.headingDeg - smoothedTrackState.headingDeg + 540) % 360) - 180
        smoothedTrackState.headingDeg += dh * alpha
        smoothedTrackState.pitchDeg += (target.pitchDeg - smoothedTrackState.pitchDeg) * alpha
        smoothedTrackState.range += (target.range - smoothedTrackState.range) * alpha
      }
      trackCadPoint(viewer, smoothedTrackState)
    },
    resetUserCameraHold: () => {
      userCameraHold = false
      lastFocusTag = ''
      smoothedTrackState = null
      if (viewer) releaseCameraTrack(viewer)
    },
    setDeviceStatus: (id, status) => deviceStore.updateDevice(id, status),
    setEquipmentLabel: (id, text) => twin3d?.setEquipmentLabel(id, text),
    setStockyardQuantity: (cargoId, quantity) => {
      const cargo = cargoItems.value.find((item) => item.id === cargoId)
      if (cargo) {
        cargo.quantity = quantity
        cargo.warning = quantity / cargo.capacity >= 0.8
        phase6Layers?.syncCargo(cargoItems.value)
      }
    },
    setOperationState: (taskId, progress, status) => {
      const task = operationTasks.value.find((item) => item.id === taskId)
      if (task) {
        task.progress = progress
        task.status = status
      }
    },
    setVehiclePose: (id, pose, height, pitchDeg, rollDeg) => twin3d?.setVehiclePose(id, pose, height, pitchDeg, rollDeg),
    setActorVisible: (id, visible) => twin3d?.setActorVisible(id, visible),
    setCranePose: (id, pose) => twin3d?.setCranePose(id, pose),
    setBridgeCranePose: (id, pose) => twin3d?.setBridgeCranePose(id, pose),
    setTipperPose: (id, pose) => twin3d?.setTipperPose(id, pose),
    setLoaderPose: (id, pose) => twin3d?.setLoaderPose(id, pose),
    setProcessHighlight: (patch) => twin3d?.setProcessHighlight(patch),
    addAlert: (alert) => alertStore.addAlert(alert),
    clearAlerts: () => alertStore.clear(),
    requestRender: () => viewer?.scene.requestRender(),
  }
}

function rebuildController(kind: ProcessKind) {
  demoController?.destroy()
  const runtime = createRuntime()
  if (kind === 'bulk') {
    demoController = new BulkProcessDemoController(runtime, (state) => Object.assign(demoState, state))
  } else {
    const ctrl = new SteelUnloadDemoController(runtime, (state) => Object.assign(demoState, state))
    ctrl.setSubMode(steelSubMode.value)
    demoController = ctrl
  }
  demoController.setSingleStep(singleStepMode.value)
  demoController.reset()
}

function switchProcessTrackMode(nextMode: ProcessTrackMode) {
  processTrackMode.value = nextMode
  if (nextMode === 'f_d07_live') {
    stopCarousel()
    demoController?.pause()
    if (activeLiveTask.value) {
      selectLiveTaskNode(activeLiveTask.value)
    }
  }
}

function selectLiveTaskNode(task: LiveTaskNodeItem) {
  stopCarousel()
  processTrackMode.value = 'f_d07_live'
  activeLiveTaskId.value = task.taskId
  if (activeProcess.value !== task.processKind) {
    activeProcess.value = task.processKind
  }
  if (task.processKind === 'steel') {
    steelSubMode.value = task.steelSubMode
  }
  rebuildController(activeProcess.value)
  demoController?.setSingleStep(true)
  demoController?.jumpToStep(task.currentStepIndex, false)
  demoState.title = `[F-D07 真实任务节点 · ${task.qualityCode}] ${task.planNo}`
  demoState.narration = task.handoverWaitReason
    ? `⚠️ 交接等待：${task.handoverWaitReason}`
    : `${task.cargoName}（${task.tonnage}t）· 关联装备：${task.assignedDevices.join(' / ')}`
  demoState.progress = Math.min(100, Math.round(((task.currentStepIndex + 1) / 5) * 100))
  const info = twin3d?.focusEquipment(task.focusDeviceId)
  if (info) selectedInfo.value = info
}

function triggerRequirementFunction(item: RequirementFunctionItem) {
  reqMatrixOpen.value = false
  if (item.fnId === 'F-D01') {
    applyTheme('overview')
  } else if (item.fnId === 'F-D02') {
    void router.push('/calibration')
  } else if (item.fnId === 'F-D03') {
    const info = twin3d?.focusEquipment('CR-02')
    if (info) selectedInfo.value = info
  } else if (item.fnId === 'F-D04') {
    const info = twin3d?.focusEquipment('SILO-01')
    if (info) selectedInfo.value = info
  } else if (item.fnId === 'F-D05') {
    openVideoChannel(VIDEO_CHANNELS[1])
  } else if (item.fnId === 'F-D06') {
    processTrackMode.value = 'f_d06_sim'
    singleStepMode.value = true
    jumpToProcessStep(1)
  } else if (item.fnId === 'F-D07') {
    const waitTask = liveTaskList.value.find((t) => t.stepStatus === 'HANDOVER_WAIT') ?? liveTaskList.value[0]
    if (waitTask) selectLiveTaskNode(waitTask)
  } else if (item.fnId === 'F-D08') {
    vendorModalOpen.value = true
  }
}

function applyUrlDeepLinkParams() {
  const sp = new URL(window.location.href).searchParams
  const processParam = sp.get('process')
  const subParam = sp.get('sub')
  const stepParam = sp.get('step')
  const devParam = sp.get('deviceId') || sp.get('siloId') || sp.get('focus')
  const taskParam = sp.get('taskId') || sp.get('planNo')
  const fnParam = sp.get('fn')

  if (processParam === 'bulk' || processParam === 'steel') {
    activeProcess.value = processParam
    if (subParam === 'rebar_magnet' || subParam === 'coil_chook') {
      steelSubMode.value = subParam
    }
    rebuildController(activeProcess.value)
  }
  if (stepParam) {
    const maxIdx = Math.max(0, demoState.stepCount - 1)
    const idx = Math.max(0, Math.min(maxIdx, (Number.parseInt(stepParam, 10) || 1) - 1))
    singleStepMode.value = true
    jumpToProcessStep(idx)
  }
  if (taskParam) {
    const matched = liveTaskList.value.find((t) => t.taskId === taskParam || t.planNo === taskParam)
    if (matched) selectLiveTaskNode(matched)
  }
  if (devParam) {
    setTimeout(() => {
      const info = twin3d?.focusEquipment(devParam)
      if (info) selectedInfo.value = info
    }, 350)
  }
  if (fnParam) {
    const matchedFn = REQUIREMENT_FUNCTION_MATRIX.find((f) => f.fnId === fnParam.toUpperCase())
    if (matchedFn) {
      setTimeout(() => triggerRequirementFunction(matchedFn), 400)
    }
  }
}

function toggleSingleStepMode() {
  singleStepMode.value = !singleStepMode.value
  demoController?.setSingleStep(singleStepMode.value)
}

function jumpToProcessStep(stepIndex: number) {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  processTrackMode.value = 'f_d06_sim'
  stopCarousel()
  twin3d?.setVehicleRouteAnimation(false)
  if (!demoController) rebuildController(activeProcess.value)
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.jumpToStep(stepIndex, true)
}

function prevProcessStep() {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  processTrackMode.value = 'f_d06_sim'
  stopCarousel()
  twin3d?.setVehicleRouteAnimation(false)
  if (!demoController) rebuildController(activeProcess.value)
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.prevStep(true)
}

function nextProcessStep() {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  processTrackMode.value = 'f_d06_sim'
  stopCarousel()
  twin3d?.setVehicleRouteAnimation(false)
  if (!demoController) rebuildController(activeProcess.value)
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.nextStep(true)
}

function switchProcess(kind: ProcessKind) {
  if (activeProcess.value === kind) return
  activeProcess.value = kind
  seedDevices()
  cargoItems.value = structuredClone(MOCK_CARGO)
  personnelItems.value = structuredClone(MOCK_PERSONNEL)
  operationTasks.value = structuredClone(MOCK_OPERATIONS)
  syncOperationalLayers()
  rebuildController(kind)
  setCamera(kind === 'bulk' ? 'tipper' : 'gantry')
}

function switchSteelSubMode(mode: SteelSubMode) {
  if (steelSubMode.value === mode) return
  steelSubMode.value = mode
  if (activeProcess.value === 'steel') {
    resetDemo()
    setCamera('rebar')
  }
}

function onRegionChange(nextRegion: RegionCode) {
  activeRegion.value = nextRegion
  dataService?.setRegionId(nextRegion)
}

function openVideoChannel(cam: VideoChannelMeta) {
  setCamera(cam.camera)
  twin3d?.focusEquipment(cam.targetDeviceId)
  activeVideoChannel.value = cam
  setTimeout(() => {
    try {
      if (viewer) {
        viewer.scene.render()
        videoSnapshotDataUrl.value = viewer.scene.canvas.toDataURL('image/jpeg', 0.82)
      }
    } catch {
      videoSnapshotDataUrl.value = ''
    }
  }, 450)
}

function inspectAlertWorkstation(alert?: AlertItem | null) {
  const target = (alert ?? criticalAlert.value)?.targetId ?? ''
  if (target.startsWith('LOADER-') || target.startsWith('SILO-')) {
    openVideoChannel(VIDEO_CHANNELS[3])
  } else if (target.startsWith('BC-') || target.startsWith('REBAR-BAY-')) {
    openVideoChannel(VIDEO_CHANNELS[2])
  } else if (target.startsWith('CR-0')) {
    openVideoChannel(VIDEO_CHANNELS[1])
  } else {
    openVideoChannel(VIDEO_CHANNELS[0])
  }
}

function toggleDataMode() {
  const next: DataSourceMode = dataMode.value === 'offline_sim' ? 'realtime_readonly' : 'offline_sim'
  dataMode.value = next
  dataService?.setMode(next)
}

function resetDemo() {
  seedDevices()
  alertStore.clear()
  lastPromptedAlertId = ''
  activeModalAlert.value = null
  alertOverlay.value = false
  alertMinimized.value = false
  cargoItems.value = structuredClone(MOCK_CARGO)
  personnelItems.value = structuredClone(MOCK_PERSONNEL)
  operationTasks.value = structuredClone(MOCK_OPERATIONS)
  syncOperationalLayers()
  twin3d?.setVehicleRouteAnimation(false)
  demoController?.reset()
}

function startDemo() {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  processTrackMode.value = 'f_d06_sim'
  resetDemo()
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.start()
}

function pauseDemo() {
  demoController?.pause()
}

function toggleDemo() {
  if (demoState.running) pauseDemo()
  else if (demoState.completed || (demoState.progress === 0 && demoState.stepIndex === 0)) startDemo()
  else {
    userCameraHold = false
    lastFocusTag = ''
    smoothedTrackState = null
    if (viewer) releaseCameraTrack(viewer)
    processTrackMode.value = 'f_d06_sim'
    demoController?.setSingleStep(singleStepMode.value)
    demoController?.start()
  }
}

function applyTheme(id: ThemeId) {
  activeTheme.value = id
  setCamera(activeThemeInfo.value.camera)
  syncOperationalLayers()
  updateChart()
}

function advanceTheme() {
  const index = THEMES.findIndex((theme) => theme.id === activeTheme.value)
  applyTheme(THEMES[(index + 1) % THEMES.length].id)
}

function stopCarousel() {
  if (carouselTimer) clearInterval(carouselTimer)
  carouselTimer = null
}

function startCarousel() {
  stopCarousel()
  if (carouselPaused.value || alertOverlay.value) return
  carouselTimer = setInterval(advanceTheme, 30_000)
}

function toggleCarousel() {
  carouselPaused.value = !carouselPaused.value
  if (carouselPaused.value) stopCarousel()
  else startCarousel()
}

function pauseOnPointerEnter() {
  if (!carouselPaused.value) stopCarousel()
  if (carouselResumeTimer) clearTimeout(carouselResumeTimer)
}

function resumeAfterPointerLeave() {
  if (carouselPaused.value || alertOverlay.value) return
  if (carouselResumeTimer) clearTimeout(carouselResumeTimer)
  carouselResumeTimer = setTimeout(startCarousel, 5_000)
}

function focusDevice(id: string) {
  const info = twin3d?.focusEquipment(id)
  if (info) selectedInfo.value = info
}

function focusCriticalAlert(customAlert?: AlertItem | null) {
  const alert = customAlert ?? criticalAlert.value
  if (!alert) return
  const target = alert.targetId
  if (twin3d) {
    const info = twin3d.focusEquipment(target)
    if (info) selectedInfo.value = info
  }
  if (target.startsWith('TIPPER-') || target.startsWith('CR-BULK')) setCamera('tipper')
  else if (target.startsWith('LOADER-') || target.startsWith('SILO-')) setCamera('loader')
  else if (target.startsWith('CR-')) setCamera('gantry')
  else if (target.startsWith('REBAR-BAY-') || target.startsWith('BC-')) setCamera('rebar')
}

function onAlertItemClick(alert: AlertItem) {
  activeModalAlert.value = alert
  alertOverlay.value = true
  alertMinimized.value = false
  focusCriticalAlert(alert)
}

function acknowledgeCritical() {
  const alert = criticalAlert.value
  if (alert) alertStore.markHandled(alert.id)
  activeModalAlert.value = null
  alertOverlay.value = false
  alertMinimized.value = false
  if (!carouselPaused.value) startCarousel()
}

function updateChart() {
  if (!chart) return
  const chartSeries: Record<ThemeId, number[]> = {
    overview: [320, 380, 410, 460, 490, 510, 480, 540, 560, 520, 580, 615],
    bulk: [180, 210, 240, 265, 290, 275, 310, 340, 355, 330, 368, 390],
    steel: [72, 76, 80, 82, 85, 84, 88, 91, 90, 93, 94, 96],
    safety: [1, 0, 2, 1, 0, 0, 1, 2, 0, 1, 0, 0],
    ehms: [96, 95, 95, 94, 94, 93, 95, 94, 93, 92, 93, ehmsAvgScore.value],
    weigh: [14, 18, 22, 26, 24, 29, 31, 35, 33, 38, 40, 42],
  }
  const safety = activeTheme.value === 'safety'
  chart.setOption(
    {
      animationDuration: 300,
      backgroundColor: 'transparent',
      grid: { left: 44, right: 16, top: 28, bottom: 22 },
      title: {
        text: activeThemeInfo.value.chartTitle,
        left: 10,
        top: 2,
        textStyle: { color: '#b8d7ed', fontSize: 12, fontWeight: 500 },
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: ['00', '02', '04', '06', '08', '10', '12', '14', '16', '18', '20', '22'],
        axisLine: { lineStyle: { color: '#24455f' } },
        axisLabel: { color: '#6f93ab', fontSize: 10 },
      },
      yAxis: {
        type: 'value',
        min: 0,
        splitLine: { lineStyle: { color: 'rgba(68, 123, 151, 0.16)' } },
        axisLabel: { color: '#6f93ab', fontSize: 10 },
      },
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(5, 20, 37, .96)', borderColor: '#237b9b', textStyle: { color: '#e4f8ff' } },
      series: [
        {
          type: safety ? 'bar' : 'line',
          data: chartSeries[activeTheme.value],
          smooth: !safety,
          symbol: 'none',
          barMaxWidth: 16,
          itemStyle: { color: safety ? '#f6b44d' : '#16d7e7' },
          lineStyle: { width: 2, color: '#16d7e7' },
          areaStyle: safety
            ? undefined
            : {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                  { offset: 0, color: 'rgba(22, 215, 231, .36)' },
                  { offset: 1, color: 'rgba(22, 215, 231, .02)' },
                ]),
              },
        },
      ],
    },
    true,
  )
}

function initChart() {
  if (!chartRef.value || chart) return
  chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })
  chartResizeObserver = new ResizeObserver(() => chart?.resize())
  chartResizeObserver.observe(chartRef.value)
  updateChart()
}

function onViewerReady(nextViewer: Cesium.Viewer) {
  viewer = nextViewer
  setupCameraInteraction(nextViewer)
  removeCanvasInteractionListeners?.()
  const canvas = nextViewer.scene.canvas
  const markManualCamera = () => {
    userCameraHold = true
    smoothedTrackState = null
    releaseCameraTrack(nextViewer)
    nextViewer.camera.cancelFlight()
  }
  canvas.addEventListener('pointerdown', markManualCamera, { passive: true })
  canvas.addEventListener('wheel', markManualCamera, { passive: true })
  removeCanvasInteractionListeners = () => {
    canvas.removeEventListener('pointerdown', markManualCamera)
    canvas.removeEventListener('wheel', markManualCamera)
  }
  sceneStatus.value = 'loading'
  nextViewer.scene.requestRender()

  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`CAD 图层 HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) =>
      Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
        stroke: Cesium.Color.fromCssColorString('#2A88A8').withAlpha(0.08),
        strokeWidth: 1,
        fill: Cesium.Color.fromCssColorString('#1D5D9B').withAlpha(0.005),
        clampToGround: false,
        markerSize: 0,
      }),
    )
    .then((dataSource) => {
      nextViewer.dataSources.add(dataSource)
      yardDataSource = dataSource
      setCategoryVisible('track', false)
      setCategoryVisible('area', false)
      setCamera('overview')
    })
    .catch((error: unknown) => {
      sceneError.value = error instanceof Error ? error.message : String(error)
    })
    .finally(() => {
      seedDevices()
      twin3d = new Twin3dController(nextViewer, { onSelect: (info) => { selectedInfo.value = info } })
      twin3d.setVehicleRouteAnimation(true)
      phase6Layers = new Phase6LayerController(nextViewer)
      syncOperationalLayers()
      rebuildController(activeProcess.value)
      dataService = new DtsDataService(deviceStore, alertStore, (s) => Object.assign(feedStatus, s), activeRegion.value)
      activeRegion.value = dataService.getRegionId()
      dataService.start(dataMode.value, 5000)
      const win = window as unknown as Record<string, unknown>
      win.__dt_viewer = nextViewer
      win.__dt_twin3d = twin3d
      win.__Cesium = Cesium
      applyUrlDeepLinkParams()
      sceneStatus.value = sceneError.value ? 'error' : 'ready'
      nextViewer.scene.requestRender()
    })
}

watch(
  () => deviceStore.devices,
  (devices) => {
    for (const [id, device] of Object.entries(devices)) twin3d?.setStatus(id, device.status)
  },
  { deep: true },
)

watch(
  () => alertStore.alerts[0],
  (latestAlert) => {
    if (!latestAlert || latestAlert.handled) return
    if (latestAlert.level !== 'critical' && !latestAlert.id.includes('DONE')) return
    if (latestAlert.id === lastPromptedAlertId) return
    lastPromptedAlertId = latestAlert.id
    activeModalAlert.value = latestAlert
    stopCarousel()
    alertOverlay.value = true
    alertMinimized.value = false
    if (!latestAlert.id.startsWith('DEMO-')) {
      focusCriticalAlert(latestAlert)
    }
    if (alertShrinkTimer) clearTimeout(alertShrinkTimer)
    alertShrinkTimer = setTimeout(() => {
      alertMinimized.value = true
      alertOverlay.value = false
    }, 12_000)
  },
)

watch(activeTheme, updateChart)
watch(() => demoState.progress, updateChart)

onMounted(async () => {
  await nextTick()
  initChart()
  startCarousel()
})

onBeforeUnmount(() => {
  stopCarousel()
  if (carouselResumeTimer) clearTimeout(carouselResumeTimer)
  if (alertShrinkTimer) clearTimeout(alertShrinkTimer)
  removeCanvasInteractionListeners?.()
  removeCanvasInteractionListeners = null
  chartResizeObserver?.disconnect()
  chartResizeObserver = null
  chart?.dispose()
  chart = null
  dataService?.stop()
  demoController?.destroy()
  phase6Layers?.destroy()
  twin3d?.destroy()
  viewer = null
  yardDataSource = null
})

void SITE_LAYOUT_V4.version
</script>

<template>
  <main class="bigscreen" @pointerenter="pauseOnPointerEnter" @pointerleave="resumeAfterPointerLeave">
    <header class="screen-header">
      <div>
        <p class="eyebrow">ZHONGSHABA RAIL TRANSFER HUB · S5 DIGITAL TWIN · R-D01~R-D05 · F-D01~F-D08 · 只监不控安全边界</p>
        <h1>{{ activeThemeInfo.title }}</h1>
      </div>
      <div class="header-meta">
        <select class="region-select" :value="activeRegion" @change="onRegionChange(($event.target as HTMLSelectElement).value as RegionCode)">
          <option v-for="r in REGION_OPTIONS" :key="r.id" :value="r.id">{{ r.label }}</option>
        </select>
        <button class="mode-switch" @click="toggleDataMode">
          数据源：<b>{{ dataMode === 'offline_sim' ? '离线仿真 (.eddx)' : '只读数据库 (/api/v1)' }}</b>
          <em class="quality-pill" :class="feedStatus.quality.toLowerCase()">{{ feedStatus.quality }}</em>
        </button>
        <button class="mode-switch req-pill" @click="reqMatrixOpen = !reqMatrixOpen">
          需求与跨系统矩阵 (F-D01~08)
        </button>
        <button class="mode-switch vendor-pill" @click="vendorModalOpen = !vendorModalOpen">
          1+5+2 厂家控制系统 (3套)
        </button>
        <nav class="cross-links" aria-label="业务系统联动跳转">
          <a v-for="item in crossLinks" :key="item.key" :href="item.url" target="_blank" rel="noopener noreferrer" :title="`${item.name} (${item.badge})`">
            {{ item.key }}·{{ item.badge }}
          </a>
        </nav>
        <RouterLink to="/calibration" class="back-link">F-D02 CAD校核</RouterLink>
        <RouterLink to="/" class="back-link">← 三维作业台</RouterLink>
        <a :href="portalUrl" class="back-link">统一门户 →</a>
      </div>
    </header>

    <!-- Slide 40 & R-D03: 顶部 5 步全流程可视化追踪条（支持 F-D06 模拟推演 / F-D07 真实节点双轨切换与自由跳步） -->
    <section class="process-tracker" aria-label="双线工艺五步流程追踪">
      <div class="process-switch">
        <button
          :class="{ active: processTrackMode === 'f_d06_sim' }"
          title="F-D06 固定工艺演示播放（显式标 SIM）"
          @click="switchProcessTrackMode('f_d06_sim')"
        >
          F-D06推演(SIM)
        </button>
        <button
          class="live-track-btn"
          :class="{ active: processTrackMode === 'f_d07_live' }"
          title="F-D07 真实任务节点接入与展示（协作X04，对接S1 PSMS计划主账）"
          @click="switchProcessTrackMode('f_d07_live')"
        >
          F-D07真实(X04)
        </button>
        <button :class="{ active: activeProcess === 'bulk' }" @click="switchProcess('bulk')">散料链</button>
        <button :class="{ active: activeProcess === 'steel' }" @click="switchProcess('steel')">钢材链</button>
        <template v-if="activeProcess === 'steel'">
          <button :class="{ active: steelSubMode === 'rebar_magnet' }" @click="switchSteelSubMode('rebar_magnet')">20t磁吸(07跨)</button>
          <button :class="{ active: steelSubMode === 'coil_chook' }" @click="switchSteelSubMode('coil_chook')">32t C型(03跨)</button>
        </template>
      </div>
      <div class="five-steps">
        <button
          v-for="(label, idx) in fivePhases"
          :key="label"
          type="button"
          class="step-pill"
          :class="{ done: demoState.completed || idx < demoState.phaseIndex, current: !demoState.completed && idx === demoState.phaseIndex }"
          :title="`点击直达演示：${label}`"
          @click="jumpToProcessStep(idx)"
        >
          <span>{{ label }}</span>
        </button>
      </div>
      <div class="tracker-summary">
        <button type="button" class="tracker-nav-btn" :disabled="demoState.stepIndex <= 0" title="上一步" @click="prevProcessStep">◀</button>
        <button type="button" class="tracker-nav-btn" :disabled="demoState.stepIndex >= demoState.stepCount - 1" title="下一步" @click="nextProcessStep">▶</button>
        <button
          type="button"
          class="tracker-nav-btn mode"
          :class="{ single: singleStepMode }"
          @click="toggleSingleStepMode"
        >
          {{ singleStepMode ? '单步驻留' : '连续联播' }}
        </button>
        <b>{{ demoState.progress }}%</b>
      </div>
    </section>

    <section class="kpi-row" aria-label="运营关键指标">
      <article v-for="kpi in kpis" :key="kpi.label" class="kpi-card" :class="`tone-${kpi.tone}`">
        <span>{{ kpi.label }}</span>
        <strong>{{ kpi.value }}</strong>
        <small>{{ kpi.delta }}</small>
      </article>
    </section>

    <section class="screen-grid">
      <aside class="side-column left-column">
        <section class="panel scs-panel">
          <div class="panel-heading"><span>S4 安全防撞与联锁回执 (X05)</span><em>预警15m/停6m</em></div>
          <div class="safety-meter">
            <span>门吊/翻箱/桥吊安全间距</span>
            <strong :class="{ warn: demoState.safetyDistanceM <= 15 }">{{ demoState.safetyDistanceM.toFixed(1) }} m</strong>
          </div>
          <div class="interlock-list">
            <div v-for="chk in demoState.interlockChecks" :key="chk.label" class="interlock-row">
              <i :class="{ pass: chk.passed }">✔</i>
              <span>{{ chk.label }}</span>
            </div>
          </div>
          <div v-if="alertStore.alerts.length" class="alert-list">
            <button v-for="alert in alertStore.alerts.slice(0, 3)" :key="alert.id" class="alert-item" :class="alert.level" @click="onAlertItemClick(alert)">
              <span class="level-dot"></span>
              <span><b>{{ alert.targetName }}</b><small>{{ alert.message }}</small></span>
            </button>
          </div>
        </section>

        <section class="panel topology-panel">
          <div class="panel-heading"><span>物料流向拓扑（Slide 40）</span><em>{{ unreadAlerts }} 告警</em></div>
          <div class="flow-chain">
            <span v-for="(node, i) in materialFlowNodes" :key="node" class="flow-node" :class="{ lit: i <= demoState.phaseIndex * 1.6 }">
              {{ node }}
            </span>
          </div>
        </section>

        <section class="panel video-panel">
          <div class="panel-heading"><span>重点工位关联视频 & PLC核查</span><em>点击核查</em></div>
          <div class="video-grid">
            <button v-for="cam in VIDEO_CHANNELS" :key="cam.id" class="video-tile" @click="openVideoChannel(cam)">
              <b>{{ cam.id }}</b>
              <span>{{ cam.title }}</span>
              <small>{{ cam.status }}</small>
            </button>
          </div>
        </section>
      </aside>

      <section class="scene-panel">
        <CesiumViewer @viewer-ready="onViewerReady" />
        <div v-if="sceneStatus === 'loading'" class="scene-overlay loading">正在加载《给大连大.dwg》V4 实测三维场站与 GLB 装备…</div>
        <div v-else-if="sceneStatus === 'error'" class="scene-overlay warning">CAD 底图未完整加载：{{ sceneError }}</div>

        <div class="scene-caption">
          <span class="live-dot"></span>
          <span>{{ activeThemeInfo.note }}</span>
          <small class="feed-note">{{ feedStatus.sourceLabel }} · {{ feedStatus.lastSyncAt }}</small>
          <button class="scene-action" @click="setCamera(activeThemeInfo.camera)">复位镜头</button>
        </div>

        <!-- Slide 41: 设备空间选取 + F-D03(EHMS) + F-D04(WMS库位) + F-D05(跨系统联查) HUD 卡 -->
        <div v-if="selectedInfo" class="selection-hud">
          <div class="hud-head">
            <div>
              <b>{{ selectedInfo.name }}</b>
              <small>{{ selectedInfo.id }} · {{ selectedInfo.statusName }}</small>
            </div>
            <button @click="selectedInfo = null">✕</button>
          </div>
          <div v-if="selectedDeviceState?.telemetry" class="hud-grid">
            <div><span>当前载荷</span><b>{{ selectedDeviceState.telemetry.loadTons }} t</b></div>
            <div><span>实时风速</span><b>{{ selectedDeviceState.telemetry.windSpeedMps }} m/s</b></div>
            <div><span>液压压力</span><b>{{ selectedDeviceState.telemetry.hydraulicPsi }} MPa</b></div>
            <div><span>累计运行</span><b>{{ selectedDeviceState.telemetry.runHours }} h</b></div>
            <div><span>今日循环</span><b>{{ selectedDeviceState.telemetry.todayCycles }} 次</b></div>
            <div><span>维保提示</span><b>{{ selectedDeviceState.telemetry.maintenanceDue }}</b></div>
          </div>
          <div v-if="selectedDeviceState?.ehms" class="hud-ehms">
            <span>[F-D03·X03] EHMS 健康 <b>{{ selectedDeviceState.ehms.healthScore }}</b> · {{ selectedDeviceState.ehms.riskSubsystem }}</span>
            <a :href="ehmsUrlForSelected" target="_blank" rel="noopener noreferrer">S3 BOM →</a>
          </div>
          <!-- F-D04 (协作 X08): S2 WMS 仓位与料位联查 -->
          <div v-if="selectedStorageSummary" class="hud-wms">
            <div class="hud-wms-top">
              <span>[F-D04·X08] 仓位 <b>{{ selectedStorageSummary.locationId }}</b> ({{ selectedStorageSummary.materialName }})</span>
              <a :href="selectedWmsUrl" target="_blank" rel="noopener noreferrer">S2台账 →</a>
            </div>
            <div class="hud-wms-sub">
              库存 {{ selectedStorageSummary.availableTon }}t / {{ selectedStorageSummary.capacityTon }}t · 料位 {{ selectedStorageSummary.levelMeters }}m · {{ selectedStorageSummary.batchNo }}
            </div>
          </div>
          <div v-if="Object.keys(selectedInfo.params ?? {}).length" class="hud-params">
            <div v-for="(val, key) in selectedInfo.params" :key="key" class="hud-param-row">
              <span>{{ key }}</span><b>{{ val }}</b>
            </div>
          </div>
          <div class="hud-cross-jumps">
            <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer">S1工序(X04)</a>
            <a :href="selectedWmsUrl" target="_blank" rel="noopener noreferrer">S2仓位(X08)</a>
            <a :href="ehmsUrlForSelected" target="_blank" rel="noopener noreferrer">S3健康(X03)</a>
            <a :href="selectedScsUrl" target="_blank" rel="noopener noreferrer">S4安全(X05)</a>
          </div>
        </div>

        <!-- 组长工作簿对齐：R-D01~R-D05 ↔ F-D01~F-D08 与跨系统协作 (X03~X11) 核验弹窗 -->
        <div v-if="reqMatrixOpen" class="req-matrix-modal" role="dialog" aria-label="S5需求功能与跨系统协作核验矩阵">
          <div class="vim-head">
            <div>
              <b>S5 数字孪生需求功能（R-D01~D05 ↔ F-D01~D08）与跨系统协作（X03~X11）实时核验矩阵</b>
              <small>100% 对齐组长《系统需求功能工作簿 v2.0/v2.1》· 点击右侧“▶ 场景核验”直接在指挥大屏验证该功能</small>
            </div>
            <button class="vim-close" @click="reqMatrixOpen = false">✕</button>
          </div>
          <div class="req-table-wrap">
            <table class="req-table">
              <thead>
                <tr>
                  <th>功能号</th>
                  <th>功能名称</th>
                  <th>支撑需求 (双向挂载)</th>
                  <th>跨系统协作号 & 对接系统</th>
                  <th>在本需求中承担职责与复用说明</th>
                  <th>当前状态</th>
                  <th>一键核验</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in REQUIREMENT_FUNCTION_MATRIX" :key="item.fnId">
                  <td class="fn-code">{{ item.fnId }}</td>
                  <td class="fn-name">{{ item.fnName }}</td>
                  <td class="req-code">{{ item.reqIds }}</td>
                  <td>
                    <span class="collab-badge">{{ item.collabCode }}</span>
                    <small class="partner-sub">{{ item.partnerSystem }}</small>
                  </td>
                  <td>
                    <div>{{ item.roleInReq }}</div>
                    <small class="partner-sub">{{ item.reuseRelation }}</small>
                  </td>
                  <td class="st-col">{{ item.statusLabel }}</td>
                  <td>
                    <button type="button" class="verify-act-btn" @click="triggerRequirementFunction(item)">
                      ▶ {{ item.actionLabel }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Slide 40: 工位关联视频与厂家控制系统 PLC 状态核查浮窗 -->
        <div v-if="activeVideoChannel" class="video-inspect-modal">
          <div class="vim-head">
            <div>
              <b>{{ activeVideoChannel.id }} · {{ activeVideoChannel.title }}（工位关联视频 & 厂家控制系统核查）</b>
              <small>{{ activeVideoChannel.status }} · 严守 S5 只监不控安全边界</small>
            </div>
            <button class="vim-close" @click="activeVideoChannel = null">✕</button>
          </div>
          <div class="vim-body">
            <div class="vim-viewport">
              <img v-if="videoSnapshotDataUrl" :src="videoSnapshotDataUrl" alt="工位实时视频镜像" class="vim-canvas-img" />
              <div class="vim-osd-top">
                <span>REC ● {{ activeVideoChannel.id }} · 中沙坝场站专网 RTSP/WebRTC</span>
                <span>防碰撞测距: {{ demoState.safetyDistanceM.toFixed(1) }} m</span>
              </div>
              <div class="vim-ai-box">
                <span>{{ activeVideoChannel.aiOverlayTag }}</span>
              </div>
            </div>
            <div v-if="activeVideoControlSystem" class="vim-plc">
              <div class="vim-plc-title">
                <b>{{ activeVideoControlSystem.name }}</b>
                <em>{{ activeVideoControlSystem.category }}</em>
              </div>
              <p class="vim-plc-sub">{{ activeVideoControlSystem.interlockSummary }}</p>
              <div class="vim-sig-list">
                <div v-for="sig in activeVideoControlSystem.plcSignals" :key="sig.tag" class="vim-sig-row">
                  <span>{{ sig.label }}</span>
                  <b>{{ sig.value }}</b>
                </div>
              </div>
              <div class="vim-actions">
                <button @click="setCamera(activeVideoChannel.camera)">复位工位镜头</button>
                <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer" class="s1-jump-btn">
                  进入 S1 装卸工艺管理系统处置 →
                </a>
              </div>
            </div>
          </div>
        </div>

        <!-- 1+5+2 底层控制系统只读状态总览弹窗 -->
        <div v-if="vendorModalOpen" class="vendor-overview-modal">
          <div class="vim-head">
            <div>
              <b>1+5+2 架构 · 底层厂家/合研控制系统只读状态监控（协作 X09 / X10）</b>
              <small>2套外购厂家控制系统 + 1套合研移动翻箱机控制系统（OPC UA / Profinet 只读镜像）</small>
            </div>
            <button class="vim-close" @click="vendorModalOpen = false">✕</button>
          </div>
          <div class="vendor-cards-row">
            <article v-for="sys in VENDOR_CONTROL_SYSTEMS" :key="sys.id" class="v-card">
              <div class="v-card-head">
                <b>{{ sys.name }}</b>
                <em>{{ sys.category }}</em>
              </div>
              <p>{{ sys.protocol }} · 纳管: {{ sys.boundDevices.join(' / ') }}</p>
              <div v-for="sig in sys.plcSignals" :key="sig.tag" class="vim-sig-row">
                <span>{{ sig.label }}</span>
                <b>{{ sig.value }}</b>
              </div>
            </article>
          </div>
        </div>
      </section>

      <aside class="side-column right-column">
        <section class="panel task-panel">
          <div class="panel-heading">
            <span>R-D03 任务与过程追踪</span>
            <em>{{ processTrackMode === 'f_d06_sim' ? 'F-D06 模拟推演(SIM)' : 'F-D07 真实节点(X04)' }}</em>
          </div>
          <div class="track-switch-tabs">
            <button :class="{ active: processTrackMode === 'f_d06_sim' }" @click="switchProcessTrackMode('f_d06_sim')">
              F-D06 固定推演(SIM)
            </button>
            <button :class="{ active: processTrackMode === 'f_d07_live' }" @click="switchProcessTrackMode('f_d07_live')">
              F-D07 真实节点(X04)
            </button>
          </div>
          <template v-if="processTrackMode === 'f_d06_sim'">
            <h2>{{ demoState.title }}</h2>
            <p>{{ demoState.narration }}</p>
            <div class="progress-track"><span :style="{ width: `${demoState.progress}%` }"></span></div>
            <div class="task-meta">
              <span>状态：{{ activeTask?.status === 'done' ? '已完成' : demoState.running ? '执行中' : '待启动/驻留' }}</span>
              <span>阶段 {{ Math.min(demoState.stepIndex + 1, demoState.stepCount) }}/{{ demoState.stepCount }}</span>
            </div>
            <div class="demo-controls">
              <button class="primary" @click="toggleDemo">{{ demoState.running ? '暂停演示' : demoState.completed ? '重新演示' : demoState.progress > 0 ? '继续演示' : '开始演示' }}</button>
              <button :disabled="demoState.stepIndex <= 0" @click="prevProcessStep">上一步</button>
              <button :disabled="demoState.stepIndex >= demoState.stepCount - 1" @click="nextProcessStep">下一步</button>
              <button @click="resetDemo">重置</button>
            </div>
          </template>
          <template v-else>
            <div class="live-task-cards">
              <button
                v-for="t in liveTaskList"
                :key="t.taskId"
                type="button"
                class="live-task-card"
                :class="{ active: activeLiveTask?.taskId === t.taskId, wait: t.stepStatus === 'HANDOVER_WAIT' }"
                @click="selectLiveTaskNode(t)"
              >
                <div class="ltc-head">
                  <b>{{ t.planNo }}</b>
                  <em>{{ t.stepStatus === 'HANDOVER_WAIT' ? '交接等待' : t.stepStatus === 'COMPLETED' ? '已完成' : `工序${t.currentStepIndex + 1}/5` }}</em>
                </div>
                <div class="ltc-sub">{{ t.cargoName }} · {{ t.tonnage }}t · {{ t.focusDeviceId }}</div>
              </button>
            </div>
            <p v-if="activeLiveTask?.handoverWaitReason" class="wait-reason-note">
              ⚠️ {{ activeLiveTask.handoverWaitReason }}
            </p>
            <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer" class="s1-live-jump">
              进入 S1 工艺管理系统处置该工单 (X04) →
            </a>
          </template>
        </section>

        <!-- S3 EHMS 关键健康状态与 BOM 风险专区（第 20 次组会明确要求） -->
        <section class="panel ehms-panel">
          <div class="panel-heading"><span>S3 EHMS 健康与 BOM 风险 (X03)</span><em>均值 {{ ehmsAvgScore }}</em></div>
          <div v-for="dev in ehmsTopRisks" :key="dev.id" class="ehms-row" @click="focusDevice(dev.id)">
            <div class="ehms-top">
              <span class="status-led" :class="dev.status"></span>
              <b>{{ dev.id }}</b>
              <em>{{ dev.name.replace('（40t）', '') }}</em>
              <strong :class="dev.ehms?.healthLevel">{{ dev.ehms?.healthScore }}分</strong>
            </div>
            <div class="ehms-sub">{{ dev.ehms?.riskSubsystem }} · RUL {{ dev.ehms?.predictedRulDays }}d</div>
          </div>
        </section>
      </aside>
    </section>

    <section class="chart-row panel"><div ref="chartRef" class="trend-chart"></div></section>

    <footer class="screen-footer">
      <nav class="theme-tabs" aria-label="大屏主题">
        <button v-for="theme in THEMES" :key="theme.id" :class="{ active: activeTheme === theme.id }" @click="applyTheme(theme.id)">{{ theme.label }}</button>
      </nav>
      <button class="carousel-control" @click="toggleCarousel">{{ carouselPaused ? '▶ 恢复自动轮播' : 'Ⅱ 暂停自动轮播' }}</button>
      <span>S5 只监不控 · 严禁对厂内起重机/翻箱机下发控制指令 · 给大连大.dwg V4</span>
    </footer>

    <div v-if="alertOverlay && criticalAlert" class="alert-modal" role="alertdialog">
      <p>Slide 40 交接等待 / 安全联锁回执 · 自动轮播已暂停</p>
      <h2>{{ criticalAlert.targetName }}</h2>
      <span>{{ criticalAlert.message }}</span>
      <div class="receipt-tags" v-if="criticalAlert.interlockReceipt">
        <b v-for="act in criticalAlert.interlockReceipt.actions" :key="act">{{ act }}</b>
      </div>
      <div class="alert-modal-actions">
        <button @click="focusCriticalAlert(criticalAlert)">1. 定位三维工位</button>
        <button @click="inspectAlertWorkstation(criticalAlert)">2. 核查厂家状态及关联视频</button>
        <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer" class="modal-link-btn">3. 进入 S1 工艺管理处置 →</a>
        <button class="primary" @click="acknowledgeCritical">已核实确认</button>
      </div>
    </div>
    <button v-if="alertMinimized && criticalAlert" class="critical-toast" @click="alertOverlay = true">告警提示：{{ criticalAlert.targetName }}</button>
  </main>
</template>

<style scoped lang="scss">
.bigscreen {
  --panel-bg: rgba(8, 25, 45, 0.9);
  --panel-border: rgba(45, 138, 178, 0.45);
  min-width: 1180px;
  min-height: 740px;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  color: #d9f1ff;
  background:
    radial-gradient(circle at 48% 30%, rgba(10, 74, 110, 0.24), transparent 38%),
    linear-gradient(135deg, #020918 0%, #061427 48%, #020a17 100%);
  padding: 10px 18px 10px;
  display: grid;
  grid-template-rows: auto auto auto minmax(340px, 1fr) 122px auto;
  gap: 8px;
  position: relative;
}

.screen-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.eyebrow { margin: 0 0 2px; color: #2abdd4; font-size: 11px; letter-spacing: 0.14em; }
h1 { margin: 0; font-size: clamp(20px, 1.45vw, 28px); letter-spacing: 0.05em; color: #e6fbff; }
.header-meta { display: flex; align-items: center; gap: 6px; font-size: 12px; flex-wrap: wrap; }
.region-select {
  padding: 4px 8px; border-radius: 4px; border: 1px solid rgba(42, 189, 212, 0.52);
  background: rgba(8, 36, 58, 0.85); color: #cdefff; font-size: 11px; cursor: pointer;
}
.mode-switch {
  display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 99px;
  border: 1px solid rgba(42, 189, 212, 0.52); background: rgba(8, 36, 58, 0.8); color: #cdefff; cursor: pointer; font-size: 11px;
  &.vendor-pill { border-color: #00e5ff; color: #7ef4ff; font-weight: 600; }
  &.req-pill { border-color: #34d399; color: #6ee7b7; font-weight: 600; }
}
.quality-pill {
  font-style: normal; padding: 1px 6px; border-radius: 99px; font-weight: 700; font-size: 10px;
  &.sim { background: rgba(0, 229, 255, 0.18); color: #5ce7ff; }
  &.good { background: rgba(52, 211, 153, 0.22); color: #34d399; }
  &.fallback { background: rgba(245, 184, 76, 0.22); color: #f5b84c; }
}
.cross-links { display: flex; gap: 5px; }
.cross-links a {
  padding: 3px 7px; border-radius: 4px; border: 1px solid rgba(64, 150, 186, 0.42); background: rgba(11, 40, 62, 0.72);
  color: #9de2f5; text-decoration: none; font-size: 11px;
  &:hover { border-color: #2ee6ff; color: #fff; }
}
.back-link { color: #b8eaff; text-decoration: none; font-size: 12px; margin-left: 3px; }

.process-tracker {
  display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 8px;
  padding: 6px 10px; border: 1px solid var(--panel-border); border-radius: 6px; background: rgba(7, 25, 44, 0.88);
}
.process-switch { display: flex; gap: 4px; }
.process-switch button {
  padding: 5px 8px; border: 1px solid rgba(49, 134, 170, 0.45); background: rgba(11, 35, 58, 0.8); color: #8cb7cd; border-radius: 4px; cursor: pointer; font-size: 11px; white-space: nowrap;
  &.active { border-color: #00e5ff; background: rgba(10, 94, 122, 0.55); color: #e8fcff; font-weight: 600; }
  &.live-track-btn.active { border-color: #34d399; background: rgba(16, 185, 129, 0.32); color: #6ee7b7; }
}
.five-steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(0, 1fr)); gap: 5px; }
.step-pill {
  padding: 5px 8px; border-radius: 4px; border: 1px solid rgba(58, 114, 146, 0.38); background: rgba(10, 28, 46, 0.72);
  font-size: 11px; color: #7da5bc; text-align: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: pointer;
  &:hover { border-color: #00e5ff; color: #ffffff; background: rgba(12, 66, 94, 0.72); }
  &.done { border-color: rgba(52, 211, 153, 0.55); color: #7aeab9; background: rgba(16, 66, 52, 0.45); }
  &.current { border-color: #00e5ff; color: #ffffff; background: linear-gradient(90deg, rgba(12, 108, 136, 0.72), rgba(14, 72, 108, 0.72)); font-weight: 600; box-shadow: 0 0 12px rgba(0, 229, 255, 0.25); }
}
.tracker-summary { display: flex; align-items: center; gap: 4px; text-align: right; font-size: 11px; color: #9de2f5; }
.tracker-summary b { font-size: 15px; color: #4ff0db; margin-left: 4px; }
.tracker-nav-btn {
  padding: 4px 7px; border-radius: 4px; border: 1px solid rgba(49, 134, 170, 0.45); background: rgba(11, 35, 58, 0.85); color: #cdefff; cursor: pointer; font-size: 11px;
  &:hover:not(:disabled) { border-color: #00e5ff; color: #ffffff; background: rgba(0, 180, 216, 0.35); }
  &:disabled { opacity: 0.38; cursor: not-allowed; }
  &.mode.single { border-color: #f5b84c; color: #f5b84c; background: rgba(245, 184, 76, 0.16); }
}

.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
.kpi-card, .panel { border: 1px solid var(--panel-border); background: var(--panel-bg); box-shadow: inset 0 1px 0 rgba(112, 211, 255, 0.05); border-radius: 5px; }
.kpi-card { padding: 7px 12px; display: grid; grid-template-columns: 1fr auto; align-items: center; }
.kpi-card span { color: #83a8bd; font-size: 11px; }
.kpi-card strong { grid-row: 2; font-size: clamp(18px, 1.25vw, 24px); color: #e5fbff; }
.kpi-card small { grid-column: 2; grid-row: 2; color: #77d6b2; font-size: 11px; }
.tone-amber small { color: #f6ba57; } .tone-violet strong { color: #d5c2ff; } .tone-blue strong { color: #84caff; } .tone-green strong { color: #7be6bb; }

.screen-grid { min-height: 0; display: grid; grid-template-columns: minmax(235px, 19%) minmax(460px, 1fr) minmax(245px, 20%); gap: 8px; }
.side-column { min-height: 0; display: grid; gap: 6px; }
.left-column { grid-template-rows: auto auto minmax(0, 1fr); }
.right-column { grid-template-rows: auto minmax(0, 1fr); }
.panel { padding: 8px 10px; min-width: 0; overflow-y: auto; }
.panel-heading { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid rgba(72, 143, 174, 0.28); padding-bottom: 6px; color: #d7f7ff; font-weight: 600; font-size: 12px; }
.panel-heading em { color: #63c9dc; font-style: normal; font-size: 11px; font-weight: 400; }

.safety-meter { display: flex; justify-content: space-between; align-items: baseline; padding: 6px 0 4px; font-size: 11px; color: #91b9ce; }
.safety-meter strong { font-size: 18px; color: #34d399; &.warn { color: #f5b84c; } }
.interlock-list { display: grid; gap: 4px; margin-bottom: 6px; }
.interlock-row { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #bfe6f7; }
.interlock-row i { font-style: normal; color: #47687f; &.pass { color: #34d399; } }
.alert-list { display: grid; gap: 4px; }
.alert-item { text-align: left; display: grid; grid-template-columns: 7px 1fr; gap: 6px; border: 0; border-left: 2px solid #f6b44d; background: rgba(18, 54, 76, 0.48); padding: 5px 7px; color: #d7eff8; cursor: pointer; }
.alert-item b { display: block; font-size: 11px; } .alert-item small { display: block; color: #8cb2c7; font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.level-dot { width: 6px; height: 6px; border-radius: 50%; margin-top: 4px; background: #f6b44d; }

.flow-chain { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 7px; }
.flow-node {
  padding: 3px 6px; border-radius: 3px; border: 1px solid rgba(64, 128, 160, 0.35); background: rgba(10, 30, 50, 0.72); font-size: 10px; color: #7fa7be;
  &.lit { border-color: #24cfe6; color: #dffaff; background: rgba(13, 86, 114, 0.55); }
}

.video-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 5px; margin-top: 5px; }
.video-tile {
  text-align: left; padding: 4px 7px; border: 1px solid rgba(52, 124, 158, 0.42); background: rgba(8, 28, 48, 0.85); border-radius: 4px; cursor: pointer; color: #d3f2ff;
  display: grid; gap: 1px;
  &:hover { border-color: #00e5ff; background: rgba(14, 54, 82, 0.9); }
  b { font-size: 10px; color: #00e5ff; } span { font-size: 10px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; } small { font-size: 9px; color: #67d9ac; }
}

.scene-panel { min-height: 0; position: relative; border: 1px solid rgba(42, 146, 185, 0.6); overflow: hidden; background: #030a17; border-radius: 5px; }
.scene-panel :deep(.cesium-viewer) { min-width: 0; min-height: 0; height: 100%; }
.scene-overlay { position: absolute; inset: 0; z-index: 3; display: grid; place-items: center; background: rgba(2, 10, 23, 0.84); color: #bceef7; font-size: 14px; }
.scene-overlay.warning { inset: auto 12px 42px; padding: 7px 10px; display: block; background: rgba(75, 42, 8, 0.9); color: #ffe6b2; }
.scene-caption { position: absolute; left: 10px; bottom: 8px; right: 10px; display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-left: 2px solid #15d4e6; background: linear-gradient(90deg, rgba(3, 15, 30, 0.9), rgba(3, 15, 30, 0.35)); color: #bdebf4; font-size: 11px; }
.live-dot { height: 7px; width: 7px; border-radius: 50%; background: #48e1ad; box-shadow: 0 0 8px #48e1ad; flex: none; }
.feed-note { color: #75adc4; margin-left: auto; }
.scene-action { border: 0; color: #bceffc; background: rgba(30, 135, 173, 0.36); padding: 3px 8px; cursor: pointer; border-radius: 3px; }

.selection-hud {
  position: absolute; top: 10px; right: 10px; width: 306px; padding: 10px; border: 1px solid rgba(0, 229, 255, 0.65); border-radius: 6px;
  background: rgba(5, 18, 36, 0.94); box-shadow: 0 8px 26px rgba(0, 0, 0, 0.5); z-index: 6; font-size: 11px;
}
.hud-head { display: flex; justify-content: space-between; align-items: start; border-bottom: 1px solid rgba(68, 148, 182, 0.32); padding-bottom: 6px; }
.hud-head b { display: block; font-size: 13px; color: #dffaff; } .hud-head small { color: #67d3ea; }
.hud-head button { border: 0; background: transparent; color: #9fc5da; cursor: pointer; font-size: 14px; }
.hud-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; padding: 8px 0; }
.hud-grid div { background: rgba(13, 40, 64, 0.72); padding: 4px 6px; border-radius: 4px; }
.hud-grid span { display: block; color: #7da5bc; font-size: 10px; } .hud-grid b { color: #e8fcff; font-size: 12px; }
.hud-ehms { display: flex; justify-content: space-between; align-items: center; padding-top: 6px; border-top: 1px solid rgba(68, 148, 182, 0.28); color: #9ee8c8; }
.hud-ehms a { color: #38e4ff; text-decoration: none; font-weight: 600; }
.hud-wms {
  margin-top: 6px; padding-top: 6px; border-top: 1px solid rgba(56, 189, 248, 0.32); color: #bae6fd;
  .hud-wms-top { display: flex; justify-content: space-between; align-items: center; a { color: #38e4ff; text-decoration: none; font-weight: 600; } }
  .hud-wms-sub { font-size: 10px; color: #7dd3fc; margin-top: 2px; }
}
.hud-params { margin-top: 6px; padding-top: 6px; border-top: 1px dashed rgba(68, 148, 182, 0.28); display: grid; gap: 3px; }
.hud-param-row { display: flex; justify-content: space-between; gap: 8px; font-size: 10px; span { color: #7da5bc; } b { color: #dffaff; text-align: right; } }
.hud-cross-jumps {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; margin-top: 7px; padding-top: 6px; border-top: 1px solid rgba(68, 148, 182, 0.28);
  a {
    text-align: center; padding: 3px 2px; border: 1px solid rgba(0, 229, 255, 0.38); border-radius: 4px;
    background: rgba(11, 40, 62, 0.8); color: #7ef4ff; font-size: 10px; text-decoration: none; font-weight: 600;
    &:hover { background: #0b6075; color: #ffffff; }
  }
}

.video-inspect-modal, .vendor-overview-modal, .req-matrix-modal {
  position: absolute; left: 14px; top: 14px; right: 14px; z-index: 12;
  background: rgba(4, 16, 32, 0.96); border: 1px solid #00e5ff; border-radius: 8px;
  box-shadow: 0 14px 38px rgba(0, 0, 0, 0.65); padding: 12px 14px; font-size: 11px;
}
.vim-head {
  display: flex; justify-content: space-between; align-items: start; border-bottom: 1px solid rgba(58, 138, 174, 0.35); padding-bottom: 8px;
  b { display: block; font-size: 13px; color: #e4fbff; } small { color: #67d3ea; font-size: 10px; }
  .vim-close { border: 0; background: transparent; color: #9fc5da; cursor: pointer; font-size: 15px; }
}
.req-table-wrap { max-height: 295px; overflow-y: auto; margin-top: 8px; }
.req-table {
  width: 100%; border-collapse: collapse; font-size: 11px;
  th { text-align: left; padding: 6px 8px; background: rgba(10, 34, 58, 0.95); color: #7ef4ff; border-bottom: 1px solid rgba(58, 138, 174, 0.4); position: sticky; top: 0; }
  td { padding: 6px 8px; border-bottom: 1px solid rgba(58, 138, 174, 0.22); vertical-align: middle; color: #d9f1ff; }
  .fn-code { font-weight: 700; color: #00e5ff; white-space: nowrap; }
  .fn-name { font-weight: 600; }
  .req-code { color: #34d399; font-weight: 600; white-space: nowrap; }
  .collab-badge { display: inline-block; padding: 1px 5px; border-radius: 3px; background: rgba(0, 229, 255, 0.18); color: #78e3fd; font-size: 10px; font-weight: 600; }
  .partner-sub { display: block; font-size: 10px; color: #8cb7cd; margin-top: 2px; }
  .st-col { color: #34d399; font-weight: 600; white-space: nowrap; }
  .verify-act-btn {
    padding: 4px 8px; border: 1px solid #00e5ff; border-radius: 4px; background: #0b6075;
    color: #ffffff; font-size: 10px; font-weight: 600; cursor: pointer; white-space: nowrap;
    &:hover { background: #1485a3; }
  }
}

.vim-body { display: grid; grid-template-columns: 1.15fr 1fr; gap: 12px; margin-top: 10px; }
.vim-viewport {
  position: relative; min-height: 190px; border: 1px solid rgba(0, 229, 255, 0.45); border-radius: 6px;
  background: radial-gradient(circle at 50% 50%, #0b2944, #030d1a); overflow: hidden; display: flex; align-items: center; justify-content: center;
}
.vim-canvas-img { width: 100%; height: 100%; object-fit: cover; opacity: 0.9; }
.vim-osd-top {
  position: absolute; top: 8px; left: 8px; right: 8px; display: flex; justify-content: space-between;
  padding: 3px 8px; background: rgba(2, 12, 24, 0.78); border-radius: 4px; color: #67e6b2; font-size: 10px; font-family: monospace;
}
.vim-ai-box {
  position: absolute; left: 16px; right: 16px; bottom: 12px; padding: 6px 10px;
  border: 1px dashed #00e5ff; background: rgba(6, 48, 72, 0.82); border-radius: 4px; color: #e8fcff; font-size: 11px;
}
.vim-plc { display: flex; flex-direction: column; gap: 6px; }
.vim-plc-title { display: flex; justify-content: space-between; align-items: center; b { color: #7ef4ff; font-size: 12px; } em { font-style: normal; color: #34d399; font-size: 10px; } }
.vim-plc-sub { margin: 0; color: #8cb7cd; font-size: 10px; }
.vim-sig-list { display: grid; gap: 4px; }
.vim-sig-row {
  display: flex; justify-content: space-between; gap: 8px; padding: 3px 6px; background: rgba(11, 36, 58, 0.68); border-radius: 4px; font-size: 10px;
  span { color: #9ec0d4; } b { color: #4ff0db; }
}
.vim-actions {
  display: flex; gap: 8px; margin-top: auto; padding-top: 6px;
  button, .s1-jump-btn {
    flex: 1; text-align: center; padding: 6px 8px; border-radius: 4px; border: 1px solid #296b85;
    background: #102e47; color: #d5faff; text-decoration: none; cursor: pointer; font-size: 11px;
  }
  .s1-jump-btn { border-color: #00e5ff; background: #0b6075; font-weight: 600; }
}
.vendor-cards-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 10px; }
.v-card {
  background: rgba(9, 30, 50, 0.85); border: 1px solid rgba(58, 138, 174, 0.38); border-radius: 6px; padding: 8px 10px; display: grid; gap: 4px;
  .v-card-head { display: flex; justify-content: space-between; b { color: #e4fbff; font-size: 11px; } em { font-style: normal; color: #00e5ff; font-size: 10px; } }
  p { margin: 0 0 4px; color: #7da5bc; font-size: 10px; }
}

.track-switch-tabs {
  display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin-top: 6px;
  button {
    padding: 4px; border: 1px solid rgba(49, 134, 170, 0.45); border-radius: 4px;
    background: rgba(11, 35, 58, 0.8); color: #8cb7cd; font-size: 10px; cursor: pointer;
    &.active { border-color: #00e5ff; background: #0b6075; color: #ffffff; font-weight: 600; }
    &:last-child.active { border-color: #34d399; background: rgba(16, 185, 129, 0.28); color: #6ee7b7; }
  }
}
.live-task-cards {
  display: grid; gap: 4px; margin-top: 6px;
  .live-task-card {
    text-align: left; padding: 5px 7px; border: 1px solid rgba(49, 134, 170, 0.45); border-radius: 4px;
    background: rgba(11, 35, 58, 0.72); color: #d9f1ff; cursor: pointer;
    .ltc-head { display: flex; justify-content: space-between; font-size: 10px; b { color: #7ef4ff; } em { font-style: normal; color: #34d399; font-size: 9px; } }
    .ltc-sub { font-size: 9px; color: #8cb7cd; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    &.active { border-color: #34d399; background: rgba(16, 185, 129, 0.18); }
    &.wait { border-left: 3px solid #f5b84c; .ltc-head em { color: #f5b84c; font-weight: 700; } }
  }
}
.wait-reason-note { margin: 5px 0 0 !important; min-height: 0 !important; color: #f5b84c !important; font-size: 10px !important; font-weight: 600; }
.s1-live-jump {
  display: block; text-align: center; margin-top: 6px; padding: 5px 6px; border-radius: 4px;
  border: 1px solid #34d399; background: rgba(16, 185, 129, 0.24); color: #6ee7b7; font-size: 10px; font-weight: 600; text-decoration: none;
  &:hover { background: rgba(16, 185, 129, 0.4); color: #ffffff; }
}

.task-panel h2 { margin: 8px 0 4px; font-size: 13px; color: #dff9ff; }
.task-panel p { min-height: 32px; margin: 0; color: #8eafc1; font-size: 11px; line-height: 1.4; }
.progress-track { height: 6px; margin-top: 8px; background: #102c43; overflow: hidden; border-radius: 3px; }
.progress-track span { display: block; height: 100%; background: linear-gradient(90deg, #13bcd0, #68f0e5); transition: width 0.2s linear; }
.task-meta { display: flex; justify-content: space-between; margin-top: 6px; color: #78a4b8; font-size: 10px; }
.demo-controls { display: flex; gap: 6px; margin-top: 8px; }
.demo-controls button, .alert-modal button { flex: 1; border: 1px solid #296b85; color: #bdeefa; background: #102e47; padding: 6px; cursor: pointer; border-radius: 4px; font-size: 11px; }
.demo-controls .primary, .alert-modal .primary { border-color: #25cbd9; background: #0b6075; color: #edffff; font-weight: 600; }

.ehms-panel { overflow-y: auto; }
.ehms-row { padding: 6px 0; border-bottom: 1px solid rgba(69, 134, 164, 0.18); cursor: pointer; }
.ehms-top { display: flex; align-items: center; gap: 6px; font-size: 11px; }
.ehms-top em { flex: 1; font-style: normal; color: #9ec0d4; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ehms-top strong { font-size: 11px; &.healthy { color: #34d399; } &.attention { color: #f5b84c; } &.risk { color: #ef4444; } }
.ehms-sub { margin-top: 2px; padding-left: 12px; font-size: 10px; color: #6e94aa; }
.status-led { width: 6px; height: 6px; border-radius: 50%; background: #50ddac; flex: none; &.standby { background: #f6b44d; } &.fault, &.offline { background: #fa5e68; } &.maintenance { background: #2b6fe0; } }

.chart-row { padding: 0; min-height: 0; }
.trend-chart { width: 100%; height: 122px; }
.screen-footer { display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 10px; color: #668ca2; font-size: 10px; }
.theme-tabs { display: flex; gap: 4px; }
.theme-tabs button, .carousel-control { border: 1px solid transparent; background: transparent; color: #7fa5b9; font-size: 11px; padding: 4px 9px; cursor: pointer; }
.theme-tabs button.active { color: #d9fbff; border-color: #27bfd6; background: rgba(20, 114, 139, 0.26); border-radius: 4px; }
.carousel-control { border-color: rgba(66, 160, 190, 0.42); border-radius: 4px; }

.alert-modal { z-index: 20; position: absolute; left: 20%; top: 22%; width: 60%; box-sizing: border-box; padding: 24px 28px; border: 1px solid #f45b65; background: rgba(35, 10, 22, 0.96); text-align: center; border-radius: 8px; box-shadow: 0 18px 48px rgba(0, 0, 0, 0.72); }
.alert-modal-actions { display: flex; gap: 8px; margin-top: 16px; justify-content: center; align-items: center; }
.modal-link-btn { flex: 1; border: 1px solid #00e5ff; color: #e8fcff; background: rgba(11, 96, 117, 0.85); padding: 6px 8px; border-radius: 4px; font-size: 11px; text-decoration: none; font-weight: 600; }
.receipt-tags { display: flex; justify-content: center; gap: 8px; margin-top: 12px; }
.receipt-tags b { padding: 3px 8px; border-radius: 4px; background: rgba(244, 91, 101, 0.25); color: #ffb3b8; font-size: 11px; }
.critical-toast { z-index: 15; position: absolute; right: 24px; top: 110px; border: 1px solid #f05c64; background: #501723; color: #ffe5e7; padding: 8px 12px; cursor: pointer; border-radius: 4px; }
</style>
