<script setup lang="ts">
import type { OpsEvent } from '@/types/operations'

defineProps<{ events: OpsEvent[] }>()
const levelText: Record<string, string> = {
  warning: '警告',
  degraded: '降级',
  fault: '故障',
  offline: '离线',
  info: '信息',
}
</script>

<template>
  <div class="dashboard-card ops-panel ops-feed-panel">
    <div class="ops-card-head">
      <div><span>NOTIFICATIONS</span><h3>最近运维异常</h3></div>
      <small>Notification Feed</small>
    </div>
    <ul class="ops-feed">
      <li v-for="e in events" :key="e.id" :data-level="e.level">
        <i></i>
        <div class="ops-feed__body">
          <div class="ops-feed__top">
            <b>{{ e.target }}</b>
            <em>{{ levelText[e.level] }}</em>
            <span class="mono">{{ e.time }}</span>
          </div>
          <p>{{ e.text }}</p>
        </div>
      </li>
    </ul>
  </div>
</template>
