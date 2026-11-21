<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import type { LightingPreset } from '@/components/cesium/cesiumInit'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { filterByCategory } from '@/components/cesium/layerManager'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import {
  buildDefaultSafetySnapshotV6,
  buildDefaultStorageSnapshotV6,
  formatProvenanceBadge,
  type DeviceHealthOverlayItemV6,
  type SurveillanceCameraStateV6,
} from '@/services/twinIntegrationContracts'
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
  getStorageSummaryForTarget,
  type DataSourceMode,
  type DtsFeedStatus,
  type LiveTaskNodeItem,
  type ProcessTrackMode,
  type RegionCode,
  type RequirementFunctionItem,
} from '@/services/dtsDataService'
import { useAlertStore } from '@/stores/alert'
import { useAppStore } from '@/stores/app'
import { buildDefaultDeviceSnapshot, useDeviceStore } from '@/stores/device'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { CRANE_UNLOAD_SEQUENCE } from '@/twin3d/animation'
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
import { cadGeoJsonToWgs84, cadToLonLat } from '@/twin3d/geo'
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
import { useAnimation } from '@/twin3d/useAnimation'

const router = useRouter()
const appStore = useAppStore()
const deviceStore = useDeviceStore()
const alertStore = useAlertStore()
const crossLinks = getCrossSystemLinks()

let viewer: Cesium.Viewer | null = null
let yardDataSource: Cesium.GeoJsonDataSource | null = null
let twin3d: Twin3dController | null = null
let phase6Layers: Phase6LayerController | null = null
let dataService: DtsDataService | null = null
let demoController: ProcessControllerHandle | null = null
let phase6Timer: ReturnType<typeof setInterval> | null = null
let diagTimer: ReturnType<typeof setInterval> | null = null
let lastFocusTag = ''
let userCameraHold = false
let smoothedTrackState: (Required<Pick<CameraTrackTarget, 'cadX' | 'cadY' | 'cadZ' | 'headingDeg' | 'pitchDeg' | 'range'>> & {
  tag: string
}) | null = null
let removeCanvasInteractionListeners: (() => void) | null = null

const layers = ref({ track: true, area: true, cargo: false, health: true, personnel: false, planned: true })
const detailVisible = ref(false)
const vendorDrawerOpen = ref(false)
const reqMatrixOpen = ref(false)
const activeCamera = ref<SurveillanceCameraStateV6 | null>(null)
const cinemaTourRunning = ref(false)
let cinemaTourTimer: ReturnType<typeof setInterval> | null = null
let cinemaTourIdx = 0
const CINEMA_TOUR_STOPS: Array<{ title: string; target: CameraFocusTarget }> = [
  { title: '① 全站 1.27km 鸟瞰全景（给大连大.dwg V6）', target: { cadX: 3920, cadY: 1625, cadZ: 12, headingDeg: 18, pitchDeg: -30, range: 640, durationSec: 2.2 } },
  { title: '② 东大门 40m 雨棚、货运综合楼与光伏停车场', target: { cadX: 4465, cadY: 1645, cadZ: 8, headingDeg: -34, pitchDeg: -22, range: 230, durationSec: 2.0 } },
  { title: '③ 散料区 40 座 3000T 筒仓群与 4 组 8 台移动翻箱机', target: { cadX: 3860, cadY: 1555, cadZ: 18, headingDeg: -22, pitchDeg: -24, range: 240, durationSec: 2.0 } },
  { title: '④ 23,882㎡ 钢筋库 01~13 跨、外伸月台轨道梁与 40t 门吊', target: { cadX: 3745, cadY: 1672, cadZ: 12, headingDeg: -18, pitchDeg: -22, range: 215, durationSec: 2.0 } },
  { title: '⑤ DF7G 内燃调车机车牵引专列与 100t 轨道衡', target: { cadX: 3630, cadY: 1590, cadZ: 6, headingDeg: 28, pitchDeg: -20, range: 170, durationSec: 2.0 } },
]
const cinemaTourLabel = ref('')
const activeRegion = ref<RegionCode>('SHEN_TIE')
const steelSubMode = ref<SteelSubMode>('rebar_magnet')
const processTrackMode = ref<ProcessTrackMode>('f_d06_sim')
const activeLiveTaskId = ref(DEFAULT_LIVE_TASKS[0].taskId)
const quickSiloId = ref('SILO-01')
const selectedInfo = reactive<SelectedInfo>({
  id: '',
  name: '',
  type: '',
  statusName: '',
  params: {},
})

const animRunning = ref(false)
const simRunning = ref(true)
const activeProcess = ref<ProcessKind>('bulk')
const singleStepMode = ref(false)
const panelOpen = reactive({
  left: false,
  device: false,
  stock: false,
  topBar: true,
})
const anySidePanelOpen = computed(() => panelOpen.left || panelOpen.device || panelOpen.stock)
function toggleAllPanels() {
  const next = !anySidePanelOpen.value
  panelOpen.left = next
  panelOpen.device = next
  panelOpen.stock = next
}
const dataMode = ref<DataSourceMode>(DEFAULT_DATA_MODE)
const feedStatus = reactive<DtsFeedStatus>({
  mode: DEFAULT_DATA_MODE,
  quality: DEFAULT_DATA_MODE === 'offline_sim' ? 'SIM' : 'GOOD',
  sourceLabel: DEFAULT_DATA_MODE === 'offline_sim' ? '离线工艺仿真 (.eddx)' : '正在连接只读底座 (/api/v1/dts)',
  lastSyncAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
  errorNote: '',
  liveTasks: structuredClone(DEFAULT_LIVE_TASKS),
  storageLocations: buildDefaultStorageSnapshotV6('SHEN_TIE'),
  safetySnapshot: buildDefaultSafetySnapshotV6('SHEN_TIE'),
})
const allSurveillanceCameras = computed<SurveillanceCameraStateV6[]>(
  () => feedStatus.safetySnapshot?.cameras ?? [],
)
const liveStorageCount = computed(
  () => (feedStatus.storageLocations ?? []).filter((s) => s.dataSource === 'LIVE_S2_WMS').length,
)
const liveSafetyCount = computed(
  () => (feedStatus.safetySnapshot?.fences ?? []).filter((f) => f.dataSource === 'LIVE_S4_SCS').length,
)

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
  title: '就绪：一键启动散料或钢材全流程演示',
  narration: '基于 给大连大.dwg 与 2026.11.14 工艺细化图',
  progress: 0,
  payloadTons: 0,
  safetyDistanceM: 28.5,
  interlockChecks: [
    { label: '防碰撞测距安全 (>15m)', passed: true },
    { label: '电子围栏与接地待命', passed: true },
  ],
})

const demoPrimaryLabel = computed(() =>
  demoState.running ? '暂停演示' : demoState.completed ? '重新演示' : demoState.progress > 0 ? '继续演示' : '开始演示',
)
const deviceSummaryCount = computed(() => Object.keys(deviceStore.devices).length)
const fivePhases = computed(() => (activeProcess.value === 'bulk' ? BULK_FIVE_PHASES : STEEL_FIVE_PHASES))
const liveTaskList = computed(() =>
  feedStatus.liveTasks && feedStatus.liveTasks.length > 0 ? feedStatus.liveTasks : DEFAULT_LIVE_TASKS,
)
const activeLiveTask = computed(
  () => liveTaskList.value.find((t) => t.taskId === activeLiveTaskId.value) ?? liveTaskList.value[0],
)
const selectedDeviceState = computed(() => (selectedInfo.id ? deviceStore.devices[selectedInfo.id] : undefined))
const selectedStorageSummary = computed(() =>
  getStorageSummaryForTarget(selectedInfo.id, feedStatus.storageLocations),
)

const selectedPsmsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S1')?.url ?? 'https://psms.bproject.de5.net/production-dispatch/'
  return buildPsmsTaskUrl(base, {
    taskId: activeLiveTask.value?.taskId,
    planNo: activeLiveTask.value?.planNo,
    deviceId: selectedInfo.id || undefined,
    step: demoState.stepIndex + 1,
  })
})
const selectedWmsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S2')?.url ?? 'https://wms.bproject.de5.net/'
  return buildWmsLocationUrl(base, selectedInfo.id || undefined, selectedStorageSummary.value?.batchNo)
})
const selectedEhmsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S3')?.url ?? 'https://ehms.bproject.de5.net/ehm/'
  return buildEhmsAssetUrl(base, selectedInfo.id || undefined)
})
const selectedScsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S4')?.url ?? 'https://scs.bproject.de5.net/'
  return buildScsAlertUrl(base, { deviceId: selectedInfo.id || undefined })
})

const showDiagnostics = ref(new URL(window.location.href).searchParams.has('diag'))
const diag = reactive({
  webgl: true,
  viewer: false,
  cadStatus: '未加载',
  cadEntities: 0,
  deviceEntities: 0,
  camera: '',
  globeShow: false,
})

function syncDeviceHealthOverlay() {
  if (!twin3d) return
  const items: DeviceHealthOverlayItemV6[] = Object.values(deviceStore.devices).map((d) => {
    const score = d.ehms?.healthScore ?? 92
    const level: DeviceHealthOverlayItemV6['healthLevel'] =
      d.status === 'fault' || score < 65 ? 'critical' : score < 76 ? 'risk' : score < 86 ? 'attention' : 'healthy'
    return {
      deviceId: d.id,
      deviceName: d.name,
      status: d.status,
      healthScore: score,
      healthLevel: level,
      activeAlarmCount: alertStore.alerts.filter((a) => !a.handled && a.targetId === d.id).length,
      dataSource: feedStatus.quality === 'GOOD' ? 'LIVE_S3_EHMS' : 'FALLBACK_SIM',
    }
  })
  twin3d.syncHealthOverlay(items)
}

watch(
  () => deviceStore.devices,
  (map) => {
    for (const [id, d] of Object.entries(map)) {
      twin3d?.setStatus(id, d.status)
    }
    syncDeviceHealthOverlay()
    if (selectedInfo.id && map[selectedInfo.id]) {
      selectedInfo.statusName = deviceStore.getStatusName(selectedInfo.id)
    }
  },
  { deep: true },
)

const CR02_DEF = SITE_LAYOUT_V4.rebar.gantries[1]
const anim = useAnimation(CRANE_UNLOAD_SEQUENCE, {
  initialState: { gantry: [CR02_DEF.cadX, CR02_DEF.cadY], trolleyOffset: 0, hoistHeight: 16 },
  onUpdate: (state) => {
    twin3d?.setCranePose('CR-02', {
      gantryX: state.gantry[0],
      gantryY: state.gantry[1],
      trolleyOffset: state.trolleyOffset,
      hoistHeight: state.hoistHeight,
      payloadTons: 28.5,
    })
  },
})

function startAnim() {
  anim.start()
}

function stopAnim() {
  anim.stop()
}

