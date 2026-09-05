<script setup lang="ts">
import type { AlertSource } from '@/types/alert'
defineProps<{ source: AlertSource }>()
</script>

<template>
  <span class="alert-source-tag" :data-source="source">{{ source }}</span>
</template>
