<script setup lang="ts">
import type { HealthState } from '@/types/operations'
import { HEALTH_LABEL } from '@/types/operations'

const props = withDefaults(defineProps<{ state: HealthState; label?: string; pulse?: boolean }>(), { label: '', pulse: false })
const text = () => props.label || HEALTH_LABEL[props.state]
</script>

<template>
  <span class="ops-badge" :data-state="state">
    <i :class="{ pulsing: pulse || state === 'fault' }"></i>{{ text() }}
  </span>
</template>
