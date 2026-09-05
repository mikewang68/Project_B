<script setup lang="ts">
import type { AlarmLevel } from '@/types/safety'
defineProps<{ level: AlarmLevel }>()
</script>

<template><span class="level-tag" :data-level="level"><i></i>{{ level }}</span></template>

