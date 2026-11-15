<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import SafetyMapCanvas from '@/components/shared/SafetyMapCanvas.vue'
import type { EquipmentPoint, PersonnelPoint, OverviewMapDto } from '@/types/overview'
import type { FenceRecord } from '@/types/fence'
import type { MobileIncident } from '@/types/incident'
import { overviewApi } from '@/api/overview'
import { mapFences } from '@/adapters/overview'
import { isLiveEvent, sharedLiveSocket } from '@/api/live'

const props = defineProps<{ incidents: MobileIncident[]; breachActive: boolean; locateId?: string }>()

// 基础坐标来自后端 /overview/map（Personnel/Fence/Collision Repository 投影，风险由未关闭 Alert 覆盖）
const mapData = ref<OverviewMapDto>({ baseDemo: true, liveOverlay: true, people: [], equipment: [], fences: [] })

async function loadMap(): Promise<void> {
  try {
    mapData.value = await overviewApi.getMap()
  } catch {
    // 保留上一次数据，避免 WS/网络抖动导致大屏地图空白
  }
}

// 大屏语义=异常聚焦：正常在岗人员不逐个渲染（聚合到图例计数），只保留
// 关注/离线/高风险与被告警定位目标；SIM-* 测试人员/设备不上大屏。
const NON_TEST = (id: string) => !id.startsWith('SIM-')
const people = computed<PersonnelPoint[]>(() => {
  const all = mapData.value.people ?? []
  return all.filter(
    (p) => NON_TEST(p.id) && (p.state !== 'normal' || p.id === props.locateId),
  )
})
const onDutyTotal = computed(
  () => (mapData.value.people ?? []).filter((p) => NON_TEST(p.id)).length,
)
const equipment = computed<EquipmentPoint[]>(
  () => (mapData.value.equipment ?? []).filter((e) => NON_TEST(e.id)),
)
const fences = computed<FenceRecord[]>(() => mapFences(mapData.value))
const activeRisk = computed(() => props.incidents.filter((i) => i.status !== '已关闭' && (i.risk === '严重' || i.risk === '紧急')).length)

let dispose: (() => void) | undefined
let reloadTimer: ReturnType<typeof setTimeout> | undefined
onMounted(() => {
  void loadMap()
  sharedLiveSocket.connect()
  dispose = sharedLiveSocket.onMessage((raw) => {
    // 人员移动 / 设备变化 / 围栏变更 / 告警变化后，刷新地图投影（轻量防抖）
    if (!isLiveEvent(raw)) return
    if (reloadTimer) clearTimeout(reloadTimer)
    reloadTimer = setTimeout(loadMap, 260)
  })
})
onBeforeUnmount(() => { dispose?.(); if (reloadTimer) clearTimeout(reloadTimer) })
watch(() => props.breachActive, loadMap)
</script>

<template>
  <section class="bs-panel bs-map">
    <div class="bs-panel__head">
      <div><span>SITE SITUATION</span><h2>站场安全态势</h2></div>
      <div class="bs-map__legend">
        <span><i data-tone="normal"></i>在岗 {{ onDutyTotal }} 人 · 异常已聚焦</span>
        <span><i data-tone="warning"></i>关注</span>
        <span><i data-tone="danger"></i>高风险（脉冲提示）</span>
        <b>活动风险点 {{ activeRisk }}</b>
      </div>
    </div>
    <div class="bs-map__canvas">
      <SafetyMapCanvas :people="people" :equipment="equipment" :fences="fences" :selected-person-id="props.locateId" />
    </div>
  </section>
</template>
