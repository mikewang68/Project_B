<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ path: string }>()
const paths: Record<string, string> = {
  '/': 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  '/inventory': 'M3 7l9-4 9 4v10l-9 4-9-4z M3 7l9 4 9-4 M12 11v10',
  '/master-data': 'M3 21V7l9-4 9 4v14 M7 21v-9h10v9 M7 16h10',
  '/stockin': 'M4 14v6h16v-6 M12 3v12 M7 10l5 5 5-5',
  '/stockout': 'M4 14v6h16v-6 M12 15V3 M7 8l5-5 5 5',
  '/finance': 'M4 3v18h17 M8 16v-5 M13 16V7 M18 16V4',
  '/system/users': 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M17 4a4 4 0 0 1 0 7 M22 21v-2a4 4 0 0 0-3-4',
  '/integration': 'M3 7h17 M16 3l4 4-4 4 M21 17H4 M8 13l-4 4 4 4',
}
const icon = computed(() => paths[props.path] ?? paths['/'])
</script>
<template><svg class="navigation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icon" /></svg></template>
