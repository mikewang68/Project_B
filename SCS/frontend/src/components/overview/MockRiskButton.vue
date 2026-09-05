<script setup lang="ts">
import { RefreshLeft, Warning } from '@element-plus/icons-vue'

defineProps<{ active: boolean }>()
defineEmits<{ toggle: [] }>()
</script>

<template>
  <button class="mock-risk-button" :class="{ 'is-active': active }" type="button" @click="$emit('toggle')">
    <el-icon><component :is="active ? RefreshLeft : Warning" /></el-icon>
    <span>{{ active ? '恢复正常' : '模拟风险' }}</span>
  </button>
</template>
