<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Cpu, DataLine, RefreshRight, VideoPause, WarningFilled } from '@element-plus/icons-vue'
import EquipmentRelationMap from '@/components/collision/EquipmentRelationMap.vue'
import RiskStatusPanel from '@/components/collision/RiskStatusPanel.vue'
import DistanceTrendChart from '@/components/collision/DistanceTrendChart.vue'
import SafetyLinkagePanel from '@/components/collision/SafetyLinkagePanel.vue'
import EquipmentDetailDrawer from '@/components/collision/EquipmentDetailDrawer.vue'
import ManualTakeoverDialog from '@/components/collision/ManualTakeoverDialog.vue'
import RiskReleaseDialog from '@/components/collision/RiskReleaseDialog.vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { CollisionEquipment, CollisionRisk, CollisionSimulateResult, DistancePoint, LinkageStep } from '@/types/collision'
import { collisionApi } from '@/api/collision'
import { isDomainLiveEvent, sharedLiveSocket } from '@/api/live'

const devices = ref<CollisionEquipment[]>([])
const selectedType = ref<'转运车辆' | '翻箱机' | '龙门吊'>('转运车辆')
const selectedCurrentId = ref('VEH-07'), selectedRelatedId = ref('TIP-02')
const distance = ref(12.6), risk = ref<CollisionRisk>('安全'), radarDown = ref(false), simulationRunning = ref(false)
const deviceStopped = ref(false), controlFailure = ref(false), takeoverDone = ref(false)
const plcStatus = ref('待命'), selectedEquipmentId = ref<string>(), drawerOpen = ref(false)
const takeoverOpen = ref(false), releaseOpen = ref(false), activeAlertId = ref<string | null>(null)
const trendPoints = ref<DistancePoint[]>([])
const linkageSteps = ref<LinkageStep[]>([])
const timers = new Set<ReturnType<typeof setTimeout>>()
let disposeLive: (() => void) | undefined

const baseSteps = (): LinkageStep[] => [
  { id: 'detect', label: '风险检测', state: 'waiting', detail: '综合距离与运动趋势' },
  { id: 'alarm', label: '声光提醒', state: 'waiting', detail: '现场声光设备待命' },
  { id: 'driver', label: '司机提醒', state: 'waiting', detail: '车载终端待命' },
  { id: 'slow', label: '减速请求', state: 'waiting', detail: '等待风险等级触发' },
  { id: 'stop', label: '紧急停机', state: 'waiting', detail: '等待紧急条件触发' },
  { id: 'plc', label: 'PLC 回执', state: 'waiting', detail: '尚未发送控制指令' },
]

const decorate = (equipment: CollisionEquipment | undefined): CollisionEquipment => {
  if (!equipment) return devices.value[0] ?? ({} as CollisionEquipment)
  const stoppedHere = deviceStopped.value && equipment.id === selectedCurrentId.value
  return {
    ...equipment,
    status: stoppedHere ? '已停止' : equipment.status,
    speed: stoppedHere ? 0 : equipment.speed,
    radarStatus: radarDown.value && equipment.id === selectedCurrentId.value ? '数据中断' : equipment.radarStatus,
    controlStatus: controlFailure.value ? '状态未确认' : stoppedHere ? '停机已确认' : equipment.controlStatus,
  }
}
const currentEquipment = computed(() => decorate(devices.value.find((item) => item.id === selectedCurrentId.value)))
const relatedEquipment = computed(() => decorate(devices.value.find((item) => item.id === selectedRelatedId.value)))
const selectedEquipment = computed(() => selectedEquipmentId.value ? decorate(devices.value.find((item) => item.id === selectedEquipmentId.value)) : undefined)
const currentChoices = computed(() => devices.value.filter((item) => item.type === selectedType.value))
const relatedChoices = computed(() => devices.value.filter((item) => item.id !== selectedCurrentId.value))
const summaryStats = computed(() => [
  { label: '在线设备', value: '36 / 38', icon: Connection, tone: 'primary' },
  { label: '雷达正常', value: radarDown.value ? '7 / 8' : '8 / 8', icon: DataLine, tone: radarDown.value ? 'warning' : 'success' },
  { label: '当前风险', value: risk.value === '安全' ? 0 : 1, icon: WarningFilled, tone: risk.value === '紧急' ? 'danger' : risk.value === '安全' ? 'success' : 'warning' },
  { label: '控制链路', value: controlFailure.value ? '待确认' : '正常', icon: Cpu, tone: controlFailure.value ? 'danger' : 'success' },
])
const releaseChecks = computed(() => [
  { label: '设备已经停止', passed: deviceStopped.value },
  { label: '安全回撤路径已确认', passed: deviceStopped.value },
  { label: '传感器工作正常', passed: !radarDown.value },
  { label: 'PLC 或人工停机已有确认', passed: ['PLC 已确认停机', '人工确认停机'].includes(plcStatus.value) },
])

