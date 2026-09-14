<script setup lang="ts">
import { computed, watch, ref } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEChart, softTooltip, CHART_COLORS } from '@/composables/useEChart'
import type { EChartsCoreOption } from 'echarts/core'

use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{ breachActive: boolean }>()

const hours = Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`)
const baseAll = [2, 1, 1, 0, 1, 2, 3, 4, 5, 4, 6, 7, 5, 6, 4, 5, 7, 8, 6, 5, 4, 3, 3, 2]
const baseHigh = [0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 2, 1, 1, 0, 1, 2, 2, 1, 1, 0, 1, 0, 0]
const tick = ref(0)
watch(() => props.breachActive, () => { tick.value += 1 })

const option = computed<EChartsCoreOption>(() => {
  void tick.value
  const raised = props.breachActive
  return {
    grid: { left: 32, right: 12, top: 32, bottom: 22 },
    tooltip: { ...softTooltip, trigger: 'axis' },
    legend: {
      right: 8, top: 2, itemWidth: 10, itemHeight: 10, icon: 'roundRect',
      textStyle: { color: '#afc2d8', fontSize: 11 },
    },
    xAxis: {
      type: 'category', data: hours, boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.18)' } },
      axisTick: { show: false },
      axisLabel: { color: '#afc2d8', fontSize: 10, interval: 3 },
    },
    yAxis: {
      type: 'value', minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.10)' } },
      axisLabel: { color: '#afc2d8', fontSize: 10 },
    },
    series: [
      {
        name: '全部事件', type: 'line', smooth: true, symbol: 'none',
        data: baseAll.map((v, i) => (raised && i >= 20 ? v + 2 : v)),
        lineStyle: { color: CHART_COLORS.primary, width: 2 },
        itemStyle: { color: CHART_COLORS.primary },
        areaStyle: { color: 'rgba(64,158,255,0.14)' },
      },
      {
        name: '严重 / 紧急', type: 'line', smooth: true, symbol: 'none',
        data: baseHigh.map((v, i) => (raised && i >= 20 ? v + 1 : v)),
        lineStyle: { color: CHART_COLORS.danger, width: 1.8 },
        itemStyle: { color: CHART_COLORS.danger },
      },
    ],
  }
})

const { el } = useEChart(option, true)
</script>

<template>
  <section class="bs-panel bs-trend">
    <div class="bs-panel__head"><div><span>24H TREND</span><h2>近 24 小时安全事件趋势</h2></div></div>
    <div ref="el" class="bs-trend__chart"></div>
  </section>
</template>
