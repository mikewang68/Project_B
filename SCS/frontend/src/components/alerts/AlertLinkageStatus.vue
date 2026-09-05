<script setup lang="ts">
import { Check, CloseBold, Loading, WarningFilled } from '@element-plus/icons-vue'
import type { LinkageStep } from '@/types/alert'

defineProps<{ steps: LinkageStep[]; failed: boolean; finished: boolean; takeover: boolean }>()
const emit = defineEmits<{ takeover: [] }>()
</script>

<template>
  <section class="alert-linkage">
    <header>
      <h4>联动执行状态</h4>
      <span v-if="finished" class="alert-linkage__state ok">安全联动执行完成</span>
      <span v-else-if="failed" class="alert-linkage__state bad">联动执行异常</span>
      <span v-else class="alert-linkage__state running">联动执行中</span>
    </header>
    <div class="alert-linkage__grid">
      <div v-for="step in steps" :key="step.id" class="alert-linkage__step" :data-state="step.state">
        <span class="alert-linkage__icon">
          <el-icon v-if="step.state === 'success'"><Check /></el-icon>
          <el-icon v-else-if="step.state === 'failed'"><CloseBold /></el-icon>
          <el-icon v-else-if="step.state === 'running'" class="is-spin"><Loading /></el-icon>
          <i v-else></i>
        </span>
        <div><b>{{ step.label }}</b><small>{{ step.detail }}</small></div>
        <em>{{ step.state === 'success' ? '成功' : step.state === 'failed' ? '失败' : step.state === 'running' ? '执行中' : '等待' }}</em>
      </div>
    </div>
    <div v-if="failed && !takeover" class="alert-linkage__alarm">
      <el-icon><WarningFilled /></el-icon>
      <p>无法确认设备已停止，请立即人工接管。</p>
      <button type="button" class="danger" @click="emit('takeover')">人工接管</button>
    </div>
    <div v-else-if="takeover" class="alert-linkage__taken">
      <el-icon><Check /></el-icon><span>已由人工接管现场设备，接管动作已记录至时间线</span>
    </div>
  </section>
</template>
