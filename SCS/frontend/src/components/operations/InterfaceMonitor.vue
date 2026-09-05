<script setup lang="ts">
import type { CloudLink, OpsInterface } from '@/types/operations'
import OpsHealthBadge from './OpsHealthBadge.vue'

const props = defineProps<{ list: OpsInterface[]; link: CloudLink }>()

function displayState(i: OpsInterface) {
  if (props.link === 'disconnected' && i.cloudSide) return 'fault' as const
  return i.state
}
function latencyTone(ms: number): string {
  if (ms >= 120) return 'text-warning'
  return ''
}
</script>

<template>
  <div class="dashboard-card ops-panel">
    <div class="ops-card-head">
      <div><span>KEY INTERFACES</span><h3>关键接口</h3></div>
      <small>延迟 / 成功率 / 错误次数</small>
    </div>
    <div class="if-list">
      <div v-for="i in list" :key="i.id" class="if-row" :data-fault="displayState(i) === 'fault'">
        <div class="if-row__main">
          <b>{{ i.name }}</b>
          <small class="mono">{{ i.id }}</small>
        </div>
        <div class="if-row__metrics">
          <span :class="latencyTone(i.latencyMs)">{{ link === 'disconnected' && i.cloudSide ? '不可达' : i.latencyMs + 'ms' }}</span>
          <span>{{ (i.successRate).toFixed(2) }}%</span>
          <span>错误 {{ i.errorCount }}</span>
          <span>{{ i.lastCall }}</span>
        </div>
        <OpsHealthBadge :state="displayState(i)"
          :label="(link === 'disconnected' && i.cloudSide) ? '中断' : (i.state === 'degraded' ? '延迟' : i.state === 'fault' ? '故障' : '正常')" />
      </div>
    </div>
  </div>
</template>