function schedule(callback: () => void, delay: number): void {
  const timer = setTimeout(() => { timers.delete(timer); callback() }, delay)
  timers.add(timer)
}
function clearTimers(): void { timers.forEach((timer) => clearTimeout(timer)); timers.clear() }
function appendDistance(value: number): void {
  trendPoints.value = [...trendPoints.value.slice(-13), { label: '现在', value }]
}
function upsertDevice(device: CollisionEquipment): void {
  const idx = devices.value.findIndex((d) => d.id === device.id)
  if (idx >= 0) devices.value.splice(idx, 1, device)
}

async function loadDevices(): Promise<void> {
  try {
    devices.value = await collisionApi.devices()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '设备数据加载失败')
  }
}
async function loadTrend(): Promise<void> {
  try {
    trendPoints.value = await collisionApi.trend(selectedCurrentId.value)
  } catch {
    trendPoints.value = []
  }
}
async function loadPair(): Promise<void> {
  try {
    const pair = await collisionApi.pair(selectedCurrentId.value, selectedRelatedId.value)
    distance.value = pair.distance
    risk.value = pair.risk
    radarDown.value = pair.radarDown
    linkageSteps.value = pair.steps?.length ? pair.steps : baseSteps()
    activeAlertId.value = pair.alertId ?? null
  } catch {
    distance.value = 12.6; risk.value = '安全'; linkageSteps.value = baseSteps()
  }
}

/** 将后端 simulate/linkage 返回结果统一应用到本地视图状态 */
function applyResult(result: CollisionSimulateResult): void {
  distance.value = result.distance
  risk.value = result.risk
  radarDown.value = result.radarDown
  deviceStopped.value = result.deviceStopped
  controlFailure.value = result.controlFailure
  plcStatus.value = result.plcStatus
  linkageSteps.value = result.steps
  activeAlertId.value = result.alertId ?? null
  if (result.device) upsertDevice(result.device)
  if (result.related) upsertDevice(result.related)
  appendDistance(result.distance)
}

async function stepApproach(): Promise<CollisionSimulateResult> {
  const result = await collisionApi.simulate(selectedCurrentId.value, 'approach')
  applyResult(result)
  return result
}

function simulateApproach(): void {
  clearTimers()
  simulationRunning.value = true
  // 逐档推进 [9.2 → 7.1 → 5.4 严重建单 → 3.8 → 2.8 紧急升级]，由后端保存状态
  ;[9.2, 7.1, 5.4, 3.8, 2.8].forEach((value, index) => {
    schedule(async () => {
      try {
        const result = await stepApproach()
        if (value === 9.2) ElMessage.warning('设备距离进入预警范围，建议减速')
        if (value === 5.4) ElMessage.warning('预测距离持续下降，系统正在发送减速请求')
        if (value === 2.8) {
          ElMessage.error('紧急碰撞风险，正在执行联动停机')
          // 紧急档位：执行成功联动，六步全部完成、设备停机
          const steps = await collisionApi.linkage(selectedCurrentId.value, 'success')
          linkageSteps.value = steps
          deviceStopped.value = true
          plcStatus.value = 'PLC 已确认停机'
          upsertDevice({ ...result.device, status: '已停止', speed: 0, controlStatus: '停机已确认' })
          simulationRunning.value = false
          ElMessage.success('PLC 已确认停机，风险控制成功')
        }
      } catch (err) {
        simulationRunning.value = false
        ElMessage.error(err instanceof Error ? err.message : '模拟失败')
      }
    }, 850 * (index + 1))
  })
}

