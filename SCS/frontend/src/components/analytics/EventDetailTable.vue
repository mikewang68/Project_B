<script setup lang="ts">
import RiskBadge from '@/components/shared/RiskBadge.vue'
import AlertStatusBadge from '@/components/alerts/AlertStatusBadge.vue'
import type { AnalyticsEventItem } from '@/types/analytics'

defineProps<{ events: AnalyticsEventItem[] }>()

function duration(sec: number | undefined): string {
  if (sec === undefined) return '处置中'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}m ${String(s).padStart(2, '0')}s`
}
</script>

<template>
  <div class="dashboard-card event-detail-card">
    <header class="event-detail-head">
      <div><span>EVENT DRILLDOWN</span><h3>事件明细</h3></div>
      <small>点击上方任意图表 / 排行 / 班组即可联动筛选</small>
    </header>
    <el-table :data="events" height="386">
      <el-table-column prop="id" label="事件编号" min-width="158">
        <template #default="{ row }"><span class="event-detail-id">{{ row.id }}</span></template>
      </el-table-column>
      <el-table-column prop="time" label="时间" min-width="104" />
      <el-table-column prop="type" label="类型" min-width="120" />
      <el-table-column prop="area" label="区域" min-width="110" />
      <el-table-column prop="target" label="对象" min-width="190" show-overflow-tooltip />
      <el-table-column label="等级" min-width="74">
        <template #default="{ row }"><RiskBadge :risk="row.level" /></template>
      </el-table-column>
      <el-table-column label="状态" min-width="90">
        <template #default="{ row }"><AlertStatusBadge :status="row.status" /></template>
      </el-table-column>
      <el-table-column label="处置时长" min-width="92">
        <template #default="{ row }">
          <span :class="{ ongoing: row.durationSec === undefined }">{{ duration(row.durationSec) }}</span>
        </template>
      </el-table-column>
      <template #empty><div class="event-detail-empty">当前下钻条件下暂无事件</div></template>
    </el-table>
  </div>
</template>
