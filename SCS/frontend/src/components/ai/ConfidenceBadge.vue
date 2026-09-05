<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ confidence: number; threshold?: number }>()

const tone = computed<'high' | 'mid' | 'low' | 'none'>(() => {
  if (!props.confidence) return 'none'
  if (props.confidence >= 85) return 'high'
  if (props.confidence >= 70) return 'mid'
  return 'low'
})

const text = computed(() => (props.confidence ? `${props.confidence.toFixed(1)}%` : '--'))
const hint = computed(() => {
  if (tone.value === 'none') return '摄像头降级，无置信度'
  if (tone.value === 'low') return '低于阈值，建议人工复核'
  if (typeof props.threshold === 'number' && props.confidence < props.threshold) return '低于规则阈值'
  return '高于规则阈值'
})
</script>

<template>
  <span class="confidence-badge" :data-tone="tone" :title="hint">
    <i></i>{{ text }}
  </span>
</template>