function setCategoryVisible(category: string, show: boolean) {
  if (!yardDataSource) return
  void filterByCategory(yardDataSource, category, show)
}

function focusCadExtent(v: Cesium.Viewer) {
  flyToCameraPreset(v, 'overview')
}

function applyUrlDeepLinkParams() {
  const sp = new URL(window.location.href).searchParams
  const processParam = sp.get('process')
  const subParam = sp.get('sub')
  const stepParam = sp.get('step')
  const devParam = sp.get('deviceId') || sp.get('siloId') || sp.get('locationCode')
  const taskParam = sp.get('taskId') || sp.get('planNo')
  const fnParam = sp.get('fn')

  if (processParam === 'bulk' || processParam === 'steel') {
    activeProcess.value = processParam
    if (subParam === 'rebar_magnet' || subParam === 'coil_chook') {
      steelSubMode.value = subParam
    }
    rebuildDemoController(activeProcess.value)
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
    setTimeout(() => selectDeviceFromList(devParam), 350)
  }
  if (fnParam) {
    const matchedFn = REQUIREMENT_FUNCTION_MATRIX.find((f) => f.fnId === fnParam.toUpperCase())
    if (matchedFn) {
      setTimeout(() => triggerRequirementFunction(matchedFn), 400)
    }
  }
}

function onViewerReady(v: Cesium.Viewer) {
  viewer = v
  setupCameraInteraction(v)
  removeCanvasInteractionListeners?.()
  const canvas = v.scene.canvas
  const markManualCamera = () => {
    userCameraHold = true
    smoothedTrackState = null
    if (cinemaTourRunning.value) stopCinemaTour()
    releaseCameraTrack(v)
    v.camera.cancelFlight()
  }
  canvas.addEventListener('pointerdown', markManualCamera, { passive: true })
  canvas.addEventListener('wheel', markManualCamera, { passive: true })
  removeCanvasInteractionListeners = () => {
    canvas.removeEventListener('pointerdown', markManualCamera)
    canvas.removeEventListener('wheel', markManualCamera)
  }

  diag.viewer = true
  diag.globeShow = v.scene.globe.show
  diag.cadStatus = '加载 CAD 底图中'

  const sp = new URL(window.location.href).searchParams
  const hasDeepLinkFocus = Boolean(
    sp.get('step') ||
      sp.get('deviceId') ||
      sp.get('siloId') ||
      sp.get('locationCode') ||
      sp.get('taskId') ||
      sp.get('planNo') ||
      sp.get('fn'),
  )

  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) =>
      Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
        stroke: Cesium.Color.fromCssColorString('#2A88A8').withAlpha(0.12),
        strokeWidth: 1,
        fill: Cesium.Color.fromCssColorString('#1D5D9B').withAlpha(0.006),
        clampToGround: false,
        markerSize: 0,
      }),
    )
    .then((ds) => {
      v.dataSources.add(ds)
      yardDataSource = ds
      diag.cadEntities = ds.entities.values.length
      setCategoryVisible('track', layers.value.track)
      setCategoryVisible('area', layers.value.area)
      diag.cadStatus = `已加载 ${diag.cadEntities} 个 CAD 实体`
      if (!hasDeepLinkFocus && !demoState.running && demoState.progress === 0) {
        focusCadExtent(v)
      }
    })
    .catch((err) => {
      diag.cadStatus = `加载失败: ${err instanceof Error ? err.message : String(err)}`
    })

  const stable = new URL(window.location.href).searchParams.get('stable')
  const skipDevices = stable === '1'
  const skipAnim = stable === '1' || stable === '2'

  if (!skipDevices) {
    seedDemoDevices()
    twin3d = new Twin3dController(v, {
      onSelect: (info) => {
        if (info) {
          Object.assign(selectedInfo, info)
          detailVisible.value = true
        } else {
          detailVisible.value = false
        }
      },
      onSelectCamera: (cam) => {
        activeCamera.value = cam
      },
    })
    diag.deviceEntities = twin3d.getStatusSummary().total
    twin3d.setStorageOverlayVisible(layers.value.cargo)
    twin3d.setHealthOverlayVisible(layers.value.health)
    twin3d.setSafetyOverlayVisible(layers.value.personnel)
    twin3d.syncStorageOverlay(feedStatus.storageLocations ?? buildDefaultStorageSnapshotV6(activeRegion.value))
    twin3d.syncSafetyOverlay(feedStatus.safetySnapshot ?? buildDefaultSafetySnapshotV6(activeRegion.value))
    syncDeviceHealthOverlay()
    phase6Layers = new Phase6LayerController(v)
    phase6Layers.syncCargo(cargoItems.value)
    phase6Layers.syncPersonnel(personnelItems.value)
    phase6Layers.setCargoVisible(false)
    phase6Layers.setPersonnelVisible(false)
    rebuildDemoController(activeProcess.value)
  }

  if (!skipAnim) {
    dataService = new DtsDataService(
      deviceStore,
      alertStore,
      (s) => {
        Object.assign(feedStatus, s)
        if (s.storageLocations) twin3d?.syncStorageOverlay(s.storageLocations)
        if (s.safetySnapshot) twin3d?.syncSafetyOverlay(s.safetySnapshot)
        syncDeviceHealthOverlay()
      },
      activeRegion.value,
    )
    activeRegion.value = dataService.getRegionId()
    twin3d?.setVehicleRouteAnimation(true)
    dataService.start(dataMode.value, 5000)
  }

  applyUrlDeepLinkParams()

  if (showDiagnostics.value) {
    diagTimer = setInterval(() => {
      const carto = v.camera.positionCartographic
      diag.camera = `${Cesium.Math.toDegrees(carto.longitude).toFixed(4)}, ${Cesium.Math.toDegrees(carto.latitude).toFixed(4)} H:${Math.round(carto.height)}m`
    }, 800)
  }

  const win = window as unknown as Record<string, unknown>
  win.__dt_viewer = v
  win.__dt_twin3d = twin3d
  win.__Cesium = Cesium
  win.__dt_deviceStore = deviceStore
  win.__dt_alertStore = alertStore
  void cadToLonLat
}

function seedDemoDevices() {
  deviceStore.batchUpdate(
    MOCK_EQUIPMENT.map((item) => buildDefaultDeviceSnapshot(item.id, item.name, item.status)),
  )
}

