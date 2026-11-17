<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { FenceRecord } from '@/types/fence'

defineProps<{
  fences: FenceRecord[]
  selectedId: string
  disabledCount: number
  showDisabled: boolean
}>()
defineEmits<{ select: [fence: FenceRecord]; create: []; 'toggle-disabled': [] }>()
</script>

<template>
  <aside class="fence-list-card dashboard-card">
    <header><div><span>GEOFENCE</span><h2>围栏列表</h2></div><button v-permission="{permission:'safety:fence:edit',menu:'SCS:fences',action:'ADD'}" type="button" @click="$emit('create')"><el-icon><Plus /></el-icon>新建围栏</button></header>
    <div class="fence-list">
      <button v-for="fence in fences" :key="fence.id" type="button" class="fence-list-item" :class="{ 'is-selected': selectedId === fence.id }" @click="$emit('select', fence)">
        <span class="fence-list-item__icon" :data-tone="fence.tone"><i></i></span>
        <span><small>{{ fence.id }}</small><b>{{ fence.name }}</b><em>{{ fence.kind }} · {{ fence.area }}</em></span>
        <StatusBadge :status="fence.status" />
      </button>
      <div v-if="!fences.length" class="fence-list-empty">
        <p>暂无生效或待发布围栏</p>
        <small v-if="disabledCount">已停用的 {{ disabledCount }} 条围栏默认隐藏</small>
      </div>
    </div>
    <footer v-if="disabledCount" class="fence-list-footer">
      <button type="button" @click="$emit('toggle-disabled')">
        {{ showDisabled ? '隐藏已停用围栏' : `显示已停用围栏（${disabledCount}）` }}
      </button>
    </footer>
  </aside>
</template>
