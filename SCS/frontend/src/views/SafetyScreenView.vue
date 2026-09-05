<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import BigScreenHeader from '@/components/screen/BigScreenHeader.vue'
import BigScreenMetrics from '@/components/screen/BigScreenMetrics.vue'
import BigScreenMap from '@/components/screen/BigScreenMap.vue'
import BigScreenAlertFeed from '@/components/screen/BigScreenAlertFeed.vue'
import BigScreenTrend from '@/components/screen/BigScreenTrend.vue'
import BigScreenDeviceHealth from '@/components/screen/BigScreenDeviceHealth.vue'
import CriticalAlertPopup from '@/components/screen/CriticalAlertPopup.vue'
import { useIncidentStore } from '@/stores/incident'
import { useLiveStore } from '@/stores/live'
import type { MobileIncident } from '@/types/incident'

const store = useIncidentStore()
const liveStore = useLiveStore()
const incidents = computed(() => store.incidents)
const overview = computed(() => store.overview)

const scale = ref(1)
function fit(): void {
  scale.value = Math.min(window.innerWidth / 1920, window.innerHeight / 1080)
}

let unsubscribeLive: (() => void) | undefined
let unsubscribeReconnect: (() => void) | undefined
onMounted(() => {
  fit()
  window.addEventListener('resize', fit)
  void store.loadAll()
  // 告警变更（含移动端处置、管理端操作）经 WS 自动刷新大屏
  unsubscribeLive = liveStore.onAlertEvent(() => store.scheduleRefresh())
  unsubscribeReconnect = liveStore.onReconnected(() => { void store.loadAll() })
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', fit)
  unsubscribeLive?.()
  unsubscribeReconnect?.()
})

async function toggleDemo(): Promise<void> {
  if (store.breachActive) {
    store.acknowledgeCritical()
    return
  }
  const found = await store.spotlightCritical()
  if (!found) ElMessage.info('当前没有紧急未关闭事件，可在移动端将事件升级为紧急后查看弹窗')
}

const locateId = ref('')
function locate(incident: MobileIncident): void {
  // 人员类紧急事件定位到赵磊（地图基础坐标仍为 Demo）
  locateId.value = incident.source === '人员安全' ? 'P-ZHAO' : ''
}
</script>

<template>
  <div class="screen-viewport">
    <div class="screen-stage" :style="{ transform: `scale(${scale})` }">
      <BigScreenHeader :breach-active="store.breachActive" @toggle-demo="toggleDemo" />
      <main class="bs-main">
        <div class="bs-col bs-col--left">
          <BigScreenMetrics :incidents="incidents" :overview="overview" />
          <BigScreenDeviceHealth :overview="overview" />
        </div>
        <div class="bs-col bs-col--center">
          <BigScreenMap :incidents="incidents" :breach-active="store.breachActive" :locate-id="locateId" />
          <BigScreenTrend :breach-active="store.breachActive" />
        </div>
        <div class="bs-col bs-col--right">
          <BigScreenAlertFeed :incidents="incidents" />
        </div>
      </main>
      <CriticalAlertPopup
        :incident="store.criticalIncident"
        @acknowledge="store.acknowledgeCritical()"
        @locate="locate"
      />
    </div>
  </div>
</template>
