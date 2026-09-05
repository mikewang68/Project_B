<script setup lang="ts">
import type { AiTimelineNode } from '@/types/ai'

defineProps<{ nodes: AiTimelineNode[] }>()
</script>

<template>
  <section class="ai-timeline">
    <h4>事件时间线</h4>
    <ol>
      <li v-for="(node, index) in nodes" :key="`${node.time}-${index}`" :data-state="node.state">
        <span class="ai-timeline__dot"></span>
        <time>{{ node.time }}</time>
        <p>{{ node.text }}</p>
        <i v-if="index < nodes.length - 1" class="ai-timeline__line"></i>
      </li>
    </ol>
  </section>
</template>
