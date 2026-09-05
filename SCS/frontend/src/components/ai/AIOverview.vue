<script setup lang="ts">
import { Aim, Bell, CircleCheck, VideoCamera, Warning } from '@element-plus/icons-vue'

defineProps<{
  today: number
  pending: number
  confirmed: number
  falsePositive: number
  cameraFault: number
}>()
</script>

<template>
  <div class="ai-overview-strip">
    <div class="ai-metric" data-tone="primary">
      <span class="ai-metric__icon"><el-icon><Aim /></el-icon></span>
      <div><small>今日 AI 事件</small><b>{{ today }}</b></div>
    </div>
    <div class="ai-metric" data-tone="pending">
      <span class="ai-metric__icon"><el-icon><Bell /></el-icon></span>
      <div><small>待复核</small><b>{{ pending }}</b></div>
    </div>
    <div class="ai-metric" data-tone="danger">
      <span class="ai-metric__icon"><el-icon><Warning /></el-icon></span>
      <div><small>确认违规</small><b>{{ confirmed }}</b></div>
    </div>
    <div class="ai-metric" data-tone="muted">
      <span class="ai-metric__icon"><el-icon><CircleCheck /></el-icon></span>
      <div><small>误报</small><b>{{ falsePositive }}</b></div>
    </div>
    <div class="ai-metric" data-tone="warning">
      <span class="ai-metric__icon"><el-icon><VideoCamera /></el-icon></span>
      <div><small>摄像头异常</small><b>{{ cameraFault }}</b></div>
    </div>
  </div>
</template>
