<script setup lang="ts">
import { computed } from 'vue'
import type { ReviewStatus } from '@/types/ai'

const props = defineProps<{ status: ReviewStatus }>()

const tone = computed(() => {
  switch (props.status) {
    case '已确认违规': return 'danger'
    case '误报': case '已关闭': return 'muted'
    case '不确定': case '处理中': return 'warning'
    case '已派单': return 'indigo'
    default: return 'primary'
  }
})
</script>

<template>
  <span class="ai-status-badge" :data-tone="tone"><i></i>{{ status }}</span>
</template>
