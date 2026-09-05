<script setup lang="ts">
import { computed } from 'vue'
import { ArrowUp, ArrowDown, Minus } from '@element-plus/icons-vue'
import type { AnalyticsKpi } from '@/types/analytics'

const props = defineProps<{ kpi: AnalyticsKpi }>()
const tone = computed(() => {
  if (props.kpi.direction === 'flat') return 'flat'
  const isGood = props.kpi.direction === 'down' ? props.kpi.goodWhenDown : !props.kpi.goodWhenDown
  return isGood ? 'good' : 'bad'
})
const deltaText = computed(() => {
  const arrow = props.kpi.direction === 'up' ? '↑' : props.kpi.direction === 'down' ? '↓' : '—'
  return `${arrow} ${props.kpi.deltaPct.toFixed(1)}%`
})
</script>

<template>
  <div class="stat-card" :data-variant="kpi.variant" :data-tone="tone">
    <div class="stat-card__top">
      <small>{{ kpi.label }}</small>
      <span class="stat-card__delta"><el-icon v-if="kpi.direction==='up'"><ArrowUp /></el-icon
        ><el-icon v-else-if="kpi.direction==='down'"><ArrowDown /></el-icon
        ><el-icon v-else><Minus /></el-icon>{{ deltaText }}</span>
    </div>
    <b class="stat-card__value">{{ kpi.value }}</b>
    <p class="stat-card__hint">{{ kpi.hint }}</p>
  </div>
</template>
