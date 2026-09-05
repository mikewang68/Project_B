<script setup lang="ts">
import { computed } from 'vue'
import type { AlertStatus } from '@/types/alert'

const props = defineProps<{ status: AlertStatus }>()
const tone = computed(() => {
  switch (props.status) {
    case '已升级': return 'danger'
    case '处理中': return 'warning'
    case '待复核': return 'indigo'
    case '待处理': return 'teal'
    case '已确认': case '待派单': return 'blue'
    case '已关闭': return 'muted'
    default: return 'primary'
  }
})
</script>

<template>
  <span class="alert-status-badge" :data-tone="tone"><i></i>{{ status }}</span>
</template>
