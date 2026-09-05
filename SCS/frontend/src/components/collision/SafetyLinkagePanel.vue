<script setup lang="ts">
import { Check, CloseBold, Loading } from '@element-plus/icons-vue'
import type { LinkageStep } from '@/types/collision'

defineProps<{ steps: LinkageStep[] }>()
</script>

<template>
  <article class="dashboard-card safety-linkage-card">
    <header class="workspace-card-header"><div><span>SAFETY LINKAGE</span><h2>安全联动</h2><p>从风险检测到 PLC 回执的完整闭环</p></div></header>
    <div class="linkage-step-list">
      <div v-for="(step, index) in steps" :key="step.id" class="linkage-step" :data-state="step.state">
        <span class="linkage-step__icon"><el-icon><Check v-if="step.state === 'success'"/><CloseBold v-else-if="step.state === 'failed'"/><Loading v-else-if="step.state === 'running'"/><i v-else></i></el-icon></span>
        <div><b>{{ step.label }}</b><small>{{ step.detail }}</small></div>
        <em>{{ step.state === 'success' ? '成功' : step.state === 'failed' ? '失败' : step.state === 'running' ? '执行中' : '等待' }}</em>
        <i v-if="index < steps.length - 1" class="linkage-step__line"></i>
      </div>
    </div>
  </article>
</template>
