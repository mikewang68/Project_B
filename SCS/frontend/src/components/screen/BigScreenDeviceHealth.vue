<script setup lang="ts">
import { computed } from 'vue'
import { Aim, Connection, Cpu, VideoCamera } from '@element-plus/icons-vue'
import type { ScreenOverview } from '@/api/screen'

const props = defineProps<{ overview?: ScreenOverview | null }>()

const iconMap: Record<string, unknown> = {
  定位基站: Cpu,
  摄像头: VideoCamera,
  雷达: Aim,
  边缘节点: Connection,
}

const devices = computed(() => {
  if (props.overview?.deviceHealth?.length) {
    return props.overview.deviceHealth.map((d) => ({ ...d, icon: iconMap[d.name] ?? Connection }))
  }
  return [
    { name: '定位基站', online: 12, total: 12, icon: Cpu, demo: true },
    { name: '摄像头', online: 22, total: 24, icon: VideoCamera, demo: true },
    { name: '雷达', online: 8, total: 8, icon: Aim, demo: true },
    { name: '边缘节点', online: 4, total: 4, icon: Connection, demo: true },
  ]
})

/** 风险类型分布：来自后端对真实 Alert 的聚合 */
const riskTypes = computed(() => {
  const tones = ['danger', 'amber', 'blue', 'blue', 'muted', 'muted']
  const rows = (props.overview?.riskTypeDistribution ?? []).slice(0, 6).map((t, idx) => ({
    name: t.type,
    value: t.count,
    tone: tones[idx] ?? 'muted',
  }))
  return rows
})
const max = computed(() => Math.max(1, ...riskTypes.value.map((t) => t.value)))
</script>

<template>
  <section class="bs-panel bs-health">
    <div class="bs-panel__head"><div><span>DEVICE HEALTH</span><h2>设备运行状态</h2></div></div>
    <div class="bs-health__grid">
      <div v-for="d in devices" :key="d.name" class="bs-health__item">
        <el-icon :size="16"><component :is="d.icon" /></el-icon>
        <span>{{ d.name }}</span>
        <b :data-warn="d.online < d.total">{{ d.online }} / {{ d.total }}</b>
      </div>
    </div>
    <div class="bs-types">
      <h4>风险类型分布（来自告警聚合）</h4>
      <div v-if="riskTypes.length === 0" class="bs-types__empty">正在加载…</div>
      <div v-for="t in riskTypes" :key="t.name" class="bs-types__row">
        <span>{{ t.name }}</span>
        <div class="bs-types__bar"><i :data-tone="t.tone" :style="{ width: (t.value / max * 100) + '%' }"></i></div>
        <b>{{ t.value }}</b>
      </div>
    </div>
  </section>
</template>
