<!--
  设备能耗画像｜第三幕主页面
  REQ-033 状态与能耗叠加 · REQ-034 画像/同类/班次对比 · REQ-035 低效证据
  引用 REQ-031/032 作业工单与单位作业能耗试算
-->
<template>
  <div class="act3-page profile-page cockpit-page">
    <header class="filter-bar">
      <div class="filter-title"><b>设备能耗画像</b><span>EQUIPMENT PROFILE · REQ-033–035</span></div>
      <el-select v-model="filters.zone" size="small" @change="applyFilters">
        <el-option label="全部区域" value="ALL" /><el-option label="A 区" value="A" /><el-option label="B 区" value="B" />
      </el-select>
      <el-select v-model="filters.energyType" size="small" @change="applyFilters">
        <el-option label="电" value="ELEC" /><el-option label="水" value="WATER" /><el-option label="压缩空气" value="AIR" />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        type="datetimerange"
        value-format="YYYY-MM-DD HH:mm:ss"
        range-separator="→"
        start-placeholder="统计开始"
        end-placeholder="统计结束"
        size="small"
      />
      <el-button type="primary" size="small" :loading="loading" @click="applyFilters">查询</el-button>
    </header>

    <section class="equipment-wall panel">
      <header class="panel-head">
        <div><div class="panel-title">设备卡片墙</div><div class="panel-sub">按区分组 · 同筛选结果能耗热度归一化</div></div>
        <span class="signature-mini">{{ listPayload?.signature?.version || '' }} · {{ cards.length }} DEVICES</span>
      </header>
      <div v-if="cards.length" class="card-grid">
        <button
          v-for="card in cards"
          :key="card.equipmentCode"
          class="equipment-card"
          :class="{ active: selectedEquipment === card.equipmentCode }"
          :style="heatStyle(card.heatRatio)"
          type="button"
          @click="selectEquipment(card.equipmentCode)"
        >
          <span class="card-area">{{ card.area?.code || card.area }}</span>
          <b>{{ card.equipmentName }}</b>
          <small>{{ card.equipmentCode }} · {{ card.equipmentType }}</small>
          <div class="card-value">{{ formatNumber(card.periodEnergy) }}<em>{{ energyUnit() }}</em></div>
          <footer><span>异常 {{ card.abnormalCount }}</span><span>{{ qualityText(card.quality) }}</span></footer>
        </button>
      </div>
      <div v-else class="empty-state">暂无数据，检查筛选条件或统计任务状态</div>
    </section>

    <div v-if="loading && !profile" class="loading-state">正在计算设备画像…</div>

    <main v-if="profile" class="profile-main">
      <section class="device-heading panel">
        <div>
          <span class="eyebrow">{{ profile.equipment.area?.name || profile.equipment.area?.code }}</span>
          <h1>{{ profile.equipment.equipmentName }}</h1>
          <p>{{ profile.equipment.equipmentCode }} · {{ profile.equipment.equipmentType }} · {{ energyTypeLabel }}</p>
        </div>
        <div class="heading-metrics">
          <article><span>周期总能耗</span><b>{{ formatNumber(profile.composition.totalEnergy) }}</b><small>{{ energyUnit() }}</small></article>
          <article><span>峰值负荷</span><b>{{ formatNumber(profile.peak.loadKw) }}</b><small>kW · {{ formatDateTime(profile.peak.occurredAt) }}</small></article>
          <article><span>异常数量</span><b>{{ profile.alerts?.length ?? 0 }}</b><small>当前画像周期</small></article>
          <article :class="`quality-${profile.quality?.band || 'unknown'}`"><span>质量状态</span><b>{{ qualityText(profile.quality) }}</b><small>{{ profile.quality?.coverage == null ? '—' : `${profile.quality.coverage}%` }}</small></article>
        </div>
      </section>

      <section v-if="profile.inefficiencyEvidence?.detected" class="evidence-banner">
        <div><span>{{ profile.inefficiencyEvidence.ruleCode }}</span><b>疑似低效证据</b></div>
        <p>{{ profile.inefficiencyEvidence.reason }}</p>
        <dl>
          <div><dt>连续时长</dt><dd>{{ profile.inefficiencyEvidence.durationHours }} h</dd></div>
          <div><dt>实际能耗</dt><dd>{{ formatNumber(profile.inefficiencyEvidence.actualEnergy) }}</dd></div>
          <div><dt>同类均值</dt><dd>{{ formatNumber(profile.inefficiencyEvidence.peerAverage) }}</dd></div>
          <div><dt>偏离倍数</dt><dd>{{ formatNumber(profile.inefficiencyEvidence.deviationRatio) }}×</dd></div>
          <div><dt>工单匹配</dt><dd>{{ profile.inefficiencyEvidence.workOrderMatched ? '有' : '无' }}</dd></div>
        </dl>
        <el-button v-if="evidenceAlertId" type="danger" plain @click="goAlertDispatch(evidenceAlertId)">返回告警详情 · 查看并派发</el-button>
      </section>

      <section class="two-col">
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">能耗构成</div><div class="panel-sub">作业 / 待机 / 停机 / 辅助</div></div></header>
          <div ref="compositionChartEl" class="small-chart" />
        </article>
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">同类设备对比</div><div class="panel-sub">同设备类型 · 同周期 · 同能源</div></div></header>
          <div v-if="profile.peerComparison?.length" ref="peerChartEl" class="small-chart" />
          <div v-else class="empty-state chart-empty">暂无同类设备对比数据</div>
        </article>
      </section>

      <section class="panel timeline-panel">
        <header class="panel-head">
          <div><div class="panel-title">运行状态与能耗叠加</div><div class="panel-sub">状态色带 + 功率曲线 + 告警窗口 · REQ-033</div></div>
          <span class="quality-badge">{{ qualityText(profile.quality) }} · COV {{ profile.quality?.coverage ?? '—' }}%</span>
        </header>
        <div v-if="profile.stateEnergySeries?.points?.length" ref="stateChartEl" class="state-chart" />
        <div v-else class="empty-state">数据不足，无法形成状态与能耗叠加结论</div>
      </section>

      <section class="two-col">
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">作业工单对照</div><div class="panel-sub">时间窗同源 · REQ-031/035</div></div></header>
          <div class="match-state" :class="{ missed: !profile.workOrderMatch?.matched }">
            {{ profile.workOrderMatch?.matched ? '当前观察窗存在匹配工单' : '异常观察窗无匹配工单' }}
          </div>
          <el-table :data="profile.workOrderMatch?.orders || []" class="dark-table" empty-text="当前观察窗无作业工单">
            <el-table-column prop="orderNo" label="工单号" min-width="130" />
            <el-table-column prop="cargoType" label="货类" width="90" />
            <el-table-column label="作业量" width="105"><template #default="{ row }">{{ row.workload }} {{ row.workloadUnit }}</template></el-table-column>
            <el-table-column label="时间窗" min-width="180"><template #default="{ row }">{{ formatDateTime(row.startTime) }} → {{ formatDateTime(row.endTime) }}</template></el-table-column>
          </el-table>
          <div v-for="window in profile.workOrderMatch?.uncoveredWindows || []" :key="window.start" class="uncovered-window">
            无工单覆盖 · {{ formatDateTime(window.start) }} → {{ formatDateTime(window.end) }}
          </div>
        </article>

        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">班次 / 班组对比</div><div class="panel-sub">单位作业能耗仅作试算 · REQ-032/034</div></div></header>
          <el-table :data="profile.shiftComparison || []" class="dark-table" empty-text="暂无班次对比数据">
            <el-table-column prop="shift" label="班次 / 班组" min-width="110" />
            <el-table-column prop="energy" label="能耗" width="95" />
            <el-table-column label="作业量" width="110"><template #default="{ row }">{{ row.workload }} {{ row.workloadUnit }}</template></el-table-column>
            <el-table-column prop="unitEnergy" label="单位能耗" width="100" />
            <el-table-column label="口径" width="72"><template #default="{ row }"><span v-if="row.trial" class="trial-tag">试算</span></template></el-table-column>
          </el-table>
          <p class="trial-note">试算结果不作为正式考核结论；缺作业量、状态或时间边界时应降级展示。</p>
        </article>
      </section>

      <section class="three-col">
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">关联采集点</div><div class="panel-sub">下钻原始数据与质量</div></div></header>
          <button v-for="point in profile.meterPoints || []" :key="point.pointCode" class="link-row" type="button" @click="drillPoint(point)">
            <span><b>{{ point.pointName }}</b><small>{{ point.pointCode }} · {{ point.energyType }}</small></span><em>下钻 →</em>
          </button>
          <div v-if="!profile.meterPoints?.length" class="empty-state">暂无关联采集点</div>
        </article>
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">关联告警</div><div class="panel-sub">当前设备 · 当前周期</div></div></header>
          <div v-for="alert in profile.alerts || []" :key="alert.eventId" class="info-row">
            <b>{{ alert.ruleCode }} · {{ alert.ruleName }}</b>
            <span>{{ alertStatusLabels[alert.status] || alert.status }}</span>
            <el-button link type="primary" @click="goAlertDispatch(alert.eventId)">查看并派发</el-button>
          </div>
          <div v-if="!profile.alerts?.length" class="empty-state">暂无关联告警</div>
        </article>
        <article class="panel">
          <header class="panel-head"><div><div class="panel-title">关联节能建议</div><div class="panel-sub">第四幕入口 · 真实空态</div></div></header>
          <div v-for="suggestion in profile.suggestions || []" :key="suggestion.suggestionId" class="info-row"><b>{{ suggestion.title }}</b><span>{{ suggestion.status }}</span></div>
          <div v-if="!profile.suggestions?.length" class="empty-state">暂未生成关联节能建议</div>
        </article>
      </section>

      <footer class="signature-footer">
        <span>REQ-031 · 032 · 033 · 034 · 035</span>
        <span>口径签名 {{ profile.signature?.version }} · {{ profile.signature?.formulaVersion }} · 生成 {{ formatDateTime(profile.signature?.generatedAt) }}</span>
      </footer>
    </main>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkAreaComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getEquipmentProfile, getEquipmentProfiles } from '@/api/equipmentProfile'
