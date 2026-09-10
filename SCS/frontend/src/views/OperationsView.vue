<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import OpsOverview, { type OpsKpi } from '@/components/operations/OpsOverview.vue'
import OpsToolbar from '@/components/operations/OpsToolbar.vue'
import OfflineBanner from '@/components/operations/OfflineBanner.vue'
import SystemTopology from '@/components/operations/SystemTopology.vue'
import EdgeNodeCard from '@/components/operations/EdgeNodeCard.vue'
import EdgeNodeDrawer from '@/components/operations/EdgeNodeDrawer.vue'
import DeviceHealthPanel from '@/components/operations/DeviceHealthPanel.vue'
import InterfaceMonitor from '@/components/operations/InterfaceMonitor.vue'
import OpsEventFeed from '@/components/operations/OpsEventFeed.vue'
import CacheStatus from '@/components/operations/CacheStatus.vue'
import LocalEventQueue from '@/components/operations/LocalEventQueue.vue'
import RecoveryDialog from '@/components/operations/RecoveryDialog.vue'
import DeviceDiagnosticDialog from '@/components/operations/DeviceDiagnosticDialog.vue'
import { useOperationsStore } from '@/stores/operations'
import { operationsApi } from '@/api/operations'
import { sharedLiveSocket, isLiveEvent, type LiveEvent } from '@/api/live'
import {
  toCloudLink,
  toDeviceCategories,
  toEdgeNode,
  toLocalEvents,
  toOpsEvents,
  toOpsInterfaces,
  onlineCount,
} from '@/adapters/operations'
import type { EdgeNode, LocalEvent, OpsDevice, OpsEvent } from '@/types/operations'
import type { DeviceCategory } from '@/types/operations'

const DEMO_NODE = 'EDGE-03'
const LOCAL_EVENT_TYPES = ['person-intrusion', 'collision-risk', 'ppe-violation', 'person-stay']

const opsStore = useOperationsStore()
const link = computed(() => opsStore.linkState)

const nodes = ref<EdgeNode[]>([])
const categories = ref<DeviceCategory[]>([])
const interfaces = ref<ReturnType<typeof toOpsInterfaces>>([])
const opsEvents = ref<OpsEvent[]>([])
const queue = ref<LocalEvent[]>([])
const loading = ref(false)

const drawerNode = ref<EdgeNode | null>(null)
const drawerOpen = ref(false)
const recoveryOpen = ref(false)
const diagOpen = ref(false)
const diagDevice = ref<OpsDevice | null>(null)
let riskPoolIdx = 0

function errorTip(error: unknown, fallback: string): void {
  ElMessage.error(error instanceof Error ? error.message : fallback)
}

/* ---------- 数据加载（REST 为权威来源，WS 只触发对应模块局部刷新） ---------- */
async function refreshNodes(): Promise<void> {
  const { list } = await operationsApi.edgeNodes()
  nodes.value = list.map(toEdgeNode)
}
async function refreshDevices(): Promise<void> {
  const { list } = await operationsApi.devices()
  categories.value = toDeviceCategories(list)
}
async function refreshInterfaces(): Promise<void> {
  const { list } = await operationsApi.interfaces()
  interfaces.value = toOpsInterfaces(list)
}
async function refreshEvents(): Promise<void> {
  const { list } = await operationsApi.events({ limit: 14 })
  opsEvents.value = toOpsEvents(list)
}
async function refreshQueue(): Promise<void> {
  const { list } = await operationsApi.localEvents()
  queue.value = toLocalEvents(list)
}
async function refreshLink(): Promise<void> {
  const l = await operationsApi.link()
  opsStore.setLink(toCloudLink(l.state))
}
async function refreshAll(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([
      refreshNodes(),
      refreshDevices(),
      refreshInterfaces(),
      refreshEvents(),
      refreshQueue(),
      refreshLink(),
    ])
  } finally {
    loading.value = false
  }
}

/* ---------- KPI（全部来自 Backend 实时数据） ---------- */
const cameraCat = computed(() => categories.value.find((c) => c.kind === '摄像头')!)
const radarCat = computed(() => categories.value.find((c) => c.kind === '雷达')!)
const stationCat = computed(() => categories.value.find((c) => c.kind === '定位基站')!)
const pendingCount = computed(() => queue.value.filter((e) => e.status === '待补传' || e.status === '补传中').length)
const avgSuccess = computed(() => {
  const cloud = interfaces.value.filter((i) => i.cloudSide)
  if (!cloud.length) return '0.00'
  return (cloud.reduce((s, i) => s + i.successRate, 0) / cloud.length).toFixed(2)
})

