<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { listInventoryCounts, reviewInventoryCount, submitInventoryCount,
  type CountState, type CountSubmission, type InventoryBalance, type InventoryCount } from '@/api/inventory'
import { createRequestIntentStore } from '@/api/requestIntent'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ active:boolean; balance:InventoryBalance|null }>()
const emit = defineEmits<{ close:[]; submitted:[]; posted:[] }>()
const auth = useAuthStore()
const canSubmit = computed(() => auth.canAction('WMS:inventory','ADD','inventory:write'))
const canReview = computed(() => auth.canAction('WMS:inventory','CONFIRM','inventory:write'))
const scope = computed(() => JSON.stringify([auth.user?.id,auth.user?.tenant.companyCode,
  auth.user?.tenant.currentWarehouse.id,auth.user?.tenant.currentOwner.id]))
const intents = createRequestIntentStore(() => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`)
const rows = ref<InventoryCount[]>([])
const state = ref<CountState|'ALL'>('PENDING')
const loading = ref(false), saving = ref(false), done = ref(false)
const error = ref(''), countError = ref(''), reviewError = ref('')
const detail = ref<InventoryCount|null>(null)
const countForm = reactive({actualQuantity:'',lossQuantity:'0',missingQuantity:'0',reason:''})
const reviewNote = ref('')
const stateLabel:Record<CountState,string> = {PENDING:'待复核',POSTED:'已入账',REJECTED:'已退回'}
const decimal = /^\d{1,15}(?:\.\d{1,3})?$/
let generation = 0

function qty(value:number) {
  return Number(value).toLocaleString('zh-CN',{maximumFractionDigits:3})
}

async function refresh(more = false) {
  const turn=++generation
  const before = more ? rows.value.at(-1)?.id : undefined
  loading.value=true
  error.value=''
  if(!more) { rows.value=[];done.value=false }
  try {
    const page=await listInventoryCounts(state.value==='ALL'?undefined:state.value,before)
    if(turn!==generation) return
    rows.value=more?[...rows.value,...page]:page
    done.value=page.length<100
  } catch(reason) {
    if(turn===generation) error.value=reason instanceof ApiError?reason.message:'盘点单加载失败'
  } finally {
    if(turn===generation) loading.value=false
  }
}

watch(() => props.balance, () => {
  Object.assign(countForm,{actualQuantity:'',lossQuantity:'0',missingQuantity:'0',reason:''})
  countError.value=''
})
watch([scope,() => props.active,state], ([nextScope,active],previous) => {
  if(nextScope!==previous?.[0]) {
    rows.value=[]
    detail.value=null
    if(props.balance) emit('close')
  }
  if(active) void refresh()
  else { generation++;loading.value=false }
},{immediate:true})

async function submit() {
  const balance=props.balance
  if(!balance||saving.value||!canSubmit.value) return
  const actualQuantity=countForm.actualQuantity.trim(),lossQuantity=countForm.lossQuantity.trim()||'0'
  const missingQuantity=countForm.missingQuantity.trim()||'0'
  if(![actualQuantity,lossQuantity,missingQuantity].every(value=>decimal.test(value))) {
    countError.value='请输入非负数量，最多三位小数。'
    return
  }
  const payload:Omit<CountSubmission,'idempotencyKey'> = {balanceId:balance.id,balanceVersion:balance.version,
    actualQuantity,lossQuantity,missingQuantity,reason:countForm.reason.trim()}
  const identity=scope.value
  const intent={scope:identity,payload}
  saving.value=true
  countError.value=''
  try {
    const result=await submitInventoryCount({...payload,idempotencyKey:intents.forPayload(intent)})
    intents.completed(intent)
    if(scope.value!==identity) return
    ElMessage.success(`盘点单 PD-${result.id} 已保存，等待复核`)
    state.value='PENDING'
    emit('submitted')
  } catch(reason) {
    if(scope.value===identity) countError.value=reason instanceof ApiError?reason.message:'提交结果未知，请用原内容重试'
  } finally { saving.value=false }
}

function openDetail(row:InventoryCount) {
  detail.value=row
  reviewNote.value=''
  reviewError.value=''
}

async function review(decision:'POST'|'REJECT') {
  const row=detail.value
  if(!row||row.state!=='PENDING'||saving.value||!canReview.value) return
  const identity=scope.value
  saving.value=true
  reviewError.value=''
  try {
    const result=await reviewInventoryCount(row.id,decision,reviewNote.value.trim())
    if(scope.value!==identity) return
    detail.value=result
    ElMessage.success(`盘点单 PD-${row.id} ${stateLabel[result.state]}`)
    if(decision==='POST') emit('posted')
    await refresh()
  } catch(reason) {
    if(scope.value===identity) reviewError.value=reason instanceof ApiError?reason.message:'复核结果未知，请刷新后核对'
  } finally { saving.value=false }
}
</script>

<template>
  <div v-if="active" class="count-panel">
    <div class="count-toolbar">
      <p>从库存余额选择货品和库位发起盘点。保存实盘量后，由有确认权限的人员复核入账。</p>
      <el-select v-model="state" aria-label="盘点单状态" class="count-filter">
        <el-option label="待复核" value="PENDING" />
        <el-option label="已入账" value="POSTED" />
        <el-option label="已退回" value="REJECTED" />
        <el-option label="全部状态" value="ALL" />
      </el-select>
      <el-button @click="refresh()">刷新盘点单</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" stripe row-key="id" empty-text="当前范围没有符合条件的盘点单">
      <el-table-column label="盘点单" min-width="96"><template #default="{row}">PD-{{ row.id }}</template></el-table-column>
      <el-table-column label="货品" min-width="175"><template #default="{row}">{{ row.goodCode }} · {{ row.goodName }}</template></el-table-column>
      <el-table-column prop="locationCode" label="库位" min-width="95" />
      <el-table-column label="账面 / 实盘" min-width="140" align="right">
        <template #default="{row}">{{ qty(row.bookQty) }} / {{ qty(row.actualQty) }} {{ row.unit }}</template>
      </el-table-column>
      <el-table-column label="盘盈 / 盘亏" min-width="110" align="right">
        <template #default="{row}">{{ row.differenceQty>0?'+':'' }}{{ qty(row.differenceQty) }} {{ row.unit }}</template>
      </el-table-column>
      <el-table-column label="损耗 / 缺失" min-width="120" align="right">
        <template #default="{row}">{{ qty(row.lossQty) }} / {{ qty(row.missingQty) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="95">
        <template #default="{row}"><el-tag :type="row.state==='POSTED'?'success':row.state==='PENDING'?'warning':'info'">{{ stateLabel[row.state as CountState] }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="88">
        <template #default="{row}"><el-button link type="primary" @click="openDetail(row)">{{ row.state==='PENDING'&&canReview?'复核':'详情' }}</el-button></template>
      </el-table-column>
    </el-table>
    <el-button v-if="rows.length&&!done" :loading="loading" @click="refresh(true)">加载更多</el-button>
  </div>

  <el-dialog :model-value="!!balance" title="登记库存盘点" width="min(580px, 95vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="(value:boolean) => { if(!value&&!saving) emit('close') }">
    <template v-if="balance">
      <p>{{ balance.goodCode }} · {{ balance.goodName }}，库位 {{ balance.locationCode }}，批次 {{ balance.batchCode || '无' }}</p>
      <p>账面总量 <strong>{{ qty(balance.totalQty) }} {{ balance.unit }}</strong>。如有已分配和冻结货品，一并计入现场实盘总量。</p>
    </template>
    <el-alert v-if="countError" :title="countError" type="error" :closable="false" show-icon />
    <el-form label-position="top" :disabled="saving">
      <el-form-item label="实盘总量" required><el-input v-model="countForm.actualQuantity" inputmode="decimal" placeholder="输入现场实际数量" /></el-form-item>
      <div class="count-fields">
        <el-form-item label="已确认损耗"><el-input v-model="countForm.lossQuantity" inputmode="decimal" /></el-form-item>
        <el-form-item label="已确认缺失"><el-input v-model="countForm.missingQuantity" inputmode="decimal" /></el-form-item>
      </div>
      <el-form-item label="差异原因"><el-input v-model="countForm.reason" type="textarea" :maxlength="256" placeholder="账实不符时必填" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="emit('close')">取消</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="saving" @click="submit">保存待复核</el-button>
    </template>
  </el-dialog>

  <el-dialog :model-value="!!detail" :title="detail ? `盘点单 PD-${detail.id}` : '盘点单'" width="min(640px, 95vw)"
    :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="(value:boolean) => { if(!value&&!saving) detail=null }">
    <template v-if="detail">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="货品与库位">{{ detail.goodCode }} · {{ detail.goodName }} / {{ detail.locationCode }}</el-descriptions-item>
        <el-descriptions-item label="库存明细">{{ detail.batchCode || '无批次' }} / {{ detail.qualityType }} / {{ detail.supplierCode || '无供应商' }} / {{ detail.lpn || '无容器' }}</el-descriptions-item>
        <el-descriptions-item label="账面 / 实盘 / 差额">{{ qty(detail.bookQty) }} / {{ qty(detail.actualQty) }} / {{ qty(detail.differenceQty) }} {{ detail.unit }}</el-descriptions-item>
        <el-descriptions-item label="损耗 / 缺失">{{ qty(detail.lossQty) }} / {{ qty(detail.missingQty) }} {{ detail.unit }}</el-descriptions-item>
        <el-descriptions-item label="登记">{{ detail.createdBy }} · {{ new Date(detail.createdAt).toLocaleString('zh-CN') }}</el-descriptions-item>
        <el-descriptions-item label="差异原因">{{ detail.reason || '无' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ stateLabel[detail.state] }}{{ detail.operationCode ? ' · '+detail.operationCode : '' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.reviewedAt" label="复核">{{ detail.reviewedBy }} · {{ new Date(detail.reviewedAt).toLocaleString('zh-CN') }} · {{ detail.reviewNote || '无备注' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" show-icon />
      <el-form v-if="detail.state==='PENDING'&&canReview" label-position="top">
        <el-form-item label="复核说明"><el-input v-model="reviewNote" type="textarea" :maxlength="256" :disabled="saving" placeholder="退回时必填。确认入账将按盘点差额调整库存。" /></el-form-item>
      </el-form>
    </template>
    <template #footer>
      <el-button :disabled="saving" @click="detail=null">关闭</el-button>
      <template v-if="detail?.state==='PENDING'&&canReview">
        <el-button :disabled="!reviewNote.trim()" :loading="saving" @click="review('REJECT')">退回重盘</el-button>
        <el-button type="primary" :loading="saving" @click="review('POST')">确认入账</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.count-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin:8px 0 14px; }
.count-toolbar p { flex:1 1 340px; margin:0; color:var(--el-text-color-secondary); }
.count-filter { width:135px; }
.count-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.count-panel > .el-button { margin-top:12px; }
.el-descriptions + .el-form, .el-alert { margin:12px 0; }
</style>
