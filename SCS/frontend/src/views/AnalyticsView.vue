<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AnalyticsOverview from '@/components/analytics/AnalyticsOverview.vue'
import AnalyticsFilters, { type AnalyticsFilterState } from '@/components/analytics/AnalyticsFilters.vue'
import AlertTrendChart from '@/components/analytics/AlertTrendChart.vue'
import RiskTypeChart from '@/components/analytics/RiskTypeChart.vue'
import AreaRiskRanking from '@/components/analytics/AreaRiskRanking.vue'
import TreatmentEfficiency from '@/components/analytics/TreatmentEfficiency.vue'
import HighRiskEquipment from '@/components/analytics/HighRiskEquipment.vue'
import RepeatedRiskPersonnel from '@/components/analytics/RepeatedRiskPersonnel.vue'
import RiskLevelChart from '@/components/analytics/RiskLevelChart.vue'
import EventDetailTable from '@/components/analytics/EventDetailTable.vue'
import AnalyticsDrilldown, { type ActiveChip } from '@/components/analytics/AnalyticsDrilldown.vue'
import { analyticsApi, type AnalyticsQuery } from '@/api/analytics'
import { isLiveEvent, sharedLiveSocket } from '@/api/live'
import type { AnalyticsDataset, AnalyticsEventItem, DrillFilter } from '@/types/analytics'

const surge = ref(false)
const loading = ref(false)
const dataset = ref<AnalyticsDataset>(emptyDataset())
const events = ref<AnalyticsEventItem[]>([])

function emptyDataset(): AnalyticsDataset {
  return { kpi: [], trend: [], riskTypes: [], areas: [], teams: [], devices: [], persons: [], levels: [], events: [] }
}

const filters = reactive<AnalyticsFilterState>({ period: '本周', area: '全部区域', team: '全部班组', type: '全部类型', level: '全部等级' })
function resetFilters(): void {
  Object.assign(filters, { period: '本周', area: '全部区域', team: '全部班组', type: '全部类型', level: '全部等级' })
  drills.value = []
}

// ---------- 图表下钻 ----------
const drills = ref<DrillFilter[]>([])
function upsertDrill(kind: DrillFilter['kind'], value: string, label = value): void {
  const exist = drills.value.find((d) => d.kind === kind)
  if (exist && exist.value === value) {
    drills.value = drills.value.filter((d) => d.kind !== kind) // 再次点击取消
    return
  }
  drills.value = [...drills.value.filter((d) => d.kind !== kind), { kind, value, label }]
}
const drillOf = (kind: DrillFilter['kind']) => drills.value.find((d) => d.kind === kind)

// 人员下钻组件回传的是姓名，转换为 personId 作为查询值
function personDrill(name: string): void {
  const p = dataset.value.persons.find((x) => x.name === name)
  upsertDrill('person', p?.personId ?? name, name)
}

// 顶部筛选 + 下钻合并为 chips
function buildChips(): ActiveChip[] {
  const list: ActiveChip[] = []
  if (filters.area !== '全部区域') list.push({ key: 'f-area', label: filters.area, source: 'filter' })
  if (filters.team !== '全部班组') list.push({ key: 'f-team', label: filters.team, source: 'filter' })
  if (filters.type !== '全部类型') list.push({ key: 'f-type', label: filters.type, source: 'filter' })
  if (filters.level !== '全部等级') list.push({ key: 'f-level', label: filters.level, source: 'filter' })
  for (const d of drills.value) list.push({ key: `d-${d.kind}`, label: d.label, source: 'drill' })
  return list
}
function removeChip(key: string): void {
  if (key.startsWith('f-')) {
    const map: Record<string, keyof AnalyticsFilterState> = { 'f-area': 'area', 'f-team': 'team', 'f-type': 'type', 'f-level': 'level' }
    const field = map[key]
    const reset: Record<string, string> = { area: '全部区域', team: '全部班组', type: '全部类型', level: '全部等级' }
    if (field) filters[field] = reset[field] ?? ''
  } else {
    const kind = key.replace('d-', '') as DrillFilter['kind']
    drills.value = drills.value.filter((d) => d.kind !== kind)
  }
}
function clearChips(): void {
  filters.area = '全部区域'; filters.team = '全部班组'; filters.type = '全部类型'; filters.level = '全部等级'
  drills.value = []
}

