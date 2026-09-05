<script setup lang="ts">
import { View } from '@element-plus/icons-vue'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import AlertStatusBadge from './AlertStatusBadge.vue'
import AlertSourceTag from './AlertSourceTag.vue'
import SLAIndicator from './SLAIndicator.vue'
import type { AlertEvent } from '@/types/alert'

defineProps<{ alerts: AlertEvent[]; loading?: boolean }>()
const emit = defineEmits<{ open: [alert: AlertEvent] }>()

function rowClass({ row }: { row: AlertEvent }): string {
  return row.risk === '紧急' ? 'alert-row-urgent' : ''
}
</script>

<template>
  <div class="dashboard-card alert-table-card">
    <el-table v-loading="loading" :data="alerts" :row-class-name="rowClass" @row-click="(row: AlertEvent) => emit('open', row)">
      <el-table-column prop="id" label="事件编号" min-width="156">
        <template #default="{ row }"><span class="alert-table-id">{{ row.id }}</span></template>
      </el-table-column>
      <el-table-column label="风险" min-width="70">
        <template #default="{ row }"><RiskBadge :risk="row.risk" /></template>
      </el-table-column>
      <el-table-column prop="title" label="事件类型" min-width="176" show-overflow-tooltip />
      <el-table-column prop="time" label="发生时间" min-width="84" />
      <el-table-column prop="area" label="区域" min-width="98" />
      <el-table-column prop="target" label="对象" min-width="140" show-overflow-tooltip />
      <el-table-column label="来源" min-width="92">
        <template #default="{ row }"><AlertSourceTag :source="row.source" /></template>
      </el-table-column>
      <el-table-column label="当前状态" min-width="88">
        <template #default="{ row }"><AlertStatusBadge :status="row.status" /></template>
      </el-table-column>
      <el-table-column prop="assignee" label="责任人" min-width="106" />
      <el-table-column label="SLA" min-width="92">
        <template #default="{ row }"><SLAIndicator :remaining-sec="row.slaRemainingSec" :deadline="row.slaDeadline" /></template>
      </el-table-column>
      <el-table-column label="操作" width="72" fixed="right">
        <template #default="{ row }">
          <button type="button" class="alert-row-view" @click.stop="emit('open', row)">
            <el-icon><View /></el-icon>详情
          </button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="alert-table-empty">当前筛选条件下暂无告警</div>
      </template>
    </el-table>
  </div>
</template>