function simulateFailure(): void {
  clearTimers()
  simulationRunning.value = true
  // 后端一次性推进到 2.8 紧急、前五步成功、PLC 失败，并写入关联 Alert
  collisionApi.simulate(selectedCurrentId.value, 'linkageFail').then((result) => {
    applyResult(result)
    controlFailure.value = true
    simulationRunning.value = false
    ElMessage.error('未确认设备已停机，请立即人工接管')
  }).catch((err) => ElMessage.error(err instanceof Error ? err.message : '联动失败模拟失败'))
}

async function confirmTakeover(): Promise<void> {
  takeoverOpen.value = false
  try {
    const res = await collisionApi.takeover(selectedCurrentId.value, '王建国', '现场人工急停确认')
    controlFailure.value = false
    takeoverDone.value = true
    deviceStopped.value = true
    plcStatus.value = res.plcStatus
    const step = linkageSteps.value.find((s) => s.id === 'plc')
    if (step) Object.assign(step, { state: 'success' as const, detail: '人工急停已现场确认' })
    ElMessage.success('人工接管完成，设备状态已确认为停止')
    void loadDevices()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '人工接管失败')
  }
}

async function toggleRadar(): Promise<void> {
  clearTimers()
  simulationRunning.value = false
  try {
    if (radarDown.value) {
      applyResult(await collisionApi.simulate(selectedCurrentId.value, 'radarRecover'))
      deviceStopped.value = false; controlFailure.value = false; takeoverDone.value = false
      ElMessage.success('雷达数据已恢复，安全距离重新确认')
    } else {
      applyResult(await collisionApi.simulate(selectedCurrentId.value, 'radarDown'))
      ElMessage.warning('传感器降级，建议限制设备运行')
    }
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '雷达状态切换失败')
  }
}

function releaseRisk(): void {
  releaseOpen.value = false
  ElMessageBox.confirm('确认提交设备运行限制解除申请？此操作仅用于 Demo 展示。', '申请解除限制', { confirmButtonText: '确认申请', cancelButtonText: '取消', type: 'warning' })
    .then(async () => {
      try {
        const res = await collisionApi.releaseRequest(selectedCurrentId.value, '王建国', releaseChecks.value.filter((c) => c.passed).map((c) => c.label))
        applyResult(await collisionApi.simulate(selectedCurrentId.value, 'reset'))
        deviceStopped.value = false; takeoverDone.value = false
        await loadTrend()
        ElMessage.success(`解除限制申请 ${res.requestId} 已提交，设备恢复安全状态`)
      } catch (err) {
        ElMessage.error(err instanceof Error ? err.message : '解除申请失败')
      }
    }).catch(() => undefined)
}

function openEquipment(equipment: CollisionEquipment): void { selectedEquipmentId.value = equipment.id; drawerOpen.value = true }
function updateType(type: typeof selectedType.value): void {
  selectedType.value = type
  if (type === '转运车辆') { selectedCurrentId.value = 'VEH-07'; selectedRelatedId.value = 'TIP-02' }
  if (type === '翻箱机') { selectedCurrentId.value = 'TIP-02'; selectedRelatedId.value = 'VEH-07' }
  if (type === '龙门吊') { selectedCurrentId.value = 'CRANE-01'; selectedRelatedId.value = 'VEH-08' }
  void loadPair(); void loadTrend()
}
function updateCurrent(id: string): void {
  selectedCurrentId.value = id
  selectedRelatedId.value = id === 'VEH-08' ? 'CRANE-01' : id === 'VEH-07' ? 'TIP-02' : id === 'TIP-02' ? 'VEH-07' : 'VEH-08'
  void loadPair(); void loadTrend()
}
function requestRestrictionRelease(): void { releaseOpen.value = true }

