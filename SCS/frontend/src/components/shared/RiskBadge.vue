<script setup lang="ts">
defineProps<{ risk: string }>()
</script>

<template><span class="risk-badge" :data-risk="risk"><i></i>{{ risk }}</span></template>
