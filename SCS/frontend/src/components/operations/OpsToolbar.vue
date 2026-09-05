<script setup lang="ts">
import { Connection, MagicStick, RefreshLeft, Timer, VideoCamera, Files } from '@element-plus/icons-vue'
import type { CloudLink } from '@/types/operations'

defineProps<{ link: CloudLink; recovering: boolean }>()
const emit = defineEmits<{
  disconnect: []
  recover: []
  localRisk: []
  timeDrift: []
  cacheAlert: []
  deviceFault: []
}>()
</script>

<template>
  <div class="dashboard-card ops-toolbar">
    <div class="ops-toolbar__title">
      <span>FAULT SIMULATION</span>
      <b>故障与自治演示</b>
    </div>
    <div class="ops-toolbar__btns">
      <button v-if="link === 'online'" type="button" class="ops-btn danger" @click="emit('disconnect')">
        <el-icon><Connection /></el-icon>模拟云边断网
      </button>
      <button v-else type="button" class="ops-btn primary" :disabled="recovering" @click="emit('recover')">
        <el-icon><RefreshLeft /></el-icon>恢复云边连接
      </button>
      <button type="button" class="ops-btn" :disabled="link !== 'disconnected'" @click="emit('localRisk')">
        <el-icon><MagicStick /></el-icon>模拟本地风险
      </button>
      <button type="button" class="ops-btn" :disabled="link !== 'disconnected'" @click="emit('timeDrift')">
        <el-icon><Timer /></el-icon>模拟时间偏差
      </button>
      <button type="button" class="ops-btn" @click="emit('cacheAlert')">
        <el-icon><Files /></el-icon>模拟缓存告警
      </button>
      <button type="button" class="ops-btn" @click="emit('deviceFault')">
        <el-icon><VideoCamera /></el-icon>模拟设备异常
      </button>
    </div>
  </div>
</template>
