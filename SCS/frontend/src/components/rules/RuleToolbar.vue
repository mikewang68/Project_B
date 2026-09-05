<script setup lang="ts">
import { Plus, VideoPlay, Connection, Warning } from '@element-plus/icons-vue'
import { RULE_LEVELS, RULE_STATUSES } from '@/types/rule'

defineProps<{ keyword: string; status: string; risk: string }>()
const emit = defineEmits<{
  'update:keyword': [v: string]
  'update:status': [v: string]
  'update:risk': [v: string]
  create: []
  simulate: []
  conflict: []
  mismatch: []
}>()
</script>

<template>
  <div class="dashboard-card rule-toolbar">
    <div class="rule-toolbar__search">
      <el-input :model-value="keyword" placeholder="搜索规则编号 / 名称 / 负责人" clearable
        @update:model-value="emit('update:keyword', $event)" />
    </div>
    <label class="rule-toolbar__select"><small>状态</small>
      <el-select :model-value="status" placeholder="全部状态" @update:model-value="emit('update:status', $event)">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in RULE_STATUSES" :key="s" :label="s" :value="s" />
      </el-select>
    </label>
    <label class="rule-toolbar__select"><small>风险等级</small>
      <el-select :model-value="risk" placeholder="全部等级" @update:model-value="emit('update:risk', $event)">
        <el-option label="全部等级" value="" />
        <el-option v-for="l in RULE_LEVELS" :key="l" :label="l" :value="l" />
      </el-select>
    </label>
    <div class="rule-toolbar__actions">
      <button type="button" class="rule-btn ghost" @click="emit('mismatch')">
        <el-icon><Warning /></el-icon>模拟版本异常
      </button>
      <button type="button" class="rule-btn ghost" @click="emit('conflict')">
        <el-icon><Connection /></el-icon>冲突检查
      </button>
      <button type="button" class="rule-btn ghost" @click="emit('simulate')">
        <el-icon><VideoPlay /></el-icon>模拟测试
      </button>
      <button type="button" class="rule-btn primary" @click="emit('create')">
        <el-icon><Plus /></el-icon>新建规则
      </button>
    </div>
  </div>
</template>
