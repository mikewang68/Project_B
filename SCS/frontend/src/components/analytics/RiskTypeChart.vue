<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEChart, softTooltip, CHART_COLORS } from '@/composables/useEChart'
import type { RiskTypeCount } from '@/types/analytics'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ items: RiskTypeCount[]; activeType: string | undefined }>()
const emit = defineEmits<{ drill: [value: string] }>()

// 从高到低，横向柱最大值显示在顶部
const sorted = computed(() => [...props.items].sort((a, b) => a.count - b.count))

const option = computed(() => ({
  tooltip: {
    ...softTooltip,
    trigger: 'item',
    formatter(p: { name: string; value: number; dataIndex: number }) {
      const item = sorted.value[p.dataIndex]
      if (!item) return p.name
      return `<div style="font-weight:700;margin-bottom:2px">${item.type}</div>事件 <b>${item.count}</b> 起<br/>其中高风险 <b style="color:${CHART_COLORS.danger}">${item.high}</b> 起`
    },
  },
  grid: { left: 86, right: 34, top: 8, bottom: 8, containLabel: false },
  xAxis: {
    type: 'value', splitLine: { lineStyle: { color: CHART_COLORS.softGrid, type: 'dashed' } },
    axisLabel: { color: CHART_COLORS.axisLabel, fontSize: 10 },
  },
  yAxis: {
    type: 'category',
    data: sorted.value.map((x) => x.type),
    axisLine: { show: false }, axisTick: { show: false },
    axisLabel: { color: '#4d5a70', fontSize: 10.5 },
  },
  series: [
    {
      type: 'bar',
      barWidth: 13,
      data: sorted.value.map((x) => ({
        value: x.count,
        itemStyle: {
          borderRadius: [0, 7, 7, 0],
          color: props.activeType === x.type ? CHART_COLORS.primary : '#b9cbe6',
        },
      })),
      label: {
        show: true, position: 'right', color: '#4d5a70', fontSize: 10.5, fontWeight: 700,
        formatter: (p: { value: number }) => `${p.value}`,
      },
      emphasis: { itemStyle: { color: CHART_COLORS.primary } },
    },
  ],
}))

const { el, onChartClick } = useEChart(option, true)
onMounted(() => {
  onChartClick((p) => {
    if (p.componentType === 'series' && typeof p.name === 'string') emit('drill', p.name)
  })
})
</script>

<template>
  <div class="chart-card risk-type-chart" :data-active="!!activeType">
    <header>
      <div><span>RISK TYPES</span><h3>风险类型分布</h3></div>
      <small>点击柱条下钻明细</small>
    </header>
    <div ref="el" class="chart-canvas type-canvas"></div>
  </div>
</template>
