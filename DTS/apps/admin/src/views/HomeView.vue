<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { filterByCategory } from '@/components/cesium/layerManager'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import {
  DEFAULT_DATA_MODE,
  DtsDataService,
  REGION_OPTIONS,
  VENDOR_CONTROL_SYSTEMS,
  buildEhmsAssetUrl,
  getCrossSystemLinks,
  type DataSourceMode,
  type DtsFeedStatus,
  type RegionCode,
} from '@/services/dtsDataService'
import { useAlertStore } from '@/stores/alert'
import { useAppStore } from '@/stores/app'
import { buildDefaultDeviceSnapshot, useDeviceStore } from '@/stores/device'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { CRANE_UNLOAD_SEQUENCE } from '@/twin3d/animation'
import { BulkProcessDemoController } from '@/twin3d/bulkProcessDemo'
import { flyToCameraPreset, type CameraPresetId } from '@/twin3d/cameraPresets'
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

const layers = ref({ track: true, area: true, cargo: false, personnel: false, planned: true })
const detailVisible = ref(false)
const vendorDrawerOpen = ref(false)
const activeRegion = ref<RegionCode>('SHEN_TIE')
const steelSubMode = ref<SteelSubMode>('rebar_magnet')
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
const dataMode = ref<DataSourceMode>(DEFAULT_DATA_MODE)
const feedStatus = reactive<DtsFeedStatus>({
  mode: DEFAULT_DATA_MODE,
  quality: DEFAULT_DATA_MODE === 'offline_sim' ? 'SIM' : 'GOOD',
  sourceLabel: DEFAULT_DATA_MODE === 'offline_sim' ? '离线工艺仿真 (.eddx)' : '正在连接只读底座 (/api/v1/dts)',
  lastSyncAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
  errorNote: '',
})

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
  demoState.running ? '重新开始' : demoState.completed ? '重演' : demoState.progress > 0 ? '继续' : '开始演示',
)
const deviceSummaryCount = computed(() => Object.keys(deviceStore.devices).length)
const fivePhases = computed(() => (activeProcess.value === 'bulk' ? BULK_FIVE_PHASES : STEEL_FIVE_PHASES))
const selectedDeviceState = computed(() => (selectedInfo.id ? deviceStore.devices[selectedInfo.id] : undefined))
const selectedEhmsUrl = computed(() => {
  const base = crossLinks.find((c) => c.key === 'S3')?.url ?? 'https://ehms.bproject.de5.net/ehm/'
  return buildEhmsAssetUrl(base, selectedInfo.id || undefined)
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

watch(
  () => deviceStore.devices,
  (map) => {
    for (const [id, d] of Object.entries(map)) {
      twin3d?.setStatus(id, d.status)
    }
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

function onViewerReady(v: Cesium.Viewer) {
  viewer = v
  setupCameraInteraction(v)
  diag.viewer = true
  diag.globeShow = v.scene.globe.show
  diag.cadStatus = '加载 CAD 底图中'

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
      focusCadExtent(v)
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
    })
    diag.deviceEntities = twin3d.getStatusSummary().total
    phase6Layers = new Phase6LayerController(v)
    phase6Layers.syncCargo(cargoItems.value)
    phase6Layers.syncPersonnel(personnelItems.value)
    phase6Layers.setCargoVisible(layers.value.cargo)
    phase6Layers.setPersonnelVisible(layers.value.personnel)
    rebuildDemoController(activeProcess.value)
  }

  if (!skipAnim) {
    dataService = new DtsDataService(deviceStore, alertStore, (s) => Object.assign(feedStatus, s), activeRegion.value)
    activeRegion.value = dataService.getRegionId()
    twin3d?.setVehicleRouteAnimation(true)
    dataService.start(dataMode.value, 5000)
  }

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
      if (viewer) flyToCameraPreset(viewer, id)
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
  demoController.reset()
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
  resetDemo()
  demoController?.start()
}

function onDemoPrimary() {
  if (demoState.running || demoState.completed || demoState.progress === 0) startDemo()
  else demoController?.start()
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

function toggleLayer(category: 'track' | 'area' | 'cargo' | 'personnel' | 'planned') {
  layers.value[category] = !layers.value[category]
  if (category === 'cargo') phase6Layers?.setCargoVisible(layers.value.cargo)
  else if (category === 'personnel') phase6Layers?.setPersonnelVisible(layers.value.personnel)
  else if (category === 'planned') twin3d?.setPlannedZonesVisible(layers.value.planned)
  else setCategoryVisible(category, layers.value[category])
}

function onResetView() {
  if (viewer) flyToCameraPreset(viewer, 'overview')
}

function onCameraPreset(id: CameraPresetId) {
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
  if (diagTimer) clearInterval(diagTimer)
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
        <span class="cad-badge">中沙坝转运站 · 给大连大.dwg V4 实测基准</span>
      </div>
      <div class="home-actions">
        <select class="region-select" :value="activeRegion" @change="onRegionChange(($event.target as HTMLSelectElement).value as RegionCode)">
          <option v-for="r in REGION_OPTIONS" :key="r.id" :value="r.id">{{ r.label }}</option>
        </select>
        <button class="mode-pill" @click="toggleDataMode">
          数据源: {{ dataMode === 'offline_sim' ? '离线仿真' : '只读库联调' }}
          <b :class="feedStatus.quality.toLowerCase()">{{ feedStatus.quality }}</b>
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
        <RouterLink to="/bigscreen" class="screen-link">→ 指挥大屏</RouterLink>
      </div>
    </header>

    <main class="scene-area">
      <CesiumViewer @viewer-ready="onViewerReady" />

      <!-- Slide 40: 顶部浮动 5 步全流程追踪条 -->
      <div class="top-process-pill" aria-label="双线工艺流程步骤">
        <span class="proc-badge">{{ activeProcess === 'bulk' ? '散料工艺链' : '钢材工艺链' }}</span>
        <span
          v-for="(label, idx) in fivePhases"
          :key="label"
          class="proc-step"
          :class="{ done: demoState.completed || idx < demoState.phaseIndex, active: !demoState.completed && idx === demoState.phaseIndex }"
        >
          {{ label }}
        </span>
      </div>

      <!-- 左侧：图层管理 + 镜头预设 + 双线流程演示 -->
      <div class="layer-panel">
        <div class="panel-title">图层与视角控制</div>
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
          <span class="dot" style="background: #60a5fa"></span> 库存柱状体
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.personnel" @change="toggleLayer('personnel')" />
          <span class="dot" style="background: #34d399"></span> S4 电子围栏联锁
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.planned" @change="toggleLayer('planned')" />
          <span class="dot" style="background: #38bdf8"></span> 缓建规划仓体
        </label>
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
          <div class="demo-title">工艺细化演示（.eddx）</div>
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
          <div class="demo-step">{{ demoState.title }}</div>
          <div class="demo-note">{{ demoState.narration }}</div>
          <div class="demo-progress"><span :style="{ width: `${demoState.progress}%` }"></span></div>
          <div class="demo-actions">
            <button class="demo-btn primary" @click="onDemoPrimary">{{ demoPrimaryLabel }}</button>
            <button class="demo-btn" :disabled="!demoState.running" @click="pauseDemo">暂停</button>
            <button class="demo-btn" @click="resetDemo">重置</button>
          </div>
        </div>

        <button class="reset-btn" :class="{ active: animRunning }" @click="toggleAnim">
          CR-02 单机循环 {{ animRunning ? '开' : '关' }}
        </button>
        <button class="reset-btn" :class="{ active: simRunning }" @click="toggleSim">
          车流/数据仿真 {{ simRunning ? '开' : '关' }}
        </button>
      </div>

      <!-- Slide 41: 设备运行参数 + S3 EHMS 健康详情 HUD 浮窗 -->
      <div v-if="detailVisible" class="detail-card">
        <div class="detail-header">
          <div>
            <span class="detail-title">{{ selectedInfo.name }}</span>
            <small class="detail-sub">{{ selectedInfo.id }} · {{ selectedInfo.type }}</small>
          </div>
          <button class="close-btn" @click="closeDetail">✕</button>
        </div>
        <div class="detail-body">
          <div class="detail-row">
            <span class="label">当前状态</span>
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
              <span>S3 EHMS 健康度: <b>{{ selectedDeviceState.ehms.healthScore }} 分</b></span>
              <a :href="selectedEhmsUrl" target="_blank" rel="noopener noreferrer">BOM详情 →</a>
            </div>
            <small>{{ selectedDeviceState.ehms.riskReason }}</small>
          </div>
          <template v-for="(val, key) in selectedInfo.params" :key="key">
            <div class="detail-row"><span class="label">{{ key }}</span><span>{{ val }}</span></div>
          </template>
        </div>
      </div>

      <!-- 1+5+2 底层控制系统只读监控抽屉（2套外购厂家系统 + 1套合研移动翻箱机系统） -->
      <div v-if="vendorDrawerOpen" class="vendor-drawer">
        <div class="vendor-header">
          <div>
            <b>1+5+2 底层控制系统只读监控（S5 只监不控）</b>
            <small>2套外购厂家控制系统 + 1套合研移动翻箱机控制系统 PLC 实时镜像</small>
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

      <!-- 右侧实时数据 + EHMS 上屏 -->
      <div class="data-panel">
        <div class="data-title">
          <span>装备状态与 EHMS 健康 ({{ deviceSummaryCount }}台)</span>
          <select
            class="silo-quick-select"
            :value="quickSiloId"
            title="快速检视40座筒仓单仓台账"
            @change="quickSiloId = ($event.target as HTMLSelectElement).value; selectDeviceFromList(quickSiloId)"
          >
            <option v-for="s in SITE_LAYOUT_V4.ash.silos" :key="s.id" :value="s.id">
              {{ s.id }} ({{ s.code }} {{ s.material === 'cement' ? '水泥' : '粉煤灰' }})
            </option>
          </select>
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

      <section class="phase6-panel" aria-label="双线库容与联锁监控">
        <div class="phase6-section">
          <div class="data-title">仓容与库存态势（V4 实测）</div>
          <div v-for="cargo in cargoItems" :key="cargo.id" class="phase6-row">
            <span class="commodity-dot" :class="cargo.kind"></span>
            <span class="phase6-name">{{ cargo.name }}</span>
            <span :class="['phase6-value', { warning: cargo.warning }]">
              {{ cargo.quantity.toLocaleString() }}t / {{ cargo.capacity.toLocaleString() }}t
            </span>
          </div>
        </div>
        <div class="phase6-section">
          <div class="data-title">作业进度与联锁校验</div>
          <div v-for="task in operationTasks.slice(0, 2)" :key="task.id" class="operation-row">
            <div class="operation-meta"><span>{{ task.name }}</span><span>{{ task.progress }}%</span></div>
            <div class="progress-track"><span :class="task.status" :style="{ width: `${task.progress}%` }"></span></div>
          </div>
          <div class="interlock-mini">
            <span v-for="chk in demoState.interlockChecks.slice(0, 2)" :key="chk.label">✔ {{ chk.label }}</span>
          </div>
        </div>
      </section>

      <div class="status-bar">
        <span class="status-item">给大连大.dwg V4 · 40筒仓 / 13跨钢筋库 / 4翻箱机</span>
        <span class="status-item">设备：{{ deviceStore.getSummary().total }} | 告警：{{ alertStore.unhandledCount }}</span>
        <span class="status-item">防撞测距：{{ demoState.safetyDistanceM.toFixed(1) }} m</span>
        <span class="status-item">点击任意三维装备打开 HUD</span>
      </div>
    </main>

    <footer class="home-footer">
      B项目 S5 数字孪生可视化系统 · {{ feedStatus.sourceLabel }} · {{ feedStatus.lastSyncAt }} · 当前主题: {{ appStore.theme }}
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
      gap: 8px;

      .region-select {
        padding: 3px 8px; border: 1px solid var(--border-color); border-radius: 4px;
        background: var(--bg-panel-alt); color: var(--text-primary); font-size: 11px; cursor: pointer;
      }
      .mode-pill {
        display: inline-flex; align-items: center; gap: 6px; padding: 3px 9px;
        border: 1px solid var(--border-color); border-radius: 99px; background: var(--bg-panel-alt);
        color: var(--text-primary); font-size: 11px; cursor: pointer;
        b { font-size: 10px; &.sim { color: #00e5ff; } &.good { color: #34d399; } &.fallback { color: #f5b84c; } }
        &.vendor-btn { border-color: rgba(0, 229, 255, 0.45); color: var(--highlight); font-weight: 600; }
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
      .screen-link { color: var(--highlight); text-decoration: none; font-size: 13px; font-weight: 600; margin-left: 4px; }
    }
  }

  .scene-area {
    flex: 1;
    min-height: 0;
    height: 0;
    position: relative;
    overflow: hidden;

    .top-process-pill {
      position: absolute; top: 10px; left: 222px; right: 342px; z-index: 9;
      display: flex; align-items: center; gap: 6px; padding: 5px 10px;
      background: rgba(7, 20, 40, 0.88); border: 1px solid rgba(0, 229, 255, 0.36); border-radius: 6px;
      overflow: hidden;
      .proc-badge { flex: none; font-size: 11px; font-weight: 700; color: #00e5ff; padding-right: 6px; border-right: 1px solid rgba(0, 229, 255, 0.3); }
      .proc-step {
        flex: 1; font-size: 10px; text-align: center; padding: 2px 4px; border-radius: 3px; color: #7fa6bf; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        &.done { color: #67e6b2; background: rgba(16, 66, 52, 0.45); }
        &.active { color: #ffffff; background: rgba(6, 98, 128, 0.72); font-weight: 600; border: 1px solid rgba(0, 229, 255, 0.5); }
      }
    }

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
        .demo-title { color: var(--highlight); font-size: 11px; font-weight: 600; margin-bottom: 5px; }
        .proc-tabs, .submode-tabs {
          display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin-bottom: 6px;
          button {
            padding: 3px; border: 1px solid var(--border-color); border-radius: 3px; background: var(--bg-panel); color: var(--text-secondary); font-size: 10px; cursor: pointer;
            &.active { border-color: #00e5ff; color: #ffffff; background: var(--brand); }
          }
        }
        .demo-step { color: var(--text-primary); font-size: 11px; font-weight: 600; line-height: 1.3; }
        .demo-note { color: var(--text-secondary); font-size: 10px; line-height: 1.35; margin-top: 3px; }
        .demo-progress {
          height: 5px; overflow: hidden; margin: 6px 0; border-radius: 999px; background: rgba(148, 163, 184, 0.24);
          span { display: block; height: 100%; background: linear-gradient(90deg, #00b4d8, #34d399); transition: width 0.12s linear; }
        }
        .demo-actions { display: grid; grid-template-columns: 1.3fr 0.8fr 0.8fr; gap: 4px; }
        .demo-btn {
          min-width: 0; padding: 4px 2px; border: 1px solid var(--border-color); border-radius: 4px;
          background: var(--bg-panel); color: var(--text-primary); cursor: pointer; font-size: 10px;
          &:disabled { opacity: 0.45; cursor: not-allowed; }
          &.primary { border-color: rgba(0, 229, 255, 0.5); background: var(--brand); color: #ffffff; }
        }
      }
    }

    .detail-card {
      position: absolute; top: 54px; left: 226px; width: 326px; background: var(--bg-panel);
      border: 1px solid rgba(0, 229, 255, 0.55); border-radius: var(--radius-md); box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45); z-index: 11;
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
        .ehms-box {
          margin: 6px 0; padding: 6px 8px; border-radius: 4px; border: 1px solid rgba(52, 211, 153, 0.38); background: var(--bg-panel-alt); font-size: 11px;
          .ehms-head { display: flex; justify-content: space-between; margin-bottom: 2px; a { color: var(--highlight); text-decoration: none; font-weight: 600; } }
          small { color: var(--text-secondary); font-size: 10px; }
        }
      }
    }

    .vendor-drawer {
      position: absolute; top: 52px; left: 226px; right: 334px; z-index: 14;
      background: var(--bg-panel); border: 1px solid rgba(0, 229, 255, 0.55); border-radius: 8px;
      box-shadow: 0 12px 32px rgba(0, 0, 0, 0.55); padding: 12px 14px;
      .vendor-header {
        display: flex; justify-content: space-between; align-items: start; padding-bottom: 8px; border-bottom: 1px solid var(--border-color);
        b { display: block; font-size: 13px; color: var(--highlight); }
        small { color: var(--text-secondary); font-size: 11px; }
        .close-btn { border: 0; background: transparent; color: var(--text-secondary); cursor: pointer; font-size: 14px; }
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
      border: 1px solid var(--border-color); border-radius: var(--radius-md); z-index: 9;
      .data-title {
        display: flex; justify-content: space-between; align-items: center; gap: 6px;
        font-size: 12px; font-weight: 600; color: var(--highlight); padding: 7px 10px; border-bottom: 1px solid var(--border-color);
        .silo-quick-select {
          max-width: 118px; padding: 2px 4px; font-size: 10px; border: 1px solid var(--border-color);
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
      position: absolute; right: 12px; bottom: 48px; width: 312px; background: var(--bg-panel);
      border: 1px solid var(--border-color); border-radius: var(--radius-md); z-index: 9;
      .phase6-section { padding: 8px 10px; border-bottom: 1px solid var(--border-color); &:last-child { border-bottom: 0; } }
      .data-title { color: var(--highlight); font-size: 12px; font-weight: 600; margin-bottom: 5px; }
      .phase6-row { display: flex; align-items: center; gap: 6px; font-size: 11px; padding: 2px 0; .commodity-dot { width: 7px; height: 7px; border-radius: 50%; background: #60a5fa; &.fly_ash { background: var(--status-warn); } &.cement { background: #34d399; } } .phase6-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } .phase6-value { color: var(--text-secondary); &.warning { color: var(--status-error); } } }
      .operation-row { padding: 2px 0 4px; .operation-meta { display: flex; justify-content: space-between; font-size: 11px; margin-bottom: 3px; } .progress-track { height: 5px; background: var(--bg-panel-alt); border-radius: 3px; overflow: hidden; span { display: block; height: 100%; background: var(--status-ok); } } }
      .interlock-mini { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; font-size: 10px; color: #7be6bb; }
    }

    .diag-panel { position: absolute; top: 52px; left: 50%; transform: translateX(-50%); background: rgba(7, 18, 40, 0.9); padding: 8px; border: 1px solid var(--border-color); border-radius: 6px; z-index: 12; font-size: 11px; }

    .status-bar {
      position: absolute; bottom: 8px; left: 50%; transform: translateX(-50%);
      display: flex; gap: 16px; padding: 6px 14px; background: var(--bg-panel);
      border: 1px solid var(--border-color); border-radius: var(--radius-md); z-index: 10; font-size: 11px; color: var(--text-secondary);
    }
  }

  .home-footer { padding: 6px 18px; font-size: 11px; color: var(--text-secondary); background: var(--bg-panel); border-top: 1px solid var(--border-color); }
}
</style>
