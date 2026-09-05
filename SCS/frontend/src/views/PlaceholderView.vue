<script setup lang="ts">
import PagePlaceholder from '@/components/PagePlaceholder.vue'

defineProps<{ title: string; description: string; section: string }>()
</script>

<template>
  <PagePlaceholder :title="title" :description="description" :section="section" />
</template>
