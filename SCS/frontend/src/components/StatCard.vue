<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  label: string
  value: string | number
  unit: string
  tone: 'blue' | 'green' | 'amber' | 'red'
  note: string
  icon: Component
}>()
</script>

<template>
  <article class="metric-card" :data-tone="tone">
    <div class="metric-card__top">
      <span class="metric-card__icon"><el-icon><component :is="icon" /></el-icon></span>
      <span class="metric-card__status"><i></i>{{ note }}</span>
    </div>
    <small>{{ label }}</small>
    <strong>{{ value }}<em>{{ unit }}</em></strong>
  </article>
</template>