import {
  activeAlertEventId,
  alertStatusLabels,
  buildStateAreas,
  compactQuery,
  equipmentStateLabels,
  withoutAlertContext
} from '@/views/energy/shared/act3'
import { useChartTheme } from '@/views/energy/shared/cockpitTheme'

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, MarkAreaComponent, TooltipComponent, CanvasRenderer])
defineOptions({ name: 'EnergyEquipmentProfile' })

// 双主题响应式：切顶栏夜览时所有图表即时重绘
const theme = useChartTheme()

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const listPayload = ref(null)
const profile = ref(null)
const cards = computed(() => listPayload.value?.equipments || [])
const selectedEquipment = ref(String(route.query.equipmentId || ''))
const dateRange = ref(route.query.periodStart && route.query.periodEnd ? [route.query.periodStart, route.query.periodEnd] : [])
const filters = reactive({ zone: String(route.query.zone || 'ALL'), energyType: String(route.query.energyType || 'ELEC'), periodStart: route.query.periodStart || '', periodEnd: route.query.periodEnd || '' })

const compositionChartEl = ref(null)
const peerChartEl = ref(null)
const stateChartEl = ref(null)
let compositionChart = null
let peerChart = null
let stateChart = null

const energyTypeLabel = computed(() => ({ ELEC: '电', WATER: '水', AIR: '压缩空气' })[filters.energyType] || filters.energyType)
const evidenceAlertId = computed(() => {
  const carriedEventId = activeAlertEventId(route.query, selectedEquipment.value, filters.energyType)
  if (carriedEventId) return carriedEventId
  const ruleCode = profile.value?.inefficiencyEvidence?.ruleCode
  return profile.value?.alerts?.find((alert) => alert.ruleCode === ruleCode)?.eventId
})

