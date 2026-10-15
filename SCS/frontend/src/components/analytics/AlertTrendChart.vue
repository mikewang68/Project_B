<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEChart, softTooltip, CHART_COLORS } from '@/composables/useEChart'
import type { TrendPoint } from '@/types/analytics'

use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{ points: TrendPoint[]; active: boolean }>()

const hasDemoBaseline = computed(() => props.points.some((p) => p.demoBaseline))

const option = computed(() => ({
  color: [CHART_COLORS.primary, CHART_COLORS.danger],
  tooltip: {
    ...softTooltip,
    formatter(params: { marker: string; seriesName: string; value: number; dataIndex: number }[] | never) {
      const list = Array.isArray(params) ? params : [params]
      const first = list[0]
      if (!first || first.dataIndex === undefined) return ''
      const p = props.points[first.dataIndex]
      if (!p) return ''
      const rows = list.map((x) => `${x.marker}${x.seriesName}：<b>${x.value}</b> 起`).join('<br/>')
      const note = p.demoBaseline ? '<div style="margin-top:4px;color:#e6a23c;font-size:11px">⚠️ 含演示基线数据</div>' : ''
      return `<div style="font-weight:700;margin-bottom:3px">${p.date}</div>${rows}${note}`
    },
  },
  legend: {
    right: 8, top: 4, icon: 'roundRect', itemWidth: 10, itemHeight: 6,
    textStyle: { color: CHART_COLORS.axisLabel, fontSize: 10 },
  },
  grid: { left: 38, right: 16, top: 40, bottom: 28 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: props.points.map((p) => p.date),
    axisLine: { lineStyle: { color: '#dcdfe6' } },
    axisTick: { show: false },
    axisLabel: { color: CHART_COLORS.axisLabel, fontSize: 10 },
  },
  yAxis: {
    type: 'value', minInterval: 1,
    axisLabel: { color: CHART_COLORS.axisLabel, fontSize: 10 },
    splitLine: { lineStyle: { color: CHART_COLORS.softGrid, type: 'dashed' } },
  },
  series: [
    {
      name: '事件总数', type: 'line', smooth: true, symbol: 'circle', symbolSize: 7,
      lineStyle: { width: 2.5 },
      data: props.points.map((p) => p.total),
      emphasis: { focus: 'series' },
    },
    {
      name: '严重/紧急', type: 'line', smooth: true, symbol: 'circle', symbolSize: 7,
      lineStyle: { width: 2.5 },
      data: props.points.map((p) => p.high),
      emphasis: { focus: 'series' },
    },
  ],
}))

const { el } = useEChart(option, true)
</script>

<template>
  <div class="chart-card trend-chart-card" :data-active="active">
    <header>
      <div>
        <span>SAFETY TREND</span>
        <h3>
          安全事件趋势
          <span v-if="hasDemoBaseline" class="demo-baseline-badge">含演示基线</span>
        </h3>
      </div>
    </header>
    <div ref="el" class="chart-canvas trend-canvas"></div>
  </div>
</template>

<style scoped>
.demo-baseline-badge {
  display: inline-block;
  font-size: 11px;
  font-weight: 500;
  color: #b87a1e;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 1px 6px;
  margin-left: 8px;
  vertical-align: middle;
}
</style>
