<script setup lang="ts">
import { computed, markRaw, onMounted, onUnmounted, ref } from 'vue'
import { Bell, Clock, Connection, User, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import AlertDrawer from './AlertDrawer.vue'
import DeviceHealth from './DeviceHealth.vue'
import RealtimeAlertFeed from './RealtimeAlertFeed.vue'
import RiskDistributionChart from './RiskDistributionChart.vue'
import RiskTrendChart from './RiskTrendChart.vue'
import SafetyMap from './SafetyMap.vue'
import SafetyStatCard from './SafetyStatCard.vue'
import { overviewApi } from '@/api/overview'
import { mapFences } from '@/adapters/overview'
import { ApiError } from '@/api/http'
import { useLiveStore } from '@/stores/live'
import type {
  EquipmentPoint,
  OverviewAlert,
  OverviewDistributionDto,
  OverviewSummaryDto,
  OverviewTrendDto,
  PersonnelPoint,
} from '@/types/overview'
import type { FenceRecord } from '@/types/fence'

// ---------- 后端聚合数据（告警为真实口径；人员/设备坐标为 Demo 底图） ----------
const summary = ref<OverviewSummaryDto>({
  onDuty: 0, deviceOnline: 0, deviceTotal: 0, activeAlerts: 0, processingAlerts: 0,
  urgentAlerts: 0, severeAlerts: 0, pendingAi: 0, riskyDevices: 0,
  onDutyDemo: true, deviceDemo: true, riskyDevicesDemo: true,
})
const people = ref<PersonnelPoint[]>([])
const equipment = ref<EquipmentPoint[]>([])
const fences = ref<FenceRecord[]>([])
const liveOverlay = ref(false)
const alerts = ref<OverviewAlert[]>([])
const trend = ref<OverviewTrendDto>({ demo: true, points: [] })
const distribution = ref<OverviewDistributionDto>({ items: [] })

const riskActive = ref(false)
const selectedAlert = ref<OverviewAlert>()
const drawerOpen = ref(false)

async function loadSummary(): Promise<void> {
  summary.value = await overviewApi.getSummary()
}
async function loadMap(): Promise<void> {
  const map = await overviewApi.getMap()
  people.value = map.people
  equipment.value = map.equipment
  fences.value = mapFences(map)
  liveOverlay.value = map.liveOverlay
}
async function loadFeed(): Promise<void> {
  alerts.value = await overviewApi.getFeed(10)
}
async function loadTrend(): Promise<void> {
  trend.value = await overviewApi.getTrend('7d')
}
async function loadDistribution(): Promise<void> {
  distribution.value = await overviewApi.getDistribution()
}

async function refreshAll(): Promise<void> {
  try {
    await Promise.all([loadSummary(), loadMap(), loadFeed(), loadTrend(), loadDistribution()])
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '安全态势数据加载失败')
  }
}

/** 风险演示开关：由后端创建/关闭固定演示告警，五端经 WS 同步 */
async function toggleRisk(): Promise<void> {
  riskActive.value = !riskActive.value
  try {
    await overviewApi.simulateRisk(riskActive.value)
    if (riskActive.value) ElMessage.warning('检测到人员进入危险区域')
    else ElMessage.success('风险演示已恢复正常')
    await Promise.all([loadSummary(), loadMap(), loadFeed(), loadDistribution()])
  } catch (error) {
    riskActive.value = !riskActive.value
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function openAlert(alert: OverviewAlert): Promise<void> {
  try {
    selectedAlert.value = await overviewApi.getAlertDetail(alert.id)
    drawerOpen.value = true
  } catch (error) {
    selectedAlert.value = alert
    drawerOpen.value = true
    if (error instanceof ApiError) ElMessage.error(error.message)
  }
}

const stats = computed(() => {
  const s = summary.value
  const deviceRate = s.deviceTotal ? ((s.deviceOnline / s.deviceTotal) * 100).toFixed(1) : '0'
  return [
    { label: '在岗人员', value: s.onDuty, suffix: '人', note: s.onDutyDemo ? 'Demo 台账数据' : '实时在岗', icon: markRaw(User), tone: 'blue' as const, variant: 'default' as const },
    { label: '在线设备', value: `${s.deviceOnline} / ${s.deviceTotal}`, suffix: '台', note: `在线率 ${deviceRate}%`, icon: markRaw(Connection), tone: 'green' as const, variant: 'progress' as const, progress: Number(deviceRate) },
    { label: '活动告警', value: s.activeAlerts, suffix: '条', note: `AI 待复核 ${s.pendingAi} 条`, icon: markRaw(Bell), tone: 'blue' as const, variant: 'signal' as const },
    { label: '处置中', value: s.processingAlerts, suffix: '条', note: `其中 ${s.urgentAlerts} 条紧急`, icon: markRaw(Clock), tone: 'amber' as const, variant: 'default' as const },
    {
      label: '高风险事件', value: s.urgentAlerts + s.severeAlerts, suffix: '起',
      note: s.urgentAlerts + s.severeAlerts > 0 ? '需要立即关注' : '当前状态平稳',
      icon: markRaw(WarningFilled), tone: 'red' as const, variant: 'risk' as const,
    },
  ]
})

// ---------- WebSocket：告警/AI 变化后按需刷新；重连后全量同步 ----------
const liveStore = useLiveStore()
let disposeLive: (() => void) | undefined
let disposeReconnect: (() => void) | undefined

onMounted(() => {
  void refreshAll()
  disposeLive = liveStore.onAlertEvent((event) => {
    if (event.type.startsWith('alert.') || event.type.startsWith('ai.')) {
      void Promise.all([loadSummary(), loadMap(), loadFeed(), loadDistribution()])
    }
  })
  disposeReconnect = liveStore.onReconnected(() => void refreshAll())
})

onUnmounted(() => {
  disposeLive?.()
  disposeReconnect?.()
})
</script>

<template>
  <section class="page-content safety-overview">
    <div class="safety-stat-grid">
      <SafetyStatCard v-for="stat in stats" :key="stat.label" v-bind="stat" />
    </div>

    <div class="safety-core-grid">
      <SafetyMap :people="people" :equipment="equipment" :fences="fences" :risk-active="riskActive"
        :live-overlay="liveOverlay" @toggle-risk="toggleRisk" />
      <RealtimeAlertFeed :alerts="alerts" :pending="summary.processingAlerts" @select="openAlert" />
    </div>

    <div class="safety-data-grid">
      <RiskTrendChart :points="trend.points" :demo="trend.demo" />
      <RiskDistributionChart :items="distribution.items" />
      <DeviceHealth />
    </div>

    <AlertDrawer v-model="drawerOpen" :alert="selectedAlert" />
  </section>
</template>
