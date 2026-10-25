<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
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
import {
  onlineCount,
  toCloudLink,
  toDeviceCategories,
  toEdgeNode,
  toLocalEvents,
  toOpsEvents,
  toOpsInterfaces,
} from '@/adapters/operations'
import { createLatestGuard } from '@/composables/useLatestGuard'
import { sharedLiveSocket } from '@/api/live'
import { LOCAL_RISK_POOL, type DeviceCategory, type EdgeNode, type LocalEvent, type OpsDevice, type OpsEvent, type OpsInterface } from '@/types/operations'

const opsStore = useOperationsStore()
const link = computed(() => opsStore.linkState)

// 复查：数据源从 mock/opsData 切换为后端 /ops + /edge（adapters/operations 已有完整映射）
const nodes = ref<EdgeNode[]>([])
const categories = ref<DeviceCategory[]>([])
const interfaces = ref<OpsInterface[]>([])
const opsEvents = ref<OpsEvent[]>([])
const queue = ref<LocalEvent[]>([])

const loadGuard = createLatestGuard()
let reloadTimer: ReturnType<typeof setTimeout> | undefined
let disposeOps: (() => void) | undefined

async function fetchQuietly<T>(run: () => Promise<T>): Promise<T | null> {
  try {
    return await run()
  } catch (cause) {
    console.warn('[operations] 运维数据加载失败', cause)
    return null
  }
}

async function loadAll(): Promise<void> {
  const token = loadGuard.next()
  const [linkRes, nodesRes, devicesRes, interfacesRes, eventsRes, queueRes] = await Promise.all([
    fetchQuietly(() => operationsApi.link()),
    fetchQuietly(() => operationsApi.edgeNodes()),
    fetchQuietly(() => operationsApi.devices()),
    fetchQuietly(() => operationsApi.interfaces()),
    fetchQuietly(() => operationsApi.events({ limit: 14 })),
    fetchQuietly(() => operationsApi.localEvents()),
  ])
  if (!loadGuard.isLatest(token)) return
  if (linkRes) opsStore.setLink(toCloudLink(linkRes.state))
  if (nodesRes) nodes.value = nodesRes.list.map(toEdgeNode)
  if (devicesRes) categories.value = toDeviceCategories(devicesRes.list)
  if (interfacesRes) interfaces.value = toOpsInterfaces(interfacesRes.list)
  if (eventsRes) opsEvents.value = toOpsEvents(eventsRes.list)
  if (queueRes) queue.value = toLocalEvents(queueRes.list)
}

function scheduleReload(): void {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => { void loadAll() }, 300)
}

onMounted(() => {
  void loadAll()
  disposeOps = sharedLiveSocket.onMessage((raw) => {
    const type = typeof raw === 'object' && raw !== null ? String((raw as { type?: unknown }).type ?? '') : ''
    if (type.startsWith('ops.')) scheduleReload()
  })
})

onUnmounted(() => {
  disposeOps?.()
  if (reloadTimer) clearTimeout(reloadTimer)
})

const drawerNode = ref<EdgeNode | null>(null)
const drawerOpen = ref(false)
const recoveryOpen = ref(false)
const recovering = ref(false)
const diagOpen = ref(false)
const diagDevice = ref<OpsDevice | null>(null)
const timeDriftPending = ref(false)

let riskSeq = 0
let riskPoolIdx = 0

/* ---------- KPI ---------- */
const cameraCat = computed(() => categories.value.find((c) => c.kind === '摄像头')!)
const radarCat = computed(() => categories.value.find((c) => c.kind === '雷达')!)
const stationCat = computed(() => categories.value.find((c) => c.kind === '定位基站')!)
const pendingCount = computed(() => queue.value.filter((e) => e.status === '待补传' || e.status === '补传中').length)
const avgSuccess = computed(() => {
  const cloud = interfaces.value.filter((i) => i.cloudSide)
  return (cloud.reduce((s, i) => s + i.successRate, 0) / cloud.length).toFixed(2)
})

