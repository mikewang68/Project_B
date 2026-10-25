<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElPagination } from 'element-plus'
import AlertOverview from '@/components/alerts/AlertOverview.vue'
import AlertFilters, { type AlertFilterState } from '@/components/alerts/AlertFilters.vue'
import AlertTable from '@/components/alerts/AlertTable.vue'
import AlertDetailDrawer from '@/components/alerts/AlertDetailDrawer.vue'
import AlertAssignDialog from '@/components/alerts/AlertAssignDialog.vue'
import TreatmentDialog from '@/components/alerts/TreatmentDialog.vue'
import AlertReviewDialog from '@/components/alerts/AlertReviewDialog.vue'
import { alertApi, type AssignPayload, type TreatmentPayload } from '@/api/alerts'
import { createLatestGuard } from '@/composables/useLatestGuard'
import { mapAlertMetricsDto, mapAlertPage, mapAlertEvent, nextRiskLevel } from '@/adapters/alert'
import { useLiveStore } from '@/stores/live'
import type { AlertEvent, AlertMetrics } from '@/types/alert'

const router = useRouter()
const liveStore = useLiveStore()

// 告警数据只从业务服务恢复；请求失败时保留错误状态，不生成替代告警。

const EMPTY_METRICS: AlertMetrics = { total: 0, pending: 0, active: 0, severe: 0, urgent: 0, closed: 0 }

const filters = reactive<AlertFilterState>({
  keyword: '', timeRange: '全部', area: '全部', eventType: '全部',
  risk: '全部', status: '全部', assignee: '全部', source: '全部',
})

// ---- 服务端状态 ----
const metrics = ref<AlertMetrics>({ ...EMPTY_METRICS })
const alerts = ref<AlertEvent[]>([])
const optionAlerts = ref<AlertEvent[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const listLoading = ref(false)
const metricsLoading = ref(false)
const listError = ref('')

// ---- 详情抽屉 ----
const drawerVisible = ref(false)
const current = ref<AlertEvent>()
const detailLoading = ref(false)
const actionPending = ref(false)

// ---- Dialog ----
const assignVisible = ref(false)
const assignMode = ref<'派单' | '转派'>('派单')
const treatmentVisible = ref(false)
const reviewVisible = ref(false)

let filterTimer: number | undefined

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback
}

// ---------- 查询 ----------
function buildListParams() {
  return {
    keyword: filters.keyword.trim() || undefined,
    risk: filters.risk !== '全部' ? filters.risk : undefined,
    status: filters.status !== '全部' ? filters.status : undefined,
    area: filters.area !== '全部' ? filters.area : undefined,
    eventType: filters.eventType !== '全部' ? filters.eventType : undefined,
    assignee: filters.assignee !== '全部' ? filters.assignee : undefined,
    source: filters.source !== '全部' ? filters.source : undefined,
    timeRange: filters.timeRange !== '全部' ? filters.timeRange : undefined,
    page: page.value,
    pageSize: pageSize.value,
  }
}

const listGuard = createLatestGuard()
const metricsGuard = createLatestGuard()
const detailGuard = createLatestGuard()

async function loadMetrics(): Promise<void> {
  const token = metricsGuard.next()
  metricsLoading.value = true
  try {
    const dto = await alertApi.getAlertMetrics()
    if (!metricsGuard.isLatest(token)) return
    metrics.value = mapAlertMetricsDto(dto)
  } catch (cause) {
    if (!metricsGuard.isLatest(token)) return
    metrics.value = { ...EMPTY_METRICS }
    ElMessage.error(errorMessage(cause, '告警指标加载失败'))
  } finally {
    metricsLoading.value = false
  }
}

async function loadList(): Promise<void> {
  const token = listGuard.next()
  listLoading.value = true
  listError.value = ''
  try {
    const result = mapAlertPage(await alertApi.getAlerts(buildListParams()))
    if (!listGuard.isLatest(token)) return
    alerts.value = result.list
    total.value = result.total
    page.value = result.page
    pageSize.value = result.pageSize
  } catch (cause) {
    if (!listGuard.isLatest(token)) return
    alerts.value = []
    total.value = 0
    listError.value = errorMessage(cause, '告警列表加载失败')
  } finally {
    listLoading.value = false
  }
}

/** 筛选下拉选项只需要字段集合，单独拉取一次大页，不参与表格渲染 */
async function loadOptions(): Promise<void> {
  try {
    const result = mapAlertPage(await alertApi.getAlerts({ page: 1, pageSize: 200 }))
    optionAlerts.value = result.list
  } catch {
    optionAlerts.value = []
  }
}

