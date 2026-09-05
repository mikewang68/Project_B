<script setup lang="ts">
// 首页：三维场景主页面
// 图层管理 + 程序化建模设备 + 数据驱动动画 + 实时状态/告警
import { computed, ref, onBeforeUnmount, reactive, watch } from 'vue'
import { onMounted } from 'vue'
import * as Cesium from 'cesium'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { useAnimation } from '@/twin3d/useAnimation'
import { CRANE_UNLOAD_SEQUENCE } from '@/twin3d/animation'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'
import {
  SteelUnloadDemoController,
  type DemoViewState,
} from '@/twin3d/steelUnloadDemo'
import { MockDataService } from '@/services/mockService'
import { useDeviceStore } from '@/stores/device'
import { useAlertStore } from '@/stores/alert'
import { useAppStore } from '@/stores/app'
import { cadGeoJsonToWgs84 } from '@/twin3d/geo'
import { CAD_GEOJSON_URL } from '@/config/runtime'
import { flyToCameraPreset, type CameraPresetId } from '@/twin3d/cameraPresets'
import {
  MOCK_CARGO,
  MOCK_OPERATIONS,
  MOCK_PERSONNEL,
  Phase6LayerController,
  type CargoItem,
  type OperationTask,
  type PersonnelItem,
} from '@/twin3d/phase6'

const appStore = useAppStore()
const deviceStore = useDeviceStore()
const alertStore = useAlertStore()
// 场站设备区域中心（对齐 CesiumViewer，重置视角用）

let viewer: Cesium.Viewer | null = null
let yardDataSource: Cesium.GeoJsonDataSource | null = null
let twin3d: Twin3dController | null = null
let mockService: MockDataService | null = null
let phase6Layers: Phase6LayerController | null = null
let phase6Timer: ReturnType<typeof setInterval> | null = null
let demoController: SteelUnloadDemoController | null = null

// 图层开关（模块A）
// The overview is an architectural night view first.  Operational overlays
// remain available through these controls, but start hidden to avoid label
// collisions when the whole station is on screen.
const layers = ref({ track: false, area: false, cargo: false, personnel: false })
const cargoItems = ref<CargoItem[]>(structuredClone(MOCK_CARGO))
const personnelItems = ref<PersonnelItem[]>(structuredClone(MOCK_PERSONNEL))
const operationTasks = ref<OperationTask[]>(structuredClone(MOCK_OPERATIONS))

// 设备详情弹窗（模块B）
const detailVisible = ref(false)
const selectedInfo = reactive<SelectedInfo>({
  id: '', name: '', type: '', status: '', statusName: '', params: {},
})

// 动画/模拟控制
const animRunning = ref(false)
const simRunning = ref(false)
const demoState = reactive<DemoViewState>({
  running: false,
  completed: false,
  stepIndex: 0,
  stepCount: 5,
  title: '钢材卸车与堆存演示',
  narration: '点击“开始演示”运行可复现的答辩验收流程。',
  progress: 0,
})
const demoPrimaryLabel = computed(() => {
  if (demoState.running || demoState.completed) return '重新开始'
  return demoState.progress > 0 ? '继续演示' : '开始演示'
})

// ── 场景诊断（帮助定位渲染问题）──
const diag = reactive({
  webgl: false,
  viewer: false,
  cadEntities: 0,
  cadStatus: '等待加载',
  deviceEntities: 0,
  camera: '',
  globeShow: false,
})
// Diagnostics are useful for support but should not cover the presentation view.
// Add ?debug=1 to the local URL when a scene investigation is required.
const showDiagnostics = new URLSearchParams(window.location.search).get('debug') === '1'
let diagTimer: ReturnType<typeof setInterval> | null = null

