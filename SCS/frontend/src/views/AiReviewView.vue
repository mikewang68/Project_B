<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import AIOverview from '@/components/ai/AIOverview.vue'
import AIEventGrid from '@/components/ai/AIEventGrid.vue'
import AIEventDrawer from '@/components/ai/AIEventDrawer.vue'
import ReviewDialog from '@/components/ai/ReviewDialog.vue'
import AssignmentDialog from '@/components/ai/AssignmentDialog.vue'
import MockAIEventButton from '@/components/ai/MockAIEventButton.vue'
import { aiEventApi, confidenceParam, timeBucketParam } from '@/api/aiEvents'
import { ApiError } from '@/api/http'
import { useLiveStore } from '@/stores/live'
import { AI_EVENT_TYPES, REVIEW_STATUSES, type AiEvent } from '@/types/ai'

const CURRENT_REVIEWER = '李娜'

// ---------- 列表 / 指标（主数据来自后端，不再使用 mock/aiEvents.ts） ----------
const events = ref<AiEvent[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const metrics = ref({ today: 0, pending: 0, confirmed: 0, falsePositive: 0, cameraFault: 0 })
const facetAreas = ref<string[]>([])
const facetCameras = ref<string[]>([])

// ---------- 筛选（变化后重新请求后端） ----------
const keyword = ref('')
const filterType = ref('全部')
const filterArea = ref('全部')
const filterCamera = ref('全部')
const filterStatus = ref('全部')
const filterRisk = ref('全部')
const filterConfidence = ref('全部')
const filterTime = ref('全部时段')

let searchTimer: ReturnType<typeof setTimeout> | undefined

async function loadList(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const page = await aiEventApi.getAiEvents({
      keyword: keyword.value.trim() || undefined,
      type: filterType.value === '全部' ? undefined : filterType.value,
      area: filterArea.value === '全部' ? undefined : filterArea.value,
      camera: filterCamera.value === '全部' ? undefined : filterCamera.value,
      status: filterStatus.value === '全部' ? undefined : filterStatus.value,
      risk: filterRisk.value === '全部' ? undefined : filterRisk.value,
      confidence: confidenceParam(filterConfidence.value),
      timeBucket: timeBucketParam(filterTime.value),
      page: 1,
      pageSize: 100,
    })
    events.value = page.list
    total.value = page.total
    metrics.value = page.metrics
    facetAreas.value = page.facets.areas
    facetCameras.value = page.facets.cameras
  } catch (error) {
    loadError.value = error instanceof ApiError ? error.message : 'AI 事件加载失败'
  } finally {
    loading.value = false
  }
}

watch([filterType, filterArea, filterCamera, filterStatus, filterRisk, filterConfidence, filterTime], () => {
  void loadList()
})
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadList(), 260)
})

function resetFilters(): void {
  keyword.value = ''; filterType.value = '全部'; filterArea.value = '全部'; filterCamera.value = '全部'
  filterStatus.value = '全部'; filterRisk.value = '全部'; filterConfidence.value = '全部'; filterTime.value = '全部时段'
}

// ---------- 详情 Drawer（打开时从后端拉取权威详情） ----------
const drawerOpen = ref(false)
const selectedId = ref<string>()
const selectedEvent = ref<AiEvent>()
const detailLoading = ref(false)

