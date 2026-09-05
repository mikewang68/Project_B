<script setup lang="ts">
import { Files, QuestionFilled, Loading, Warning, AlarmClock, CircleCheck } from '@element-plus/icons-vue'

defineProps<{
  total: number
  pending: number
  active: number
  severe: number
  urgent: number
  closed: number
}>()
</script>

<template>
  <div class="alert-overview">
    <div class="alert-metric" data-tone="primary">
      <span class="alert-metric__icon"><el-icon><Files /></el-icon></span>
      <div><small>全部告警</small><b>{{ total }}</b></div>
    </div>
    <div class="alert-metric" data-tone="pending">
      <span class="alert-metric__icon"><el-icon><QuestionFilled /></el-icon></span>
      <div><small>待确认</small><b>{{ pending }}</b></div>
    </div>
    <div class="alert-metric" data-tone="active">
      <span class="alert-metric__icon"><el-icon><Loading /></el-icon></span>
      <div><small>处理中</small><b>{{ active }}</b></div>
    </div>
    <div class="alert-metric" data-tone="severe">
      <span class="alert-metric__icon"><el-icon><Warning /></el-icon></span>
      <div><small>严重</small><b>{{ severe }}</b></div>
    </div>
    <div class="alert-metric" data-tone="urgent">
      <span class="alert-metric__icon"><el-icon><AlarmClock /></el-icon></span>
      <div><small>紧急</small><b>{{ urgent }}</b></div>
    </div>
    <div class="alert-metric" data-tone="closed">
      <span class="alert-metric__icon"><el-icon><CircleCheck /></el-icon></span>
      <div><small>已关闭</small><b>{{ closed }}</b></div>
    </div>
  </div>
</template>
