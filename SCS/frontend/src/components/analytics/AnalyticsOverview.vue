<script setup lang="ts">
import AnalyticsStatCard from './AnalyticsStatCard.vue'
import type { AnalyticsKpi } from '@/types/analytics'
defineProps<{ items: AnalyticsKpi[] }>()
</script>

<template>
  <div class="analytics-kpi">
    <AnalyticsStatCard v-for="kpi in items" :key="kpi.key" :kpi="kpi" />
  </div>
</template>