onMounted(() => {
  linkageSteps.value = baseSteps()
  void loadDevices().then(loadPair).then(loadTrend)
  sharedLiveSocket.connect()
  disposeLive = sharedLiveSocket.onMessage((raw) => {
    if (isDomainLiveEvent(raw) && String(raw.type).startsWith('collision.')) {
      void loadPair(); void loadDevices()
    }
  })
})
onBeforeUnmount(() => { clearTimers(); disposeLive?.() })
</script>

<template>
  <section class="page-content collision-page">
    <header class="module-intro collision-intro header-actions-only">
      <div class="collision-summary-stats"><div v-for="item in summaryStats" :key="item.label" :data-tone="item.tone"><span><el-icon><component :is="item.icon" /></el-icon></span><div><small>{{ item.label }}</small><b>{{ item.value }}</b></div></div></div>
    </header>

    <div class="dashboard-card collision-filter-bar">
      <div class="equipment-type-chips"><small>设备类型</small><button v-for="type in ['转运车辆','翻箱机','龙门吊'] as const" :key="type" type="button" :class="{ active: selectedType === type }" @click="updateType(type)">{{ type }}</button></div>
      <label><small>当前设备</small><el-select :model-value="selectedCurrentId" @update:model-value="updateCurrent"><el-option v-for="item in currentChoices" :key="item.id" :label="item.name" :value="item.id" /></el-select></label>
      <label><small>关联设备</small><el-select v-model="selectedRelatedId"><el-option v-for="item in relatedChoices" :key="item.id" :label="item.name" :value="item.id" /></el-select></label>
      <div class="filter-device-state"><small>状态</small><StatusBadge :status="currentEquipment?.status ?? '—'" /></div>
      <div class="collision-demo-actions">
        <button type="button" class="primary" :disabled="simulationRunning || radarDown" @click="simulateApproach"><el-icon><VideoPause /></el-icon>{{ simulationRunning ? '模拟进行中' : '模拟接近风险' }}</button>
        <button type="button" :disabled="simulationRunning" @click="simulateFailure">模拟联动失败</button>
        <button type="button" :class="{ warning: radarDown }" @click="toggleRadar"><el-icon><RefreshRight /></el-icon>{{ radarDown ? '恢复雷达' : '模拟雷达断数' }}</button>
      </div>
    </div>

    <div class="collision-core-grid">
      <EquipmentRelationMap v-if="currentEquipment" :current="currentEquipment" :related="relatedEquipment" :distance="distance" :risk="risk" :radar-down="radarDown" @select-equipment="openEquipment" />
      <RiskStatusPanel :risk="risk" :distance="distance" :radar-down="radarDown" :device-stopped="deviceStopped" :plc-status="plcStatus" :control-failure="controlFailure" :takeover-done="takeoverDone" @takeover="takeoverOpen = true" @release="releaseOpen = true" />
    </div>

    <div class="collision-bottom-grid"><DistanceTrendChart :points="trendPoints" :risk="risk" /><SafetyLinkagePanel :steps="linkageSteps" /></div>

    <EquipmentDetailDrawer v-model="drawerOpen" :equipment="selectedEquipment" @history="ElMessage.info('历史风险可在告警中心按设备筛选查看')" @request-release="requestRestrictionRelease" />
    <ManualTakeoverDialog v-model="takeoverOpen" @confirm="confirmTakeover" />
    <RiskReleaseDialog v-model="releaseOpen" :checks="releaseChecks" @confirm="releaseRisk" />
  </section>
</template>