function updateDiag() {
  try {
    const canvas = document.createElement('canvas')
    diag.webgl = !!(canvas.getContext('webgl2') || canvas.getContext('webgl'))
  } catch {
    diag.webgl = false
  }
  const win = window as unknown as { __dt_viewer?: Cesium.Viewer }
  const v = win.__dt_viewer
  diag.viewer = !!v
  if (v) {
    const cam = v.camera.positionCartographic
    diag.camera = `${Cesium.Math.toDegrees(cam.longitude).toFixed(4)}, ${Cesium.Math.toDegrees(cam.latitude).toFixed(4)} @${cam.height.toFixed(0)}m`
    diag.globeShow = v.scene.globe.show
    const ds = v.dataSources.getByName('dt-equipment')[0]
    diag.deviceEntities = ds?.entities.values.length ?? 0
  }
}

onMounted(() => {
  diagTimer = setInterval(updateDiag, 2000)
})

// ── 龙门吊三轴动画（数据驱动，复刻项目1）──
const anim = useAnimation(
  CRANE_UNLOAD_SEQUENCE,
  {
    initialState: { gantry: [3727.235, 1673.434], trolleyOffset: 0, hoistHeight: 16 },
    onUpdate: (state) => {
      // 动画输出 → 驱动 Cesium 龙门吊（CR-02）
      twin3d?.setCranePose('CR-02', {
        gantryX: state.gantry[0],
        gantryY: state.gantry[1],
        trolleyOffset: state.trolleyOffset,
        hoistHeight: state.hoistHeight,
      })
    },
  },
)
const { start: startAnim, stop: stopAnim } = anim

// 监听设备状态变化 → 同步三维场景状态色
watch(
  () => deviceStore.devices,
  (devices) => {
    for (const [id, d] of Object.entries(devices)) {
      twin3d?.setStatus(id, d.status)
    }
  },
  { deep: true },
)

function setCategoryVisible(category: string, visible: boolean) {
  if (!yardDataSource) return
  for (const entity of yardDataSource.entities.values) {
    if ((entity.properties?.category?.getValue?.() ?? '') === category) {
      entity.show = visible
    }
  }
}

/** 使用真实 CAD 边界定相机，避免依赖未确认的场站地理原点或固定朝向。 */
function focusCadExtent(v: Cesium.Viewer) {
  flyToCameraPreset(v, 'overview', 0)
}

function onViewerReady(v: Cesium.Viewer) {
  viewer = v
  setupCameraInteraction(v)

  // 1. 加载 CAD 图层
  diag.cadStatus = '加载 CAD 图纸中'
  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) => Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
      // CAD stays visible as an engineering underlay, without competing with GLB assets.
      // CAD is a verification underlay, not the visual subject of the night view.
      stroke: Cesium.Color.fromCssColorString('#2A88A8').withAlpha(0.10),
      strokeWidth: 1,
      fill: Cesium.Color.fromCssColorString('#1D5D9B').withAlpha(0.006),
      clampToGround: false, // 不贴地，避免被深色 globe 淹没
      markerSize: 0,
    }))
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
      const message = err instanceof Error ? err.message : String(err)
      diag.cadStatus = `加载失败: ${message}`
      console.error('加载 CAD 图层失败:', err)
    })

  // 崩溃定位：?stable=1 只加载CAD图层 / ?stable=2 加设备无动画 / 默认全开
  const stable = new URL(window.location.href).searchParams.get('stable')
  const skipDevices = stable === '1'
  const skipAnim = stable === '1' || stable === '2'

  // 2. 程序化建模设备 + 点击交互
  if (!skipDevices) {
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
  }

  if (!skipDevices) {
    phase6Layers = new Phase6LayerController(v)
    phase6Layers.syncCargo(cargoItems.value)
    phase6Layers.syncPersonnel(personnelItems.value)
    phase6Layers.setCargoVisible(layers.value.cargo)
    phase6Layers.setPersonnelVisible(layers.value.personnel)
    createDemoController()
    resetDemo()
  }

  // 3. 启动龙门吊数据驱动动画（stable 模式跳过）
  if (!skipAnim && animRunning.value) startAnim()

  // 4. 启动 mock 数据流（设备状态 + 告警）
  if (!skipAnim && simRunning.value) {
    mockService = new MockDataService(deviceStore, alertStore)
    mockService.start()
    startPhase6Simulation()
  }

  // 暴露调试
  const win = window as unknown as Record<string, unknown>
  win.__dt_viewer = v
  win.__dt_twin3d = twin3d
  win.__Cesium = Cesium
  win.__dt_deviceStore = deviceStore
  win.__dt_alertStore = alertStore
  win.__dt_anim = {
    running: () => anim.running.value,
    getState: () => ({ ...anim.actorState.value }),
  }
}

