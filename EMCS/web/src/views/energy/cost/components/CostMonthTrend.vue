<!-- 月度成本趋势｜PRD §5.9 · REQ-052/056 -->
<template>
  <section class="panel trend-panel">
    <header class="panel-head">
      <div class="panel-title">月度成本趋势</div>
      <span class="panel-sub">MONTHLY · 异常标记完全来自规则引擎</span>
    </header>
    <div ref="chartEl" class="trend-chart" />
    <div class="month-cards">
      <button
        v-for="item in items"
        :key="item.statMonth"
        class="month-card"
        :class="{ anomaly: item.anomaly?.detected }"
        type="button"
        @click="select(item)"
      >
        <span class="card-month">{{ item.statMonth }}</span>
        <b class="card-cost">¥{{ money(item.totalCost) }}</b>
        <small class="card-meta">{{ periodStateLabels[item.periodState] || item.periodState }} · 环比 {{ percent(item.momPct) }}</small>
        <em v-if="item.anomaly?.detected" class="card-anomaly">{{ item.anomaly.ruleCode }} · {{ item.anomaly.note }}</em>
        <small v-else-if="item.periodNote" class="card-meta">{{ item.periodNote }}</small>
      </button>
    </div>
  </section>
</template>

<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { periodStateLabels } from '../../shared/act5'
import { chartAxisTheme, chartSeriesColors, chartTooltipTheme, useChartTheme } from '../../shared/cockpitTheme'

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps({ items: { type: Array, default: () => [] } })
const emit = defineEmits(['drill', 'select'])
const chartEl = ref()
const theme = useChartTheme()
let chart

const money = (value) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const percent = (value) => value == null ? '—' : `${Number(value) >= 0 ? '+' : ''}${Number(value).toFixed(2)}%`
function select(item) {
  if (item.anomaly?.detected && item.drillParams) emit('drill', item.drillParams)
  else emit('select', item.statMonth)
}
async function render() {
  await nextTick()
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  const t = theme.value
  const axis = chartAxisTheme(t)
  const series = chartSeriesColors(t)
  chart.setOption({
    grid: { left: 58, right: 18, top: 18, bottom: 32 },
    tooltip: { trigger: 'axis', ...chartTooltipTheme(t), valueFormatter: money },
    xAxis: { type: 'category', data: props.items.map((item) => item.statMonth), ...axis, splitLine: { show: false } },
    yAxis: { type: 'value', ...axis, axisLine: { show: false }, axisLabel: { ...axis.axisLabel, formatter: (value) => `¥${Math.round(value / 1000)}k` } },
    series: [{
      type: 'bar',
      barMaxWidth: 48,
      data: props.items.map((item) => ({
        value: item.totalCost,
        itemStyle: { color: item.anomaly?.detected ? series.anomaly : series.normal }
      }))
    }]
  }, true)
}
watch([() => props.items, theme], render, { deep: true, immediate: true })
onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink);margin-top:12px}
.panel-head{display:flex;align-items:center;gap:10px;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}
.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink);margin-right:auto}
.panel-sub{font-family:var(--mono);font-size:10px;color:var(--ink-3);letter-spacing:.08em}
.trend-chart{height:240px}
.month-cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:10px;margin-top:12px}
.month-card{appearance:none;background:var(--panel-2);border:1px solid var(--line);padding:12px;text-align:left;cursor:pointer;color:var(--ink);display:flex;flex-direction:column;gap:4px;transition:border-color .16s,transform .16s}
.month-card:hover{border-color:var(--line-strong);transform:translateY(-1px)}
.card-month{color:var(--ink-2);font:10px var(--mono);letter-spacing:.06em}
.card-cost{font:20px var(--mono);color:var(--ink)}
.card-meta{color:var(--ink-3);font:10px var(--mono)}
.card-anomaly{color:var(--red);font-style:normal;font:10px var(--mono);margin-top:4px}
.month-card.anomaly{border-color:var(--red);background:var(--red-tint)}
</style>
