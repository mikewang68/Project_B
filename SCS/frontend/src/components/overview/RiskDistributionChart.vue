<script setup lang="ts">
import { computed } from 'vue'
import type { OverviewDistributionDto } from '@/types/overview'

const props = defineProps<{ items: OverviewDistributionDto['items'] }>()

// 克制的同色系色阶（不使用荧光色，不超过 4 个主色层级）
const PALETTE = ['#315fa8', '#5f84bc', '#879bb8', '#c58a2a', '#aeb9c8', '#c7d2e2']
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
