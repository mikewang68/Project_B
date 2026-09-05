<script setup lang="ts">
import { VideoPlay, View } from '@element-plus/icons-vue'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import RuleStatusBadge from './RuleStatusBadge.vue'
import type { SafetyRule } from '@/types/rule'

defineProps<{ rules: SafetyRule[] }>()
const emit = defineEmits<{
  detail: [rule: SafetyRule]
  simulate: [rule: SafetyRule]
}>()
function onRowClick(row: unknown): void {
  emit('detail', row as SafetyRule)
}
function rowClass({ row }: { row: SafetyRule }): string {
  return row.status === '版本异常' ? 'row-mismatch' : ''
}
</script>

<template>
  <div class="dashboard-card rule-table-card">
    <div class="rule-table-head">
      <div><span>RULE LIBRARY</span><h3>规则列表</h3></div>
      <small>共 {{ rules.length }} 条 · 点击行查看规则详情与版本</small>
    </div>
    <el-table :data="rules" class="rule-table" row-key="id" :row-class-name="rowClass"
      @row-click="onRowClick">
      <el-table-column prop="id" label="规则编号" width="148">
        <template #default="{ row }"><span class="rule-table__id">{{ row.id }}</span></template>
      </el-table-column>
      <el-table-column prop="name" label="规则名称" min-width="218">
        <template #default="{ row }">
          <div class="rule-table__name">
            <b>{{ row.name }}</b>
            <small v-if="row.highRisk">高危参数</small>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="规则类型" width="104" />
      <el-table-column label="适用区域" min-width="168">
        <template #default="{ row }">
          <span class="rule-table__areas">{{ row.areas.join('、') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="84">
        <template #default="{ row }"><span class="rule-table__version">{{ row.version }}</span></template>
      </el-table-column>
      <el-table-column label="风险等级" width="96">
        <template #default="{ row }"><RiskBadge :risk="row.risk" /></template>
      </el-table-column>
      <el-table-column label="状态" width="108">
        <template #default="{ row }"><RuleStatusBadge :status="row.status" /></template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="148" />
      <el-table-column prop="owner" label="负责人" width="132" />
      <el-table-column label="操作" width="132" fixed="right">
        <template #default="{ row }">
          <button type="button" class="rule-table__op" @click.stop="emit('detail', row)">
            <el-icon><View /></el-icon>详情
          </button>
          <button type="button" class="rule-table__op" @click.stop="emit('simulate', row)">
            <el-icon><VideoPlay /></el-icon>仿真
          </button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="rule-table__empty">当前筛选条件下没有规则</div>
      </template>
    </el-table>
  </div>
</template>
