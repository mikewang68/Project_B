<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { use } from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useEChart, softTooltip } from '@/composables/useEChart'
import type { LevelCount } from '@/types/analytics'

use([PieChart, TooltipComponent, CanvasRenderer])

const props = defineProps<{ items: LevelCount[]; activeLevel: string | undefined }>()
const emit = defineEmits<{ drill: [value: string] }>()

const LEVEL_COLOR: Record<string, string> = {
  一般: '#d7dee8',
  预警: '#e3c08a',
  严重: '#e08a8e',
  紧急: '#d6474f',
}
const total = computed(() => props.items.reduce((s, x) => s + x.count, 0))

const option = computed(() => ({
  tooltip: {
    ...softTooltip,
    trigger: 'item',
    formatter(p: { name: string; value: number; percent: number }) {
      return `<b>${p.name}</b>：${p.value} 起（${p.percent}%）`
    },
  },
  series: [
    {
      type: 'pie',
      radius: ['58%', '78%'],
      center: ['50%', '52%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 3, borderRadius: 6 },
      label: {
        show: true,
        formatter: '{b}\n{c}',
        color: '#4d5a70', fontSize: 10, lineHeight: 14,
      },
      labelLine: { length: 8, length2: 8, lineStyle: { color: '#d5dce5' } },
      emphasis: { scale: true, scaleSize: 4, label: { fontWeight: 700 } },
      selectedMode: 'single',
      data: props.items.map((x) => ({
        name: x.level,
        value: x.count,
        selected: props.activeLevel === x.level,
        itemStyle: { color: LEVEL_COLOR[x.level] ?? '#c8d1de' },
      })),
    },
  ],
  graphic: [
    {
      type: 'text', left: 'center', top: '46%',
      style: { text: `${total.value}`, fill: '#172033', fontSize: 22, fontWeight: 700, textAlign: 'center' },
    },
    {
      type: 'text', left: 'center', top: '58%',
      style: { text: '事件总数', fill: '#929bad', fontSize: 10, textAlign: 'center' },
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
  <div class="chart-card level-chart" :data-active="!!activeLevel">
    <header>
      <div><span>SEVERITY MIX</span><h3>告警等级分布</h3></div>
      <small>点击扇区下钻</small>
    </header>
    <div ref="el" class="chart-canvas level-canvas"></div>
  </div>
</template>