async function loadCards() {
  const response = await getEquipmentProfiles(compactQuery(filters))
  listPayload.value = response.data
  if (!dateRange.value?.length && response.data?.filters?.periodStart && response.data?.filters?.periodEnd) {
    filters.periodStart = response.data.filters.periodStart
    filters.periodEnd = response.data.filters.periodEnd
    dateRange.value = [filters.periodStart, filters.periodEnd]
  }
  if (!cards.value.some((card) => card.equipmentCode === selectedEquipment.value)) {
    selectedEquipment.value = cards.value[0]?.equipmentCode || ''
  }
}

async function loadProfile() {
  if (!selectedEquipment.value) {
    profile.value = null
    return
  }
  const response = await getEquipmentProfile(selectedEquipment.value, compactQuery({
    energyType: filters.energyType,
    periodStart: filters.periodStart,
    periodEnd: filters.periodEnd,
    eventId: activeAlertEventId(route.query, selectedEquipment.value, filters.energyType)
  }))
  profile.value = response.data
  await nextTick()
  renderCharts()
}

async function loadAll() {
  loading.value = true
  try {
    await loadCards()
    await loadProfile()
  } finally {
    loading.value = false
  }
}

async function applyFilters() {
  filters.periodStart = dateRange.value?.[0] || ''
  filters.periodEnd = dateRange.value?.[1] || ''
  await router.replace({
    query: {
      ...withoutAlertContext(route.query),
      equipmentId: selectedEquipment.value || undefined,
      zone: filters.zone,
      energyType: filters.energyType,
      periodStart: filters.periodStart || undefined,
      periodEnd: filters.periodEnd || undefined
    }
  })
  await loadAll()
}

