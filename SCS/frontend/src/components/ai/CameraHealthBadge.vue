<script setup lang="ts">
import { computed } from 'vue'
import type { CameraHealth } from '@/types/ai'

const props = defineProps<{ health: CameraHealth }>()
const tone = computed(() => (props.health === '正常' ? 'ok' : 'fault'))
</script>

<template>
  <span class="camera-health-badge" :data-tone="tone">
    <i></i>{{ health === '正常' ? '摄像头正常' : health }}
  </span>
</template>