async function openDetail(row: AlertEvent): Promise<void> {
  // 详情竞态守卫：快速连点两行时，先发后到的响应不得覆盖当前选中项，
  // 否则后续「复核通过 / 驳回 / 升级」会作用在错误的事件上。
  const token = detailGuard.next()
  drawerVisible.value = true
  detailLoading.value = true
  current.value = row
  try {
    const detail = mapAlertEvent(await alertApi.getAlertDetail(row.id))
    if (!detailGuard.isLatest(token)) return
    current.value = detail
  } catch (cause) {
    if (!detailGuard.isLatest(token)) return
    current.value = undefined
    ElMessage.error(errorMessage(cause, '告警详情加载失败'))
  } finally {
    if (detailGuard.isLatest(token)) detailLoading.value = false
  }
}

/** 统一刷新：写操作成功后只通过重新请求同步状态，绝不本地改状态 / 本地 push 时间线 */
async function refreshAlertContext(flags: { metrics?: boolean; list?: boolean; detail?: boolean } = {}): Promise<void> {
  const tasks: Promise<unknown>[] = []
  if (flags.metrics !== false) tasks.push(loadMetrics())
  if (flags.list !== false) tasks.push(loadList())
  if (flags.detail && current.value?.id) {
    const id = current.value.id
    const token = detailGuard.next()
    tasks.push(
      alertApi.getAlertDetail(id).then((dto) => {
        // 刷新期间用户可能已切到另一条；只在仍停留在同一事件时回写
        if (!detailGuard.isLatest(token) || current.value?.id !== id) return
        current.value = mapAlertEvent(dto)
      }),
    )
  }
  await Promise.all(tasks)
  await loadOptions()
}

// ---------- 写操作 ----------
async function runAction(work: () => Promise<unknown>, successText: string, refresh = true): Promise<boolean> {
  actionPending.value = true
  try {
    await work()
    ElMessage.success(successText)
    if (refresh) await refreshAlertContext({ metrics: true, list: true, detail: true })
    return true
  } catch (cause) {
    // 409 STATE_CONFLICT 等错误：直接展示后端 message，页面状态保持不变
    ElMessage.error(errorMessage(cause, '操作失败，请稍后重试'))
    return false
  } finally {
    actionPending.value = false
  }
}

async function onConfirm(): Promise<void> {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('确认该安全事件已核实？确认后进入派单环节。', '确认事件', { type: 'warning' })
  } catch {
    return
  }
  const id = current.value.id
  await runAction(() => alertApi.confirmAlert(id), '事件已确认')
}

function onAssign(): void {
  assignMode.value = '派单'
  assignVisible.value = true
}

function onTransfer(): void {
  assignMode.value = '转派'
  assignVisible.value = true
}

async function onAssignConfirm(payload: AssignPayload | { assignee: string; priority: '普通' | '紧急'; limitMin: number; note: string }): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  if (assignMode.value === '转派') {
    await runAction(
      () => alertApi.transferAlert(id, {
        assignee: payload.assignee,
        assigneeId: (payload as { assigneeId?: string }).assigneeId,
        note: payload.note,
      }),
      `已转派给${payload.assignee}`,
    )
  } else {
    await runAction(() => alertApi.assignAlert(id, payload), `事件已派发给${payload.assignee}`)
  }
}

async function onStart(): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  await runAction(() => alertApi.startAlert(id), '已接单，开始处理')
}

function onTreat(): void {
  treatmentVisible.value = true
}

async function onTreatmentConfirm(payload: TreatmentPayload): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  await runAction(() => alertApi.submitTreatment(id, payload), '处置结果已提交，等待复核')
}

function onReview(): void {
  reviewVisible.value = true
}

async function onReviewApprove(): Promise<void> {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('复核通过后事件将关闭，确认处置结果有效？', '安全复核', { type: 'warning' })
  } catch {
    return
  }
  const id = current.value.id
  await runAction(() => alertApi.reviewAlert(id, { reviewer: '刘志明' }), '复核通过，事件已关闭')
  reviewVisible.value = false
}

async function onReviewReject(reason: string): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  reviewVisible.value = false
  await runAction(() => alertApi.rejectReview(id, reason), '已驳回，事件回到处理中')
}

async function onLinkage(): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  await runAction(() => alertApi.triggerLinkage(id, 'success'), '安全联动执行完成')
}

