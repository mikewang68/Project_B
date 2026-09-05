<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { FenceRecord } from '@/types/fence'

defineProps<{ fences: FenceRecord[]; selectedId: string }>()
defineEmits<{ select: [fence: FenceRecord]; create: [] }>()
</script>

<template>
  <aside class="fence-list-card dashboard-card">
    <header><div><span>GEOFENCE</span><h2>围栏列表</h2></div><button type="button" @click="$emit('create')"><el-icon><Plus /></el-icon>新建围栏</button></header>
    <div class="fence-list">
      <button v-for="fence in fences" :key="fence.id" type="button" class="fence-list-item" :class="{ 'is-selected': selectedId === fence.id }" @click="$emit('select', fence)">
        <span class="fence-list-item__icon" :data-tone="fence.tone"><i></i></span>
        <span><small>{{ fence.id }}</small><b>{{ fence.name }}</b><em>{{ fence.kind }} · {{ fence.area }}</em></span>
        <StatusBadge :status="fence.status" />
      </button>
    </div>
  </aside>
</template>