function createRuntime(): UnifiedProcessRuntime {
  return {
    setCamera: (id) => {
      lastFocusTag = ''
      userCameraHold = false
      smoothedTrackState = null
      if (viewer) flyToCameraPreset(viewer, id)
    },
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

function rebuildDemoController(kind: ProcessKind) {
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
    demoController?.pause()
    if (activeLiveTask.value) {
      selectLiveTaskNode(activeLiveTask.value)
    }
  }
}

function selectLiveTaskNode(task: LiveTaskNodeItem) {
  processTrackMode.value = 'f_d07_live'
  activeLiveTaskId.value = task.taskId
  if (activeProcess.value !== task.processKind) {
    activeProcess.value = task.processKind
  }
  if (task.processKind === 'steel') {
    steelSubMode.value = task.steelSubMode
  }
  rebuildDemoController(activeProcess.value)
  demoController?.setSingleStep(true)
  demoController?.jumpToStep(task.currentStepIndex, false)
  demoState.title = `[F-D07 真实任务节点 · ${task.qualityCode}] ${task.planNo}`
  demoState.narration = task.handoverWaitReason
    ? `⚠️ 交接等待：${task.handoverWaitReason}`
    : `${task.cargoName}（${task.tonnage}t）· 关联装备：${task.assignedDevices.join(' / ')}`
  demoState.progress = Math.min(100, Math.round(((task.currentStepIndex + 1) / 5) * 100))
  selectDeviceFromList(task.focusDeviceId)
}

function triggerRequirementFunction(item: RequirementFunctionItem) {
  reqMatrixOpen.value = false
  if (item.fnId === 'F-D01') {
    onResetView()
  } else if (item.fnId === 'F-D02') {
    void router.push('/calibration')
  } else if (item.fnId === 'F-D03') {
    selectDeviceFromList('CR-02')
  } else if (item.fnId === 'F-D04') {
    quickSiloId.value = 'SILO-01'
    selectDeviceFromList('SILO-01')
  } else if (item.fnId === 'F-D05') {
    selectDeviceFromList('TIPPER-01')
    vendorDrawerOpen.value = true
  } else if (item.fnId === 'F-D06') {
    processTrackMode.value = 'f_d06_sim'
    singleStepMode.value = true
    jumpToProcessStep(1)
  } else if (item.fnId === 'F-D07') {
    const waitTask = liveTaskList.value.find((t) => t.stepStatus === 'HANDOVER_WAIT') ?? liveTaskList.value[0]
    if (waitTask) selectLiveTaskNode(waitTask)
  } else if (item.fnId === 'F-D08') {
    vendorDrawerOpen.value = true
  }
}

function toggleSingleStepMode() {
  singleStepMode.value = !singleStepMode.value
  demoController?.setSingleStep(singleStepMode.value)
}

function prepareManualDemo() {
  processTrackMode.value = 'f_d06_sim'
  dataService?.stop()
  stopPhase6Simulation()
  simRunning.value = false
  stopAnim()
  animRunning.value = false
  twin3d?.setVehicleRouteAnimation(false)
  if (!demoController) rebuildDemoController(activeProcess.value)
}

function jumpToProcessStep(stepIndex: number) {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  prepareManualDemo()
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.jumpToStep(stepIndex, true)
}

function prevProcessStep() {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  prepareManualDemo()
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.prevStep(true)
}

function nextProcessStep() {
  userCameraHold = false
  lastFocusTag = ''
  smoothedTrackState = null
  if (viewer) releaseCameraTrack(viewer)
  prepareManualDemo()
  demoController?.setSingleStep(singleStepMode.value)
  demoController?.nextStep(true)
}

function switchSteelSubMode(mode: SteelSubMode) {
  if (steelSubMode.value === mode) return
  steelSubMode.value = mode
  if (activeProcess.value === 'steel') {
    resetDemo()
    onCameraPreset('rebar')
  }
}

function onRegionChange(nextRegion: RegionCode) {
  activeRegion.value = nextRegion
  dataService?.setRegionId(nextRegion)
}

function selectDeviceFromList(id: string) {
  const info = twin3d?.focusEquipment(id)
  if (info) {
    Object.assign(selectedInfo, info)
    detailVisible.value = true
  }
}

function switchProcess(kind: ProcessKind) {
  if (activeProcess.value === kind) return
  activeProcess.value = kind
  resetDemo()
  onCameraPreset(kind === 'bulk' ? 'tipper' : 'gantry')
}

function toggleDataMode() {
  const next: DataSourceMode = dataMode.value === 'offline_sim' ? 'realtime_readonly' : 'offline_sim'
  dataMode.value = next
  dataService?.setMode(next)
  if (!simRunning.value && next === 'realtime_readonly') {
    simRunning.value = true
    dataService?.start('realtime_readonly', 5000)
  }
}

function stopPhase6Simulation() {
  if (phase6Timer) clearInterval(phase6Timer)
  phase6Timer = null
}

function resetDemo() {
  dataService?.stop()
  stopPhase6Simulation()
  simRunning.value = false
  stopAnim()
  animRunning.value = false
  twin3d?.setVehicleRouteAnimation(false)
  seedDemoDevices()
  cargoItems.value = structuredClone(MOCK_CARGO)
  personnelItems.value = structuredClone(MOCK_PERSONNEL)
  operationTasks.value = structuredClone(MOCK_OPERATIONS)
  phase6Layers?.syncCargo(cargoItems.value)
  phase6Layers?.syncPersonnel(personnelItems.value)
  rebuildDemoController(activeProcess.value)
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

function onDemoPrimary() {
  if (demoState.running) {
    pauseDemo()
  } else if (demoState.completed || (demoState.progress === 0 && demoState.stepIndex === 0)) {
    startDemo()
  } else {
    userCameraHold = false
    lastFocusTag = ''
    smoothedTrackState = null
    if (viewer) releaseCameraTrack(viewer)
    prepareManualDemo()
    demoController?.setSingleStep(singleStepMode.value)
    demoController?.start()
  }
}

function pauseDemo() {
  demoController?.pause()
}

function startPhase6Simulation() {
  if (phase6Timer) return
  phase6Timer = setInterval(() => {
    const active = operationTasks.value.find((task) => task.status === 'running')
    if (active) {
      active.progress = Math.min(100, active.progress + 3)
      if (active.progress === 100) active.status = 'done'
    }
    phase6Layers?.syncCargo(cargoItems.value)
    phase6Layers?.syncPersonnel(personnelItems.value)
  }, 4500)
}

function toggleLayer(category: 'track' | 'area' | 'cargo' | 'health' | 'personnel' | 'planned') {
  layers.value[category] = !layers.value[category]
  if (category === 'cargo') twin3d?.setStorageOverlayVisible(layers.value.cargo)
  else if (category === 'health') twin3d?.setHealthOverlayVisible(layers.value.health)
  else if (category === 'personnel') twin3d?.setSafetyOverlayVisible(layers.value.personnel)
  else if (category === 'planned') twin3d?.setPlannedZonesVisible(layers.value.planned)
  else setCategoryVisible(category, layers.value[category])
}

function onLightingChange(preset: LightingPreset) {
  twin3d?.setLightingAccentMode(preset)
}

function stopCinemaTour() {
  cinemaTourRunning.value = false
  cinemaTourLabel.value = ''
  if (cinemaTourTimer) {
    clearInterval(cinemaTourTimer)
    cinemaTourTimer = null
  }
}

function runCinemaStop() {
  if (!viewer) return
  const stop = CINEMA_TOUR_STOPS[cinemaTourIdx % CINEMA_TOUR_STOPS.length]
  cinemaTourIdx = (cinemaTourIdx + 1) % CINEMA_TOUR_STOPS.length
  cinemaTourLabel.value = stop.title
  focusCadPoint(viewer, stop.target)
}

function toggleCinemaTour() {
  if (cinemaTourRunning.value) {
    stopCinemaTour()
    return
  }
  demoController?.pause()
  userCameraHold = false
  cinemaTourRunning.value = true
  cinemaTourIdx = 0
  runCinemaStop()
  cinemaTourTimer = setInterval(runCinemaStop, 6500)
}

function openCameraModal(cam: SurveillanceCameraStateV6) {
  activeCamera.value = cam
  if (viewer) {
    focusCadPoint(viewer, { cadX: cam.cadX, cadY: cam.cadY, cadZ: cam.cadZ, headingDeg: -cam.headingDeg + 90, pitchDeg: -24, range: 78, durationSec: 1.1 })
  }
}

function onResetView() {
  lastFocusTag = ''
  userCameraHold = false
  if (viewer) flyToCameraPreset(viewer, 'overview')
}

function onCameraPreset(id: CameraPresetId) {
  lastFocusTag = ''
  userCameraHold = false
  if (viewer) flyToCameraPreset(viewer, id)
}

function toggleAnim() {
  if (!animRunning.value) demoController?.pause()
  animRunning.value = !animRunning.value
  if (animRunning.value) startAnim()
  else stopAnim()
}

function toggleSim() {
  simRunning.value = !simRunning.value
  if (simRunning.value) {
    demoController?.pause()
    twin3d?.setVehicleRouteAnimation(true)
    dataService?.start(dataMode.value, 4500)
    startPhase6Simulation()
  } else {
    dataService?.stop()
    twin3d?.setVehicleRouteAnimation(false)
    stopPhase6Simulation()
  }
}

function closeDetail() {
  detailVisible.value = false
}

onBeforeUnmount(() => {
  stopCinemaTour()
  if (diagTimer) clearInterval(diagTimer)
  removeCanvasInteractionListeners?.()
  removeCanvasInteractionListeners = null
  stopAnim()
  dataService?.stop()
  stopPhase6Simulation()
  demoController?.destroy()
  phase6Layers?.destroy()
  twin3d?.destroy()
  viewer = null
  yardDataSource = null
})
</script>

<template>
  <div class="home">
    <header class="home-header">
      <div class="title-group">
        <span class="home-title">数字孪生可视化系统（S5）</span>
        <span class="cad-badge">中沙坝转运站 · 给大连大.dwg V6 Draco/PBR 实测基准</span>
      </div>
      <div class="home-actions">
        <select class="region-select" :value="activeRegion" @change="onRegionChange(($event.target as HTMLSelectElement).value as RegionCode)">
          <option v-for="r in REGION_OPTIONS" :key="r.id" :value="r.id">{{ r.label }}</option>
        </select>
        <button class="mode-pill" @click="toggleDataMode">
          数据源: {{ dataMode === 'offline_sim' ? '离线仿真' : '只读库联调' }}
          <b :class="feedStatus.quality.toLowerCase()">{{ feedStatus.quality }}</b>
        </button>
        <button
          type="button"
          class="mode-pill tour-btn"
          :class="{ active: cinemaTourRunning }"
          title="一键自动巡游全站五大核心汇报视点，鼠标拖拽/滚轮可随时自由接管"
          @click="toggleCinemaTour"
        >
          {{ cinemaTourRunning ? '⏹ 停止巡游' : '🎬 汇报巡游' }}
        </button>
        <button
          type="button"
          class="mode-pill panel-master-btn"
          :class="{ clean: !anySidePanelOpen }"
          :title="anySidePanelOpen ? '一键收起左右全部任务浮窗，享受无遮挡全景 3D 视野' : '一键展开全部任务监控面板'"
          @click="toggleAllPanels"
        >
          {{ anySidePanelOpen ? '👁 收起全部面板' : '🗂 展开全部面板' }}
        </button>
        <button class="mode-pill req-btn" @click="reqMatrixOpen = !reqMatrixOpen">
          需求与跨系统矩阵 (F-D01~08)
        </button>
        <button class="mode-pill vendor-btn" @click="vendorDrawerOpen = !vendorDrawerOpen">
          1+5+2 厂家控制系统 (3套)
        </button>
        <span class="module-tag">边界：只监不控</span>
        <a
          v-for="link in crossLinks"
          :key="link.key"
          :href="link.url"
          target="_blank"
          rel="noopener noreferrer"
          class="sys-jump"
          :title="link.name"
        >
          {{ link.key }}
        </a>
        <button class="theme-btn" @click="appStore.toggleTheme()">
          {{ appStore.theme === 'dark' ? '浅色' : '深色' }}
        </button>
        <RouterLink to="/calibration" class="screen-link">F-D02 CAD校核</RouterLink>
        <RouterLink to="/bigscreen" class="screen-link">→ 指挥大屏</RouterLink>
      </div>
    </header>

    <main class="scene-area">
      <CesiumViewer @viewer-ready="onViewerReady" @lighting-change="onLightingChange" />
      <div v-if="cinemaTourRunning && cinemaTourLabel" class="cinema-tour-banner">
        <span class="tour-dot"></span>
        <span>自动汇报巡游中 · {{ cinemaTourLabel }}</span>
        <button type="button" class="tour-stop-chip" @click="stopCinemaTour">退出巡游</button>
      </div>

      <!-- Slide 40 & R-D03: 顶部浮动全流程追踪条（随左右面板收起自适应满宽，亦可折叠为居中精简胶囊） -->
      <div
        v-if="panelOpen.topBar"
        class="top-process-pill"
        :class="{ 'left-open': panelOpen.left, 'right-open': panelOpen.device }"
        aria-label="双线工艺流程步骤"
      >
        <div class="proc-chain-switch">
          <button
            type="button"
            class="proc-badge-btn track-tag"
            :class="{ active: processTrackMode === 'f_d06_sim' }"
            title="F-D06 固定工艺演示播放（显式标 SIM，支持自由跳步与单步驻留）"
            @click="switchProcessTrackMode('f_d06_sim')"
          >
            F-D06推演(SIM)
          </button>
          <button
            type="button"
            class="proc-badge-btn track-tag live"
            :class="{ active: processTrackMode === 'f_d07_live' }"
            title="F-D07 真实任务节点接入与展示（协作X04，只读对接 S1 PSMS 计划主账）"
            @click="switchProcessTrackMode('f_d07_live')"
          >
            F-D07真实(X04)
          </button>
          <button
            type="button"
            class="proc-badge-btn"
            :class="{ active: activeProcess === 'bulk' }"
            title="切换至散料工艺链（货1/货2翻箱入仓+库底定量）"
            @click="switchProcess('bulk')"
          >
            散料链
          </button>
          <button
            type="button"
            class="proc-badge-btn"
            :class="{ active: activeProcess === 'steel' }"
            title="切换至钢材工艺链（40t门吊+20t磁吸/32t C型钩室内桥吊）"
            @click="switchProcess('steel')"
          >
            钢材链
          </button>
          <template v-if="activeProcess === 'steel'">
            <button
              type="button"
              class="proc-sub-btn"
              :class="{ active: steelSubMode === 'rebar_magnet' }"
              title="螺纹钢 20t 电磁吸盘桥吊 (07跨)"
              @click="switchSteelSubMode('rebar_magnet')"
            >
              20t磁吸
            </button>
            <button
              type="button"
              class="proc-sub-btn"
              :class="{ active: steelSubMode === 'coil_chook' }"
              title="卷材 32t C型钩桥吊 (03跨)"
              @click="switchSteelSubMode('coil_chook')"
            >
              32t C型钩
            </button>
          </template>
        </div>
        <button
          v-for="(label, idx) in fivePhases"
          :key="label"
          type="button"
          class="proc-step"
          :class="{ done: demoState.completed || idx < demoState.phaseIndex, active: !demoState.completed && idx === demoState.phaseIndex }"
          :title="`点击直达演示：${label}`"
          @click="jumpToProcessStep(idx)"
        >
          {{ label }}
        </button>
        <div class="proc-step-nav">
          <button
            type="button"
            class="nav-mini-btn play-btn"
            :title="demoPrimaryLabel"
            @click="onDemoPrimary"
          >
            {{ demoState.running ? '⏸' : '▶' }}
          </button>
          <button type="button" class="nav-mini-btn" :disabled="demoState.stepIndex <= 0" title="上一步工序" @click="prevProcessStep">◀</button>
          <button type="button" class="nav-mini-btn" :disabled="demoState.stepIndex >= demoState.stepCount - 1" title="下一步工序" @click="nextProcessStep">▶</button>
          <button
            type="button"
            class="nav-mini-btn mode-toggle"
            :class="{ single: singleStepMode }"
            :title="singleStepMode ? '当前：单步演示（演完本步自动驻留等待讲解）' : '当前：连续联播（1~5步自动衔接）'"
            @click="toggleSingleStepMode"
          >
            {{ singleStepMode ? '单步' : '连播' }}
          </button>
          <button
            type="button"
            class="nav-mini-btn"
            title="收起顶部演示控制条"
            @click="panelOpen.topBar = false"
          >
            ▴
          </button>
        </div>
      </div>
      <button
        v-else
        type="button"
        class="dock-pill dock-top"
        title="展开顶部双线工艺演示控制条"
        @click="panelOpen.topBar = true"
      >
        ▾ {{ activeProcess === 'bulk' ? '散料链' : '钢材链' }} · 工艺演示条 ({{ demoState.progress }}%)
      </button>

      <!-- 左侧边缘收起态触发胶囊 -->
      <button
        v-if="!panelOpen.left"
        type="button"
        class="dock-pill dock-left"
        title="展开 F-D01 图层管理、镜头预设与 R-D03 任务追踪栏"
        @click="panelOpen.left = true"
      >
        <span class="dock-dot" :class="{ active: demoState.running }"></span>
        <span>图层与控制</span>
        <span class="dock-arrow">▸</span>
      </button>

      <!-- 左侧：图层管理 + 镜头预设 + R-D03 双轨流程控制（可收起抽屉） -->
      <transition name="slide-left">
      <div v-if="panelOpen.left" class="layer-panel">
        <div class="panel-header-row">
          <span class="panel-title">F-D01 图层与视角控制</span>
          <button type="button" class="collapse-chip" title="收起左侧控制栏" @click="panelOpen.left = false">« 收起</button>
        </div>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.track" @change="toggleLayer('track')" />
          <span class="dot" style="background: #00b4d8"></span> 股道 CAD
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.area" @change="toggleLayer('area')" />
          <span class="dot" style="background: #f4a261"></span> 区域边界
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.cargo" @change="toggleLayer('cargo')" />
          <span class="dot" style="background: #60a5fa"></span> 筒仓料位剖视 & 钢筋跨库存体 (S2·X08)
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.health" @change="toggleLayer('health')" />
          <span class="dot" style="background: #10b981"></span> 设备健康态势光环 (S3·X03)
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.personnel" @change="toggleLayer('personnel')" />
          <span class="dot" style="background: #34d399"></span> 立体电子围栏 & 监控视场锥 (S4·X05)
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.planned" @change="toggleLayer('planned')" />
          <span class="dot" style="background: #38bdf8"></span> 缓建规划仓体 (F-D02)
        </label>
        <div class="v6-dock-summary" title="S2/S3/S4 真实数据直连接口契约已就绪，上游接入后自动切换 LIVE 实测态">
          <span>接口直连:</span>
          <b :class="{ live: liveStorageCount > 0 }">S2 {{ liveStorageCount > 0 ? `LIVE(${liveStorageCount})` : '预留就绪' }}</b>
          <b :class="{ live: feedStatus.quality === 'GOOD' }">S3 {{ feedStatus.quality === 'GOOD' ? '已聚合' : '预留就绪' }}</b>
          <b :class="{ live: liveSafetyCount > 0 }">S4 {{ liveSafetyCount > 0 ? `LIVE(${liveSafetyCount})` : '预留就绪' }}</b>
        </div>
        <div class="panel-divider"></div>
        <div class="camera-title">施工图 V4 镜头预设</div>
        <div class="camera-grid">
          <button class="reset-btn" @click="onResetView">全站总览</button>
          <button class="reset-btn" @click="onCameraPreset('tipper')">翻箱卸料室</button>
          <button class="reset-btn" @click="onCameraPreset('ash')">40座筒仓</button>
          <button class="reset-btn" @click="onCameraPreset('loader')">库底定量</button>
          <button class="reset-btn" @click="onCameraPreset('gantry')">40t门吊转接</button>
          <button class="reset-btn" @click="onCameraPreset('rebar')">13跨室内桥吊</button>
          <button class="reset-btn" @click="onCameraPreset('weighbridge')">100t地磅</button>
          <button class="reset-btn" @click="onCameraPreset('steel')">钢材作业线</button>
        </div>

        <div class="demo-panel">
          <div class="demo-title-row">
            <span class="demo-title">R-D03 任务与过程追踪</span>
            <button
              type="button"
              class="step-mode-chip"
              :class="{ single: singleStepMode }"
              @click="toggleSingleStepMode"
            >
              {{ singleStepMode ? '单步驻留' : '连续联播' }}
            </button>
          </div>
          <!-- 显式拆分 F-D06（模拟推演）与 F-D07（S1真实节点接入） -->
          <div class="proc-tabs track-mode-tabs">
            <button :class="{ active: processTrackMode === 'f_d06_sim' }" @click="switchProcessTrackMode('f_d06_sim')">
              F-D06 固定推演(SIM)
            </button>
            <button :class="{ active: processTrackMode === 'f_d07_live' }" @click="switchProcessTrackMode('f_d07_live')">
              F-D07 真实节点(X04)
            </button>
          </div>

          <template v-if="processTrackMode === 'f_d06_sim'">
            <div class="proc-tabs">
              <button :class="{ active: activeProcess === 'bulk' }" @click="switchProcess('bulk')">散料入仓</button>
              <button :class="{ active: activeProcess === 'steel' }" @click="switchProcess('steel')">钢材联吊</button>
            </div>
            <div v-if="activeProcess === 'steel'" class="submode-tabs">
              <button :class="{ active: steelSubMode === 'rebar_magnet' }" @click="switchSteelSubMode('rebar_magnet')">
                20t磁吸·螺纹钢(07跨)
              </button>
              <button :class="{ active: steelSubMode === 'coil_chook' }" @click="switchSteelSubMode('coil_chook')">
                32t C型·卷材(03跨)
              </button>
            </div>
            <div class="step-jump-list" aria-label="自主选择演示步骤">
              <button
                v-for="(label, idx) in fivePhases"
                :key="label"
                type="button"
                class="step-jump-btn"
                :class="{ active: !demoState.completed && idx === demoState.phaseIndex, done: demoState.completed || idx < demoState.phaseIndex }"
                @click="jumpToProcessStep(idx)"
              >
                {{ label }}
              </button>
            </div>
            <div class="demo-step">{{ demoState.title }}</div>
            <div class="demo-note">{{ demoState.narration }}</div>
            <div class="demo-progress"><span :style="{ width: `${demoState.progress}%` }"></span></div>
            <div class="demo-actions">
              <button class="demo-btn primary" @click="onDemoPrimary">{{ demoPrimaryLabel }}</button>
              <button class="demo-btn" :disabled="demoState.stepIndex <= 0" @click="prevProcessStep">上一步</button>
              <button class="demo-btn" :disabled="demoState.stepIndex >= demoState.stepCount - 1" @click="nextProcessStep">下一步</button>
              <button class="demo-btn" @click="resetDemo">重置</button>
            </div>
          </template>

          <template v-else>
            <div class="live-task-list" aria-label="F-D07 S1真实任务节点列表">
              <button
                v-for="t in liveTaskList"
                :key="t.taskId"
                type="button"
                class="live-task-item"
                :class="{ active: activeLiveTask?.taskId === t.taskId, wait: t.stepStatus === 'HANDOVER_WAIT' }"
                @click="selectLiveTaskNode(t)"
              >
                <div class="lt-top">
                  <b>{{ t.planNo }}</b>
                  <em :class="t.stepStatus.toLowerCase()">
                    {{ t.stepStatus === 'HANDOVER_WAIT' ? '交接等待' : t.stepStatus === 'COMPLETED' ? '已完成' : `工序${t.currentStepIndex + 1}/5` }}
                  </em>
                </div>
                <div class="lt-sub">{{ t.cargoName }} ({{ t.tonnage }}t)</div>
              </button>
            </div>
            <div v-if="activeLiveTask" class="live-task-detail">
              <div class="demo-step">[{{ activeLiveTask.qualityCode }}] {{ activeLiveTask.contractNote }}</div>
              <div v-if="activeLiveTask.handoverWaitReason" class="demo-note warn-note">
                {{ activeLiveTask.handoverWaitReason }}
              </div>
              <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer" class="s1-task-jump">
                进入 S1 工艺管理系统处置该工单 (X04) →
              </a>
            </div>
          </template>
        </div>

        <button class="reset-btn" :class="{ active: animRunning }" @click="toggleAnim">
          CR-02 单机循环 {{ animRunning ? '开' : '关' }}
        </button>
        <button class="reset-btn" :class="{ active: simRunning }" @click="toggleSim">
          车流/数据仿真 {{ simRunning ? '开' : '关' }}
        </button>
      </div>
      </transition>

      <!-- Slide 41: 设备运行参数 + F-D03(EHMS) + F-D04(WMS库位) + F-D05(四系统联查) HUD 浮窗 -->
      <div v-if="detailVisible" class="detail-card" :class="{ 'left-open': panelOpen.left }">
        <div class="detail-header">
          <div>
            <span class="detail-title">{{ selectedInfo.name }}</span>
            <small class="detail-sub">{{ selectedInfo.id }} · {{ selectedInfo.type }}</small>
          </div>
          <button class="close-btn" @click="closeDetail">✕</button>
        </div>
        <div class="detail-body">
          <div class="detail-row">
            <span class="label">当前状态 (F-D03)</span>
            <span class="status-pill" :class="`st-${selectedDeviceState?.status ?? 'ok'}`">{{ selectedInfo.statusName }}</span>
          </div>
          <div v-if="selectedDeviceState?.telemetry" class="hud-telemetry">
            <div><span>载荷</span><b>{{ selectedDeviceState.telemetry.loadTons }} t</b></div>
            <div><span>风速</span><b>{{ selectedDeviceState.telemetry.windSpeedMps }} m/s</b></div>
            <div><span>液压/气压</span><b>{{ selectedDeviceState.telemetry.hydraulicPsi }} MPa</b></div>
            <div><span>累计运行</span><b>{{ selectedDeviceState.telemetry.runHours }} h</b></div>
            <div><span>今日循环</span><b>{{ selectedDeviceState.telemetry.todayCycles }} 次</b></div>
            <div><span>维保到期</span><b>{{ selectedDeviceState.telemetry.maintenanceDue }}</b></div>
          </div>
          <div v-if="selectedDeviceState?.ehms" class="ehms-box">
            <div class="ehms-head">
              <span>[F-D03·X03] S3 EHMS 健康: <b>{{ selectedDeviceState.ehms.healthScore }} 分</b></span>
              <a :href="selectedEhmsUrl" target="_blank" rel="noopener noreferrer">BOM详情 →</a>
            </div>
            <small>{{ selectedDeviceState.ehms.riskReason }}</small>
          </div>
          <!-- F-D04 (协作 X08): S2 WMS 仓储库位与筒仓料位联查卡 -->
          <div v-if="selectedStorageSummary" class="wms-box">
            <div class="ehms-head">
              <span>[F-D04·X08] S2 仓位: <b>{{ selectedStorageSummary.locationId }}</b></span>
              <a :href="selectedWmsUrl" target="_blank" rel="noopener noreferrer">S2台账 →</a>
            </div>
            <div class="wms-meta">
              <span>{{ selectedStorageSummary.materialName }}</span>
              <b>{{ selectedStorageSummary.availableTon }}t / {{ selectedStorageSummary.capacityTon }}t (料位 {{ selectedStorageSummary.levelMeters }}m)</b>
            </div>
            <small>批次: {{ selectedStorageSummary.batchNo }} · {{ selectedStorageSummary.highLevelWarning ? '⚠️ 高料位预警' : '仓容正常' }}</small>
          </div>
          <template v-for="(val, key) in selectedInfo.params" :key="key">
            <div class="detail-row"><span class="label">{{ key }}</span><span>{{ val }}</span></div>
          </template>
          <!-- F-D05: 跨系统带参一键联查按钮组 -->
          <div class="hud-cross-jumps">
            <a :href="selectedPsmsUrl" target="_blank" rel="noopener noreferrer" title="协作X04：带参跳转 S1 工艺管理">S1 工序(X04)</a>
            <a :href="selectedWmsUrl" target="_blank" rel="noopener noreferrer" title="协作X08：带参跳转 S2 仓储管理">S2 仓位(X08)</a>
            <a :href="selectedEhmsUrl" target="_blank" rel="noopener noreferrer" title="协作X03：带参跳转 S3 设备健康">S3 健康(X03)</a>
            <a :href="selectedScsUrl" target="_blank" rel="noopener noreferrer" title="协作X05：带参跳转 S4 安全联锁">S4 安全(X05)</a>
          </div>
        </div>
      </div>

      <!-- 组长工作簿对齐：R-D01~R-D05 ↔ F-D01~F-D08 与跨系统协作 (X03~X11) 核验抽屉 -->
      <div v-if="reqMatrixOpen" class="req-matrix-drawer" :class="{ 'left-open': panelOpen.left, 'right-open': panelOpen.device }" role="dialog" aria-label="S5需求功能与跨系统协作核验矩阵">
        <div class="vendor-header">
          <div>
            <b>S5 数字孪生需求功能（R-D01~D05 ↔ F-D01~D08）与跨系统协作（X03~X11）实时核验矩阵</b>
            <small>100% 对齐组长《系统需求功能工作簿 v2.0/v2.1》· 点击右侧“▶ 场景核验”直接在三维场景验证该功能</small>
          </div>
          <button class="close-btn" @click="reqMatrixOpen = false">✕</button>
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
                  <div class="role-txt">{{ item.roleInReq }}</div>
                  <small class="reuse-txt">{{ item.reuseRelation }}</small>
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

      <!-- 1+5+2 底层控制系统只读监控抽屉（2套外购厂家系统 + 1套合研移动翻箱机系统） -->
      <div v-if="vendorDrawerOpen" class="vendor-drawer" :class="{ 'left-open': panelOpen.left, 'right-open': panelOpen.device }">
        <div class="vendor-header">
          <div>
            <b>1+5+2 底层控制系统只读监控（协作 X09 / X10 · S5 只监不控）</b>
            <small>2套外购厂家控制系统 + 1套合研移动翻箱机控制系统 PLC 实时镜像（非GET请求 405 硬拦截）</small>
          </div>
          <button class="close-btn" @click="vendorDrawerOpen = false">✕</button>
        </div>
        <div class="vendor-grid">
          <article v-for="sys in VENDOR_CONTROL_SYSTEMS" :key="sys.id" class="vendor-card">
            <div class="vc-head">
              <b>{{ sys.name }}</b>
              <em>{{ sys.category }}</em>
            </div>
            <p class="vc-meta">{{ sys.protocol }} · 纳管: {{ sys.boundDevices.join(' / ') }}</p>
            <div class="vc-signals">
              <div v-for="sig in sys.plcSignals" :key="sig.tag" class="vc-sig">
                <span>{{ sig.label }}</span>
                <b>{{ sig.value }}</b>
              </div>
            </div>
          </article>
        </div>
      </div>

      <!-- 场景诊断浮层 -->
      <div v-if="showDiagnostics" class="diag-panel">
        <div class="diag-title">🩺 V4 场景诊断</div>
        <div class="diag-row">Viewer: <b :class="diag.viewer ? 'ok' : 'bad'">{{ diag.viewer ? '✅' : '❌' }}</b> | {{ diag.cadStatus }}</div>
        <div class="diag-row">相机: {{ diag.camera }}</div>
      </div>

      <!-- 右上边缘收起态触发胶囊（含未处理告警实时角标） -->
      <button
        v-if="!panelOpen.device"
        type="button"
        class="dock-pill dock-right-top"
        title="展开 F-D03 装备状态与健康监测栏"
        @click="panelOpen.device = true"
      >
        <span class="dock-arrow">◂</span>
        <span>装备状态 ({{ deviceSummaryCount }}台)</span>
        <span v-if="alertStore.alerts.length > 0" class="dock-badge" :class="alertStore.alerts[0]?.level">
          {{ alertStore.alerts.length }}
        </span>
      </button>

      <!-- 右侧实时数据 + EHMS 上屏（可收起抽屉） -->
      <transition name="slide-right">
      <div v-if="panelOpen.device" class="data-panel">
        <div class="data-title">
          <span>F-D03 装备状态与健康 ({{ deviceSummaryCount }}台)</span>
          <div class="data-title-right">
            <select
              class="silo-quick-select"
              :value="quickSiloId"
              title="F-D04：快速检视40座筒仓单仓台账(协作X08)"
              @change="quickSiloId = ($event.target as HTMLSelectElement).value; selectDeviceFromList(quickSiloId)"
            >
              <option v-for="s in SITE_LAYOUT_V4.ash.silos" :key="s.id" :value="s.id">
                {{ s.id }} ({{ s.code }} {{ s.material === 'cement' ? '水泥' : '粉煤灰' }})
              </option>
            </select>
            <button type="button" class="collapse-chip" title="收起装备状态栏" @click="panelOpen.device = false">收起 »</button>
          </div>
        </div>
        <div class="device-list">
          <div
            v-for="(d, id) in deviceStore.devices"
            :key="id"
            class="device-item"
            @click="selectDeviceFromList(String(id))"
          >
            <span class="device-dot" :style="{ background: d.status === 'ok' ? '#34D399' : d.status === 'standby' ? '#F5B84C' : d.status === 'fault' ? '#EF4444' : '#2B6FE0' }"></span>
            <span class="device-name">{{ d.id }} · {{ d.name.slice(0, 10) }}</span>
            <span class="device-score">{{ d.ehms?.healthScore ?? 92 }}分</span>
            <span class="device-status">{{ deviceStore.getStatusName(String(id)) }}</span>
          </div>
        </div>
        <div class="alert-list">
          <div
            v-for="a in alertStore.alerts.slice(0, 3)"
            :key="a.id"
            class="alert-item"
            style="cursor: pointer"
            @click="selectDeviceFromList(a.targetId)"
          >
            <span class="alert-level" :class="a.level">{{ a.level === 'critical' ? '紧急' : a.level === 'warning' ? '预警' : '提示' }}</span>
            <span class="alert-text">{{ a.targetName }} {{ a.message }}</span>
          </div>
          <div v-if="alertStore.alerts.length === 0" class="alert-empty">SCS 联锁正常 · 间距 {{ demoState.safetyDistanceM.toFixed(1) }}m</div>
        </div>
      </div>
      </transition>

      <!-- 右下边缘收起态触发胶囊（显示实时作业完成度） -->
      <button
        v-if="!panelOpen.stock"
        type="button"
        class="dock-pill dock-right-bottom"
        title="展开 F-D04 仓容态势与 F-D06/F-D07 作业进度联锁栏"
        @click="panelOpen.stock = true"
      >
        <span class="dock-arrow">◂</span>
        <span>仓容与进度</span>
        <span class="dock-pct">{{ demoState.progress }}%</span>
      </button>

      <transition name="slide-right">
      <section v-if="panelOpen.stock" class="phase6-panel" aria-label="双线库容与联锁监控">
        <div class="phase6-section">
          <div class="panel-header-row">
            <span class="data-title">F-D04 仓容与库存态势（协作 X08 · S2）</span>
            <button type="button" class="collapse-chip" title="收起仓容与进度栏" @click="panelOpen.stock = false">收起 »</button>
          </div>
          <div v-for="cargo in cargoItems" :key="cargo.id" class="phase6-row">
            <span class="commodity-dot" :class="cargo.kind"></span>
            <span class="phase6-name">{{ cargo.name }}</span>
            <span :class="['phase6-value', { warning: cargo.warning }]">
              {{ cargo.quantity.toLocaleString() }}t / {{ cargo.capacity.toLocaleString() }}t
            </span>
          </div>
        </div>
        <div class="phase6-section">
          <div class="data-title">F-D06/F-D07 作业进度与联锁校验</div>
          <div v-for="task in operationTasks.slice(0, 2)" :key="task.id" class="operation-row">
            <div class="operation-meta"><span>{{ task.name }}</span><span>{{ task.progress }}%</span></div>
            <div class="progress-track"><span :class="task.status" :style="{ width: `${task.progress}%` }"></span></div>
          </div>
          <div class="interlock-mini">
            <span v-for="chk in demoState.interlockChecks.slice(0, 2)" :key="chk.label">✔ {{ chk.label }}</span>
          </div>
        </div>
      </section>
      </transition>

      <!-- V6 S4-SCS 监控摄像机视频核查弹窗（原生预留 HLS/MP4/MJPEG/SNAPSHOT/IFRAME 实流直连接口） -->
      <div v-if="activeCamera" class="camera-modal-backdrop" @click.self="activeCamera = null">
        <div class="camera-modal-card">
          <div class="cam-modal-head">
            <div>
              <strong>{{ activeCamera.cameraId }} · {{ activeCamera.name }}</strong>
              <span class="cam-proto-tag" :class="formatProvenanceBadge(activeCamera.dataSource).tone">
                {{ formatProvenanceBadge(activeCamera.dataSource).label }} · {{ activeCamera.streamProtocol }}
              </span>
            </div>
            <button type="button" class="collapse-chip" @click="activeCamera = null">✕ 关闭</button>
          </div>
          <div class="cam-switcher-row">
            <button
              v-for="cam in allSurveillanceCameras"
              :key="cam.cameraId"
              type="button"
              class="cam-chip"
              :class="{ active: cam.cameraId === activeCamera.cameraId, alert: cam.status === 'ALERT' }"
              @click="openCameraModal(cam)"
            >
              {{ cam.cameraId }}
            </button>
          </div>
          <div class="cam-viewport">
            <video
              v-if="activeCamera.streamUrl && (activeCamera.streamProtocol === 'HLS' || activeCamera.streamProtocol === 'MP4')"
              :src="activeCamera.streamUrl"
              controls
              autoplay
              muted
              playsinline
              class="cam-media"
            />
            <img
              v-else-if="activeCamera.streamUrl && (activeCamera.streamProtocol === 'MJPEG' || activeCamera.streamProtocol === 'SNAPSHOT')"
              :src="activeCamera.streamUrl"
              :alt="activeCamera.name"
              class="cam-media"
            />
            <iframe
              v-else-if="activeCamera.streamUrl && activeCamera.streamProtocol === 'IFRAME'"
              :src="activeCamera.streamUrl"
              class="cam-media"
              title="S4 监控视频流"
            />
            <div v-else class="cam-unbound-placeholder">
              <div class="cam-placeholder-icon">📹</div>
              <div class="cam-placeholder-title">已预留 S4-SCS 实时视频流直连接口（当前状态：{{ activeCamera.streamProtocol }}）</div>
              <div class="cam-placeholder-sub">
                CAD 点位：({{ activeCamera.cadX.toFixed(1) }}, {{ activeCamera.cadY.toFixed(1) }}, H={{ activeCamera.cadZ }}m) · 视场角 {{ activeCamera.fovDeg }}° / 覆盖 {{ activeCamera.rangeM }}m
              </div>
              <div class="cam-placeholder-hint">
                待 S4 负责人接入 <code>GET /api/v1/cameras</code> 返回 <code>streamUrl</code>（支持 HLS / MP4 / MJPEG / IFRAME）后自动播放实况画面
              </div>
            </div>
          </div>
          <div v-if="activeCamera.latestAiEvent" class="cam-ai-bar">
            <span>AI 识别事件：<b>{{ activeCamera.latestAiEvent.type }}</b></span>
            <span>置信度 {{ activeCamera.latestAiEvent.confidence.toFixed(1) }}% · 风险 {{ activeCamera.latestAiEvent.riskCode }}</span>
          </div>
          <div class="cam-modal-foot">
            <span>所属联锁区：{{ activeCamera.zoneCode || '场站全域' }}</span>
            <a :href="selectedScsUrl" target="_blank" rel="noopener noreferrer" class="cam-scs-link">跳转 S4 安全监控系统 →</a>
          </div>
        </div>
      </div>
    </main>

    <footer class="home-footer">
      <span>B项目 S5 数字孪生可视化系统 · {{ feedStatus.sourceLabel }} · {{ feedStatus.lastSyncAt }} · 主题: {{ appStore.theme }}</span>
      <span class="footer-metrics">
        R-D01~R-D05 · 设备 {{ deviceStore.getSummary().total }} · 告警 {{ alertStore.unhandledCount }} · 防撞测距 {{ demoState.safetyDistanceM.toFixed(1) }} m · {{ processTrackMode === 'f_d06_sim' ? 'F-D06 模拟推演(SIM)' : 'F-D07 真实节点(X04)' }}
      </span>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.home {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;

  .home-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 18px;
    background: var(--bg-panel);
    border-bottom: 1px solid var(--border-color);

    .title-group { display: flex; align-items: baseline; gap: 10px; }
    .home-title { font-size: 20px; font-weight: 700; }
    .cad-badge { font-size: 11px; color: var(--highlight); opacity: 0.88; }

    .home-actions {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;

      .region-select {
        padding: 3px 8px; border: 1px solid var(--border-color); border-radius: 4px;
        background: var(--bg-panel-alt); color: var(--text-primary); font-size: 11px; cursor: pointer;
      }
      .mode-pill {
        display: inline-flex; align-items: center; gap: 5px; padding: 3px 8px;
        border: 1px solid var(--border-color); border-radius: 99px; background: var(--bg-panel-alt);
        color: var(--text-primary); font-size: 11px; cursor: pointer;
        b { font-size: 10px; &.sim { color: #00e5ff; } &.good { color: #34d399; } &.fallback { color: #f5b84c; } }
        &.vendor-btn { border-color: rgba(0, 229, 255, 0.45); color: var(--highlight); font-weight: 600; }
        &.req-btn { border-color: rgba(52, 211, 153, 0.55); color: #34d399; font-weight: 600; }
        &.panel-master-btn {
          border-color: rgba(56, 189, 248, 0.65); color: #7dd3fc; font-weight: 600;
          background: rgba(14, 116, 144, 0.24);
          &:hover { background: rgba(14, 116, 144, 0.45); color: #ffffff; }
          &.clean { border-color: rgba(52, 211, 153, 0.65); color: #6ee7b7; background: rgba(16, 185, 129, 0.16); }
        }
        &.tour-btn {
          border-color: rgba(245, 184, 76, 0.62); color: #fde68a; font-weight: 600;
          background: rgba(180, 83, 9, 0.24);
          &.active { border-color: #ef4444; color: #fecaca; background: rgba(185, 28, 28, 0.36); }
        }
      }
      .module-tag { font-size: 11px; color: var(--text-secondary); }
      .sys-jump {
        padding: 2px 7px; border-radius: 4px; border: 1px solid var(--border-color); color: var(--text-secondary);
        font-size: 11px; text-decoration: none;
        &:hover { border-color: var(--highlight); color: var(--highlight); }
      }
      .theme-btn {
        padding: 3px 9px; border: 1px solid var(--border-color); border-radius: var(--radius-sm);
        background: var(--bg-panel-alt); color: var(--text-primary); cursor: pointer; font-size: 11px;
      }
      .screen-link { color: var(--highlight); text-decoration: none; font-size: 12px; font-weight: 600; margin-left: 2px; }
    }
  }

  .scene-area {
    flex: 1;
    min-height: 0;
    height: 0;
    position: relative;
    overflow: hidden;

    .top-process-pill {
      position: absolute; top: 10px; left: 12px; right: 12px; z-index: 9;
      display: flex; align-items: center; gap: 4px; padding: 5px 8px;
      background: rgba(7, 20, 40, 0.88); backdrop-filter: blur(8px);
      border: 1px solid rgba(0, 229, 255, 0.36); border-radius: 6px;
      overflow: hidden;
      transition: left 0.24s cubic-bezier(0.22, 1, 0.36, 1), right 0.24s cubic-bezier(0.22, 1, 0.36, 1);
      &.left-open { left: 238px; }
      &.right-open { right: 334px; }
      .proc-chain-switch {
        display: flex; align-items: center; gap: 3px; flex: none; padding-right: 5px; border-right: 1px solid rgba(0, 229, 255, 0.3);
      }
      .proc-badge-btn, .proc-sub-btn {
        border: 1px solid rgba(0, 229, 255, 0.3); background: rgba(11, 35, 58, 0.8); color: #8cb7cd;
        border-radius: 4px; padding: 2px 5px; font-size: 10px; cursor: pointer; white-space: nowrap;
        &:hover { border-color: #00e5ff; color: #ffffff; }
        &.active { background: rgba(0, 180, 216, 0.42); border-color: #00e5ff; color: #00e5ff; font-weight: 700; }
        &.track-tag.live.active { background: rgba(16, 185, 129, 0.35); border-color: #34d399; color: #6ee7b7; }
      }
      .proc-sub-btn.active { background: rgba(52, 211, 153, 0.25); border-color: #34d399; color: #67e6b2; }
      .proc-step {
        flex: 1; font-size: 10px; text-align: center; padding: 3px 4px; border-radius: 3px; color: #8cb2c7;
        border: 1px solid rgba(64, 140, 176, 0.28); background: rgba(10, 28, 48, 0.65); cursor: pointer;
        white-space: nowrap; overflow: hidden; text-overflow: ellipsis; transition: all 0.14s ease;
        &:hover { border-color: #00e5ff; color: #ffffff; background: rgba(12, 66, 94, 0.72); }
        &.done { color: #67e6b2; background: rgba(16, 66, 52, 0.45); border-color: rgba(52, 211, 153, 0.45); }
        &.active { color: #ffffff; background: rgba(6, 98, 128, 0.82); font-weight: 600; border: 1px solid #00e5ff; box-shadow: 0 0 8px rgba(0, 229, 255, 0.28); }
      }
      .proc-step-nav {
        display: flex; align-items: center; gap: 3px; flex: none; padding-left: 4px; border-left: 1px solid rgba(0, 229, 255, 0.28);
        .nav-mini-btn {
          border: 1px solid rgba(0, 229, 255, 0.35); background: rgba(10, 32, 54, 0.85); color: #cdefff;
          border-radius: 3px; padding: 2px 5px; font-size: 10px; cursor: pointer;
          &:hover:not(:disabled) { border-color: #00e5ff; background: rgba(0, 180, 216, 0.35); color: #ffffff; }
          &:disabled { opacity: 0.38; cursor: not-allowed; }
          &.play-btn { border-color: rgba(52, 211, 153, 0.6); color: #6ee7b7; background: rgba(16, 185, 129, 0.22); }
          &.mode-toggle.single { border-color: #f5b84c; color: #f5b84c; background: rgba(245, 184, 76, 0.16); }
        }
      }
    }

    /* 边缘毛玻璃触发胶囊按钮（默认收起态，点击按需呼出各任务浮窗） */
    .dock-pill {
      position: absolute; z-index: 10; display: inline-flex; align-items: center; gap: 6px;
      padding: 6px 11px; border-radius: 999px;
      background: rgba(7, 22, 42, 0.82); backdrop-filter: blur(8px);
      border: 1px solid rgba(0, 229, 255, 0.42); color: #d8f3ff;
      font-size: 11px; font-weight: 600; cursor: pointer;
      box-shadow: 0 4px 14px rgba(0, 0, 0, 0.38);
      transition: all 0.18s ease;
      &:hover {
        background: rgba(12, 48, 78, 0.94); border-color: #00e5ff; color: #ffffff;
        box-shadow: 0 0 12px rgba(0, 229, 255, 0.35); transform: translateY(-1px);
      }
      .dock-arrow { color: #38bdf8; font-size: 12px; line-height: 1; }
      .dock-dot {
        width: 7px; height: 7px; border-radius: 50%; background: #38bdf8;
        &.active { background: #34d399; box-shadow: 0 0 6px #34d399; }
      }
      .dock-badge {
        padding: 0 5px; border-radius: 99px; font-size: 10px; font-weight: 700;
        background: #f5b84c; color: #091526;
        &.critical { background: #ef4444; color: #ffffff; }
      }
      .dock-pct {
        padding: 1px 6px; border-radius: 99px; font-size: 10px;
        background: rgba(52, 211, 153, 0.22); color: #6ee7b7; border: 1px solid rgba(52, 211, 153, 0.45);
      }
      &.dock-left { top: 52px; left: 12px; }
      &.dock-right-top { top: 52px; right: 12px; }
      &.dock-right-bottom { bottom: 14px; right: 12px; }
      &.dock-top { top: 10px; left: 50%; transform: translateX(-50%); &:hover { transform: translateX(-50%) translateY(-1px); } }
    }

    .panel-header-row {
      display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 6px;
      .panel-title, .data-title { margin-bottom: 0; }
    }
    .collapse-chip {
      flex: none; padding: 1px 7px; border-radius: 4px;
      border: 1px solid rgba(0, 229, 255, 0.34); background: rgba(10, 32, 54, 0.72);
      color: #8fd3ef; font-size: 10px; font-weight: 600; cursor: pointer; white-space: nowrap;
      transition: all 0.14s ease;
      &:hover { border-color: #00e5ff; background: rgba(0, 180, 216, 0.32); color: #ffffff; }
    }

    .slide-left-enter-active, .slide-left-leave-active,
    .slide-right-enter-active, .slide-right-leave-active {
      transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.2s ease;
    }
    .slide-left-enter-from, .slide-left-leave-to { transform: translateX(-24px); opacity: 0; }
    .slide-right-enter-from, .slide-right-leave-to { transform: translateX(24px); opacity: 0; }

    .layer-panel {
      position: absolute; top: 10px; left: 12px; width: 204px; max-height: calc(100% - 20px); overflow-y: auto; padding: 10px;
      background: var(--bg-panel); border: 1px solid var(--border-color); border-radius: var(--radius-md);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.35); z-index: 10;

      .panel-title { font-size: 12px; font-weight: 600; color: var(--highlight); margin-bottom: 8px; }
      .layer-item {
        display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--text-primary); margin-bottom: 5px; cursor: pointer;
        input[type='checkbox'] { accent-color: var(--highlight); }
        .dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
      }
      .panel-divider { border-top: 1px solid var(--border-color); margin: 8px 0; }
      .camera-title { margin-bottom: 6px; color: var(--text-secondary); font-size: 11px; }
      .camera-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin-bottom: 6px; .reset-btn { margin-bottom: 0; font-size: 11px; } }
      .reset-btn {
        width: 100%; padding: 4px 6px; border: 1px solid var(--border-color); border-radius: var(--radius-sm);
        background: var(--bg-panel-alt); color: var(--text-primary); cursor: pointer; font-size: 11px; margin-bottom: 5px;
        &:hover, &.active { background: var(--brand); color: #ffffff; }
      }

      .demo-panel {
        margin: 8px 0; padding: 8px; border: 1px solid rgba(0, 229, 255, 0.34); border-radius: var(--radius-sm); background: var(--bg-panel-alt);
        .demo-title-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 5px; }
        .demo-title { color: var(--highlight); font-size: 11px; font-weight: 600; }
        .step-mode-chip {
          padding: 1px 6px; border: 1px solid var(--border-color); border-radius: 99px; background: var(--bg-panel);
          color: var(--highlight); font-size: 9px; cursor: pointer;
          &.single { border-color: #f5b84c; color: #f5b84c; }
        }
        .proc-tabs, .submode-tabs {
          display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin-bottom: 5px;
          button {
            padding: 3px; border: 1px solid var(--border-color); border-radius: 3px; background: var(--bg-panel); color: var(--text-secondary); font-size: 10px; cursor: pointer;
            &.active { border-color: #00e5ff; color: #ffffff; background: var(--brand); }
          }
        }
        .track-mode-tabs button.active:last-child {
          border-color: #34d399; background: rgba(16, 185, 129, 0.3); color: #6ee7b7; font-weight: 600;
        }
        .step-jump-list {
          display: grid; gap: 3px; margin-bottom: 6px;
          .step-jump-btn {
            width: 100%; text-align: left; padding: 3px 6px; border: 1px solid var(--border-color); border-radius: 3px;
            background: var(--bg-panel); color: var(--text-secondary); font-size: 10px; cursor: pointer;
            white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
            &:hover { border-color: #00e5ff; color: var(--text-primary); }
            &.done { border-color: rgba(52, 211, 153, 0.45); color: #34d399; }
            &.active { border-color: #00e5ff; background: var(--brand); color: #ffffff; font-weight: 600; }
          }
        }
        .live-task-list {
          display: grid; gap: 4px; margin-bottom: 6px;
          .live-task-item {
            width: 100%; text-align: left; padding: 4px 6px; border: 1px solid var(--border-color); border-radius: 4px;
            background: var(--bg-panel); color: var(--text-primary); cursor: pointer;
            .lt-top { display: flex; justify-content: space-between; align-items: center; font-size: 10px; b { color: var(--highlight); } em { font-style: normal; font-size: 9px; color: #34d399; &.handover_wait { color: #f5b84c; font-weight: 700; } } }
            .lt-sub { font-size: 9px; color: var(--text-secondary); margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            &.active { border-color: #34d399; background: rgba(16, 185, 129, 0.16); }
            &.wait { border-left: 3px solid #f5b84c; }
          }
        }
        .live-task-detail {
          .warn-note { color: #f5b84c; font-weight: 600; }
          .s1-task-jump {
            display: block; text-align: center; margin-top: 6px; padding: 4px 6px; border-radius: 4px;
            background: rgba(16, 185, 129, 0.22); border: 1px solid #34d399; color: #6ee7b7; font-size: 10px; font-weight: 600; text-decoration: none;
            &:hover { background: rgba(16, 185, 129, 0.38); color: #ffffff; }
          }
        }
        .demo-step { color: var(--text-primary); font-size: 11px; font-weight: 600; line-height: 1.3; }
        .demo-note { color: var(--text-secondary); font-size: 10px; line-height: 1.35; margin-top: 3px; }
        .demo-progress {
          height: 5px; overflow: hidden; margin: 6px 0; border-radius: 999px; background: rgba(148, 163, 184, 0.24);
          span { display: block; height: 100%; background: linear-gradient(90deg, #00b4d8, #34d399); transition: width 0.12s linear; }
        }
        .demo-actions { display: grid; grid-template-columns: 1.25fr 0.85fr 0.85fr 0.75fr; gap: 3px; }
        .demo-btn {
          min-width: 0; padding: 4px 2px; border: 1px solid var(--border-color); border-radius: 4px;
          background: var(--bg-panel); color: var(--text-primary); cursor: pointer; font-size: 10px;
          &:disabled { opacity: 0.45; cursor: not-allowed; }
          &.primary { border-color: rgba(0, 229, 255, 0.5); background: var(--brand); color: #ffffff; }
        }
      }
    }

    .detail-card {
      position: absolute; top: 54px; left: 16px; width: 336px; background: var(--bg-panel);
      border: 1px solid rgba(0, 229, 255, 0.55); border-radius: var(--radius-md); box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45); z-index: 11;
      transition: left 0.24s cubic-bezier(0.22, 1, 0.36, 1);
      &.left-open { left: 226px; }
      .detail-header {
        display: flex; justify-content: space-between; align-items: start; padding: 10px 12px; border-bottom: 1px solid var(--border-color);
        .detail-title { display: block; font-size: 14px; font-weight: 600; color: var(--highlight); }
        .detail-sub { font-size: 10px; color: var(--text-secondary); }
        .close-btn { border: none; background: transparent; color: var(--text-secondary); cursor: pointer; }
      }
      .detail-body {
        padding: 10px 12px;
        .detail-row { display: flex; justify-content: space-between; gap: 8px; padding: 3px 0; font-size: 12px; .label { color: var(--text-secondary); flex: none; } }
        .status-pill {
          padding: 1px 8px; border-radius: 4px; background: var(--status-ok); color: #0b1b30; font-weight: 600; font-size: 11px;
          &.st-standby { background: var(--status-warn); color: #0b1b30; }
          &.st-fault { background: var(--status-error); color: #ffffff; }
          &.st-maintenance { background: var(--accent-blue); color: #ffffff; }
          &.st-offline { background: var(--status-offline); color: #ffffff; }
        }
        .hud-telemetry {
          display: grid; grid-template-columns: repeat(3, 1fr); gap: 5px; margin: 7px 0;
          div { background: var(--bg-panel-alt); padding: 4px 6px; border-radius: 4px; }
          span { display: block; font-size: 10px; color: var(--text-secondary); }
          b { font-size: 11px; color: var(--highlight); }
        }
        .ehms-box, .wms-box {
          margin: 6px 0; padding: 6px 8px; border-radius: 4px; border: 1px solid rgba(52, 211, 153, 0.38); background: var(--bg-panel-alt); font-size: 11px;
          .ehms-head { display: flex; justify-content: space-between; margin-bottom: 2px; a { color: var(--highlight); text-decoration: none; font-weight: 600; } }
          .wms-meta { display: flex; justify-content: space-between; font-size: 10px; margin: 2px 0; b { color: #38bdf8; } }
          small { color: var(--text-secondary); font-size: 10px; }
        }
        .wms-box { border-color: rgba(56, 189, 248, 0.45); }
        .hud-cross-jumps {
          display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; margin-top: 8px; padding-top: 6px; border-top: 1px solid var(--border-color);
          a {
            text-align: center; padding: 3px 2px; border: 1px solid rgba(0, 229, 255, 0.35); border-radius: 4px;
            background: var(--bg-panel-alt); color: var(--highlight); font-size: 10px; text-decoration: none; font-weight: 600;
            &:hover { background: var(--brand); color: #ffffff; }
          }
        }
      }
    }

    .req-matrix-drawer, .vendor-drawer {
      position: absolute; top: 52px; left: 16px; right: 16px; z-index: 15;
      background: var(--bg-panel); border: 1px solid rgba(0, 229, 255, 0.55); border-radius: 8px;
      box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6); padding: 12px 14px;
      transition: left 0.24s cubic-bezier(0.22, 1, 0.36, 1), right 0.24s cubic-bezier(0.22, 1, 0.36, 1);
      &.left-open { left: 226px; }
      &.right-open { right: 334px; }
      .vendor-header {
        display: flex; justify-content: space-between; align-items: start; padding-bottom: 8px; border-bottom: 1px solid var(--border-color);
        b { display: block; font-size: 13px; color: var(--highlight); }
        small { color: var(--text-secondary); font-size: 11px; }
        .close-btn { border: 0; background: transparent; color: var(--text-secondary); cursor: pointer; font-size: 14px; }
      }
      .req-table-wrap { max-height: 360px; overflow-y: auto; margin-top: 8px; }
      .req-table {
        width: 100%; border-collapse: collapse; font-size: 11px;
        th { text-align: left; padding: 6px 8px; background: var(--bg-panel-alt); color: var(--highlight); border-bottom: 1px solid var(--border-color); position: sticky; top: 0; }
        td { padding: 6px 8px; border-bottom: 1px solid var(--border-color); vertical-align: middle; }
        .fn-code { font-weight: 700; color: #00e5ff; white-space: nowrap; }
        .fn-name { font-weight: 600; color: var(--text-primary); }
        .req-code { color: #34d399; font-weight: 600; white-space: nowrap; }
        .collab-badge { display: inline-block; padding: 1px 5px; border-radius: 3px; background: rgba(0, 229, 255, 0.16); color: #78e3fd; font-size: 10px; font-weight: 600; }
        .partner-sub { display: block; font-size: 10px; color: var(--text-secondary); margin-top: 2px; }
        .role-txt { color: var(--text-primary); }
        .reuse-txt { display: block; font-size: 10px; color: var(--text-secondary); margin-top: 2px; }
        .st-col { color: #34d399; font-weight: 600; white-space: nowrap; }
        .verify-act-btn {
          padding: 4px 8px; border: 1px solid #00e5ff; border-radius: 4px; background: rgba(0, 180, 216, 0.25);
          color: #ffffff; font-size: 10px; font-weight: 600; cursor: pointer; white-space: nowrap;
          &:hover { background: var(--brand); }
        }
      }
      .vendor-grid {
        display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-top: 10px;
      }
      .vendor-card {
        background: var(--bg-panel-alt); border: 1px solid var(--border-color); border-radius: 6px; padding: 8px 10px;
        .vc-head { display: flex; justify-content: space-between; align-items: center; gap: 4px; b { font-size: 11px; color: var(--text-primary); } em { font-style: normal; font-size: 10px; color: var(--highlight); } }
        .vc-meta { margin: 4px 0 6px; font-size: 10px; color: var(--text-secondary); }
        .vc-signals { display: grid; gap: 3px; }
        .vc-sig { display: flex; justify-content: space-between; gap: 6px; font-size: 10px; padding: 2px 0; border-top: 1px dashed rgba(148, 163, 184, 0.16); span { color: var(--text-secondary); } b { color: var(--status-ok); } }
      }
    }

    .data-panel {
      position: absolute; top: 10px; right: 12px; width: 316px; background: var(--bg-panel);
      border: 1px solid var(--border-color); border-radius: var(--radius-md); box-shadow: 0 6px 20px rgba(0, 0, 0, 0.4); z-index: 9;
      .data-title {
        display: flex; justify-content: space-between; align-items: center; gap: 6px;
        font-size: 12px; font-weight: 600; color: var(--highlight); padding: 7px 10px; border-bottom: 1px solid var(--border-color);
        .data-title-right { display: flex; align-items: center; gap: 5px; flex: none; }
        .silo-quick-select {
          max-width: 100px; padding: 2px 4px; font-size: 10px; border: 1px solid var(--border-color);
          border-radius: 4px; background: var(--bg-panel-alt); color: var(--text-primary); cursor: pointer;
        }
      }
      .device-list { padding: 6px 10px; max-height: 196px; overflow-y: auto; }
      .device-item {
        display: flex; align-items: center; gap: 6px; padding: 3px 0; font-size: 11px; cursor: pointer;
        &:hover { color: var(--highlight); }
        .device-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
        .device-name { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .device-score { color: var(--status-ok); font-size: 10px; }
        .device-status { color: var(--text-secondary); font-size: 10px; }
      }
      .alert-list { padding: 4px 10px 8px; border-top: 1px solid var(--border-color); }
      .alert-item {
        display: flex; gap: 6px; padding: 2px 0; font-size: 11px;
        .alert-level {
          padding: 0 4px; border-radius: 3px; background: var(--status-warn); color: #0b1b30; font-size: 10px; flex: none;
          &.critical { background: var(--status-error); color: #ffffff; }
        }
      }
      .alert-empty { color: var(--text-secondary); font-size: 11px; }
    }

    .phase6-panel {
      position: absolute; right: 12px; bottom: 14px; width: 312px; background: var(--bg-panel);
      border: 1px solid var(--border-color); border-radius: var(--radius-md); box-shadow: 0 6px 20px rgba(0, 0, 0, 0.4); z-index: 9;
      .phase6-section { padding: 8px 10px; border-bottom: 1px solid var(--border-color); &:last-child { border-bottom: 0; } }
      .data-title { color: var(--highlight); font-size: 12px; font-weight: 600; margin-bottom: 5px; }
      .phase6-row { display: flex; align-items: center; gap: 6px; font-size: 11px; padding: 2px 0; .commodity-dot { width: 7px; height: 7px; border-radius: 50%; background: #60a5fa; &.fly_ash { background: var(--status-warn); } &.cement { background: #34d399; } } .phase6-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .phase6-value { color: var(--text-secondary); &.warning { color: var(--status-error); } } }
      .operation-row { padding: 2px 0 4px; .operation-meta { display: flex; justify-content: space-between; font-size: 11px; margin-bottom: 3px; } .progress-track { height: 5px; background: var(--bg-panel-alt); border-radius: 3px; overflow: hidden; span { display: block; height: 100%; background: var(--status-ok); } } }
      .interlock-mini { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; font-size: 10px; color: #7be6bb; }
    }

    .diag-panel { position: absolute; top: 52px; left: 50%; transform: translateX(-50%); background: rgba(7, 18, 40, 0.9); padding: 8px; border: 1px solid var(--border-color); border-radius: 6px; z-index: 12; font-size: 11px; }

    .v6-dock-summary {
      display: flex; align-items: center; gap: 4px; flex-wrap: wrap;
      margin-top: 6px; padding: 4px 6px; border-radius: 5px;
      background: rgba(8, 30, 56, 0.72); border: 1px solid rgba(56, 189, 248, 0.25);
      font-size: 10px; color: var(--text-secondary);
      b {
        padding: 1px 5px; border-radius: 99px; font-weight: 600;
        background: rgba(56, 189, 248, 0.14); color: #7dd3fc;
        &.live { background: rgba(16, 185, 129, 0.24); color: #6ee7b7; }
      }
    }

    .cinema-tour-banner {
      position: absolute; top: 62px; left: 50%; transform: translateX(-50%);
      display: inline-flex; align-items: center; gap: 8px;
      padding: 6px 14px; border-radius: 99px; z-index: 14;
      background: rgba(7, 22, 46, 0.90); border: 1px solid rgba(245, 184, 76, 0.65);
      color: #fef3c7; font-size: 12px; font-weight: 600;
      box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
      .tour-dot { width: 8px; height: 8px; border-radius: 50%; background: #f59e0b; box-shadow: 0 0 8px #f59e0b; }
      .tour-stop-chip {
        padding: 2px 8px; border-radius: 99px; border: 1px solid rgba(254, 202, 202, 0.55);
        background: rgba(185, 28, 28, 0.35); color: #fecaca; font-size: 11px; cursor: pointer;
      }
    }

    .camera-modal-backdrop {
      position: absolute; inset: 0; background: rgba(2, 8, 20, 0.62); backdrop-filter: blur(4px);
      display: flex; align-items: center; justify-content: center; z-index: 30;
    }
    .camera-modal-card {
      width: min(640px, 92%); background: rgba(7, 20, 42, 0.96);
      border: 1px solid rgba(56, 189, 248, 0.55); border-radius: 10px;
      box-shadow: 0 18px 48px rgba(0, 0, 0, 0.65); overflow: hidden;
      .cam-modal-head {
        display: flex; align-items: center; justify-content: space-between; gap: 10px;
        padding: 10px 14px; border-bottom: 1px solid var(--border-color);
        strong { font-size: 14px; color: #f0f9ff; margin-right: 8px; }
        .cam-proto-tag {
          padding: 2px 7px; border-radius: 99px; font-size: 10px; font-weight: 600;
          &.live { background: rgba(16, 185, 129, 0.25); color: #6ee7b7; }
          &.sim { background: rgba(56, 189, 248, 0.20); color: #7dd3fc; }
          &.isolated { background: rgba(100, 116, 139, 0.30); color: #cbd5e1; }
        }
      }
      .cam-switcher-row {
        display: flex; gap: 6px; padding: 8px 14px; border-bottom: 1px solid var(--border-color); flex-wrap: wrap;
        .cam-chip {
          padding: 3px 9px; border-radius: 99px; border: 1px solid var(--border-color);
          background: var(--bg-panel-alt); color: var(--text-secondary); font-size: 11px; cursor: pointer;
          &.active { border-color: #38bdf8; color: #e0f2fe; background: rgba(14, 116, 144, 0.38); font-weight: 600; }
          &.alert { border-color: #ef4444; color: #fecaca; }
        }
      }
      .cam-viewport {
        height: 290px; background: #030916; display: flex; align-items: center; justify-content: center;
        .cam-media { width: 100%; height: 100%; object-fit: cover; border: 0; }
        .cam-unbound-placeholder {
          text-align: center; padding: 20px; color: #94a3b8; max-width: 520px;
          .cam-placeholder-icon { font-size: 36px; margin-bottom: 8px; }
          .cam-placeholder-title { font-size: 13px; color: #e0f2fe; font-weight: 600; margin-bottom: 6px; }
          .cam-placeholder-sub { font-size: 11px; color: #7dd3fc; margin-bottom: 8px; }
          .cam-placeholder-hint { font-size: 11px; line-height: 1.5; code { color: #fde68a; background: rgba(255, 255, 255, 0.08); padding: 1px 4px; border-radius: 3px; } }
        }
      }
      .cam-ai-bar {
        display: flex; justify-content: space-between; padding: 7px 14px;
        background: rgba(185, 28, 28, 0.22); border-top: 1px solid rgba(239, 68, 68, 0.45);
        font-size: 12px; color: #fecaca;
      }
      .cam-modal-foot {
        display: flex; justify-content: space-between; align-items: center; padding: 9px 14px;
        border-top: 1px solid var(--border-color); font-size: 11px; color: var(--text-secondary);
        .cam-scs-link { color: #38bdf8; text-decoration: none; font-weight: 600; &:hover { text-decoration: underline; } }
      }
    }
  }

  .home-footer {
    display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
    padding: 5px 18px; font-size: 11px; color: var(--text-secondary);
    background: var(--bg-panel); border-top: 1px solid var(--border-color);
    .footer-metrics { color: var(--highlight); opacity: 0.88; font-size: 11px; }
  }
}
</style>