function seedDemoDevices() {
  deviceStore.batchUpdate(MOCK_EQUIPMENT.map((item) => ({
    id: item.id,
    name: item.name,
    status: item.id === 'CR-02' ? 'ok' : 'standby',
    updatedAt: new Date().toISOString(),
  })))
}

function createDemoController() {
  if (demoController) return
  demoController = new SteelUnloadDemoController(
    {
      setCamera: (id) => { if (viewer) flyToCameraPreset(viewer, id) },
      setDeviceStatus: (id, status) => deviceStore.updateDevice(id, status),
      setStockyardQuantity: (quantity) => {
        const cargo = cargoItems.value.find((item) => item.id === 'CARGO-01')
        if (cargo) {
          cargo.quantity = quantity
          cargo.warning = quantity / cargo.capacity >= 0.8
          phase6Layers?.syncCargo(cargoItems.value)
        }
      },
      setUnloadOperation: (progress, status) => {
        const task = operationTasks.value.find((item) => item.id === 'OP-240801')
        if (task) {
          task.progress = progress
          task.status = status
        }
      },
      setVehiclePose: (id, pose) => twin3d?.setVehiclePose(id, pose),
      setCranePose: (pose) => twin3d?.setCranePose('CR-02', pose),
      addAlert: (alert) => alertStore.addAlert(alert),
      clearAlerts: () => alertStore.clear(),
      requestRender: () => viewer?.scene.requestRender(),
    },
    (state) => Object.assign(demoState, state),
  )
}

function stopPhase6Simulation() {
  if (phase6Timer) clearInterval(phase6Timer)
  phase6Timer = null
}

function resetDemo() {
  mockService?.stop()
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
  demoController?.reset()
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
    const person = personnelItems.value[Math.floor(Math.random() * personnelItems.value.length)]
    person.cadX += Math.round((Math.random() - 0.5) * 16)
    person.cadY += Math.round((Math.random() - 0.5) * 10)
    const cargo = cargoItems.value[1]
    cargo.quantity = Math.max(0, Math.min(cargo.capacity, cargo.quantity + (Math.random() > 0.5 ? 20 : -20)))
    cargo.warning = cargo.quantity / cargo.capacity > 0.9
    phase6Layers?.syncCargo(cargoItems.value)
    phase6Layers?.syncPersonnel(personnelItems.value)
  }, 4000)
}

function toggleLayer(category: 'track' | 'area' | 'cargo' | 'personnel') {
  layers.value[category] = !layers.value[category]
  if (category === 'cargo') phase6Layers?.setCargoVisible(layers.value.cargo)
  else if (category === 'personnel') phase6Layers?.setPersonnelVisible(layers.value.personnel)
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
    if (!mockService) mockService = new MockDataService(deviceStore, alertStore)
    mockService.start()
    startPhase6Simulation()
  } else {
    mockService?.stop()
    stopPhase6Simulation()
  }
}

function closeDetail() {
  detailVisible.value = false
}

