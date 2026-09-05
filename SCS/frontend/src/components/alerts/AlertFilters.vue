<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { ALERT_RISKS, ALERT_SOURCES, ALERT_STATUSES, type AlertEvent } from '@/types/alert'

export interface AlertFilterState {
  keyword: string
  timeRange: string
  area: string
  eventType: string
  risk: string
  status: string
  assignee: string
  source: string
}

defineProps<{ filters: AlertFilterState; alerts: AlertEvent[] }>()
const emit = defineEmits<{ reset: [] }>()

function areasOf(alerts: AlertEvent[]): string[] { return Array.from(new Set(alerts.map((a) => a.area))) }
function typesOf(alerts: AlertEvent[]): string[] { return Array.from(new Set(alerts.map((a) => a.eventType))) }
function assigneesOf(alerts: AlertEvent[]): string[] {
  return Array.from(new Set(alerts.map((a) => a.assignee).filter((x) => x !== '待分配')))
}
</script>

<template>
  <div class="dashboard-card alert-filter-bar">
    <label class="alert-filter-search">
      <el-icon><Search /></el-icon>
      <el-input v-model="filters.keyword" placeholder="搜索编号 / 标题 / 对象 / 规则编号" />
    </label>
    <label><small>时间范围</small>
      <el-select v-model="filters.timeRange">
        <el-option label="全部" value="全部" /><el-option label="近 2 小时（12 点后）" value="近2小时" />
        <el-option label="近 4 小时（10 点后）" value="近4小时" />
      </el-select>
    </label>
    <label><small>区域</small>
      <el-select v-model="filters.area"><el-option label="全部" value="全部" /><el-option v-for="a in areasOf(alerts)" :key="a" :label="a" :value="a" /></el-select>
    </label>
    <label><small>告警类型</small>
      <el-select v-model="filters.eventType"><el-option label="全部" value="全部" /><el-option v-for="t in typesOf(alerts)" :key="t" :label="t" :value="t" /></el-select>
    </label>
    <label><small>风险等级</small>
      <el-select v-model="filters.risk"><el-option label="全部" value="全部" /><el-option v-for="r in ALERT_RISKS" :key="r" :label="r" :value="r" /></el-select>
    </label>
    <label><small>当前状态</small>
      <el-select v-model="filters.status"><el-option label="全部" value="全部" /><el-option v-for="s in ALERT_STATUSES" :key="s" :label="s" :value="s" /></el-select>
    </label>
    <label><small>责任人</small>
      <el-select v-model="filters.assignee"><el-option label="全部" value="全部" /><el-option v-for="p in assigneesOf(alerts)" :key="p" :label="p" :value="p" /></el-select>
    </label>
    <label><small>来源</small>
      <el-select v-model="filters.source"><el-option label="全部" value="全部" /><el-option v-for="c in ALERT_SOURCES" :key="c" :label="c" :value="c" /></el-select>
    </label>
    <button type="button" class="alert-filter-reset" @click="emit('reset')">重置筛选</button>
  </div>
</template>