async function openEvent(event: AiEvent): Promise<void> {
  selectedId.value = event.id
  drawerOpen.value = true
  detailLoading.value = true
  try {
    selectedEvent.value = await aiEventApi.getAiEventDetail(event.id)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '事件详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

/** 写操作成功后：用返回值更新当前详情/列表项，并重新拉取列表与指标（保证 Timeline/指标权威） */
function applyChanged(changed: AiEvent): void {
  if (selectedEvent.value?.id === changed.id) selectedEvent.value = changed
  const idx = events.value.findIndex((e) => e.id === changed.id)
  if (idx >= 0) events.value[idx] = changed
  else events.value.unshift(changed)
  void loadList()
}

function reportError(error: unknown, fallback: string): void {
  const message = error instanceof ApiError ? error.message : fallback
  ElMessage.error(message)
  if (error instanceof ApiError && import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.warn(`[ai] ${error.status} ${error.code} traceId=${error.traceId}`)
  }
}

// ---------- 人工复核动作 ----------
function confirmViolation(): void {
  if (!selectedEvent.value) return
  const id = selectedEvent.value.id
  void ElMessageBox.confirm('确认该 AI 事件为真实违规？确认后将生成统一安全告警并进入处置主链。', '确认违规', {
    confirmButtonText: '确认违规', cancelButtonText: '取消', type: 'warning',
  }).then(async () => {
    try {
      applyChanged(await aiEventApi.confirmAiEvent(id, CURRENT_REVIEWER))
      ElMessage.success('事件已确认违规，已生成安全告警')
    } catch (error) {
      reportError(error, '确认违规失败')
    }
  }).catch(() => undefined)
}

const falseDialogOpen = ref(false)
function openFalseDialog(): void { falseDialogOpen.value = true }
async function submitFalse(reason: string): Promise<void> {
  if (!selectedEvent.value) return
  try {
    applyChanged(await aiEventApi.markFalsePositive(selectedEvent.value.id, reason, CURRENT_REVIEWER))
    ElMessage.success('事件已标记为误报')
  } catch (error) {
    reportError(error, '标记误报失败')
  }
}

async function markUncertain(): Promise<void> {
  if (!selectedEvent.value) return
  try {
    applyChanged(await aiEventApi.markUncertain(selectedEvent.value.id, CURRENT_REVIEWER))
    ElMessage.info('该事件已进入人工复核队列')
  } catch (error) {
    reportError(error, '操作失败')
  }
}

const assignDialogOpen = ref(false)
function openAssignDialog(): void { assignDialogOpen.value = true }
async function submitAssign(payload: { assignee: string; priority: '普通' | '紧急'; note: string }): Promise<void> {
  if (!selectedEvent.value) return
  try {
    applyChanged(await aiEventApi.assignAiEvent(selectedEvent.value.id, { ...payload, reviewer: CURRENT_REVIEWER }))
    ElMessage.success(`已派单至${payload.assignee}`)
  } catch (error) {
    reportError(error, '派单失败')
  }
}

async function markProcessing(): Promise<void> {
  if (!selectedEvent.value) return
  try {
    applyChanged(await aiEventApi.processAiEvent(selectedEvent.value.id, CURRENT_REVIEWER))
    ElMessage.success('事件已进入处理中')
  } catch (error) {
    reportError(error, '操作失败')
  }
}

async function closeEvent(): Promise<void> {
  if (!selectedEvent.value) return
  try {
    applyChanged(await aiEventApi.closeAiEvent(selectedEvent.value.id, CURRENT_REVIEWER))
    ElMessage.success('事件已关闭')
  } catch (error) {
    reportError(error, '关闭失败')
  }
}

// ---------- Demo 模拟（由后端生成，走真实 WS 广播链路） ----------
const freshTimers = new Set<ReturnType<typeof setTimeout>>()
function markFresh(event: AiEvent): void {
  event.fresh = true
  const timer = setTimeout(() => { event.fresh = false; freshTimers.delete(timer) }, 5200)
  freshTimers.add(timer)
}

async function simulateEvent(): Promise<void> {
  try {
    const created = await aiEventApi.simulate('new')
    markFresh(created)
    events.value.unshift(created)
    void loadList()
    ElMessage.success('检测到新的 AI 安全事件')
  } catch (error) {
    reportError(error, '模拟事件失败')
  }
}
async function simulateLowConfidence(): Promise<void> {
  try {
    const created = await aiEventApi.simulate('low-confidence')
    markFresh(created)
    events.value.unshift(created)
    void loadList()
    ElMessage.warning('AI 置信度不足，建议人工复核')
  } catch (error) {
    reportError(error, '模拟事件失败')
  }
}
async function simulateCameraFault(): Promise<void> {
  try {
    const created = await aiEventApi.simulate('camera-fault')
    markFresh(created)
    events.value.unshift(created)
    void loadList()
    ElMessage.warning('当前摄像头 AI 结果可信度降低')
  } catch (error) {
    reportError(error, '模拟事件失败')
  }
}

// ---------- WebSocket：ai.* 实时增量；重连后全量同步 ----------
const liveStore = useLiveStore()
let disposeLive: (() => void) | undefined
let disposeReconnect: (() => void) | undefined

onMounted(() => {
  void loadList()
  disposeLive = liveStore.onAlertEvent((event) => {
    if (!event.type.startsWith('ai.')) return
    // 本地刚发起的写操作已乐观更新，这里做一次权威同步即可
    void loadList()
    if (selectedId.value) {
      aiEventApi.getAiEventDetail(selectedId.value)
        .then((detail) => { if (selectedEvent.value?.id === detail.id) selectedEvent.value = detail })
        .catch(() => undefined)
    }
  })
  disposeReconnect = liveStore.onReconnected(() => void loadList())
})

onUnmounted(() => {
  disposeLive?.()
  disposeReconnect?.()
  freshTimers.forEach(clearTimeout)
  if (searchTimer) clearTimeout(searchTimer)
})

const listCountText = computed(() => (loading.value ? '加载中…' : `共 <b>${total.value}</b> 条事件`))
</script>

<template>
  <section class="page-content ai-review-page">
    <header class="module-intro header-actions-only">
      <MockAIEventButton @simulate="simulateEvent" @low-confidence="simulateLowConfidence" @camera-fault="simulateCameraFault" />
    </header>

    <AIOverview :today="metrics.today" :pending="metrics.pending" :confirmed="metrics.confirmed"
      :false-positive="metrics.falsePositive" :camera-fault="metrics.cameraFault" />

    <div class="dashboard-card ai-filter-bar">
      <label class="ai-filter-search">
        <el-icon><Search /></el-icon>
        <el-input v-model="keyword" placeholder="搜索事件编号 / 摄像头 / 区域 / 模型 / 人员" :prefix-icon="null" />
        <button v-if="keyword" type="button" @click="keyword = ''">清除</button>
      </label>
      <label><small>事件类型</small>
        <el-select v-model="filterType"><el-option label="全部" value="全部" /><el-option v-for="t in AI_EVENT_TYPES" :key="t" :label="t" :value="t" /></el-select>
      </label>
      <label><small>区域</small>
        <el-select v-model="filterArea"><el-option label="全部" value="全部" /><el-option v-for="a in facetAreas" :key="a" :label="a" :value="a" /></el-select>
      </label>
      <label><small>摄像头</small>
        <el-select v-model="filterCamera"><el-option label="全部" value="全部" /><el-option v-for="c in facetCameras" :key="c" :label="c" :value="c" /></el-select>
      </label>
      <label><small>复核状态</small>
        <el-select v-model="filterStatus"><el-option label="全部" value="全部" /><el-option v-for="s in REVIEW_STATUSES" :key="s" :label="s" :value="s" /></el-select>
      </label>
      <label><small>风险等级</small>
        <el-select v-model="filterRisk"><el-option label="全部" value="全部" /><el-option label="高风险" value="高" /><el-option label="中风险" value="中" /><el-option label="低风险" value="低" /></el-select>
      </label>
      <label><small>置信度</small>
        <el-select v-model="filterConfidence">
          <el-option label="全部" value="全部" /><el-option label="高（≥85%）" value="高（≥85%）" />
          <el-option label="中（70-84%）" value="中（70-84%）" /><el-option label="低（&lt;70%）" value="低" />
        </el-select>
      </label>
      <label><small>时间</small>
        <el-select v-model="filterTime">
          <el-option label="全部时段" value="全部时段" /><el-option label="近1小时" value="近1小时" />
          <el-option label="近2小时" value="近2小时" /><el-option label="更早" value="更早" />
        </el-select>
      </label>
      <button type="button" class="ai-filter-reset" @click="resetFilters">重置筛选</button>
    </div>

    <div class="ai-list-meta">
      <span v-html="listCountText"></span>
      <small v-if="loadError" class="ai-list-error">{{ loadError }}</small>
      <small v-else>AI 识别结果来自后端 Demo 接口；视频流与模型推理为模拟数据</small>
    </div>

    <AIEventGrid :events="events" :loading="loading" @open="openEvent" />

    <AIEventDrawer v-model="drawerOpen" :event="selectedEvent" :loading="detailLoading" @confirm="confirmViolation"
      @false-report="openFalseDialog" @uncertain="markUncertain" @assign="openAssignDialog"
      @processing="markProcessing" @close="closeEvent" />
    <ReviewDialog v-model="falseDialogOpen" @confirm="submitFalse" />
    <AssignmentDialog v-model="assignDialogOpen" :event="selectedEvent" @confirm="submitAssign" />
  </section>
</template>
