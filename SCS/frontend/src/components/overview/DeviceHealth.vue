<script setup lang="ts">
import { Camera, Connection, Monitor, Position } from '@element-plus/icons-vue'
import { computed } from 'vue'

interface HealthItem { label: string; online: number; total: number; icon?: unknown }

const props = withDefaults(defineProps<{ items?: HealthItem[]; demo?: boolean }>(), {
  items: () => [
    { label: '定位基站', online: 12, total: 12 },
    { label: '摄像头', online: 22, total: 24 },
    { label: '雷达', online: 8, total: 8 },
    { label: '边缘网关', online: 4, total: 4 },
  ],
  demo: true,
})

const ICONS = [Position, Camera, Monitor, Connection]
const devices = computed(() => props.items.map((item, index) => ({ ...item, icon: ICONS[index % ICONS.length] })))
const rate = computed(() => {
  const online = devices.value.reduce((s, d) => s + d.online, 0)
  const total = devices.value.reduce((s, d) => s + d.total, 0)
  return total ? ((online / total) * 100).toFixed(1) : '0.0'
})
</script>

<template>
  <article class="dashboard-card device-health-card">
    <header class="dashboard-card__header">
      <div><span>DEVICE HEALTH</span><h2>设备健康</h2>
        <p>{{ demo ? '设备台账为 Demo 数据' : '关键感知设备在线状态' }}</p>
      </div>
      <span class="health-rate">{{ rate }}%</span>
    </header>
    <div class="device-health-list">
      <div v-for="device in devices" :key="device.label" class="device-health-row">
        <span><el-icon><component :is="device.icon" /></el-icon></span>
        <div><b>{{ device.label }}</b><small>{{ device.online === device.total ? '全部在线' : `${device.total - device.online} 台离线` }}</small></div>
        <strong :data-warning="device.online < device.total">{{ device.online }}<em>/ {{ device.total }}</em></strong>
      </div>
    </div>
  </article>
</template>
