<script setup lang="ts">
import { computed } from 'vue'
import type { OverviewTrendDto } from '@/types/overview'

const props = defineProps<{ points: OverviewTrendDto['points']; demo?: boolean }>()

const labels = computed(() => props.points.map((p) => p.label))
const total = computed(() => props.points.reduce((sum, p) => sum + p.total, 0))
const points = computed(() => {
  const max = Math.max(10, ...props.points.map((p) => p.total))
  const step = props.points.length > 1 ? 600 / (props.points.length - 1) : 600
  return props.points.map((point, index) => ({
    x: 20 + index * step,
    y: 155 - (point.total / max) * 130,
    value: point.total,
    high: point.high,
    label: point.label,
  }))
})
const line = computed(() => points.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(' '))
const area = computed(() => (points.value.length ? `${line.value} L 620 168 L 20 168 Z` : ''))
</script>

<template>
  <article class="dashboard-card trend-chart-card">
    <header class="dashboard-card__header">
      <div><span>7-DAY TREND</span><h2>近7日告警趋势</h2>
        <p>{{ demo ? 'Demo 历史样本，告警接入后替换为真实聚合' : '按安全事件发生时间聚合' }}</p>
      </div>
      <div class="chart-summary"><b>{{ total }}</b><small>累计告警</small></div>
    </header>
    <div class="risk-trend-chart">
      <div class="trend-y-axis"><span>20</span><span>15</span><span>10</span><span>5</span><span>0</span></div>
      <svg viewBox="0 0 640 190" preserveAspectRatio="none" aria-label="告警趋势折线图">
        <defs>
          <linearGradient id="trendArea" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#315fa8" stop-opacity=".16" /><stop offset="1" stop-color="#315fa8" stop-opacity="0" /></linearGradient>
        </defs>
        <path class="trend-area" :d="area" />
        <path class="trend-line" :d="line" />
        <g v-for="point in points" :key="point.label" class="trend-point">
          <circle :cx="point.x" :cy="point.y" r="5"><title>{{ point.label }}：全部 {{ point.value }} 条 · 高风险 {{ point.high }} 条</title></circle>
        </g>
      </svg>
      <div class="trend-x-axis"><span v-for="label in labels" :key="label">{{ label }}</span></div>
    </div>
  </article>
</template>
