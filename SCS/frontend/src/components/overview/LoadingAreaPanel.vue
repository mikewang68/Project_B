<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Flag, Van, UserFilled } from '@element-plus/icons-vue'
import { zonesApi } from '@/api/zones'
import { useLiveStore } from '@/stores/live'
import type { ZonePresence, ZoneState } from '@/types/zone'

/**
 * 装卸区状态卡片：
 * ① 实时在场——固定作业区按人员实时坐标归区计数（UWB 1Hz），已生效临时围栏按多边形判定（含隔离带）；
 * ② 设备上报——A 区限位开关判人员作业，B 区位置传感器判车辆进出。
 *
 * 未收到设备上报时后端返回 online=false、字段为 null，这里显示「未接入」——
 * 不能当成「无人 / 无车」，两者语义完全不同。
 */
const zones = ref<ZoneState[]>([])
const presence = ref<ZonePresence[]>([])
const liveStore = useLiveStore()
let disposeLive: (() => void) | undefined
let disposeReconnect: (() => void) | undefined
let timer: number | undefined

async function load(): Promise<void> {
  try {
    const data = await apiRequestBoth()
    zones.value = data.zones
    presence.value = data.presence
  } catch {
    // 首页其它卡片各自处理错误，这里静默保留上次数据，避免整页被一张卡片拖红
  }
}

async function apiRequestBoth(): Promise<{ zones: ZoneState[]; presence: ZonePresence[] }> {
  const [zones, presence] = await Promise.all([zonesApi.list(), zonesApi.presence()])
  return { zones, presence }
}

/** 事件可能密集到达，做 300ms 合并，避免每次上报都打一次接口 */
function scheduleReload(): void {
  if (timer !== undefined) return
  timer = window.setTimeout(() => { timer = undefined; void load() }, 300)
}

onMounted(() => {
  void load()
  // person.moved（1Hz 人员位置）/ zone.changed（设备上报）/ fence.changed（围栏生命周期）都触发合并刷新
  disposeLive = liveStore.onAlertEvent((event) => {
    if (event.type.startsWith('zone.') || event.type.startsWith('person.') || event.type.startsWith('fence.')) {
      scheduleReload()
    }
  })
  disposeReconnect = liveStore.onReconnected(() => void load())
})

onUnmounted(() => {
  disposeLive?.()
  disposeReconnect?.()
  if (timer !== undefined) window.clearTimeout(timer)
})

const fixedAreas = computed<ZonePresence[]>(() => presence.value.filter((p) => p.kind === 'fixed'))
const tempFences = computed<ZonePresence[]>(() => presence.value.filter((p) => p.kind === 'fence'))
const empty = computed(() => !fixedAreas.value.length && !tempFences.value.length && !rows.value.length)

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
      label: z.personnelWorking === null ? '状态未知' : working ? '作业中' : '空闲',
      detail: z.signal,
      tone: z.personnelWorking === null ? 'unknown' as const : working ? 'on' as const : 'off' as const }
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
        <p>实时在场：固定区域按人员坐标归区 · 已生效临时围栏按多边形判定</p>
      </div>
    </header>
    <ul class="loading-area-list">
      <li v-for="area in fixedAreas" :key="area.code" :data-tone="area.personCount > 0 ? 'on' : 'off'">
        <span class="loading-area-icon"><el-icon><UserFilled /></el-icon></span>
        <div class="loading-area-main">
          <b>{{ area.name }}</b>
          <small>{{ area.personCount > 0 ? '人员在场' : '当前无人' }}</small>
        </div>
        <em>{{ area.personCount > 0 ? `${area.personCount} 人` : '无人' }}</em>
      </li>
      <li v-if="empty" class="loading-area-empty">暂无区域数据</li>
    </ul>
    <div v-if="tempFences.length" class="loading-area-fence-section">
      <div class="loading-area-fence-title">
        <el-icon><Flag /></el-icon>
        <span>临时围栏 · 已生效</span>
      </div>
      <ul class="loading-area-list loading-area-list--fence">
        <li v-for="fence in tempFences" :key="fence.code" data-tone="on">
          <span class="loading-area-icon"><el-icon><Flag /></el-icon></span>
          <div class="loading-area-main">
            <b>{{ fence.name }}</b>
            <small>
              {{ fence.bufferCount > 0 ? `隔离带 ${fence.bufferCount} 人` : '核心区内' }}
            </small>
          </div>
          <em>{{ fence.personCount > 0 ? `${fence.personCount} 人` : '无人' }}</em>
        </li>
      </ul>
    </div>
    <div v-if="rows.length" class="loading-area-device-section">
      <div class="loading-area-device-title">设备上报</div>
      <ul class="loading-area-list loading-area-list--device">
        <li v-for="row in rows" :key="row.key" :data-tone="row.tone">
          <div class="loading-area-main">
            <b>{{ row.title }}</b>
            <small>{{ row.sensor }} · {{ row.detail }}</small>
          </div>
          <em>{{ row.label }}</em>
        </li>
      </ul>
    </div>
  </article>
</template>

<style scoped>
.loading-area-list { list-style: none; margin: 0; padding: 0 16px 10px; display: grid; gap: 8px; }
.loading-area-list li { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 10px;
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
.loading-area-fence-section { padding: 4px 16px 0; }
.loading-area-fence-title,
.loading-area-device-title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
  color: rgba(148, 163, 184, 0.9); padding: 6px 0 2px; letter-spacing: 0.04em; }
.loading-area-device-section { padding: 2px 0 6px; }
</style>