const kpis = computed<OpsKpi[]>(() => {
  const disconnected = link.value === 'disconnected'
  const linkErr = link.value === 'link-error' || link.value === 'recovering'
  const onlineNodes = nodes.value.filter((n) => n.online).length
  return [
    {
      key: 'platform', label: '平台服务',
      value: disconnected ? '连接中断' : linkErr ? '链路异常' : '正常',
      hint: disconnected ? '中心平台不可达，边缘自治继续' : '事件 / 规则 / 数据服务',
      tone: disconnected ? 'danger' : linkErr ? 'warning' : 'success', icon: 'platform',
    },
    {
      key: 'edge', label: '边缘节点',
      value: `${onlineNodes} / ${nodes.value.length || 4} ${disconnected ? '自治' : '在线'}`,
      hint: disconnected ? '本地自治运行中（SIMULATED）' : 'EDGE-01 ~ EDGE-04',
      tone: disconnected ? 'warning' : 'success', icon: 'edge',
    },
    {
      key: 'camera', label: '摄像头',
      value: cameraCat.value ? `${onlineCount(cameraCat.value.devices)} / ${cameraCat.value.total} 在线` : '—',
      hint: '现场摄像头台账', tone: cameraCat.value && onlineCount(cameraCat.value.devices) < cameraCat.value.total ? 'warning' : 'success', icon: 'camera',
    },
    {
      key: 'radar', label: '雷达',
      value: radarCat.value ? `${onlineCount(radarCat.value.devices)} / ${radarCat.value.total} 在线` : '—',
      hint: '毫米波雷达台账', tone: 'success', icon: 'radar',
    },
    {
      key: 'station', label: '定位基站',
      value: stationCat.value ? `${onlineCount(stationCat.value.devices)} / ${stationCat.value.total} 在线` : '—',
      hint: 'UWB 定位基站台账', tone: 'success', icon: 'station',
    },
    {
      key: 'api', label: '接口成功率',
      value: disconnected ? '中心侧不可达' : `${avgSuccess.value}%`,
      hint: '近 1 小时滑动统计', tone: disconnected ? 'danger' : 'primary', icon: 'api',
    },
    {
      key: 'queue', label: '待补传事件',
      value: String(pendingCount.value),
      hint: pendingCount.value ? '链路恢复后自动幂等补传' : '队列已清空',
      tone: pendingCount.value ? 'warning' : 'muted', icon: 'queue',
    },
  ]
})

/* ---------- 模拟云边断网（调用 Backend，非前端本地改状态） ---------- */
async function simulateDisconnect(): Promise<void> {
  try {
    await operationsApi.simulateLink('disconnect', DEMO_NODE)
    ElMessage.warning('EDGE-03 云连接中断，边缘自治激活：本地判定与联动继续')
    await Promise.all([refreshNodes(), refreshQueue(), refreshEvents(), refreshLink()])
  } catch (e) {
    errorTip(e, '模拟断网失败')
  }
}

/* ---------- 断网期间本地风险（进入离线队列，不产生云端 Alert） ---------- */
async function simulateLocalRisk(): Promise<void> {
  if (link.value !== 'disconnected') {
    ElMessage.info('请先模拟 EDGE-03 断网，再演示离线本地风险')
    return
  }
  const eventType = LOCAL_EVENT_TYPES[riskPoolIdx % LOCAL_EVENT_TYPES.length]!
  riskPoolIdx += 1
  try {
    const evt = await operationsApi.createLocalEvent({ nodeId: DEMO_NODE, eventType })
    ElMessage({
      type: evt.risk === '紧急' || evt.risk === '严重' ? 'warning' : 'success',
      message: `${DEMO_NODE} 本地完成判定与联动，事件 ${evt.eventId} 进入离线缓存`,
      duration: 2600,
    })
    await Promise.all([refreshNodes(), refreshQueue(), refreshEvents()])
  } catch (e) {
    errorTip(e, '本地风险模拟失败')
  }
}

/* ---------- 时间偏差（EDGE-02 +3200ms，恢复时需时间对账） ---------- */
async function simulateTimeDrift(): Promise<void> {
  try {
    await operationsApi.simulate('timeDrift', 'EDGE-02')
    ElMessage.warning('EDGE-02 出现 +3.2s 时间偏差，节点降级，恢复 / 维护时需重新校时')
    await Promise.all([refreshNodes(), refreshEvents()])
  } catch (e) {
    errorTip(e, '时间偏差模拟失败')
  }
}

async function simulateCacheAlert(): Promise<void> {
  try {
    await operationsApi.simulate('cacheAlert', DEMO_NODE)
    ElMessage.warning('EDGE-03 缓存占用升高，将优先保留高等级事件与控制回执')
    await Promise.all([refreshNodes(), refreshEvents()])
  } catch (e) {
    errorTip(e, '缓存告警模拟失败')
  }
}

async function simulateDeviceFault(): Promise<void> {
  try {
    await operationsApi.simulate('deviceFault', 'CAM-12')
    ElMessage.error('CAM-12 设备故障，已打开设备诊断')
    await refreshDevices()
    const cam = categories.value.find((c) => c.kind === '摄像头')?.devices.find((d) => d.id === 'CAM-12') ?? null
    diagDevice.value = cam
    diagOpen.value = true
  } catch (e) {
    errorTip(e, '设备故障模拟失败')
  }
}

async function reconnectDevice(d: OpsDevice): Promise<void> {
  try {
    await operationsApi.reconnectDevice(d.id)
    ElMessage.success(`${d.id} 已重新连接，恢复正常`)
    await refreshDevices()
  } catch (e) {
    errorTip(e, '设备重连失败')
  }
}

