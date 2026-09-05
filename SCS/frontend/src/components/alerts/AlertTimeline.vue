<script setup lang="ts">
import type { TimelineNode } from '@/types/alert'
defineProps<{ nodes: TimelineNode[] }>()
</script>

<template>
  <section class="alert-timeline">
    <h4>事件时间线</h4>
    <ol>
      <li v-for="(node, index) in nodes" :key="`${node.time}-${index}`" :data-state="node.state">
        <span class="alert-timeline__dot"></span>
        <time>{{ node.time }}</time>
        <p>{{ node.text }}</p>
        <i v-if="index < nodes.length - 1" class="alert-timeline__line"></i>
      </li>
    </ol>
  </section>
</template>
