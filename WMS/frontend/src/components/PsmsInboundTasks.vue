<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { confirmPsmsInbound, listPsmsInboundTasks, retryPsmsInboundDelivery, type PsmsInboundTask } from '@/api/psmsInbound'

const emit = defineEmits<{ openOrder: [id: number] }>()
const auth = useAuthStore()
const tasks = ref<PsmsInboundTask[]>([]), loading = ref(false), busy = ref(''), error = ref('')
const tenant = computed(() => auth.user?.tenant)
const scope = computed(() => JSON.stringify([auth.user?.id, auth.user?.authorizationVersion, auth.user?.menuOperations, tenant.value?.companyCode, tenant.value?.currentWarehouse.code, tenant.value?.currentOwner.code]))
const statuses: Record<string,string> = { WAITING_WAREHOUSE:'待确认仓库', WAREHOUSE_CONFIRMED:'已确认仓库', PARTIALLY_RECEIVED:'部分收货', FULLY_RECEIVED:'已收齐', EXCEPTION:'需处理' }
const deliveries: Record<string,string> = { NONE:'尚无回执', PENDING:'待回传', SENDING:'回传中', DELIVERED:'已回传', BLOCKED:'回传需处理' }
let generation = 0
async function load() {
  const version = ++generation
  loading.value = true
  try {
    const result = await listPsmsInboundTasks()
    if (version === generation) { tasks.value = result; error.value = '' }
  } catch (e) {
    if (version === generation) { error.value = e instanceof Error ? e.message : '调度到货任务加载失败'; tasks.value = [] }
  } finally { if (version === generation) loading.value = false }
}
function canConfirm(task: PsmsInboundTask) {
  return auth.canAction('WMS:stockin','ADD','stockin:write') && task.status === 'WAITING_WAREHOUSE' &&
    task.companyCode === tenant.value?.companyCode && task.ownerCode === tenant.value?.currentOwner.code
}
async function confirm(task: PsmsInboundTask) {
  const current = tenant.value
  if (!current || busy.value || !canConfirm(task)) return
  const expectedScope = scope.value
  try {
    await ElMessageBox.confirm(`将任务 ${task.taskId} 建到当前仓库「${current.currentWarehouse.name}（${current.currentWarehouse.code}）」、货主「${current.currentOwner.name}」。此步骤仅建待收货单，不增加库存。`, '确认入库目的仓', { type: 'warning' })
  } catch { return }
  if (scope.value !== expectedScope) return void ElMessage.warning('当前仓库或货主已变化，请核对后重新确认')
  busy.value = task.taskId; error.value = ''
  try {
    const result = await confirmPsmsInbound(task.taskId, current.currentWarehouse.code, current.currentOwner.code)
    if (scope.value !== expectedScope) return
    ElMessage.success(`已创建待收货单：${result.wmsOrderCode}`)
    await load()
    if (result.wmsOrderId) emit('openOrder', result.wmsOrderId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '确认结果未取得，请刷新任务核对后重试；同一任务不会重复建单'
    await load()
    if (!error.value) error.value = '请核对任务的仓库和入库单号；尚未确认时可重试同一任务'
  } finally { busy.value = '' }
}
async function retry(task: PsmsInboundTask) {
  if (!auth.canAction('WMS:stockin','EDIT','stockin:write') || busy.value) return
  busy.value = task.taskId
  try { await retryPsmsInboundDelivery(task.taskId); await load(); ElMessage.success('原回执已重新排队') }
  catch (e) { error.value = e instanceof Error ? e.message : '回执重试失败' }
  finally { busy.value = '' }
}
watch(() => [scope.value,auth.canAction('WMS:stockin','VIEW','stockin:read')], () => {
  generation++; tasks.value = []
  if (auth.canAction('WMS:stockin','VIEW','stockin:read')) void load()
  else { loading.value=false; error.value='当前账号无权查询调度到货任务' }
}, { immediate: true })
defineExpose({ reload: load })
</script>

<template>
  <section class="panel psms-tasks" aria-label="调度到货任务">
    <div class="psms-heading"><div><h2>调度到货任务</h2><p>由生产调度派发，确认目的仓后使用下方入库单完成实际收货。</p></div><el-button :loading="loading" @click="load">刷新任务</el-button></div>
    <p class="scope-hint">当前仓库：{{ tenant?.currentWarehouse.name || '未选择' }}（{{ tenant?.currentWarehouse.code || '-' }}） · 货主：{{ tenant?.currentOwner.name || '未选择' }}。更换目的仓请先使用页面顶部的仓库切换。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="task-error" />
    <el-table v-loading="loading" :data="tasks" row-key="taskId" stripe :empty-text="error ? '任务尚未加载，请重试' : '当前范围没有调度到货任务'">
      <el-table-column type="expand"><template #default="{ row }">
        <div class="line-detail"><p>计划：{{ row.planId }} · 工单：{{ row.workOrderId }} · 已生成回执序号：{{ row.lastSequence }}</p>
          <el-table :data="row.lines" size="small"><el-table-column prop="lineId" label="行号" width="65" /><el-table-column prop="goodCode" label="物料编码" /><el-table-column prop="batchCode" label="批次" /><el-table-column prop="supplierCode" label="供应商" /><el-table-column prop="unit" label="单位" width="80" /><el-table-column prop="plannedQty" label="计划" /><el-table-column prop="receivedQty" label="累计实收" /><el-table-column prop="remainingQty" label="剩余" /></el-table>
          <el-alert v-if="row.deliveryError" :title="row.deliveryError" type="warning" :closable="false" class="task-error" />
        </div>
      </template></el-table-column>
      <el-table-column prop="taskId" label="调度任务号" min-width="180" />
      <el-table-column prop="workOrderId" label="PSMS 工单" min-width="160" />
      <el-table-column label="预计到达" min-width="165"><template #default="{ row }">{{ new Date(row.expectedArrivalAt).toLocaleString('zh-CN', { hour12:false }) }}</template></el-table-column>
      <el-table-column label="状态" min-width="115"><template #default="{ row }"><el-tag :type="row.status === 'FULLY_RECEIVED' ? 'success' : row.status === 'EXCEPTION' ? 'danger' : 'primary'">{{ statuses[row.status] || row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="warehouseCode" label="目的仓" min-width="100" /><el-table-column prop="wmsOrderCode" label="WMS 入库单" min-width="155" />
      <el-table-column label="回传" min-width="110"><template #default="{ row }">{{ deliveries[row.deliveryState] || row.deliveryState }}</template></el-table-column>
      <el-table-column label="操作" min-width="200" fixed="right"><template #default="{ row }">
        <el-button v-if="row.status === 'WAITING_WAREHOUSE'" link type="primary" :disabled="!canConfirm(row) || !!busy" :loading="busy === row.taskId" @click="confirm(row)">确认当前仓库</el-button>
        <el-button v-if="row.wmsOrderId" link type="primary" @click="emit('openOrder', row.wmsOrderId)">入库单 / 收货</el-button>
        <el-button v-if="row.deliveryState === 'BLOCKED'" link type="warning" :disabled="!auth.canAction('WMS:stockin','EDIT','stockin:write') || !!busy" @click="retry(row)">重试回执</el-button>
      </template></el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.psms-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.psms-heading h2{font-size:18px;margin:0 0 8px}.psms-heading p,.scope-hint,.line-detail>p{font-size:13px;color:var(--el-text-color-secondary)}.scope-hint{padding:12px;background:var(--app-surface-secondary);border-radius:8px}.line-detail{padding:12px 24px}.task-error{margin:12px 0}.psms-tasks{padding:20px}
</style>
