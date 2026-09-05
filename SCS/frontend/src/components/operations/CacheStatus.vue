<script setup lang="ts">
import type { EdgeNode } from '@/types/operations'

defineProps<{ nodes: EdgeNode[] }>()
const emit = defineEmits<{ detail: [node: EdgeNode] }>()

function stateText(n: EdgeNode): string {
  if (n.storage >= 85) return '容量高风险'
  if (n.storage >= 60) return '占用偏高'
  return '正常'
}
</script>

<template>
  <div class="dashboard-card ops-panel">
    <div class="ops-card-head">
      <div><span>EDGE CACHE</span><h3>边缘缓存容量</h3></div>
      <small>事件 / 视频证据 / 日志空间</small>
    </div>
    <div class="cache-list">
      <button v-for="n in nodes" :key="n.id" type="button" class="cache-row" @click="emit('detail', n)">
        <div class="cache-row__head">
          <b class="mono">{{ n.id }}</b>
          <span :data-danger="n.storage >= 85" :data-warn="n.storage >= 60 && n.storage < 85">{{ stateText(n) }}</span>
        </div>
        <div class="edge-bar edge-bar--lg">
          <i :data-tone="n.storage >= 85 ? 'danger' : n.storage >= 60 ? 'warning' : 'primary'"
            :style="{ width: n.storage + '%' }"></i>
        </div>
        <div class="cache-row__foot">
          <span>已用 {{ n.storage }}%</span>
          <span>缓存事件 {{ n.cacheEvents }}</span>
        </div>
      </button>
    </div>
  </div>
</template>
