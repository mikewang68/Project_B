<script setup lang="ts">
import AIEventCard from './AIEventCard.vue'
import type { AiEvent } from '@/types/ai'

withDefaults(defineProps<{ events: AiEvent[]; loading?: boolean }>(), { loading: false })
defineEmits<{ open: [event: AiEvent] }>()
</script>

<template>
  <div v-if="loading && !events.length" class="ai-event-empty">
    <span>正在加载 AI 事件…</span>
  </div>
  <div v-else-if="events.length" class="ai-event-grid">
    <AIEventCard v-for="event in events" :key="event.id" :event="event" @open="$emit('open', $event)" />
  </div>
  <div v-else class="ai-event-empty">
    <span>当前筛选条件下暂无 AI 事件</span>
    <small>可调整筛选条件，或使用顶部模拟按钮生成新事件</small>
  </div>
</template>