async function selectEquipment(equipmentCode) {
  if (!equipmentCode || equipmentCode === selectedEquipment.value) return
  selectedEquipment.value = equipmentCode
  await router.replace({ query: { ...withoutAlertContext(route.query), equipmentId: equipmentCode } })
  loading.value = true
  try { await loadProfile() } finally { loading.value = false }
}

function drillPoint(point) {
  router.push({
    path: '/raw-quality',
    query: { pointId: point.pointCode, timeStart: filters.periodStart, timeEnd: filters.periodEnd }
  })
}

function goAlertDispatch(eventId) {
  router.push({
    path: '/energy/alert/alert-list',
    query: { eventId, action: 'dispatch' }
  })
}

function renderCharts() {
  renderComposition()
  renderPeers()
  renderStateEnergy()
}

function renderComposition() {
  if (!compositionChartEl.value || !profile.value?.composition) return
  compositionChart ||= echarts.init(compositionChartEl.value)
  const composition = profile.value.composition
  const pal = theme.value
  compositionChart.setOption({
    tooltip: { trigger: 'item', backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder, textStyle: { color: pal.tooltipInk } },
    legend: { bottom: 0, textStyle: { color: pal.ink2 } },
    series: [{
      type: 'pie', radius: ['48%', '72%'], center: ['50%', '43%'], label: { color: pal.ink2, formatter: '{b}\n{d}%' },
      data: [
        { name: '作业', value: composition.workEnergy, itemStyle: { color: pal.cyan } },
        { name: '待机', value: composition.standbyEnergy, itemStyle: { color: pal.amber } },
        { name: '停机', value: composition.stoppedEnergy, itemStyle: { color: pal.ink3 } },
        { name: '辅助', value: composition.auxiliaryEnergy, itemStyle: { color: pal.violet } }
      ]
    }]
  })
}

function renderPeers() {
  const peers = profile.value?.peerComparison || []
  if (!peerChartEl.value || !peers.length) return
  peerChart ||= echarts.init(peerChartEl.value)
  const pal = theme.value
  peerChart.setOption({
    grid: { top: 18, left: 100, right: 30, bottom: 25 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder, textStyle: { color: pal.tooltipInk } },
    xAxis: { type: 'value', axisLabel: { color: pal.ink3 }, splitLine: { lineStyle: { color: pal.splitLine } } },
    yAxis: { type: 'category', data: peers.map((item) => item.equipmentName), axisLabel: { color: pal.ink2 }, axisLine: { show: false }, axisTick: { show: false } },
    series: [{ type: 'bar', data: peers.map((item) => item.energy), barMaxWidth: 18, itemStyle: { color: pal.cyan } }]
  })
}

