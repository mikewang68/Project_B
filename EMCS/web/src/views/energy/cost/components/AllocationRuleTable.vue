<!-- 分摊规则表｜PRD §5.9 · REQ-051/054 -->
<template>
  <section class="panel allocation-panel">
    <header class="panel-head">
      <div class="head-title">
        <b>分摊规则</b>
        <small>历史成本反查始终读取版本行冻结快照</small>
      </div>
      <el-button
        v-if="access.allocation"
        v-hasRole="['finance']"
        type="primary"
        size="small"
        @click="visible=true"
      >
        新增规则版本
      </el-button>
    </header>
    <el-table v-loading="loading" :data="items" size="small" class="dark-table">
      <el-table-column prop="ruleName" label="规则名称" />
      <el-table-column label="适用范围" width="110">
        <template #default="{row}">{{ scopeLabel(row.scope) }}</template>
      </el-table-column>
      <el-table-column label="分摊方法" width="110">
        <template #default="{row}">{{ allocationMethodLabels[row.method] || row.method }}</template>
      </el-table-column>
      <el-table-column label="配置" min-width="200" show-overflow-tooltip>
        <template #default="{row}"><code>{{ displayConfig(row.config) }}</code></template>
      </el-table-column>
      <el-table-column prop="versionNo" label="版本号" width="80" />
      <el-table-column prop="effectiveFrom" label="生效日" />
      <el-table-column prop="effectiveTo" label="失效日">
        <template #default="{row}">{{ row.effectiveTo || '长期有效' }}</template>
      </el-table-column>
      <el-table-column label="影响表计" width="100">
        <template #default="{row}">{{ row.affectedMeters?.length || 0 }} 个</template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="visible" title="新增不可变分摊规则" width="620px" append-to-body custom-class="cockpit-modal">
      <el-form label-position="top">
        <el-form-item label="规则名称"><el-input v-model="form.ruleName" /></el-form-item>
        <el-form-item label="适用范围"><el-input v-model="form.scope" placeholder="area / equipment / meter" /></el-form-item>
        <el-form-item label="分摊方法">
          <el-select v-model="form.method">
            <el-option v-for="(label, value) in allocationMethodLabels" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="生效日"><el-date-picker v-model="form.effectiveFrom" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="分摊配置 JSON"><el-input v-model="configText" type="textarea" :rows="5" placeholder='{"allocations":[{"objectId":1,"ratio":0.5}]}' /></el-form-item>
        <el-form-item label="维护说明"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createAllocationRule, getAllocationRules } from '@/api/cost'
import useUserStore from '@/store/modules/user'
import { allocationMethodLabels, costBusinessAccess, costWriteFailurePolicy, errorStatus } from '../../shared/act5'

const emit = defineEmits(['changed', 'permission-blocked'])
const items = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const configText = ref('')
const form = reactive({ ruleName: '', scope: 'area', method: 'ratio', effectiveFrom: '', remark: '' })
const access = computed(() => costBusinessAccess(useUserStore().roles))
const displayConfig = (value) => JSON.stringify(value || {})
const scopeLabel = (value) => ({ area: '区域', equipment: '设备', meter: '计量点' }[value] || value || '—')

async function load() {
  loading.value = true
  try { items.value = (await getAllocationRules({ includeHistory: true })).data?.items || [] }
  finally { loading.value = false }
}
async function save() {
  let config
  try { config = JSON.parse(configText.value) }
  catch { ElMessage.error('分摊配置必须是合法 JSON'); return }
  saving.value = true
  try {
    const data = (await createAllocationRule({ ...form, effectiveTo: null, config })).data
    visible.value = false
    await load()
    emit('changed', data)
  } catch (error) {
    const policy = costWriteFailurePolicy(errorStatus(error))
    if (policy.refresh) await load()
    if (policy.permissionBlocked) { visible.value = false; emit('permission-blocked') }
  } finally { saving.value = false }
}
onMounted(load)
defineExpose({ refresh: load })
</script>

<style scoped>
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px;color:var(--ink)}
.panel-head{display:flex;align-items:center;gap:12px;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}
.head-title{margin-right:auto;display:flex;flex-direction:column}
.head-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.head-title small{color:var(--ink-3);font:10px var(--mono);margin-top:4px}
code{color:var(--ink-2);font-family:var(--mono);font-size:11px}
</style>
