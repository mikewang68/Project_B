<script setup lang="ts">
/**
 * 指挥中心大屏（模块 F）
 *
 * 该页面直接复用与首页相同的 Twin3dController、GLB 清单、CAD 底图、
 * 钢材卸车演示控制器和 Pinia 数据源；它不是静态截图或占位页。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as Cesium from 'cesium'
import * as echarts from 'echarts'
import CesiumViewer from '@/components/cesium/CesiumViewer.vue'
import { setupCameraInteraction } from '@/components/cesium/cameraControl'
import { Twin3dController, type SelectedInfo } from '@/twin3d'
import { MOCK_EQUIPMENT } from '@/twin3d/mock/mockData'
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
import {
  SteelUnloadDemoController,
  type DemoViewState,
} from '@/twin3d/steelUnloadDemo'
import { useAlertStore } from '@/stores/alert'
import { useDeviceStore } from '@/stores/device'
import { STATUS_NAMES } from '@/twin3d/constants'

type ThemeId = 'overview' | 'operation' | 'safety' | 'storage' | 'equipment' | 'energy'

interface BigScreenTheme {
  id: ThemeId
  label: string
  title: string
  note: string
  camera: CameraPresetId
  chartTitle: string
}

const THEMES: BigScreenTheme[] = [
  { id: 'overview', label: '综合态势', title: '场站综合态势', note: '全站设备、库存与任务概览', camera: 'overview', chartTitle: '24 小时综合吞吐趋势' },
  { id: 'operation', label: '作业进度', title: '钢材卸车作业', note: '龙门吊、车辆与任务进度联动', camera: 'gantry', chartTitle: '作业效率趋势' },
  { id: 'safety', label: '安全监控', title: '区域安全态势', note: '仅展示区域人数和状态，不展示个人信息', camera: 'route', chartTitle: '安全事件趋势' },
  { id: 'storage', label: '仓储管理', title: '钢筋露天仓位', note: '13 个料位库存容量与关注线', camera: 'steel', chartTitle: '库存占用趋势' },
  { id: 'equipment', label: '设备健康', title: '关键设备状态', note: '三台龙门吊与钢材线设备状态汇总', camera: 'processing', chartTitle: '设备可用率趋势' },
  { id: 'energy', label: '能源管控', title: '煤灰储运工艺线', note: '煤灰两轨、筒仓组与输送带；单仓尺寸为比例推断', camera: 'ash', chartTitle: '模拟综合能耗趋势' },
]

const deviceStore = useDeviceStore()
const alertStore = useAlertStore()

let viewer: Cesium.Viewer | null = null
let yardDataSource: Cesium.GeoJsonDataSource | null = null
let twin3d: Twin3dController | null = null
let phase6Layers: Phase6LayerController | null = null
let demoController: SteelUnloadDemoController | null = null
let carouselTimer: ReturnType<typeof setInterval> | null = null
let carouselResumeTimer: ReturnType<typeof setTimeout> | null = null
let alertShrinkTimer: ReturnType<typeof setTimeout> | null = null
let chart: echarts.ECharts | null = null
let chartResizeObserver: ResizeObserver | null = null

const chartRef = ref<HTMLElement | null>(null)
const selectedInfo = ref<SelectedInfo | null>(null)
const sceneStatus = ref<'loading' | 'ready' | 'error'>('loading')
const sceneError = ref('')
const activeTheme = ref<ThemeId>('overview')
const carouselPaused = ref(false)
const alertExpanded = ref(true)
const alertOverlay = ref(false)
const alertMinimized = ref(false)
const cargoItems = ref<CargoItem[]>(structuredClone(MOCK_CARGO))
const personnelItems = ref<PersonnelItem[]>(structuredClone(MOCK_PERSONNEL))
const operationTasks = ref<OperationTask[]>(structuredClone(MOCK_OPERATIONS))
const demoState = reactive<DemoViewState>({
  running: false,
  completed: false,
  stepIndex: 0,
  stepCount: 5,
  title: '钢材卸车与堆存演示',
  narration: '场景加载后可播放可复现的钢材卸车验收流程。',
  progress: 0,
})

const activeThemeInfo = computed(() => THEMES.find((theme) => theme.id === activeTheme.value) ?? THEMES[0])
const activeTask = computed(() => operationTasks.value.find((task) => task.id === 'OP-240801') ?? operationTasks.value[0])
const stockyard = computed(() => cargoItems.value.find((item) => item.id === 'CARGO-01') ?? cargoItems.value[0])
const deviceSummary = computed(() => deviceStore.getSummary())
const criticalAlert = computed(() => alertStore.alerts.find((alert) => alert.level === 'critical' && !alert.handled) ?? null)
const unreadAlerts = computed(() => alertStore.unhandledCount)
const stockUsage = computed(() => stockyard.value ? Math.round((stockyard.value.quantity / stockyard.value.capacity) * 100) : 0)
const kpis = computed(() => [
  { label: '今日吞吐量', value: `${(1280 + Math.round(demoState.progress * 7.2)).toLocaleString()} t`, delta: '+6.8%', tone: 'cyan' },
  { label: '卸车效率', value: `${(22.6 + demoState.progress * 0.035).toFixed(1)} t/h`, delta: '+2.1%', tone: 'blue' },
  { label: '装车效率', value: '18.4 t/h', delta: '持平', tone: 'violet' },
  { label: '转运效率', value: `${Math.min(98, 82 + Math.round(demoState.progress * 0.08))}%`, delta: '+1.4%', tone: 'green' },
  { label: '未处理告警', value: String(unreadAlerts.value), delta: unreadAlerts.value ? '待核验' : '运行平稳', tone: unreadAlerts.value ? 'amber' : 'green' },
])

function seedDevices() {
  deviceStore.batchUpdate(MOCK_EQUIPMENT.map((item) => ({
    id: item.id,
    name: item.name,
    status: item.id === 'CR-02' ? 'ok' : 'standby',
    updatedAt: new Date().toISOString(),
  })))
}

function setCategoryVisible(category: string, visible: boolean) {
  if (!yardDataSource) return
  for (const entity of yardDataSource.entities.values) {
    if ((entity.properties?.category?.getValue?.() ?? '') === category) entity.show = visible
  }
}

function setCamera(id: CameraPresetId) {
  if (viewer) flyToCameraPreset(viewer, id)
}

function syncOperationalLayers() {
  phase6Layers?.syncCargo(cargoItems.value)
  phase6Layers?.syncPersonnel(personnelItems.value)
  // 大屏综合态势默认显示货物与人员的低干扰图层；人员仅通过区域统计呈现。
  phase6Layers?.setCargoVisible(true)
  phase6Layers?.setPersonnelVisible(activeTheme.value === 'safety')
}

function createDemoController() {
  if (demoController) return
  demoController = new SteelUnloadDemoController(
    {
      setCamera,
      setDeviceStatus: (id, status) => deviceStore.updateDevice(id, status),
      setStockyardQuantity: (quantity) => {
        const cargo = cargoItems.value.find((item) => item.id === 'CARGO-01')
        if (!cargo) return
        cargo.quantity = quantity
        cargo.warning = quantity / cargo.capacity >= 0.8
        phase6Layers?.syncCargo(cargoItems.value)
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

function resetDemo() {
  seedDevices()
  alertStore.clear()
  cargoItems.value = structuredClone(MOCK_CARGO)
  personnelItems.value = structuredClone(MOCK_PERSONNEL)
  operationTasks.value = structuredClone(MOCK_OPERATIONS)
  syncOperationalLayers()
  twin3d?.setVehicleRouteAnimation(false)
  demoController?.reset()
}

function startDemo() {
  resetDemo()
  demoController?.start()
}

function pauseDemo() {
  demoController?.pause()
}

function toggleDemo() {
  if (demoState.running) pauseDemo()
  else if (demoState.completed || demoState.progress === 0) startDemo()
  else demoController?.start()
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

function focusCriticalAlert() {
  const alert = criticalAlert.value
  if (!alert) return
  const target = alert.targetId
  if (target.startsWith('CR-')) setCamera('gantry')
  else if (target.startsWith('REBAR-BAY-')) setCamera('rebar')
  else setCamera('overview')
}

function acknowledgeCritical() {
  const alert = criticalAlert.value
  if (alert) alertStore.markHandled(alert.id)
  alertOverlay.value = false
  alertMinimized.value = false
  if (!carouselPaused.value) startCarousel()
}

function updateChart() {
  if (!chart) return
  const chartSeries: Record<ThemeId, number[]> = {
    overview: [48, 54, 51, 68, 72, 65, 78, 86, 91, 88, 96, 104],
    operation: [73, 76, 74, 79, 82, 84, 80, 86, 89, 91, 88, 93],
    safety: [1, 0, 2, 1, 1, 0, 3, 1, 0, 1, 0, 0],
    storage: [68, 69, 70, 70, 71, 72, 73, 75, 76, 78, 80, stockUsage.value],
    equipment: [94, 94, 95, 96, 95, 96, 97, 96, 97, 98, 97, 98],
    energy: [42, 46, 45, 50, 54, 52, 59, 61, 63, 60, 66, 64],
  }
  const safety = activeTheme.value === 'safety'
  chart.setOption({
    animationDuration: 300,
    backgroundColor: 'transparent',
    grid: { left: 42, right: 18, top: 34, bottom: 24 },
    title: { text: activeThemeInfo.value.chartTitle, left: 10, top: 2, textStyle: { color: '#b8d7ed', fontSize: 13, fontWeight: 500 } },
    xAxis: { type: 'category', boundaryGap: false, data: ['00', '02', '04', '06', '08', '10', '12', '14', '16', '18', '20', '22'], axisLine: { lineStyle: { color: '#24455f' } }, axisLabel: { color: '#6f93ab', fontSize: 10 } },
    yAxis: { type: 'value', min: 0, splitLine: { lineStyle: { color: 'rgba(68, 123, 151, 0.16)' } }, axisLabel: { color: '#6f93ab', fontSize: 10 } },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(5, 20, 37, .96)', borderColor: '#237b9b', textStyle: { color: '#e4f8ff' } },
    series: [{
      type: safety ? 'bar' : 'line',
      data: chartSeries[activeTheme.value],
      smooth: !safety,
      symbol: 'none',
      barMaxWidth: 18,
      itemStyle: { color: safety ? '#f6b44d' : '#16d7e7' },
      lineStyle: { width: 2, color: '#16d7e7' },
      areaStyle: safety ? undefined : { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(22, 215, 231, .38)' }, { offset: 1, color: 'rgba(22, 215, 231, .02)' }]) },
    }],
  }, true)
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
  sceneStatus.value = 'loading'
  nextViewer.scene.requestRender()

  fetch(CAD_GEOJSON_URL)
    .then((response) => {
      if (!response.ok) throw new Error(`CAD 图层 HTTP ${response.status}`)
      return response.json()
    })
    .then((cadGeoJson) => Cesium.GeoJsonDataSource.load(cadGeoJsonToWgs84(cadGeoJson), {
      stroke: Cesium.Color.fromCssColorString('#2A88A8').withAlpha(0.08),
      strokeWidth: 1,
      fill: Cesium.Color.fromCssColorString('#1D5D9B').withAlpha(0.005),
      clampToGround: false,
      markerSize: 0,
    }))
    .then((dataSource) => {
      nextViewer.dataSources.add(dataSource)
      yardDataSource = dataSource
      // The big screen is a presentation surface.  CAD stays locally loaded
      // for verification and failure isolation, but is hidden by default so
      // its engineering linework never competes with the GLB scene.
      setCategoryVisible('track', false)
      setCategoryVisible('area', false)
      setCamera('overview')
    })
    .catch((error: unknown) => {
      sceneError.value = error instanceof Error ? error.message : String(error)
    })
    .finally(() => {
      // CAD 失败不会阻止 GLB 资产和业务面板；这是大屏的局部降级策略。
      seedDevices()
      twin3d = new Twin3dController(nextViewer, { onSelect: (info) => { selectedInfo.value = info } })
      twin3d.setVehicleRouteAnimation(false)
      phase6Layers = new Phase6LayerController(nextViewer)
      syncOperationalLayers()
      createDemoController()
      resetDemo()
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

watch(criticalAlert, (alert) => {
  if (!alert) return
  stopCarousel()
  alertExpanded.value = true
  alertOverlay.value = true
  alertMinimized.value = false
  focusCriticalAlert()
  if (alertShrinkTimer) clearTimeout(alertShrinkTimer)
  alertShrinkTimer = setTimeout(() => {
    alertMinimized.value = true
    alertOverlay.value = false
  }, 15_000)
})

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
  chartResizeObserver?.disconnect()
  chartResizeObserver = null
  chart?.dispose()
  chart = null
  demoController?.destroy()
  phase6Layers?.destroy()
  twin3d?.destroy()
  viewer = null
  yardDataSource = null
})
</script>

<template>
  <main class="bigscreen" @pointerenter="pauseOnPointerEnter" @pointerleave="resumeAfterPointerLeave">
    <header class="screen-header">
      <div>
        <p class="eyebrow">RAIL FREIGHT DIGITAL TWIN · OFFLINE DEMONSTRATION</p>
        <h1>{{ activeThemeInfo.title }}</h1>
      </div>
      <div class="header-meta">
        <span class="data-badge">本地模拟 / 推断数据</span>
        <span class="clock">{{ new Date().toLocaleDateString('zh-CN') }}</span>
        <RouterLink to="/" class="back-link">返回三维作业台</RouterLink>
      </div>
    </header>

    <section class="kpi-row" aria-label="运营关键指标">
      <article v-for="kpi in kpis" :key="kpi.label" class="kpi-card" :class="`tone-${kpi.tone}`">
        <span>{{ kpi.label }}</span>
        <strong>{{ kpi.value }}</strong>
        <small>{{ kpi.delta }}</small>
      </article>
    </section>

    <section class="screen-grid">
      <aside class="side-column left-column">
        <section class="panel alert-panel">
          <div class="panel-heading"><span>实时告警</span><em>{{ unreadAlerts }} 条待处理</em></div>
          <div v-if="alertStore.alerts.length" class="alert-list">
            <button v-for="alert in alertStore.alerts.slice(0, 5)" :key="alert.id" class="alert-item" :class="alert.level" @click="focusCriticalAlert">
              <span class="level-dot"></span>
              <span><b>{{ alert.targetName }}</b><small>{{ alert.message }}</small></span>
            </button>
          </div>
          <div v-else class="empty-state">当前无未处理告警<br /><small>演示进入第 5 阶段后将生成堆位关注告警</small></div>
        </section>

        <section class="panel zone-panel">
          <div class="panel-heading"><span>区域安全概览</span><em>脱敏</em></div>
          <div class="zone-metrics">
            <div><strong>{{ personnelItems.length }}</strong><span>场内人员</span></div>
            <div><strong>0</strong><span>围栏入侵</span></div>
            <div><strong>1</strong><span>重点关注</span></div>
          </div>
          <p>人员姓名及精确位置仅在作业台授权界面显示。</p>
        </section>
      </aside>

      <section class="scene-panel">
        <CesiumViewer @viewer-ready="onViewerReady" />
        <div v-if="sceneStatus === 'loading'" class="scene-overlay loading">正在加载本地 CAD 与 GLB 场站资产…</div>
        <div v-else-if="sceneStatus === 'error'" class="scene-overlay warning">CAD 底图未完整加载：{{ sceneError }}<br /><small>三维资产和业务面板仍可继续使用。</small></div>
        <div class="scene-caption">
          <span class="live-dot"></span>
          {{ activeThemeInfo.note }}
          <button class="scene-action" @click="setCamera(activeThemeInfo.camera)">复位镜头</button>
        </div>
        <div v-if="selectedInfo" class="selection-card">
          <b>{{ selectedInfo.name }}</b><span>{{ selectedInfo.id }} · {{ selectedInfo.statusName }}</span>
        </div>
      </section>

      <aside class="side-column right-column">
        <section class="panel task-panel">
          <div class="panel-heading"><span>钢材卸车任务</span><em>{{ demoState.progress }}%</em></div>
          <h2>{{ demoState.title }}</h2>
          <p>{{ demoState.narration }}</p>
          <div class="progress-track"><span :style="{ width: `${demoState.progress}%` }"></span></div>
          <div class="task-meta">
            <span>任务状态：{{ activeTask?.status === 'done' ? '已完成' : demoState.running ? '执行中' : '待启动' }}</span>
            <span>阶段 {{ Math.min(demoState.stepIndex + 1, demoState.stepCount) }}/{{ demoState.stepCount }}</span>
          </div>
          <div class="demo-controls">
            <button class="primary" @click="toggleDemo">{{ demoState.running ? '暂停演示' : demoState.completed ? '重新演示' : '开始演示' }}</button>
            <button @click="resetDemo">重置</button>
          </div>
        </section>

        <section class="panel equipment-panel">
          <div class="panel-heading"><span>关键设备状态</span><em>{{ deviceSummary.total }} 台</em></div>
          <div v-for="device in Object.values(deviceStore.devices).slice(0, 5)" :key="device.id" class="equipment-row">
            <span class="status-led" :class="device.status"></span><b>{{ device.name }}</b><em>{{ STATUS_NAMES[device.status] }}</em>
          </div>
        </section>

        <section class="panel stock-panel">
          <div class="panel-heading"><span>钢筋露天仓位汇总</span><em>{{ stockUsage }}%</em></div>
          <strong>{{ stockyard?.quantity.toLocaleString() }} t</strong><span>容量 {{ stockyard?.capacity.toLocaleString() }} t</span>
          <div class="stock-bar"><span :style="{ width: `${stockUsage}%` }"></span></div>
        </section>
      </aside>
    </section>

    <section class="chart-row panel"><div ref="chartRef" class="trend-chart"></div></section>

    <footer class="screen-footer">
      <nav class="theme-tabs" aria-label="大屏主题">
        <button v-for="theme in THEMES" :key="theme.id" :class="{ active: activeTheme === theme.id }" @click="applyTheme(theme.id)">{{ theme.label }}</button>
      </nav>
      <button class="carousel-control" @click="toggleCarousel">{{ carouselPaused ? '▶ 恢复自动轮播' : 'Ⅱ 暂停自动轮播' }}</button>
      <span>轮播间隔 30 秒 · 鼠标移入暂停 · 离开 5 秒后恢复</span>
    </footer>

    <div v-if="alertOverlay && criticalAlert" class="alert-modal" role="alertdialog">
      <p>严重告警 · 轮播已暂停</p>
      <h2>{{ criticalAlert.targetName }}</h2>
      <span>{{ criticalAlert.message }}</span>
      <div><button @click="focusCriticalAlert">定位场景</button><button class="primary" @click="acknowledgeCritical">确认告警</button></div>
    </div>
    <button v-if="alertMinimized && criticalAlert" class="critical-toast" @click="alertOverlay = true">严重告警：{{ criticalAlert.targetName }}</button>
  </main>
</template>

<style scoped lang="scss">
.bigscreen {
  --panel-bg: rgba(8, 26, 47, .88);
  --panel-border: rgba(45, 133, 173, .48);
  min-width: 1180px;
  min-height: 720px;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  color: #d9f1ff;
  background:
    radial-gradient(circle at 48% 32%, rgba(10, 72, 106, .22), transparent 34%),
    linear-gradient(135deg, #020918 0%, #061427 48%, #020a17 100%);
  padding: clamp(12px, 1.25vw, 24px) clamp(18px, 1.8vw, 34px) 14px;
  display: grid;
  grid-template-rows: auto auto minmax(360px, 1fr) 136px auto;
  gap: 10px;
  position: relative;
}

.screen-header, .kpi-row, .screen-grid, .screen-footer { min-width: 0; }
.screen-header { display: flex; align-items: end; justify-content: space-between; gap: 20px; }
.eyebrow { margin: 0 0 4px; color: #2abdd4; font-size: clamp(9px, .58vw, 12px); letter-spacing: .15em; }
h1 { margin: 0; font-size: clamp(22px, 1.65vw, 34px); letter-spacing: .06em; color: #e1fbff; }
.header-meta { display: flex; align-items: center; gap: 12px; color: #87a9bd; font-size: 12px; }
.data-badge { border: 1px solid rgba(42, 189, 212, .48); color: #6edbe9; padding: 4px 8px; border-radius: 99px; }
.back-link { color: #b8eaff; text-decoration: none; }

.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
.kpi-card, .panel { border: 1px solid var(--panel-border); background: var(--panel-bg); box-shadow: inset 0 1px 0 rgba(112, 211, 255, .04), 0 12px 28px rgba(0, 0, 0, .16); }
.kpi-card { min-height: 68px; padding: 9px 14px; border-radius: 5px; display: grid; grid-template-columns: 1fr auto; align-items: center; }
.kpi-card span { color: #83a8bd; font-size: 12px; }.kpi-card strong { grid-row: 2; font-size: clamp(20px, 1.4vw, 29px); line-height: 1.05; color: #e5fbff; }.kpi-card small { grid-column: 2; grid-row: 2; color: #77d6b2; font-size: 11px; }
.tone-amber small { color: #f6ba57; }.tone-violet strong { color: #d5c2ff; }.tone-blue strong { color: #84caff; }.tone-green strong { color: #7be6bb; }

.screen-grid { min-height: 0; display: grid; grid-template-columns: minmax(210px, 16%) minmax(500px, 1fr) minmax(240px, 18%); gap: 10px; }
.side-column { min-height: 0; display: grid; gap: 10px; }.left-column { grid-template-rows: minmax(0, 1fr) auto; }.right-column { grid-template-rows: minmax(0, 1fr) auto auto; }
.panel { border-radius: 5px; padding: 12px; min-width: 0; overflow: hidden; }.panel-heading { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid rgba(72, 143, 174, .28); padding-bottom: 8px; color: #d7f7ff; font-weight: 600; font-size: 13px; }.panel-heading em, .equipment-row em { color: #63c9dc; font-style: normal; font-size: 11px; font-weight: 400; }
.scene-panel { min-height: 0; position: relative; border: 1px solid rgba(42, 146, 185, .6); overflow: hidden; background: #030a17; box-shadow: 0 0 30px rgba(0, 129, 175, .1); }.scene-panel :deep(.cesium-viewer) { min-width: 0; min-height: 0; height: 100%; }.scene-caption { position: absolute; left: 12px; bottom: 10px; right: 12px; display: flex; align-items: center; gap: 7px; padding: 7px 9px; border-left: 2px solid #15d4e6; background: linear-gradient(90deg, rgba(3, 15, 30, .88), rgba(3, 15, 30, .25)); color: #bdebf4; font-size: 12px; pointer-events: none; }.live-dot { height: 7px; width: 7px; border-radius: 50%; background: #48e1ad; box-shadow: 0 0 8px #48e1ad; }.scene-action { margin-left: auto; pointer-events: auto; border: 0; color: #bceffc; background: rgba(30, 135, 173, .3); padding: 3px 7px; cursor: pointer; }
.scene-overlay { position: absolute; inset: 0; z-index: 3; display: grid; place-items: center; align-content: center; padding: 20px; text-align: center; background: rgba(2, 10, 23, .84); color: #bceef7; font-size: 14px; }.scene-overlay.warning { inset: auto 12px 42px; padding: 7px 10px; display: block; background: rgba(75, 42, 8, .9); color: #ffe6b2; border-left: 2px solid #f6b44d; font-size: 11px; }.scene-overlay.warning small { color: #e8c994; }
.selection-card { position: absolute; top: 12px; left: 12px; padding: 8px 10px; background: rgba(2, 13, 27, .88); border: 1px solid rgba(53, 183, 208, .58); display: grid; font-size: 12px; }.selection-card span { color: #82acc2; margin-top: 4px; }

.alert-list { display: grid; gap: 6px; margin-top: 8px; }.alert-item { text-align: left; display: grid; grid-template-columns: 8px 1fr; gap: 8px; border: 0; border-left: 2px solid #3589a9; background: rgba(18, 54, 76, .42); padding: 8px; color: #d7eff8; cursor: pointer; }.alert-item.warning { border-left-color: #f6b44d; }.alert-item.critical { border-left-color: #fa5e68; }.alert-item b, .alert-item small { display: block; }.alert-item b { font-size: 11px; }.alert-item small { margin-top: 3px; color: #84a6b9; font-size: 10px; line-height: 1.35; }.level-dot { width: 6px; height: 6px; border-radius: 50%; margin-top: 3px; background: #38cca0; }.warning .level-dot { background: #f6b44d; }.critical .level-dot { background: #fa5e68; }.empty-state { color: #6f93aa; text-align: center; padding: 32px 8px; font-size: 12px; line-height: 1.65; }.empty-state small { color: #506f85; }
.zone-metrics { display: grid; grid-template-columns: repeat(3, 1fr); margin: 12px 0; }.zone-metrics div { display: grid; text-align: center; border-right: 1px solid rgba(67, 130, 160, .25); }.zone-metrics div:last-child { border: 0; }.zone-metrics strong { color: #6eddeb; font-size: 20px; }.zone-metrics span, .zone-panel p { color: #7293a9; font-size: 10px; }.zone-panel p { line-height: 1.45; margin: 0; }
.task-panel h2 { margin: 12px 0 7px; font-size: 15px; color: #dff9ff; }.task-panel p { min-height: 32px; margin: 0; color: #8eafc1; font-size: 11px; line-height: 1.45; }.progress-track, .stock-bar { height: 6px; margin-top: 13px; background: #102c43; overflow: hidden; }.progress-track span { display: block; height: 100%; background: linear-gradient(90deg, #13bcd0, #68f0e5); transition: width .25s linear; }.task-meta { display: flex; justify-content: space-between; gap: 6px; margin-top: 8px; color: #78a4b8; font-size: 10px; }.demo-controls { display: flex; gap: 7px; margin-top: 13px; }.demo-controls button, .alert-modal button { flex: 1; border: 1px solid #296b85; color: #bdeefa; background: #102e47; padding: 7px; cursor: pointer; }.demo-controls .primary, .alert-modal .primary { border-color: #25cbd9; background: #0b6075; color: #edffff; }
.equipment-row { display: grid; grid-template-columns: 8px 1fr auto; gap: 7px; align-items: center; padding: 8px 0; border-bottom: 1px solid rgba(69, 134, 164, .18); font-size: 11px; }.status-led { width: 6px; height: 6px; border-radius: 50%; background: #50ddac; box-shadow: 0 0 7px currentColor; color: #50ddac; }.status-led.standby { background: #f6b44d; color: #f6b44d; }.status-led.fault, .status-led.offline { background: #fa5e68; color: #fa5e68; }.status-led.maintenance { background: #a78bfa; color: #a78bfa; }.stock-panel strong { display: block; margin-top: 10px; color: #8ad5ff; font-size: 22px; }.stock-panel > span { color: #789cb1; font-size: 10px; }.stock-bar span { display: block; height: 100%; background: linear-gradient(90deg, #228fd5, #f4b44c); }

.chart-row { padding: 0; min-height: 0; }.trend-chart { width: 100%; height: 136px; }.screen-footer { display: grid; grid-template-columns: 1fr auto auto; align-items: center; gap: 12px; min-height: 28px; color: #668ca2; font-size: 10px; }.theme-tabs { display: flex; gap: 4px; }.theme-tabs button, .carousel-control { border: 1px solid transparent; background: transparent; color: #7fa5b9; font-size: 11px; padding: 5px 10px; cursor: pointer; }.theme-tabs button.active { color: #d9fbff; border-color: #27bfd6; background: rgba(20, 114, 139, .26); }.carousel-control { border-color: rgba(66, 160, 190, .42); }
.alert-modal { z-index: 20; position: absolute; left: 25%; top: 25%; width: 50%; box-sizing: border-box; padding: clamp(24px, 3vw, 48px); border: 1px solid #f45b65; background: linear-gradient(140deg, rgba(65, 10, 22, .97), rgba(20, 16, 29, .98)); box-shadow: 0 0 0 100vmax rgba(1, 6, 15, .56), 0 22px 70px rgba(0, 0, 0, .55); text-align: center; }.alert-modal p { color: #ff858d; letter-spacing: .1em; }.alert-modal h2 { margin: 8px; font-size: 26px; }.alert-modal span { color: #e8c9cc; }.alert-modal div { display: flex; justify-content: center; gap: 10px; margin-top: 24px; }.alert-modal button { max-width: 150px; }.critical-toast { z-index: 15; position: absolute; right: 28px; top: 110px; border: 1px solid #f05c64; background: #501723; color: #ffe5e7; padding: 10px 15px; cursor: pointer; }

@media (max-width: 1300px) { .bigscreen { padding: 12px 16px; }.screen-grid { grid-template-columns: 200px minmax(430px, 1fr) 220px; }.header-meta .clock { display: none; }.trend-chart { height: 118px; }.bigscreen { grid-template-rows: auto auto minmax(340px, 1fr) 118px auto; } }
</style>
