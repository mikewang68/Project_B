<script setup lang="ts">
const props = defineProps<{ status: string }>()
function tone(): string {
  switch (props.status) {
    case '已生效': return 'success'
    case '版本异常': return 'danger'
    case '待评审':
    case '发布中': return 'warning'
    case '已批准': return 'primary'
    case '草稿':
    case '已停用': return 'muted'
    default: return 'primary'
  }
}
</script>

<template>
  <span class="rule-status-badge" :data-tone="tone()">
    <i :class="{ pulsing: status === '发布中' }"></i>{{ status }}
  </span>
</template>