const kpis = computed<OpsKpi[]>(() => {
  const disconnected = link.value === 'disconnected'
  const linkErr = link.value === 'link-error' || link.value === 'recovering'
  return [
    {
      key: 'platform', label: '平台服务',
      value: disconnected ? '连接中断' : linkErr ? '链路异常' : '正常',
      hint: disconnected ? '中心平台不可达' : '事件 / 规则 / 数据服务',
      tone: disconnected ? 'danger' : linkErr ? 'warning' : 'success', icon: 'platform',
    },
    {
      key: 'edge', label: '边缘节点',
      value: `${nodes.value.filter((n) => n.online).length} / 4 ${disconnected ? '自治' : '在线'}`,
      hint: disconnected ? '本地自治运行中' : 'EDGE-01 ~ EDGE-04',
      tone: disconnected ? 'warning' : 'success', icon: 'edge',
    },
    {
      key: 'camera', label: '摄像头',
      value: `${onlineCount(cameraCat.value.devices)} / ${cameraCat.value.total} 在线`,
      hint: '2 台画面质量降级', tone: onlineCount(cameraCat.value.devices) < cameraCat.value.total ? 'warning' : 'success', icon: 'camera',
    },
    {
      key: 'radar', label: '雷达',
      value: `${onlineCount(radarCat.value.devices)} / ${radarCat.value.total} 在线`,
      hint: '毫米波雷达全部正常', tone: 'success', icon: 'radar',
    },
    {
      key: 'station', label: '定位基站',
      value: `${onlineCount(stationCat.value.devices)} / ${stationCat.value.total} 在线`,
      hint: 'UWB 基站全部正常', tone: 'success', icon: 'station',
    },
    {
      key: 'api', label: '接口成功率',
      value: disconnected ? '中心侧不可达' : `${avgSuccess.value}%`,
      hint: '近 1 小时滑动统计', tone: disconnected ? 'danger' : 'primary', icon: 'api',
    },
    {
      key: 'queue', label: '待补传事件',
      value: String(pendingCount.value),
      hint: pendingCount.value ? '链路恢复后自动补传' : '队列已清空',
      tone: pendingCount.value ? 'warning' : 'muted', icon: 'queue',
    },
  ]
})

/* ---------- 模拟云边断网（后端 simulate-link 驱动，响应后 loadAll） ---------- */
async function simulateDisconnect(): Promise<void> {
  opsStore.setLink('link-error')
  ElMessage.warning('检测到中心链路抖动，正在评估连接状态…')
  await fetchQuietly(() => operationsApi.simulateLink('disconnect'))
  await loadAll()
  ElMessage.warning('中心链路中断，边缘节点进入本地自治模式')
}

/* ---------- 断网期间本地风险 ---------- */
async function simulateLocalRisk(): Promise<void> {
  if (link.value !== 'disconnected') return
  const tpl = LOCAL_RISK_POOL[riskPoolIdx % LOCAL_RISK_POOL.length]!
  riskPoolIdx += 1
  const nodeOrder = ['EDGE-02', 'EDGE-01', 'EDGE-03', 'EDGE-04']
  const nodeId = nodeOrder[riskSeq % nodeOrder.length]!
  riskSeq += 1
  // 复查：本地风险事件由后端 createLocalEvent 驱动（本地判定 + 联动 + 入补传队列）
  await fetchQuietly(() => operationsApi.createLocalEvent({
    nodeId,
    eventType: tpl.type,
    risk: tpl.risk,
    title: tpl.type,
  }))
  await loadAll()
  ElMessage({
    type: tpl.risk === '紧急' || tpl.risk === '严重' ? 'warning' : 'success',
    message: `${nodeId} 本地完成联动，事件已进入补传队列`,
    duration: 2600,
  })
}

/* ---------- 时间偏差 ---------- */
async function simulateTimeDrift(): Promise<void> {
  if (link.value !== 'disconnected') {
    ElMessage.info('请先模拟云边断网，再演示时间偏差')
    return
  }
  await fetchQuietly(() => operationsApi.simulate('timeDrift', 'EDGE-02'))
  await loadAll()
  ElMessage.warning('EDGE-02 出现时间同步偏差，恢复时需重新校时')
}

/* ---------- 缓存告警 ---------- */
async function simulateCacheAlert(): Promise<void> {
  await fetchQuietly(() => operationsApi.simulate('cacheAlert', 'EDGE-03'))
  await loadAll()
  ElMessage.warning('边缘缓存空间不足，将优先保留高等级事件与控制回执')
}

