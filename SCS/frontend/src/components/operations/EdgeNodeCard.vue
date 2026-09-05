<script setup lang="ts">
import type { CloudLink, EdgeNode } from '@/types/operations'
import OpsHealthBadge from './OpsHealthBadge.vue'

const props = defineProps<{ node: EdgeNode; link: CloudLink }>()
const emit = defineEmits<{ detail: [node: EdgeNode] }>()

function stateOf() {
  if (!props.node.online) return 'offline' as const
  if (props.node.storage >= 85) return 'fault' as const
  if (props.link === 'disconnected' || props.link === 'link-error') return 'degraded' as const
  if (props.node.storage >= 60 || props.node.timeOffsetMs !== null) return 'degraded' as const
  return 'normal' as const
}
function barTone(v: number): string {
  if (v >= 85) return 'danger'
  if (v >= 60) return 'warning'
  return 'primary'
}
</script>

<template>
  <button type="button" class="edge-card" :data-state="stateOf()" @click="emit('detail', node)">
    <div class="edge-card__head">
      <div>
        <b>{{ node.id }}</b>
        <small>{{ node.name.split(' · ')[1] }}</small>
      </div>
      <OpsHealthBadge
        :state="stateOf()"
        :label="(link === 'disconnected' || link === 'link-error') ? '本地自治' : (node.online ? '在线' : '离线')"
      />
    </div>
    <div class="edge-card__metrics">
      <div v-for="m in [
        { label: 'CPU', value: node.cpu },
        { label: '内存', value: node.memory },
        { label: '缓存', value: node.storage },
      ]" :key="m.label" class="edge-metric">
        <div class="edge-metric__row"><span>{{ m.label }}</span><b>{{ m.value }}%</b></div>
        <div class="edge-bar"><i :data-tone="barTone(m.value)" :style="{ width: m.value + '%' }"></i></div>
      </div>
    </div>
    <div class="edge-card__foot">
      <span class="mono">{{ node.ruleVersion }}</span>
      <span :data-warn="node.timeOffsetMs !== null">
        时间{{ node.timeOffsetMs === null ? '同步正常' : '偏差 ' + node.timeOffsetMs / 1000 + 's' }}
      </span>
      <span :data-warn="node.cacheEvents > 0">缓存 {{ node.cacheEvents }} 条</span>
    </div>
  </button>
</template>
