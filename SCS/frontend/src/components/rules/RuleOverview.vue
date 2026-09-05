<script setup lang="ts">
import { CircleCheck, Document, EditPen, Warning, Refresh } from '@element-plus/icons-vue'

export interface RuleKpi {
  key: string
  label: string
  value: number
  hint: string
  tone: 'primary' | 'success' | 'warning' | 'muted' | 'danger'
  icon: 'check' | 'review' | 'draft' | 'error' | 'change'
}

defineProps<{ items: RuleKpi[] }>()
</script>

<template>
  <div class="rule-kpi">
    <div v-for="k in items" :key="k.key" class="rule-kpi__card" :data-tone="k.tone">
      <div class="rule-kpi__icon">
        <el-icon v-if="k.icon === 'check'"><CircleCheck /></el-icon>
        <el-icon v-else-if="k.icon === 'review'"><Document /></el-icon>
        <el-icon v-else-if="k.icon === 'draft'"><EditPen /></el-icon>
        <el-icon v-else-if="k.icon === 'error'"><Warning /></el-icon>
        <el-icon v-else><Refresh /></el-icon>
      </div>
      <div class="rule-kpi__body">
        <b>{{ k.value }}</b>
        <small>{{ k.label }}</small>
        <em>{{ k.hint }}</em>
      </div>
    </div>
  </div>
</template>