async function onSimulateFail(): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  await runAction(() => alertApi.triggerLinkage(id, 'fail'), '已模拟 PLC 回执失败，请人工接管')
}

async function onTakeover(): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  await runAction(
    () => alertApi.takeoverAlert(id, { reason: 'PLC 回执超时，人工现场确认设备状态' }),
    '人工接管已记录',
  )
}

async function onEscalate(): Promise<void> {
  if (!current.value) return
  const id = current.value.id
  const targetLevel = nextRiskLevel(current.value.risk)
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(`事件将升级为「${targetLevel}」，请填写升级原因`, '事件升级', {
      confirmButtonText: '确认升级',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '如：现场情况恶化 / 设备无法停止 / 存在人员受伤风险',
      inputValidator: (v) => (!!v && v.trim().length > 0) || '请填写升级原因',
    })
    reason = String(value).trim()
  } catch {
    return
  }
  await runAction(
    () => alertApi.escalateAlert(id, { level: targetLevel, reason, targets: ['调度员', '管理人员'] }),
    `事件已升级为${targetLevel}`,
  )
}

// ---------- 筛选 / 分页 ----------
watch(
  filters,
  () => {
    window.clearTimeout(filterTimer)
    filterTimer = window.setTimeout(() => {
      page.value = 1
      void loadList()
    }, 250)
  },
  { deep: true },
)

function onPageChange(next: number): void {
  page.value = next
  void loadList()
}
function onSizeChange(size: number): void {
  pageSize.value = size
  page.value = 1
  void loadList()
}
function resetFilters(): void {
  Object.assign(filters, {
    keyword: '', timeRange: '全部', area: '全部', eventType: '全部',
    risk: '全部', status: '全部', assignee: '全部', source: '全部',
  })
}
function retryList(): void {
  void loadList()
}

function openRule(ruleId: string): void {
  void router.push({ name: 'rules', query: { rule: ruleId } })
}

onMounted(() => {
  void loadMetrics()
  void loadList()
  void loadOptions()
  // 其他终端（移动端 / 大屏）引起的变更经 WS 通知后自动刷新，无需 F5
  unsubscribeLive = liveStore.onAlertEvent(() => {
    window.clearTimeout(liveTimer)
    liveTimer = window.setTimeout(() => {
      void refreshAlertContext({ metrics: true, list: true, detail: true })
    }, 300)
  })
  unsubscribeReconnect = liveStore.onReconnected(() => {
    void refreshAlertContext({ metrics: true, list: true, detail: true })
  })
})

let unsubscribeLive: (() => void) | undefined
let unsubscribeReconnect: (() => void) | undefined
let liveTimer: number | undefined

onBeforeUnmount(() => {
  unsubscribeLive?.()
  unsubscribeReconnect?.()
  window.clearTimeout(liveTimer)
  window.clearTimeout(filterTimer)
})
</script>

<template>
  <div class="page-view">
    <header class="page-head">
      <div>
        <h1>告警中心</h1>
        <p>统一管理人员、设备、AI 与系统安全事件</p>
      </div>
    </header>

    <div v-loading="metricsLoading">
      <AlertOverview v-bind="metrics" />
    </div>

    <AlertFilters :filters="filters" :alerts="optionAlerts" @reset="resetFilters" />

    <AlertTable :alerts="alerts" :loading="listLoading" @open="openDetail" />

    <div v-if="listError" class="dashboard-card alert-list-error">
      <p>{{ listError }}</p>
      <button type="button" class="primary" @click="retryList">重新加载</button>
    </div>

    <div v-if="!listError && total > 0" class="alert-pagination">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>

    <AlertDetailDrawer
      v-model="drawerVisible"
      :alert="current"
      :detail-loading="detailLoading"
      :action-pending="actionPending"
      @confirm="onConfirm"
      @assign="onAssign"
      @transfer="onTransfer"
      @start="onStart"
      @treat="onTreat"
      @linkage="onLinkage"
      @review="onReview"
      @escalate="onEscalate"
      @simulate-fail="onSimulateFail"
      @takeover="onTakeover"
      @open-rule="openRule"
    />

    <AlertAssignDialog v-model="assignVisible" :mode="assignMode" :current="current?.assignee" @confirm="onAssignConfirm" />
    <TreatmentDialog v-model="treatmentVisible" @confirm="onTreatmentConfirm" />
    <AlertReviewDialog v-model="reviewVisible" :alert="current" @approve="onReviewApprove" @reject="onReviewReject" />
  </div>
</template>