/* ---------- 设备异常 ---------- */
async function simulateDeviceFault(): Promise<void> {
  await fetchQuietly(() => operationsApi.simulate('deviceFault', 'CAM-12'))
  await loadAll()
  const cam = categories.value.find((c) => c.kind === '摄像头')?.devices.find((d) => d.id === 'CAM-12')
  if (cam) {
    diagDevice.value = cam
    diagOpen.value = true
  }
  ElMessage.error('CAM-12 网络中断，已打开设备诊断')
}
async function reconnectDevice(d: OpsDevice): Promise<void> {
  await fetchQuietly(() => operationsApi.reconnectDevice(d.id))
  await loadAll()
  ElMessage.success(`${d.id} 已重新连接，恢复正常`)
}

/* ---------- 节点 Drawer 操作 ---------- */
function openNode(n: EdgeNode): void {
  drawerNode.value = n
  drawerOpen.value = true
}
async function reconnectNode(n: EdgeNode): Promise<void> {
  await fetchQuietly(() => operationsApi.maintain(n.id, 'reconnect'))
  await loadAll()
  ElMessage.success(`${n.id} 连接已恢复`)
}
async function resyncNodeTime(n: EdgeNode): Promise<void> {
  await fetchQuietly(() => operationsApi.maintain(n.id, 'resyncTime'))
  await loadAll()
  if (n.id === 'EDGE-02') timeDriftPending.value = false
  ElMessage.success(`${n.id} 时间同步完成`)
}
async function redeliverNode(n: EdgeNode): Promise<void> {
  await fetchQuietly(() => operationsApi.maintain(n.id, 'redeliverRule'))
  await loadAll()
  ElMessage.success(`${n.id} 规则已重新下发`)
}

/* ---------- 恢复流程 ---------- */
async function startRecover(): Promise<void> {
  recovering.value = true
  opsStore.setLink('recovering')
  // 复查：恢复由后端 /edge/recover 驱动（补传状态机 + 对账），前端弹窗做进度可视化
  await fetchQuietly(() => operationsApi.recover())
  await loadAll()
  recoveryOpen.value = true
  ElMessage.info('开始恢复云边连接，自动执行补传与对账')
}
function onUploadProgress(doneCount: number): void {
  // 恢复动画期间仅做视觉推进；队列权威状态以后端 localEvents 为准（finished 后 loadAll）
  const total = queue.value.length
  const remain = total - doneCount
  queue.value.forEach((e, idx) => {
    if (idx >= remain) e.status = idx >= total - 2 ? '重复' : '已补传'
  })
}
async function onRuleRedeliver(): Promise<void> {
  await fetchQuietly(() => operationsApi.reconcileRules('redeliver'))
  await loadAll()
  ElMessage.success('EDGE-03 规则重新下发完成，4 / 4 版本一致')
}
async function onRuleKeep(): Promise<void> {
  await fetchQuietly(() => operationsApi.reconcileRules('keep'))
  ElMessage.warning('已保持 EDGE-03 当前版本，登记为待人工处理')
}
async function onTimeResync(): Promise<void> {
  await fetchQuietly(() => operationsApi.reconcileTime())
  await loadAll()
  timeDriftPending.value = false
}
async function onRecoveryFinished(): Promise<void> {
  recovering.value = false
  opsStore.setLink('online')
  await loadAll()
  ElMessage.success('云边链路已恢复，补传、去重、规则与时间对账全部完成')
}
</script>

<template>
  <div class="operations-page">
    <OpsToolbar
      :link="link"
      :recovering="recovering"
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
            <div><span>EDGE NODES</span><h3>边缘节点状态</h3></div>
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
      :queue="queue"
      :nodes="nodes"
      :time-drift="timeDriftPending"
      @progress="onUploadProgress"
      @rule-redeliver="onRuleRedeliver"
      @rule-keep="onRuleKeep"
      @time-resync="onTimeResync"
      @finished="onRecoveryFinished"
    />
    <DeviceDiagnosticDialog v-model="diagOpen" :device="diagDevice" @reconnect="reconnectDevice" />
  </div>
</template>
