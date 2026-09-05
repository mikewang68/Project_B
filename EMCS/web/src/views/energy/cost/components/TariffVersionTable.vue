<!-- 单价版本表｜PRD §5.9 · REQ-051/054 -->
<template>
  <section class="panel tariff-panel">
    <header class="panel-head">
      <div class="head-title">
        <b>单价版本</b>
        <small>历史版本只读；新增版本不会隐式重算</small>
      </div>
      <el-button
        v-if="access.tariff"
        v-hasRole="['energy_mgr', 'finance']"
        type="primary"
        size="small"
        @click="open"
      >
        新增单价版本
      </el-button>
    </header>
    <el-table v-loading="loading" :data="items" size="small" class="dark-table">
      <el-table-column label="介质" width="90">
        <template #default="{row}">{{ energyTypeLabels[row.energyType] || row.energyType }}</template>
      </el-table-column>
      <el-table-column label="时段" width="80">
        <template #default="{row}">{{ periodLabel(row.touPeriod) }}</template>
      </el-table-column>
      <el-table-column prop="price" label="单价" width="100" />
      <el-table-column prop="versionNo" label="版本号" width="80" />
      <el-table-column prop="effectiveFrom" label="生效日" />
      <el-table-column prop="effectiveTo" label="失效日">
        <template #default="{row}">{{ row.effectiveTo || '长期有效' }}</template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="visible" title="新增不可变单价版本" width="580px" append-to-body custom-class="cockpit-modal">
      <el-form label-position="top">
        <el-form-item label="介质">
          <el-select v-model="form.energyType">
            <el-option v-for="(label, value) in energyTypeLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效区间">
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="生效日" end-placeholder="失效日（可空）" />
        </el-form-item>
        <template v-if="form.energyType === 'electricity'">
          <el-form-item label="峰段单价"><el-input-number v-model="form.prices.peak" :min="0.0001" :precision="4" /></el-form-item>
          <el-form-item label="平段单价"><el-input-number v-model="form.prices.flat" :min="0.0001" :precision="4" /></el-form-item>
          <el-form-item label="谷段单价"><el-input-number v-model="form.prices.valley" :min="0.0001" :precision="4" /></el-form-item>
        </template>
        <el-form-item v-else label="单一计价"><el-input-number v-model="form.prices.flatOnly" :min="0.0001" :precision="4" /></el-form-item>
        <el-form-item label="维护说明"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存版本</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createCostTariff, getCostTariffs } from '@/api/cost'
import useUserStore from '@/store/modules/user'
import { costBusinessAccess, costWriteFailurePolicy, energyTypeLabels, errorStatus } from '../../shared/act5'

const props = defineProps({ energyType: String, effectiveOn: String })
const emit = defineEmits(['changed', 'permission-blocked'])
const items = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const range = ref([])
const form = reactive({ energyType: 'electricity', prices: { peak: null, flat: null, valley: null, flatOnly: null }, remark: '' })
const access = computed(() => costBusinessAccess(useUserStore().roles))
const periodLabel = (value) => ({ peak: '峰段', flat: '平段', valley: '谷段', flatOnly: '单一' }[value] || value || '—')

async function load() {
  loading.value = true
  try {
    items.value = (await getCostTariffs({ energyType: props.energyType, includeHistory: true })).data?.items || []
  } finally { loading.value = false }
}
function open() {
  form.energyType = props.energyType || 'electricity'
  form.remark = ''
  form.prices = { peak: null, flat: null, valley: null, flatOnly: null }
  range.value = []
  visible.value = true
}
async function save() {
  saving.value = true
  try {
    const prices = form.energyType === 'electricity'
      ? { peak: form.prices.peak, flat: form.prices.flat, valley: form.prices.valley }
      : { flatOnly: form.prices.flatOnly }
    const data = (await createCostTariff({
      energyType: form.energyType,
      effectiveFrom: range.value?.[0],
      effectiveTo: range.value?.[1] || null,
      prices,
      remark: form.remark
    })).data
    visible.value = false
    await load()
    if (data?.recomputeRequired) ElMessage.warning('单价版本已保存；请显式发起成本重算')
    emit('changed', data)
  } catch (error) {
    const policy = costWriteFailurePolicy(errorStatus(error))
    if (policy.refresh) await load()
    if (policy.permissionBlocked) { visible.value = false; emit('permission-blocked') }
  } finally { saving.value = false }
}
watch(() => [props.energyType, props.effectiveOn], load)
onMounted(load)
defineExpose({ refresh: load })
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink)}
.panel-head{display:flex;align-items:center;gap:12px;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}
.head-title{margin-right:auto;display:flex;flex-direction:column}
.head-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.head-title small{color:var(--ink-3);font:10px var(--mono);margin-top:4px}
</style>
