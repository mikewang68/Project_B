<!-- 重算版本与差异｜PRD §5.9 · REQ-055 -->
<template>
  <section class="panel recompute-panel">
    <header class="panel-head">
      <div class="head-title">
        <b>重算版本与差异</b>
        <small>旧版本 → 新版本 · diffSummary 冻结为审计证据</small>
      </div>
      <el-button
        v-if="access.recompute"
        v-hasRole="['energy_mgr', 'finance']"
        type="primary"
        size="small"
        @click="createVisible=true"
      >
        发起重算
      </el-button>
    </header>
    <el-table
      v-loading="loading"
      :data="items"
      class="dark-table"
      row-key="recomputeId"
      @expand-change="loadDetail"
    >
      <el-table-column type="expand">
        <template #default="{row}">
          <div v-loading="row.detailLoading" class="diff-expand">
            <div class="diff-caption">对象级差异 diffSummary</div>
            <el-table :data="row.diffSummary||[]" size="small" class="dark-table nested">
              <el-table-column prop="objectCode" label="对象编码" />
              <el-table-column prop="metric" label="指标" />
              <el-table-column prop="oldValue" label="旧值" />
              <el-table-column prop="newValue" label="新值" />
              <el-table-column prop="deltaValue" label="差额" />
              <el-table-column prop="deltaPct" label="差异率" />
              <el-table-column label="反查证据" width="200">
                <template #default="{row:diff}">
                  <el-button link type="primary" @click="$emit('trace-diff',diff,row,row.oldCostVersion)">旧版本反查</el-button>
                  <el-button link type="primary" @click="$emit('trace-diff',diff,row,row.newCostVersion)">新版本反查</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="row.traceLinks?.length" class="trace-links">冻结反查链接：共 {{ row.traceLinks.length }} 个对象</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="recomputeId" label="编号" width="80" />
      <el-table-column label="版本变更">
        <template #default="{row}">{{ row.oldCostVersion }} → {{ row.newCostVersion }}</template>
      </el-table-column>
      <el-table-column prop="triggerReason" label="触发原因" show-overflow-tooltip />
      <el-table-column label="复核状态" width="120">
        <template #default="{row}">{{ reviewStateLabels[row.reviewStatus] || row.reviewStatus }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{row}">
          <el-button
            v-if="row.reviewStatus==='pending' && access.review"
            v-hasRole="['finance']"
            link
            type="primary"
            @click="openReview(row)"
          >
            财务复核
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="发起完整月成本重算" width="520px" append-to-body class="cockpit-modal">
      <el-form label-position="top">
        <el-form-item label="统计月"><el-input :model-value="statMonth" disabled /></el-form-item>
        <el-form-item label="介质"><el-input :model-value="energyTypeLabels[energyType] || energyType" disabled /></el-form-item>
        <el-form-item label="触发原因" required><el-input v-model="createForm.triggerReason" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="指定单价版本（可选）"><el-input-number v-model="createForm.tariffVersion" :min="1" /></el-form-item>
        <el-form-item label="指定分摊版本（可选）"><el-input-number v-model="createForm.allocRuleVersion" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">确认重算</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="财务复核" width="500px" append-to-body class="cockpit-modal">
      <el-form label-position="top">
        <el-form-item label="复核结论">
          <el-radio-group v-model="reviewForm.action">
            <el-radio value="approve">通过</el-radio>
            <el-radio value="reject">拒绝并恢复旧版本</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复核说明" required><el-input v-model="reviewForm.remark" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="review">提交复核</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createCostRecomputation, getCostRecomputation, getCostRecomputations, reviewCostRecomputation } from '@/api/cost'
import useUserStore from '@/store/modules/user'
import { compactPayload, costBusinessAccess, costWriteFailurePolicy, energyTypeLabels, errorStatus, reviewStateLabels } from '../../shared/act5'

const props = defineProps({ statMonth: String, energyType: String })
const emit = defineEmits(['changed', 'trace-diff', 'permission-blocked'])
const items = ref([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const reviewVisible = ref(false)
const createForm = reactive({ triggerReason: '', tariffVersion: null, allocRuleVersion: null })
const reviewForm = reactive({ recomputeId: null, action: 'approve', remark: '' })
const access = computed(() => costBusinessAccess(useUserStore().roles))

async function load() {
  if (!props.statMonth || !props.energyType) return
  loading.value = true
  try {
    items.value = (await getCostRecomputations({ statMonth: props.statMonth, energyType: props.energyType, pageNum: 1, pageSize: 100 })).data?.items || []
  } finally { loading.value = false }
}
async function loadDetail(row, expandedRows) {
  if (!expandedRows.some((item) => item.recomputeId === row.recomputeId) || row.detailLoaded) return
  row.detailLoading = true
  try {
    Object.assign(row, (await getCostRecomputation(row.recomputeId)).data || {}, { detailLoaded: true })
  } finally { row.detailLoading = false }
}
async function guarded(action, close) {
  saving.value = true
  try {
    const data = await action()
    close()
    await load()
    emit('changed', data)
  } catch (error) {
    const policy = costWriteFailurePolicy(errorStatus(error))
    if (policy.refresh) { await load(); emit('changed', null) }
    if (policy.permissionBlocked) { close(); emit('permission-blocked') }
  } finally { saving.value = false }
}
function create() {
  if (!createForm.triggerReason.trim()) { ElMessage.warning('请填写触发原因'); return }
  return guarded(
    async () => (await createCostRecomputation(compactPayload({ statMonth: props.statMonth, energyType: props.energyType, ...createForm }))).data,
    () => { createVisible.value = false }
  )
}
function openReview(row) {
  reviewForm.recomputeId = row.recomputeId
  reviewForm.action = 'approve'
  reviewForm.remark = ''
  reviewVisible.value = true
}
function review() {
  if (!reviewForm.remark.trim()) { ElMessage.warning('复核说明必填'); return }
  return guarded(
    async () => (await reviewCostRecomputation(reviewForm.recomputeId, { action: reviewForm.action, remark: reviewForm.remark })).data,
    () => { reviewVisible.value = false }
  )
}
watch(() => [props.statMonth, props.energyType], load, { immediate: true })
defineExpose({ refresh: load })
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink);margin-top:12px}
.panel-head{display:flex;align-items:center;gap:12px;border-bottom:1px solid var(--line);padding-bottom:8px;margin-bottom:10px}
.head-title{margin-right:auto;display:flex;flex-direction:column}
.head-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.head-title small{color:var(--ink-3);font:12px var(--mono);margin-top:4px}
.dark-table.nested{margin-top:8px}
.diff-expand{padding:8px 12px;background:var(--panel-2)}
.diff-caption{color:var(--ink-3);font:12px var(--mono);letter-spacing:.08em;margin-bottom:4px}
.trace-links{margin-top:10px;color:var(--ink-2);font:12px var(--mono)}
</style>
