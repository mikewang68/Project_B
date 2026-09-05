<script setup lang="ts">
import { computed } from 'vue'
import type { CloudLink, LocalEvent } from '@/types/operations'

const props = defineProps<{ queue: LocalEvent[]; link: CloudLink }>()

const pending = computed(() => props.queue.filter((e) => e.status === '待补传' || e.status === '补传中'))
const highRisk = computed(() => pending.value.filter((e) => e.risk === '严重' || e.risk === '紧急'))
const earliest = computed(() => (pending.value.length ? (pending.value[pending.value.length - 1]?.time ?? '—') : '—'))
</script>

<template>
  <div class="dashboard-card ops-panel" :data-offline="link === 'disconnected'">
    <div class="ops-card-head">
      <div><span>LOCAL EVENT QUEUE</span><h3>待补传队列</h3></div>
      <small>断网期间边缘本地缓存</small>
    </div>
    <div class="queue-stats">
      <div><b>{{ pending.length }}</b><span>待补传</span></div>
      <div><b :class="{ 'text-danger': highRisk.length }">{{ highRisk.length }}</b><span>高风险</span></div>
      <div><b>{{ earliest }}</b><span>最早事件</span></div>
    </div>
    <div class="queue-list">
      <p v-if="!queue.length" class="queue-empty">中心链路正常，无待补传事件</p>
      <div v-for="e in queue" :key="e.id" class="queue-row" :data-status="e.status">
        <b class="mono">{{ e.id }}</b>
        <span class="queue-row__type">{{ e.type }}</span>
        <span class="queue-row__node">{{ e.node }}</span>
        <span class="mono queue-row__time">{{ e.time }}</span>
        <em>{{ e.status }}</em>
      </div>
    </div>
  </div>
</template>
