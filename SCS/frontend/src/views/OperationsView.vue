<script setup lang="ts">
import { computed, ref } from 'vue'
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
import {
  buildDeviceCategories,
  buildEdgeNodes,
  buildInterfaces,
  buildOpsEvents,
  onlineCount,
  PLATFORM_RULE_VERSION,
} from '@/mock/opsData'
import { LOCAL_RISK_POOL, type EdgeNode, type LocalEvent, type OpsDevice, type OpsEvent } from '@/types/operations'

const opsStore = useOperationsStore()
const link = computed(() => opsStore.linkState)

const nodes = ref<EdgeNode[]>(buildEdgeNodes())
const categories = ref(buildDeviceCategories())
const interfaces = ref(buildInterfaces())
const opsEvents = ref<OpsEvent[]>(buildOpsEvents())
const queue = ref<LocalEvent[]>([])

const drawerNode = ref<EdgeNode | null>(null)
const drawerOpen = ref(false)
const recoveryOpen = ref(false)
const recovering = ref(false)
const diagOpen = ref(false)
const diagDevice = ref<OpsDevice | null>(null)
const timeDriftPending = ref(false)

let riskSeq = 0
let riskPoolIdx = 0
let eventSeq = 5

function nowTime(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
function pushFeed(level: OpsEvent['level'], target: string, text: string): void {
  eventSeq += 1
  opsEvents.value = [
    { id: `OPS-E-${String(eventSeq).padStart(2, '0')}`, time: nowTime(), level, target, text },
    ...opsEvents.value,
  ].slice(0, 14)
}

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

/* ---------- 模拟云边断网 ---------- */
function simulateDisconnect(): void {
  opsStore.setLink('link-error')
  ElMessage.warning('检测到中心链路抖动，正在评估连接状态…')
  pushFeed('degraded', '云边链路', '中心链路抖动，边缘进入自治准备')
  window.setTimeout(() => {
    opsStore.setLink('disconnected')
    nodes.value.forEach((n) => { n.autonomy = true })
    pushFeed('fault', '云边链路', '中心连接中断，4 个边缘节点进入本地自治模式')
    ElMessage.warning('中心链路中断，边缘节点进入本地自治模式')
  }, 1300)
}

/* ---------- 断网期间本地风险 ---------- */
function simulateLocalRisk(): void {
  if (link.value !== 'disconnected') return
  riskSeq += 1
  const tpl = LOCAL_RISK_POOL[riskPoolIdx % LOCAL_RISK_POOL.length]!
  riskPoolIdx += 1
  const nodeOrder = ['EDGE-02', 'EDGE-01', 'EDGE-03', 'EDGE-04']
  const nodeId = nodeOrder[(riskSeq - 1) % nodeOrder.length]!
  const node = nodes.value.find((n) => n.id === nodeId)!
  const evt: LocalEvent = {
    id: `EVT-EDGE-${String(riskSeq).padStart(3, '0')}`,
    type: tpl.type,
    node: nodeId,
    time: nowTime(),
    risk: tpl.risk,
    status: '待补传',
    dedupKey: `${nodeId}-${Date.now()}-${riskSeq}`,
    localActions: tpl.actions,
  }
  queue.value = [evt, ...queue.value]
  node.cacheEvents += 1
  node.localEventCount += 1
  node.storage = Math.min(99, node.storage + 2)
  pushFeed('warning', nodeId, `本地判定「${tpl.type}」，已完成现场联动并缓存事件`)
  ElMessage({
    type: tpl.risk === '紧急' || tpl.risk === '严重' ? 'warning' : 'success',
    message: `${nodeId} 本地完成：${tpl.actions.join(' → ')}，事件待补传`,
    duration: 2600,
  })
}

/* ---------- 时间偏差 ---------- */
function simulateTimeDrift(): void {
  if (link.value !== 'disconnected') {
    ElMessage.info('请先模拟云边断网，再演示时间偏差')
    return
  }
  const n = nodes.value.find((x) => x.id === 'EDGE-02')!
  n.timeOffsetMs = 3800
  timeDriftPending.value = true
  pushFeed('warning', 'EDGE-02', '时间同步偏差 +3.8s，事件时间可能存在偏差')
  ElMessage.warning('EDGE-02 出现 +3.8s 时间偏差，恢复时需重新校时')
}

/* ---------- 缓存告警 ---------- */
function simulateCacheAlert(): void {
  const n = nodes.value.find((x) => x.id === 'EDGE-03')!
  n.storage = 86
  n.cacheParts = [
    { label: '事件缓存', percent: 85 },
    { label: '视频证据缓存', percent: 88 },
    { label: '日志空间', percent: 82 },
  ]
  n.recentIssue = '缓存占用达到容量高风险阈值'
  pushFeed('fault', 'EDGE-03', '缓存占用达到 86%，进入容量高风险状态')
  ElMessage.warning('边缘缓存空间不足，将优先保留高等级事件与控制回执')
}

/* ---------- 设备异常 ---------- */
function simulateDeviceFault(): void {
  const cam = categories.value.find((c) => c.kind === '摄像头')!.devices.find((d) => d.id === 'CAM-12')!
  cam.state = 'offline'
  cam.issue = '网络中断'
  pushFeed('fault', 'CAM-12', '设备网络中断，摄像头离线')
  ElMessage.error('CAM-12 网络中断，已打开设备诊断')
  diagDevice.value = cam
  diagOpen.value = true
}
function reconnectDevice(d: OpsDevice): void {
  d.state = 'normal'
  d.issue = ''
  pushFeed('info', d.id, '设备重新连接成功，状态恢复正常')
  ElMessage.success(`${d.id} 已重新连接，恢复正常`)
}

/* ---------- 节点 Drawer 操作 ---------- */
function openNode(n: EdgeNode): void {
  drawerNode.value = n
  drawerOpen.value = true
}
function reconnectNode(n: EdgeNode): void {
  n.online = true
  ElMessage.success(`${n.id} 连接已恢复`)
}
function resyncNodeTime(n: EdgeNode): void {
  n.timeOffsetMs = null
  if (n.id === 'EDGE-02') timeDriftPending.value = false
  ElMessage.success(`${n.id} 时间同步完成，偏差 32ms`)
}
function redeliverNode(n: EdgeNode): void {
  n.ruleVersion = n.platformVersion
  ElMessage.success(`${n.id} 规则已重新下发至 ${n.platformVersion}`)
}

/* ---------- 恢复流程 ---------- */
function startRecover(): void {
  recovering.value = true
  opsStore.setLink('recovering')
  // 恢复时发现 EDGE-03 规则版本滞后（与规则配置中心演示口径一致）
  const e3 = nodes.value.find((n) => n.id === 'EDGE-03')!
  e3.ruleVersion = 'v3.2'
  recoveryOpen.value = true
  ElMessage.info('开始恢复云边连接，自动执行补传与对账')
}
function onUploadProgress(doneCount: number): void {
  // 队列新事件在前，补传从最早（队尾）开始；其中最早 2 条按「重复」去重
  const total = queue.value.length
  const remain = total - doneCount
  queue.value.forEach((e, idx) => {
    if (idx >= remain) e.status = idx >= total - 2 ? '重复' : '已补传'
  })
  nodes.value.forEach((n) => {
    n.cacheEvents = queue.value.filter((e) => e.node === n.id && e.status === '待补传').length
  })
}
function onRuleRedeliver(): void {
  const e3 = nodes.value.find((n) => n.id === 'EDGE-03')!
  window.setTimeout(() => {
    e3.ruleVersion = PLATFORM_RULE_VERSION
    ElMessage.success('EDGE-03 规则重新下发完成，4 / 4 版本一致')
  }, 700)
}
function onRuleKeep(): void {
  ElMessage.warning('已保持 EDGE-03 当前版本，登记为待人工处理')
}
function onTimeResync(): void {
  const e2 = nodes.value.find((n) => n.id === 'EDGE-02')!
  e2.timeOffsetMs = null
  timeDriftPending.value = false
}
function onRecoveryFinished(): void {
  recovering.value = false
  opsStore.setLink('online')
  nodes.value.forEach((n) => {
    n.autonomy = false
    n.cacheEvents = 0
  })
  const uploaded = queue.value.length
  queue.value = []
  pushFeed('info', '云边链路', `恢复在线：补传 ${Math.max(uploaded - 2, 0)} 条、去重 2 条，规则与时间对账完成`)
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
