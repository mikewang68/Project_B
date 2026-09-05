<script setup lang="ts">
import { Bell, WarningFilled } from '@element-plus/icons-vue'
import type { OverviewAlert } from '@/types/overview'

defineProps<{ alert: OverviewAlert }>()
defineEmits<{ select: [alert: OverviewAlert] }>()
</script>

<template>
  <button class="realtime-alert-item" :data-level="alert.level" type="button" @click="$emit('select', alert)">
    <span class="realtime-alert-item__icon"><el-icon><component :is="alert.level === '一般' ? Bell : WarningFilled" /></el-icon></span>
    <span class="realtime-alert-item__content">
      <span class="realtime-alert-item__meta"><em>{{ alert.level }}</em><time>{{ alert.time }}</time></span>
      <b>{{ alert.title }}</b>
      <small>{{ alert.summary }}</small>
    </span>
    <i class="realtime-alert-item__arrow">›</i>
  </button>
</template>
