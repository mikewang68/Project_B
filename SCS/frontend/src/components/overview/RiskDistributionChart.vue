<script setup lang="ts">
import { computed } from 'vue'
import type { OverviewDistributionDto } from '@/types/overview'

const props = defineProps<{ items: OverviewDistributionDto['items'] }>()

// 统一 ECharts/图表色序（规范第 12 节），同一业务语义同一颜色
const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#79bbff']
const max = computed(() => Math.max(1, ...props.items.map((i) => i.count)))
const rows = computed(() => props.items.slice(0, 6).map((item, index) => ({
  ...item,
  color: PALETTE[index % PALETTE.length],
  percent: Math.round((item.count / max.value) * 100),
})))
const top = computed(() => rows.value[0])
</script>

<template>
  <article class="dashboard-card distribution-chart-card">
    <header class="dashboard-card__header">
      <div><span>RISK PROFILE</span><h2>风险类型分布</h2>
        <p>{{ top ? `${top.type}是当前主要风险来源` : '暂无风险事件' }}</p>
      </div>
    </header>
    <div class="risk-bars">
      <div v-for="risk in rows" :key="risk.type" class="risk-bar-row">
        <span><i :style="{ background: risk.color }"></i>{{ risk.type }}</span>
        <div><i :style="{ width: `${risk.percent}%`, background: risk.color }"><em>{{ risk.count }} 起</em></i></div>
      </div>
      <div v-if="!rows.length" class="risk-bars-empty">暂无数据</div>
    </div>
  </article>
</template>
