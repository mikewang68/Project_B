<script setup lang="ts">
import { Switch } from '@element-plus/icons-vue'
import { ANALYTICS_AREAS, ANALYTICS_LEVELS, ANALYTICS_TEAMS, ANALYTICS_TYPES, ANALYTICS_PERIODS } from '@/types/analytics'

export interface AnalyticsFilterState {
  period: string
  area: string
  team: string
  type: string
  level: string
}
defineProps<{ filters: AnalyticsFilterState; surge: boolean }>()
const emit = defineEmits<{ 'toggle-scene': []; reset: [] }>()
</script>

<template>
  <div class="dashboard-card analytics-filters">
    <div class="analytics-period">
      <el-segmented v-model="filters.period" :options="[...ANALYTICS_PERIODS]" size="small" />
    </div>
    <label><small>区域</small>
      <el-select v-model="filters.area">
        <el-option v-for="a in ANALYTICS_AREAS" :key="a" :label="a" :value="a" />
      </el-select>
    </label>
    <label><small>班组</small>
      <el-select v-model="filters.team">
        <el-option v-for="t in ANALYTICS_TEAMS" :key="t" :label="t" :value="t" />
      </el-select>
    </label>
    <label><small>风险类型</small>
      <el-select v-model="filters.type">
        <el-option v-for="t in ANALYTICS_TYPES" :key="t" :label="t" :value="t" />
      </el-select>
    </label>
    <label><small>风险等级</small>
      <el-select v-model="filters.level">
        <el-option v-for="l in ANALYTICS_LEVELS" :key="l" :label="l" :value="l" />
      </el-select>
    </label>
    <div class="analytics-filters__actions">
      <button type="button" class="scene-toggle" :class="{ active: surge }" @click="emit('toggle-scene')">
        <el-icon><Switch /></el-icon>{{ surge ? '恢复默认数据' : '切换风险场景' }}
      </button>
      <button type="button" class="filters-reset" @click="emit('reset')">重置</button>
    </div>
  </div>
</template>