/* ---------- 节点 Drawer 操作 ---------- */
function openNode(n: EdgeNode): void {
  drawerNode.value = n
  drawerOpen.value = true
}
async function reconnectNode(n: EdgeNode): Promise<void> {
  // 节点发起恢复：打开后端 phase 驱动的恢复对话框
  drawerOpen.value = false
  recoveryOpen.value = true
  ElMessage.info(`${n.id} 开始恢复云边链路`)
}
async function resyncNodeTime(n: EdgeNode): Promise<void> {
  try {
    await operationsApi.maintain(n.id, 'resyncTime')
    ElMessage.success(`${n.id} 时间同步完成，偏差回到正常范围`)
    await Promise.all([refreshNodes(), refreshEvents()])
  } catch (e) {
    errorTip(e, '时间同步失败')
  }
}
async function redeliverNode(n: EdgeNode): Promise<void> {
  try {
    const node = await operationsApi.maintain(n.id, 'redeliverRule')
    ElMessage.success(`${n.id} 规则已重新下发至 ${node.expectedRuleVersion}`)
    await Promise.all([refreshNodes(), refreshEvents()])
  } catch (e) {
    errorTip(e, '规则重新下发失败')
  }
}

/* ---------- 恢复流程（RecoveryDialog 后端 phase 驱动） ---------- */
function startRecover(): void {
  recoveryOpen.value = true
  opsStore.setLink('recovering')
}
async function onRecoveryChanged(): Promise<void> {
  // 恢复每推进一步：局部刷新节点 / 队列 / 日志，不整页 reload
  await Promise.all([refreshNodes(), refreshQueue(), refreshEvents()])
}
async function onRecoveryFinished(): Promise<void> {
  await Promise.all([refreshNodes(), refreshQueue(), refreshEvents(), refreshLink(), refreshDevices(), refreshInterfaces()])
  ElMessage.success('云边链路已恢复：时间 / 规则对账完成，离线事件已幂等补传并同步至各端')
}

/* ---------- ops.* WebSocket：只刷新相应模块，不整页 reload ---------- */
function onLiveMessage(raw: Record<string, unknown>): void {
  if (!isLiveEvent(raw)) return
  const evt = raw as LiveEvent
  if (!evt.type.startsWith('ops.')) return
  switch (evt.type) {
    case 'ops.node.changed':
      void refreshNodes()
      void refreshLink()
      break
    case 'ops.sync.changed':
      void refreshNodes()
      break
    case 'ops.queue.changed':
      void refreshNodes()
      void refreshQueue()
      break
    case 'ops.recovery.changed':
      void refreshNodes()
      void refreshQueue()
      break
    default:
      break
  }
}

let disposeLive: (() => void) | null = null
onMounted(() => {
  void refreshAll()
  disposeLive = sharedLiveSocket.onMessage(onLiveMessage)
  sharedLiveSocket.connect()
})
onBeforeUnmount(() => {
  disposeLive?.()
})
</script>

<template>
  <div class="operations-page" v-loading="loading">
    <OpsToolbar
      :link="link"
      :recovering="link === 'recovering'"
      @disconnect="simulateDisconnect"
      @recover="startRecover"
      @local-risk="simulateLocalRisk"
      @time-drift="simulateTimeDrift"
      @cache-alert="simulateCacheAlert"
      @device-fault="simulateDeviceFault"
    />

    <OfflineBanner v-if="link === 'disconnected' || link === 'link-error'" />

    <OpsOverview :items="kpis" />

    <SystemTopology :link="link" :nodes="nodes" :categories="categories" />

    <div class="ops-layout">
      <div class="ops-layout__main">
        <div class="dashboard-card ops-panel">
          <div class="ops-card-head">
            <div><span>EDGE NODES · SIMULATED EDGE AUTONOMY</span><h3>边缘节点状态</h3></div>
            <small>点击节点查看详情与运维操作</small>
          </div>
          <div class="edge-grid">
            <EdgeNodeCard v-for="n in nodes" :key="n.id" :node="n" :link="link" @detail="openNode" />
          </div>
        </div>

        <div class="ops-layout__row">
          <DeviceHealthPanel :categories="categories" @reconnect="reconnectDevice" />
          <InterfaceMonitor :list="interfaces" :link="link" />
        </div>
      </div>

      <aside class="ops-layout__side">
        <CacheStatus :nodes="nodes" @detail="openNode" />
        <LocalEventQueue :queue="queue" :link="link" />
      </aside>
    </div>

    <OpsEventFeed :events="opsEvents" />

    <EdgeNodeDrawer
      v-model="drawerOpen"
      :node="drawerNode"
      @reconnect="reconnectNode"
      @resync-time="resyncNodeTime"
      @redeliver="redeliverNode"
    />
    <RecoveryDialog
      v-model="recoveryOpen"
      node-id="EDGE-03"
      @changed="onRecoveryChanged"
      @finished="onRecoveryFinished"
    />
    <DeviceDiagnosticDialog v-model="diagOpen" :device="diagDevice" @reconnect="reconnectDevice" />
  </div>
</template>
