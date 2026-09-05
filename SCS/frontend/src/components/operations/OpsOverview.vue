<script setup lang="ts">
export interface OpsKpi {
  key: string
  label: string
  value: string
  hint: string
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'muted'
  icon: 'platform' | 'edge' | 'camera' | 'radar' | 'station' | 'api' | 'queue'
}

defineProps<{ items: OpsKpi[] }>()
</script>

<template>
  <div class="ops-kpi">
    <div v-for="k in items" :key="k.key" class="ops-kpi__card" :data-tone="k.tone">
      <div class="ops-kpi__icon">
        <span v-if="k.icon === 'platform'">☁</span>
        <span v-else-if="k.icon === 'edge'">▦</span>
        <span v-else-if="k.icon === 'camera'">◉</span>
        <span v-else-if="k.icon === 'radar'">◈</span>
        <span v-else-if="k.icon === 'station'">⌖</span>
        <span v-else-if="k.icon === 'api'">⇄</span>
        <span v-else>≔</span>
      </div>
      <div class="ops-kpi__body">
        <b>{{ k.value }}</b>
        <small>{{ k.label }}</small>
        <em>{{ k.hint }}</em>
      </div>
    </div>
  </div>
</template>
