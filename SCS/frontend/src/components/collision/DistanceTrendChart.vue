<script setup lang="ts">
import { computed } from 'vue'
import type { CollisionRisk, DistancePoint } from '@/types/collision'

const props = defineProps<{ points: DistancePoint[]; risk: CollisionRisk }>()
const width = 720, height = 178, max = 18
const chartPoints = computed(() => props.points.map((point, index) => ({
  x: props.points.length <= 1 ? 0 : (index / (props.points.length - 1)) * width,
  y: height - (Math.min(max, point.value) / max) * height,
  ...point,
})))
const line = computed(() => chartPoints.value.map((point) => `${point.x},${point.y}`).join(' '))
const area = computed(() => `0,${height} ${line.value} ${width},${height}`)
const y = (value: number): number => height - (value / max) * height
</script>

<template>
  <article class="dashboard-card distance-trend-card" :data-risk="risk">
    <header class="workspace-card-header"><div><span>DISTANCE TREND</span><h2>最近30秒距离趋势</h2><p>距离、阈值与风险变化同步更新</p></div><div class="trend-current"><small>最新距离</small><b>{{ points.at(-1)?.value.toFixed(1) }}m</b></div></header>
    <div class="collision-chart-stage">
      <div class="chart-y-labels"><span>18m</span><span>12m</span><span>6m</span><span>0m</span></div>
      <svg viewBox="0 0 720 178" preserveAspectRatio="none" aria-label="最近30秒距离趋势图">
        <defs><linearGradient id="collisionTrendArea" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#409eff" stop-opacity=".16"/><stop offset="1" stop-color="#409eff" stop-opacity="0"/></linearGradient></defs>
        <rect x="0" :y="y(10)" width="720" :height="y(6)-y(10)" class="threshold-zone warning" />
        <rect x="0" :y="y(6)" width="720" :height="y(3)-y(6)" class="threshold-zone severe" />
        <rect x="0" :y="y(3)" width="720" :height="y(0)-y(3)" class="threshold-zone emergency" />
        <line x1="0" x2="720" :y1="y(10)" :y2="y(10)" class="threshold-line warning"/><line x1="0" x2="720" :y1="y(6)" :y2="y(6)" class="threshold-line severe"/><line x1="0" x2="720" :y1="y(3)" :y2="y(3)" class="threshold-line emergency"/>
        <polygon :points="area" class="collision-trend-area"/><polyline :points="line" class="collision-trend-line"/>
        <circle v-if="chartPoints.length" :cx="chartPoints.at(-1)!.x" :cy="chartPoints.at(-1)!.y" r="5" class="collision-trend-point"/>
      </svg>
      <div class="threshold-labels"><span>预警 10m</span><span>严重 6m</span><span>紧急 3m</span></div>
      <div class="chart-x-labels"><span>-30s</span><span>-20s</span><span>-10s</span><span>现在</span></div>
    </div>
  </article>
</template>
