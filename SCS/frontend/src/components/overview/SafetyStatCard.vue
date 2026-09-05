<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(defineProps<{
  label: string
  value: string | number
  suffix?: string
  note: string
  icon: Component
  tone?: 'blue' | 'green' | 'amber' | 'red'
  variant?: 'default' | 'progress' | 'signal' | 'risk'
  progress?: number
}>(), {
  suffix: '',
  tone: 'blue',
  variant: 'default',
  progress: 0,
})
</script>

<template>
  <article class="safety-stat-card" :data-tone="tone" :data-variant="variant">
    <div class="safety-stat-card__head">
      <span class="safety-stat-card__icon"><el-icon><component :is="icon" /></el-icon></span>
      <span class="safety-stat-card__note"><i></i>{{ note }}</span>
    </div>
    <small>{{ label }}</small>
    <div class="safety-stat-card__value"><strong>{{ value }}</strong><em>{{ suffix }}</em></div>
    <div v-if="variant === 'progress'" class="stat-progress"><i :style="{ width: `${progress}%` }"></i></div>
    <div v-else-if="variant === 'signal'" class="stat-signal" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div>
    <span v-else-if="variant === 'risk'" class="stat-risk-line"></span>
  </article>
</template>
