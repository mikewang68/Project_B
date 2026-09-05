<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type EChartsType } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const element = ref<HTMLDivElement>()
let chart: EChartsType | undefined

onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  chart.setOption({
    color: ['#065A82', '#F5B84C', '#EF4444'],
    tooltip: { trigger: 'axis' },
    legend: { right: 8, textStyle: { color: '#64748b' } },
    grid: { left: 42, right: 20, top: 42, bottom: 28 },
    xAxis: { type: 'category', data: ['08/20', '08/21', '08/22', '08/23', '08/24', '08/25', '08/26'], axisLine: { lineStyle: { color: '#d8e2ec' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#edf2f7' } } },
    series: [
      { name: '一般', type: 'line', smooth: true, data: [12, 9, 15, 11, 8, 13, 7], areaStyle: { opacity: 0.07 } },
      { name: '严重', type: 'line', smooth: true, data: [4, 6, 3, 5, 2, 4, 3] },
      { name: '紧急', type: 'line', smooth: true, data: [1, 2, 1, 0, 1, 1, 1] },
    ],
  })
  window.addEventListener('resize', resize)
})

const resize = () => chart?.resize()

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
</script>

<template><div ref="element" class="trend-chart" role="img" aria-label="近七日一般、严重和紧急告警趋势折线图"></div></template>
