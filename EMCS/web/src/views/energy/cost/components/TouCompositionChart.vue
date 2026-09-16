<!-- 峰平谷构成｜PRD §5.9 · REQ-053 -->
<template>
  <section class="panel tou-panel">
    <header class="panel-head">
      <div class="panel-title">峰平谷构成</div>
      <span class="panel-sub">TOU · 用量、单价与成本均来自服务端冻结口径</span>
    </header>
    <div v-if="flatOnly" class="flat-card">
      <span>单一计价</span>
      <b>¥{{ number(flatOnly.price) }}</b>
      <small>用量 {{ number(flatOnly.usageQty) }} · 成本 ¥{{ number(flatOnly.cost) }} · 单价版本 {{ flatOnly.tariffVersion }}</small>
    </div>
    <template v-else>
      <div ref="chartEl" class="tou-chart" />
      <div class="tou-legend">
        <span v-for="item in segments" :key="item.key">
          <i :style="{ background: item.color }" />
          {{ item.label }} 用量占比 {{ number(item.value.usagePct) }}% · 成本 ¥{{ number(item.value.cost) }}
        </span>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { chartSeriesColors, chartTooltipTheme, useChartTheme } from '../../shared/cockpitTheme'

echarts.use([PieChart, LegendComponent, TooltipComponent, CanvasRenderer])
const props = defineProps({ composition: { type: [Array, Object], default: () => [] } })
const chartEl = ref()
const theme = useChartTheme()
let chart
const number = (value) => Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const rows = computed(() => Array.isArray(props.composition)
  ? props.composition
  : Object.entries(props.composition || {}).map(([period, value]) => ({ period, ...value })))
const flatOnly = computed(() => rows.value.find((item) => item.period === 'flatOnly') || null)
const segments = computed(() => {
  const series = chartSeriesColors(theme.value)
  return [
    { key: 'peak', label: '峰段', color: series.peak, value: rows.value.find((item) => item.period === 'peak') || {} },
    { key: 'flat', label: '平段', color: series.flat, value: rows.value.find((item) => item.period === 'flat') || {} },
    { key: 'valley', label: '谷段', color: series.valley, value: rows.value.find((item) => item.period === 'valley') || {} }
  ]
})
async function render() {
  await nextTick()
  if (flatOnly.value || !chartEl.value) { chart?.dispose(); chart = null; return }
  chart ||= echarts.init(chartEl.value)
  const t = theme.value
  chart.setOption({
    tooltip: { trigger: 'item', ...chartTooltipTheme(t), formatter: (params) => `${params.name} 成本占比 ${number(params.data.costPct)}%` },
    series: [{
      type: 'pie',
      radius: ['46%', '72%'],
      label: { color: t.ink2, formatter: (params) => `${params.name} ${number(params.data.costPct)}%` },
      labelLine: { lineStyle: { color: t.lineStrong } },
      data: segments.value.map((item) => ({
        name: item.label,
        value: item.value.costPct,
        costPct: item.value.costPct,
        itemStyle: { color: item.color, borderColor: t.panel, borderWidth: 2 }
      }))
    }]
  }, true)
}
watch([() => props.composition, theme], render, { deep: true, immediate: true })
onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink)}
.panel-head{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--line);padding-bottom:8px;margin-bottom:10px}
.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink);margin-right:auto}
.panel-sub{font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.08em}
.tou-chart{height:260px}
.tou-legend{display:flex;gap:16px;justify-content:center;flex-wrap:wrap;color:var(--ink-2);font-family:var(--mono);font-size:11px}
.tou-legend i{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:5px;vertical-align:middle}
.flat-card{margin-top:16px;padding:20px;background:var(--panel-2);border:1px solid var(--line);border-left:2px solid var(--cyan)}
.flat-card span,.flat-card b,.flat-card small{display:block}
.flat-card span{color:var(--ink-2);font-family:var(--mono);font-size:11px}
.flat-card b{color:var(--ink);font:28px var(--mono);margin:6px 0}
.flat-card small{color:var(--ink-3);font:12px var(--mono)}
</style>
