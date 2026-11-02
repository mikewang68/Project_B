<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Van, UserFilled } from '@element-plus/icons-vue'
import { zonesApi } from '@/api/zones'
import { useLiveStore } from '@/stores/live'
import type { ZoneState } from '@/types/zone'

/**
 * 装卸区状态卡片：A 区限位开关判人员作业，B 区位置传感器判车辆进出。
 *
 * 未收到上报时后端返回 online=false、字段为 null，这里显示「未接入」——
 * 不能当成「无人 / 无车」，两者语义完全不同。
 */
const zones = ref<ZoneState[]>([])
const liveStore = useLiveStore()
let disposeLive: (() => void) | undefined
let disposeReconnect: (() => void) | undefined
let timer: number | undefined

async function load(): Promise<void> {
  try {
    zones.value = await zonesApi.list()
  } catch {
    // 首页其它卡片各自处理错误，这里静默保留上次数据，避免整页被一张卡片拖红
  }
}

/** 事件可能密集到达，做 300ms 合并，避免每次上报都打一次接口 */
function scheduleReload(): void {
  if (timer !== undefined) return
  timer = window.setTimeout(() => { timer = undefined; void load() }, 300)
}

onMounted(() => {
  void load()
  disposeLive = liveStore.onAlertEvent((event) => {
    if (event.type.startsWith('zone.')) scheduleReload()
  })
  disposeReconnect = liveStore.onReconnected(() => void load())
})

onUnmounted(() => {
  disposeLive?.()
  disposeReconnect?.()
  if (timer !== undefined) window.clearTimeout(timer)
})

interface Row {
  key: string
  title: string
  sensor: string
  icon: unknown
  label: string
  detail: string
  tone: 'on' | 'off' | 'unknown'
}

const rows = computed<Row[]>(() => zones.value.map((z) => {
  const isA = z.code === 'LOADING-A'
  const icon = isA ? UserFilled : Van
  if (!z.online) {
    return { key: z.code, title: z.name, sensor: z.sensorType, icon,
      label: '未接入', detail: '尚未收到设备上报', tone: 'unknown' as const }
  }
  if (isA) {
    const working = z.personnelWorking === true
    return { key: z.code, title: z.name, sensor: z.sensorType, icon,
      label: working ? '作业中' : '空闲',
      detail: z.signal,
      tone: working ? 'on' as const : 'off' as const }
  }
  const present = z.vehiclePresent === true
  const distance = z.distanceMm === null ? '' : ` · ${(z.distanceMm / 1000).toFixed(1)}m`
  return { key: z.code, title: z.name, sensor: z.sensorType, icon,
    label: z.vehiclePresent === null ? '状态未知' : present ? '有车' : '无车',
    detail: `${z.signal}${distance}`,
    tone: z.vehiclePresent === null ? 'unknown' as const : present ? 'on' as const : 'off' as const }
}))
</script>

<template>
  <article class="dashboard-card loading-area-card">
    <header class="dashboard-card__header">
      <div>
        <span>LOADING AREA</span>
        <h2>装卸区状态</h2>
        <p>A 区限位开关判人员作业 · B 区位置传感器判车辆进出</p>
      </div>
    </header>
    <ul class="loading-area-list">
      <li v-for="row in rows" :key="row.key" :data-tone="row.tone">
        <span class="loading-area-icon"><el-icon><component :is="row.icon" /></el-icon></span>
        <div class="loading-area-main">
          <b>{{ row.title }}</b>
          <small>{{ row.sensor }} · {{ row.detail }}</small>
        </div>
        <em>{{ row.label }}</em>
      </li>
      <li v-if="!rows.length" class="loading-area-empty">暂无区域数据</li>
    </ul>
  </article>
</template>

<style scoped>
.loading-area-list { list-style: none; margin: 0; padding: 0 16px 16px; display: grid; gap: 10px; }
.loading-area-list li { display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 10px;
  background: rgba(148, 163, 184, 0.08); }
.loading-area-icon { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px;
  border-radius: 9px; background: rgba(148, 163, 184, 0.16); font-size: 18px; }
.loading-area-main { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.loading-area-main b { font-size: 14px; }
.loading-area-main small { font-size: 12px; opacity: 0.72; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.loading-area-list em { font-style: normal; font-size: 13px; font-weight: 600; padding: 3px 10px; border-radius: 999px; }
.loading-area-list li[data-tone='on'] em { color: #0f9d58; background: rgba(15, 157, 88, 0.12); }
.loading-area-list li[data-tone='off'] em { color: #64748b; background: rgba(100, 116, 139, 0.12); }
.loading-area-list li[data-tone='unknown'] em { color: #b45309; background: rgba(180, 83, 9, 0.12); }
.loading-area-empty { justify-content: center; opacity: 0.6; font-size: 13px; }
</style>