onBeforeUnmount(() => {
  if (diagTimer) clearInterval(diagTimer)
  stopAnim()
  mockService?.stop()
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
      <span class="home-title">数字孪生可视化系统</span>
      <div class="home-actions">
        <span class="module-tag">模块：数字孪生可视化系统（S4）</span>
        <button class="theme-btn" @click="appStore.toggleTheme()">
          {{ appStore.theme === 'dark' ? '切换浅色' : '切换深色' }}
        </button>
        <RouterLink to="/bigscreen" class="screen-link">→ 大屏</RouterLink>
      </div>
    </header>

    <main class="scene-area">
      <CesiumViewer @viewer-ready="onViewerReady" />

      <!-- 图层管理面板（模块A） -->
      <div class="layer-panel">
        <div class="panel-title">图层管理</div>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.track" @change="toggleLayer('track')" />
          <span class="dot" style="background: #00b4d8"></span> 股道
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.area" @change="toggleLayer('area')" />
          <span class="dot" style="background: #f4a261"></span> 区域边界
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.cargo" @change="toggleLayer('cargo')" />
          <span class="dot" style="background: #60a5fa"></span> 货物库存
        </label>
        <label class="layer-item">
          <input type="checkbox" :checked="layers.personnel" @change="toggleLayer('personnel')" />
          <span class="dot" style="background: #34d399"></span> 场内人员
        </label>
        <div class="panel-divider"></div>
        <button class="reset-btn" @click="onResetView">重置视角</button>
        <div class="panel-divider"></div>
        <div class="camera-title">镜头预设</div>
        <div class="camera-grid">
          <button class="reset-btn" @click="onCameraPreset('overview')">核心总览</button>
          <button class="reset-btn" @click="onCameraPreset('gantry')">龙门吊近景</button>
          <button class="reset-btn" @click="onCameraPreset('rebar')">钢筋仓位近景</button>
          <button class="reset-btn" @click="onCameraPreset('route')">作业路线</button>
          <button class="reset-btn" @click="onCameraPreset('processing')">物料加工区</button>
          <button class="reset-btn" @click="onCameraPreset('steel')">钢材堆场</button>
          <button class="reset-btn" @click="onCameraPreset('ash')">煤灰筒仓线</button>
        </div>
        <div class="demo-panel">
          <div class="demo-title">答辩演示 · 钢材卸车</div>
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
          龙门吊动画 {{ animRunning ? '开' : '关' }}
        </button>
        <button class="reset-btn" :class="{ active: simRunning }" @click="toggleSim">
          状态模拟 {{ simRunning ? '开' : '关' }}
        </button>
      </div>

      <!-- 设备详情弹窗（模块B） -->
      <div v-if="detailVisible" class="detail-card">
        <div class="detail-header">
          <span class="detail-title">{{ selectedInfo.name }}</span>
          <button class="close-btn" @click="closeDetail">✕</button>
        </div>
        <div class="detail-body">
          <div class="detail-row"><span class="label">编号</span><span>{{ selectedInfo.id }}</span></div>
          <div class="detail-row"><span class="label">类型</span><span>{{ selectedInfo.type }}</span></div>
          <div class="detail-row">
            <span class="label">状态</span>
            <span class="status-pill">{{ selectedInfo.statusName }}</span>
          </div>
          <template v-for="(val, key) in selectedInfo.params" :key="key">
            <div class="detail-row"><span class="label">{{ key }}</span><span>{{ val }}</span></div>
          </template>
        </div>
      </div>

      <!-- 场景诊断浮层 -->
      <div v-if="showDiagnostics" class="diag-panel">
        <div class="diag-title">🩺 场景诊断</div>
        <div class="diag-row">WebGL: <b :class="diag.webgl ? 'ok' : 'bad'">{{ diag.webgl ? '✅ 支持' : '❌ 不支持' }}</b></div>
        <div class="diag-row">Viewer: <b :class="diag.viewer ? 'ok' : 'bad'">{{ diag.viewer ? '✅ 已创建' : '❌ 未创建' }}</b></div>
        <div class="diag-row">CAD: {{ diag.cadStatus }}</div>
        <div class="diag-row">设备实体: {{ diag.deviceEntities }}</div>
        <div class="diag-row">相机: {{ diag.camera }}</div>
        <div class="diag-row">Globe: {{ diag.globeShow ? '显示' : '隐藏' }}</div>
        <div class="diag-row" style="font-size:11px;color:var(--text-secondary)">v0.1.4-debug</div>
      </div>

      <!-- 实时数据面板（设备状态 + 告警） -->
      <div class="data-panel">
        <div class="data-title">实时数据</div>
        <div class="device-list">
          <div v-for="(d, id) in deviceStore.devices" :key="id" class="device-item">
            <span class="device-dot" :style="{ background: d.status === 'ok' ? '#34D399' : d.status === 'standby' ? '#F5B84C' : d.status === 'fault' ? '#EF4444' : '#64748B' }"></span>
            <span class="device-name">{{ d.name }}</span>
            <span class="device-status">{{ deviceStore.getStatusName(id) }}</span>
          </div>
        </div>
        <div class="alert-list">
          <div v-for="a in alertStore.alerts.slice(0, 3)" :key="a.id" class="alert-item">
            <span class="alert-level" :class="a.level">{{ a.level === 'critical' ? '紧急' : a.level === 'warning' ? '严重' : '一般' }}</span>
            <span class="alert-text">{{ a.targetName }} {{ a.message }}</span>
          </div>
          <div v-if="alertStore.alerts.length === 0" class="alert-empty">暂无告警</div>
        </div>
      </div>

      <section class="phase6-panel" aria-label="货物、人员与作业态势">
        <div class="phase6-section">
          <div class="data-title">货物库存</div>
          <div v-for="cargo in cargoItems" :key="cargo.id" class="phase6-row">
            <span class="commodity-dot" :class="cargo.kind"></span>
            <span class="phase6-name">{{ cargo.name }}</span>
            <span :class="['phase6-value', { warning: cargo.warning }]">
              {{ cargo.quantity.toLocaleString() }}t / {{ cargo.capacity.toLocaleString() }}t
            </span>
          </div>
        </div>
        <div class="phase6-section">
          <div class="data-title">作业进度</div>
          <div v-for="task in operationTasks" :key="task.id" class="operation-row">
            <div class="operation-meta"><span>{{ task.name }}</span><span>{{ task.equipment }} · {{ task.progress }}%</span></div>
            <div class="progress-track"><span :class="task.status" :style="{ width: `${task.progress}%` }"></span></div>
          </div>
        </div>
        <div class="phase6-section personnel-summary">
          <span>场内人员 {{ personnelItems.length }} 人</span>
          <span class="warning-count">安全关注 {{ personnelItems.filter((person) => person.status !== 'normal').length }} 人</span>
        </div>
      </section>

      <div class="status-bar">
        <span class="status-item">v0.1.3-camera-fix</span>
        <span class="status-item">设备：{{ deviceStore.getSummary().total }} | 告警：{{ alertStore.unhandledCount }}</span>
        <span class="status-item">龙门吊动画：{{ animRunning ? '运行中' : '已停止' }}</span>
        <span class="status-item">点击设备查看详情</span>
      </div>
    </main>

    <footer class="home-footer">统一基线 · Vue 3 + Pinia + Element Plus + CesiumJS · 当前主题: {{ appStore.theme }}</footer>
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
    padding: var(--space-3) var(--space-5);
    background: var(--bg-panel);
    border-bottom: 1px solid var(--border-color);

    .home-title {
      font-size: var(--font-size-page-title);
      font-weight: 700;
    }

    .home-actions {
      display: flex;
      align-items: center;
      gap: var(--space-4);

      .module-tag {
        font-size: 12px;
        color: var(--text-secondary);
      }

      .theme-btn {
        padding: var(--space-1) var(--space-3);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-sm);
        background: var(--bg-panel-alt);
        color: var(--text-primary);
        cursor: pointer;

        &:hover {
          background: var(--brand);
        }
      }

      .screen-link {
        color: var(--highlight);
        text-decoration: none;
        font-size: 14px;

        &:hover {
          text-decoration: underline;
        }
      }
    }
  }

  .scene-area {
    flex: 1;
    min-height: 0;
    height: 0;
    position: relative;
    overflow: hidden;

    .layer-panel {
      position: absolute;
      top: var(--space-4);
      left: var(--space-4);
      width: 180px;
      padding: var(--space-3);
      background: var(--bg-panel);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
      z-index: 10;

      .panel-title {
        font-size: 13px;
        font-weight: 600;
        color: var(--highlight);
        margin-bottom: var(--space-3);
      }

      .layer-item {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        font-size: 13px;
        color: var(--text-primary);
        margin-bottom: var(--space-2);
        cursor: pointer;

        input[type='checkbox'] {
          accent-color: var(--highlight);
        }

        .dot {
          width: 10px;
          height: 10px;
          border-radius: 50%;
          display: inline-block;
        }
      }

      .panel-divider {
        border-top: 1px solid var(--border-color);
        margin: var(--space-3) 0;
      }

      .reset-btn {
        width: 100%;
        padding: var(--space-1) var(--space-2);
        border: 1px solid var(--border-color);
        border-radius: var(--radius-sm);
        background: var(--bg-panel-alt);
        color: var(--text-primary);
        cursor: pointer;
        font-size: 12px;
        margin-bottom: var(--space-2);

        &:hover,
        &.active {
          background: var(--brand);
        }
      }

      .camera-title {
        margin-bottom: var(--space-2);
        color: var(--text-secondary);
        font-size: 12px;
      }

      .camera-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--space-1);

        .reset-btn {
          margin-bottom: 0;
        }
      }

      .demo-panel {
        margin: var(--space-3) 0;
        padding: var(--space-2);
        border: 1px solid rgba(0, 229, 255, 0.34);
        border-radius: var(--radius-sm);
        background: rgba(0, 73, 112, 0.2);

        .demo-title {
          color: var(--highlight);
          font-size: 12px;
          font-weight: 600;
          margin-bottom: 4px;
        }

        .demo-step {
          color: var(--text-primary);
          font-size: 12px;
          line-height: 1.35;
        }

        .demo-note {
          color: var(--text-secondary);
          font-size: 11px;
          line-height: 1.4;
          margin-top: 3px;
        }

        .demo-progress {
          height: 5px;
          overflow: hidden;
          margin: 7px 0;
          border-radius: 999px;
          background: rgba(148, 163, 184, 0.24);

          span {
            display: block;
            height: 100%;
            border-radius: inherit;
            background: linear-gradient(90deg, #00b4d8, #34d399);
            transition: width 0.12s linear;
          }
        }

        .demo-actions {
          display: grid;
          grid-template-columns: 1.3fr 0.8fr 0.8fr;
          gap: 4px;
        }

        .demo-btn {
          min-width: 0;
          padding: 5px 3px;
          border: 1px solid var(--border-color);
          border-radius: 4px;
          background: var(--bg-panel-alt);
          color: var(--text-primary);
          cursor: pointer;
          font-size: 11px;

          &:disabled { opacity: 0.45; cursor: not-allowed; }
          &.primary { border-color: rgba(0, 229, 255, 0.5); color: #d5faff; }
        }
      }
    }

    .detail-card {
      position: absolute;
      top: var(--space-4);
      right: var(--space-4);
      width: 300px;
      background: var(--bg-panel);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
      z-index: 10;

      .detail-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-3);
        border-bottom: 1px solid var(--border-color);

        .detail-title {
          font-size: 15px;
          font-weight: 600;
          color: var(--highlight);
        }

        .close-btn {
          border: none;
          background: transparent;
          color: var(--text-secondary);
          cursor: pointer;
          font-size: 14px;
        }
      }

      .detail-body {
        padding: var(--space-3);

        .detail-row {
          display: flex;
          justify-content: space-between;
          padding: var(--space-1) 0;
          font-size: 13px;
          color: var(--text-primary);

          .label {
            color: var(--text-secondary);
          }

          .status-pill {
            padding: 0 var(--space-2);
            border-radius: var(--radius-sm);
            background: var(--status-ok);
            color: #13243a;
            font-size: 12px;
          }
        }
      }
    }

    .data-panel {
      position: absolute;
      top: var(--space-4);
      right: var(--space-4);
      width: 260px;
      background: var(--bg-panel);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
      z-index: 9;

      .data-title {
        font-size: 13px;
        font-weight: 600;
        color: var(--highlight);
        padding: var(--space-3) var(--space-3) var(--space-2);
        border-bottom: 1px solid var(--border-color);
      }

      .device-list {
        padding: var(--space-2) var(--space-3);

        .device-item {
          display: flex;
          align-items: center;
          gap: var(--space-2);
          padding: 2px 0;
          font-size: 12px;
          color: var(--text-primary);

          .device-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
          }

          .device-name {
            flex: 1;
          }

          .device-status {
            color: var(--text-secondary);
          }
        }
      }

      .alert-list {
        padding: var(--space-2) var(--space-3) var(--space-3);

        .alert-item {
          display: flex;
          align-items: center;
          gap: var(--space-2);
          padding: 2px 0;
          font-size: 12px;

          .alert-level {
            padding: 0 4px;
            border-radius: 3px;
            font-size: 11px;
            background: var(--status-ok);

            &.warning {
              background: var(--status-warn);
            }
            &.critical {
              background: var(--status-error);
              color: #fff;
            }
          }

          .alert-text {
            color: var(--text-primary);
          }
        }

        .alert-empty {
          color: var(--text-secondary);
          font-size: 12px;
          padding: var(--space-1) 0;
        }
      }
    }

    .phase6-panel {
      position: absolute;
      right: var(--space-4);
      bottom: 52px;
      width: 320px;
      background: var(--bg-panel);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
      z-index: 9;

      .phase6-section {
        padding: var(--space-2) var(--space-3);
        border-bottom: 1px solid var(--border-color);

        &:last-child {
          border-bottom: 0;
        }

        .data-title {
          margin: calc(var(--space-2) * -1) calc(var(--space-3) * -1) var(--space-2);
          padding: var(--space-2) var(--space-3);
          border-bottom: 1px solid var(--border-color);
          color: var(--highlight);
          font-size: 13px;
          font-weight: 600;
        }
      }

      .phase6-row,
      .operation-meta,
      .personnel-summary {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        min-width: 0;
        font-size: 12px;
        color: var(--text-primary);
      }

      .phase6-row {
        padding: 3px 0;
      }

      .commodity-dot {
        width: 8px;
        height: 8px;
        flex: 0 0 auto;
        border-radius: 50%;
        background: #60a5fa;

        &.fly_ash { background: var(--status-warn); }
        &.cement { background: #e5e7eb; }
      }

      .phase6-name {
        flex: 1;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .phase6-value {
        color: var(--text-secondary);
        white-space: nowrap;

        &.warning { color: var(--status-error); }
      }

      .operation-row {
        padding: 3px 0 5px;
      }

      .operation-meta {
        justify-content: space-between;
        margin-bottom: 4px;

        span:last-child { color: var(--text-secondary); }
      }

      .progress-track {
        height: 5px;
        overflow: hidden;
        background: var(--bg-panel-alt);
        border-radius: 3px;

        span {
          display: block;
          height: 100%;
          background: var(--status-ok);
          transition: width 0.3s ease;

          &.queued { background: var(--status-warn); }
          &.warning { background: var(--status-error); }
          &.done { background: var(--accent-blue); }
        }
      }

      .personnel-summary {
        justify-content: space-between;
        color: var(--text-secondary);

        .warning-count { color: var(--status-warn); }
      }
    }

    .diag-panel {
      position: absolute;
      top: var(--space-4);
      left: 50%;
      transform: translateX(-50%);
      width: 240px;
      padding: var(--space-2) var(--space-3);
      background: rgba(7, 18, 40, 0.9);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      font-size: 12px;
      z-index: 12;

      .diag-title {
        font-weight: 700;
        color: var(--highlight);
        margin-bottom: var(--space-1);
      }

      .diag-row {
        padding: 1px 0;
        color: var(--text-primary);

        .ok {
          color: var(--status-ok);
        }
        .bad {
          color: var(--status-error);
        }
      }
    }

    .status-bar {
      position: absolute;
      bottom: var(--space-3);
      left: 50%;
      transform: translateX(-50%);
      display: flex;
      gap: var(--space-5);
      padding: var(--space-2) var(--space-4);
      background: rgba(7, 18, 40, 0.85);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-md);
      z-index: 10;

      .status-item {
        font-size: 12px;
        color: var(--text-secondary);
      }
    }
  }

  .home-footer {
    padding: var(--space-2) var(--space-5);
    font-size: 12px;
    color: var(--text-secondary);
    background: var(--bg-panel);
    border-top: 1px solid var(--border-color);
  }

  @media (max-width: 1100px) {
    .scene-area .diag-panel { display: none; }
    .scene-area .phase6-panel { width: 280px; }
  }
}
</style>