// x 轴时间标签稀疏化：单日窗口按整点每 3h 一个；多日窗口每天 1 个（>3 天）或半天 1 个（≤3 天）
// 用 interval 函数选中要显示的下标，避免旋转硬挤；仅多日窗口开小角度旋转让 MM-DD HH 时不撞车
function computeSpanHours(tsList) {
  if (!tsList?.length) return 0
  const start = new Date(String(tsList[0]).replace(' ', 'T')).getTime()
  const end = new Date(String(tsList[tsList.length - 1]).replace(' ', 'T')).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 0
  return Math.max(0, (end - start) / 3600000)
}
function planTimeAxisLabels(tsList, spanHours) {
  const parseHour = (ts) => Number(String(ts).slice(11, 13))
  const parseMinute = (ts) => Number(String(ts).slice(14, 16))
  if (spanHours <= 26) {
    // 单日：整点且 hour % 3 === 0
    return {
      interval: (_idx, value) => parseMinute(value) === 0 && parseHour(value) % 3 === 0,
      rotate: 0,
      formatter: (value) => String(value).slice(11, 16)
    }
  }
  const step = spanHours > 72 ? 24 : 12 // >3 天按天，≤3 天按半天
  return {
    interval: (_idx, value) => parseMinute(value) === 0 && parseHour(value) % step === 0,
    rotate: 30,
    formatter: (value) => {
      const md = String(value).slice(5, 10)
      const hh = String(value).slice(11, 13)
      return `${md} ${hh}时`
    }
  }
}

function renderStateEnergy() {
  const series = profile.value?.stateEnergySeries
  if (!stateChartEl.value || !series?.points?.length) return
  stateChart ||= echarts.init(stateChartEl.value)
  const pal = theme.value
  // 状态段不带顶部文字标签（段多时挤成一团）,状态改由 tooltip 悬停显示
  const areas = buildStateAreas(series.stateSegments || [], { withLabels: false })
  const segments = series.stateSegments || []
  const stateAt = (ts) => {
    const seg = segments.find((s) => s.start <= ts && ts < s.end)
      || (segments.length && ts >= segments[segments.length - 1].start ? segments[segments.length - 1] : null)
    return seg ? (equipmentStateLabels[seg.state] || seg.state) : '—'
  }
  if (series.alertWindow) {
    areas.push([
      { xAxis: series.alertWindow.start, name: '告警窗口', itemStyle: { color: pal.red, opacity: 0.12 } },
      { xAxis: series.alertWindow.end }
    ])
  }
  const tsList = series.points.map((point) => point.ts)
  const spanHours = computeSpanHours(tsList)
  const axisLabelPlan = planTimeAxisLabels(tsList, spanHours)
  stateChart.setOption({
    grid: { top: 24, left: 60, right: 24, bottom: axisLabelPlan.rotate ? 54 : 42 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder, textStyle: { color: pal.tooltipInk },
      formatter: (params) => {
        const p = Array.isArray(params) ? params[0] : params
        if (!p) return ''
        const ts = p.axisValue || ''
        const power = series.points[p.dataIndex]?.powerKw
        return `${String(ts).slice(5, 16)}<br/>功率 <b>${power == null ? '—' : Number(power).toFixed(1)}</b> ${series.unit || 'kW'}<br/>状态 <b>${stateAt(ts)}</b>`
      }
    },
    xAxis: {
      type: 'category',
      data: tsList,
      axisLabel: {
        color: pal.ink3,
        interval: axisLabelPlan.interval,
        rotate: axisLabelPlan.rotate,
        hideOverlap: true,
        formatter: axisLabelPlan.formatter
      },
      axisLine: { lineStyle: { color: pal.lineStrong } }
    },
    yAxis: { type: 'value', name: series.unit, nameTextStyle: { color: pal.ink2 }, axisLabel: { color: pal.ink3 }, splitLine: { lineStyle: { color: pal.splitLine } } },
    series: [{
      type: 'line', symbol: 'none', smooth: true, data: series.points.map((point) => point.powerKw),
      lineStyle: { color: pal.cyan, width: 2 }, areaStyle: { color: pal.areaTint },
      markArea: { silent: false, label: { color: pal.ink2, fontSize: 10 }, data: areas }
    }]
  })
}