// ---------- 查询参数：顶部筛选 + 下钻叠加（同维度下钻优先） ----------
function prefer(drillValue: string | undefined, filterValue: string | undefined): string | undefined {
  return drillValue || filterValue || undefined
}
function baseQuery(): AnalyticsQuery {
  return {
    period: filters.period,
    area: filters.area !== '全部区域' ? filters.area : undefined,
    team: filters.team !== '全部班组' ? filters.team : undefined,
    type: filters.type !== '全部类型' ? filters.type : undefined,
    level: filters.level !== '全部等级' ? filters.level : undefined,
  }
}

async function loadDataset(): Promise<void> {
  loading.value = true
  try {
    dataset.value = await analyticsApi.dataset(baseQuery())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '统计数据加载失败')
  } finally {
    loading.value = false
  }
}

async function loadEvents(): Promise<void> {
  try {
    const q: AnalyticsQuery = {
      period: filters.period,
      type: prefer(drillOf('type')?.value, filters.type !== '全部类型' ? filters.type : undefined),
      area: prefer(drillOf('area')?.value, filters.area !== '全部区域' ? filters.area : undefined),
      level: prefer(drillOf('level')?.value, filters.level !== '全部等级' ? filters.level : undefined),
      team: prefer(drillOf('team')?.value, filters.team !== '全部班组' ? filters.team : undefined),
      deviceId: drillOf('device')?.value,
      person: drillOf('person')?.value,
      page: 1,
      pageSize: 200,
    }
    events.value = (await analyticsApi.events(q)).list
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '事件明细加载失败')
  }
}

let reloadTimer: ReturnType<typeof setTimeout> | undefined
function scheduleReload(): void {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => { void loadDataset(); void loadEvents() }, 700)
}

watch(() => ({ ...filters }), () => scheduleReload(), { deep: true })
watch(drills, () => void loadEvents(), { deep: true })

// ---------- 场景切换（后端注入 / 移除演示事件，首页 / 大屏同步变化） ----------
async function toggleScene(): Promise<void> {
  surge.value = !surge.value
  drills.value = []
  try {
    const res = await analyticsApi.simulateSurge({ area: '装卸区 A', active: surge.value })
    dataset.value = res.dataset
    await loadEvents()
    if (surge.value) ElMessage.warning('检测到装卸区 A 风险事件明显上升')
    else ElMessage.success('已恢复默认统计数据')
  } catch (e) {
    surge.value = !surge.value
    ElMessage.error(e instanceof Error ? e.message : '场景切换失败')
  }
}

// ---------- WebSocket：告警变化后防抖刷新（不做秒级连续请求） ----------
const offMessage = sharedLiveSocket.onMessage((raw) => {
  if (isLiveEvent(raw) && String(raw.type).startsWith('alert.')) scheduleReload()
})

onMounted(() => {
  sharedLiveSocket.connect()
  void loadDataset()
  void loadEvents()
})
onUnmounted(() => {
  offMessage()
  if (reloadTimer) clearTimeout(reloadTimer)
})
</script>

<template>
  <section class="page-content analytics-page" v-loading="loading">
    <AnalyticsFilters :filters="filters" :surge="surge" @toggle-scene="toggleScene" @reset="resetFilters" />

    <AnalyticsOverview :items="dataset.kpi" />

    <div class="analytics-row analytics-row--lead">
      <AlertTrendChart :points="dataset.trend" :active="!!drillOf('area')" />
      <RiskTypeChart :items="dataset.riskTypes" :active-type="drillOf('type')?.value"
        @drill="(v) => upsertDrill('type', v)" />
    </div>

    <div class="analytics-row analytics-row--mid">
      <AreaRiskRanking :items="dataset.areas" :active-area="drillOf('area')?.value"
        @drill="(v) => upsertDrill('area', v)" />
      <TreatmentEfficiency :items="dataset.teams" :active-team="drillOf('team')?.value"
        @drill="(v) => upsertDrill('team', v)" />
    </div>

    <div class="analytics-row analytics-row--tri">
      <HighRiskEquipment :items="dataset.devices" @drill="(id, name) => upsertDrill('device', id, name)" />
      <RepeatedRiskPersonnel :items="dataset.persons" @drill="personDrill" />
      <RiskLevelChart :items="dataset.levels" :active-level="drillOf('level')?.value"
        @drill="(v) => upsertDrill('level', v)" />
    </div>

    <AnalyticsDrilldown :chips="buildChips()" @remove="removeChip" @clear="clearChips" />
    <EventDetailTable :events="events" />
  </section>
</template>