function heatStyle(ratio) {
  const normalized = Math.max(0, Math.min(1, Number(ratio) || 0))
  return { '--heat': String(0.05 + normalized * 0.2) }
}
function qualityText(quality) {
  if (typeof quality === 'string') return quality
  return ({ ok: '合格', degraded: '降级', insufficient: '数据不足' })[quality?.band] || quality?.summary || '—'
}
function energyUnit() {
  return ({ ELEC: 'kWh', WATER: 'm³', AIR: 'm³' })[filters.energyType] || ''
}
function formatNumber(value) { return value == null ? '—' : Number(value).toLocaleString(undefined, { maximumFractionDigits: 2 }) }
function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function resizeCharts() { compositionChart?.resize(); peerChart?.resize(); stateChart?.resize() }

// 主题切换即时重绘
watch(theme, () => { if (profile.value) renderCharts() })

onMounted(() => {
  loadAll()
  window.addEventListener('resize', resizeCharts)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  compositionChart?.dispose(); peerChart?.dispose(); stateChart?.dispose()
})
</script>

<style scoped>
/* 页面布局：主题 token 由 .cockpit-page 提供；.act3-page 只做布局特化 + 页专色 */
.act3-page{
  /* 页专色（cockpit-tokens 未覆盖，页级双主题双写） */
  --orange:#EA580C;

  min-height:calc(100vh - 84px);margin:-16px -16px 0;padding:16px 20px 40px;color:var(--ink);
  background:radial-gradient(1000px 460px at 0 0,var(--cyan-tint),transparent 60%),var(--bg);
  font-family:"PingFang SC",system-ui,sans-serif;font-size:13px;
}
html.dark .act3-page{
  --orange:#F97316;
}
/* el-input/select/date-editor 皮由 cockpit-tokens 通用块统一供 */
.filter-bar{display:flex;align-items:center;gap:10px;padding:12px 14px;background:var(--panel);border:1px solid var(--line)}.filter-title{margin-right:auto;display:flex;flex-direction:column}.filter-title b{font-family:var(--serif);font-size:17px;letter-spacing:.08em}.filter-title span,.panel-sub{color:var(--ink-3);font:10px var(--mono);letter-spacing:.1em}.filter-bar :deep(.el-select){width:120px}.filter-bar :deep(.el-date-editor){width:350px}
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px}.panel-head{display:flex;align-items:center;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em}.signature-mini,.quality-badge{margin-left:auto;color:var(--ink-3);font:10px var(--mono)}.equipment-wall{margin-top:12px}.card-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:9px}.equipment-card{--heat:.05;appearance:none;text-align:left;color:var(--ink);background:linear-gradient(color-mix(in srgb, var(--orange) calc(var(--heat) * 100%), transparent),color-mix(in srgb, var(--panel) 96%, transparent));border:1px solid var(--line);padding:10px;cursor:pointer;display:flex;flex-direction:column;min-width:0}.equipment-card:hover,.equipment-card.active{border-color:var(--cyan);box-shadow:0 0 0 1px color-mix(in srgb, var(--cyan) 18%, transparent) inset}.equipment-card b{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:5px}.equipment-card small,.card-area{color:var(--ink-3);font:9px var(--mono)}.card-area{color:var(--cyan)}.card-value{font:18px var(--mono);margin-top:10px}.card-value em{font-size:9px;color:var(--ink-3);font-style:normal;margin-left:4px}.equipment-card footer{display:flex;justify-content:space-between;color:var(--ink-3);font:9px var(--mono);margin-top:7px}.loading-state,.empty-state{padding:24px;text-align:center;color:var(--ink-3);border:1px dashed var(--line)}.loading-state{margin-top:12px;background:var(--panel)}
.profile-main{display:flex;flex-direction:column;gap:12px;margin-top:12px}.device-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.eyebrow{color:var(--cyan);font:10px var(--mono)}.device-heading h1{font-family:var(--serif);font-size:25px;letter-spacing:.08em;margin:3px 0}.device-heading p{color:var(--ink-3);font:10px var(--mono);margin:0}.heading-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;min-width:60%}.heading-metrics article{border-left:1px solid var(--line-strong);padding-left:12px;display:flex;flex-direction:column}.heading-metrics span,.heading-metrics small{color:var(--ink-3);font:9px var(--mono)}.heading-metrics b{font:20px var(--mono);margin:3px 0}.quality-ok b{color:var(--lime)}.quality-degraded b{color:var(--amber)}.quality-insufficient b{color:var(--red)}
.evidence-banner{display:grid;grid-template-columns:auto 1fr auto auto;gap:18px;align-items:center;padding:12px 16px;border:1px solid color-mix(in srgb, var(--red) 42%, transparent);background:var(--red-tint)}.evidence-banner>div{display:flex;flex-direction:column}.evidence-banner>div span{color:var(--red);font:11px var(--mono)}.evidence-banner>div b{font-family:var(--serif);font-size:16px}.evidence-banner p{color:var(--ink-2)}.evidence-banner dl{display:flex;gap:18px;margin:0}.evidence-banner dl div{display:flex;flex-direction:column}.evidence-banner dt{color:var(--ink-3);font:9px var(--mono)}.evidence-banner dd{margin:2px 0 0;font:13px var(--mono)}
.two-col{display:grid;grid-template-columns:1fr 1fr;gap:12px}.three-col{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.small-chart{height:280px}.state-chart{height:330px}.chart-empty{height:230px;display:grid;place-items:center}.match-state{padding:8px;border:1px solid color-mix(in srgb, var(--lime) 35%, transparent);color:var(--lime);font-family:var(--mono);margin-bottom:8px}.match-state.missed{color:var(--red);border-color:color-mix(in srgb, var(--red) 42%, transparent);background:var(--red-tint)}/* .dark-table 变量由 cockpit-tokens 通用块提供 */.uncovered-window{padding:7px 8px;margin-top:6px;color:var(--red);border-left:2px solid var(--red);background:var(--red-tint);font:10px var(--mono)}.trial-tag{color:var(--amber);border:1px solid color-mix(in srgb, var(--amber) 40%, transparent);padding:1px 5px;font:9px var(--mono)}.trial-note{color:var(--ink-3);font-size:10px;margin:10px 0 0}.link-row{appearance:none;width:100%;display:flex;justify-content:space-between;align-items:center;text-align:left;background:none;border:0;border-bottom:1px dashed var(--line);color:var(--ink);padding:9px 2px;cursor:pointer}.link-row span{display:flex;flex-direction:column}.link-row small{color:var(--ink-3);font:9px var(--mono)}.link-row em{color:var(--cyan);font:10px var(--mono);font-style:normal}.info-row{display:flex;justify-content:space-between;gap:8px;padding:9px 2px;border-bottom:1px dashed var(--line)}.info-row span{color:var(--ink-3);font:10px var(--mono)}.signature-footer{display:flex;justify-content:space-between;color:var(--ink-3);font:10px var(--mono);padding:8px 2px}
@media(max-width:1180px){.card-grid{grid-template-columns:repeat(4,1fr)}.device-heading{align-items:flex-start;flex-direction:column}.heading-metrics{width:100%}.evidence-banner{grid-template-columns:1fr}.evidence-banner dl{flex-wrap:wrap}}
@media(max-width:820px){.filter-bar{flex-wrap:wrap}.filter-title{width:100%}.card-grid{grid-template-columns:repeat(2,1fr)}.two-col,.three-col{grid-template-columns:1fr}.heading-metrics{grid-template-columns:1fr 1fr}}
</style>
