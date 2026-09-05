<!-- 报表导出中心｜PRD §5.9 · REQ-030/059/061/062/073/074 -->
<template>
  <div class="cockpit-page act5-page report-page">
    <header class="page-head">
      <div class="page-title">
        <span class="eyebrow">第五幕 · 报表导出</span>
        <b>报表导出中心</b>
        <small>预览 / 导出 Excel / 冻结归档 复用同一份口径快照</small>
      </div>
      <el-button @click="$router.push('/energy/cost/record')">返回成本核算</el-button>
    </header>
    <el-empty
      v-if="!access.report"
      description="报表中心仅财务与能源管理员可用（PRD 附录 K.6 · 契约 §6.8）"
      class="report-forbidden"
    >
      <template #image><el-icon :size="56"><Lock /></el-icon></template>
      <el-button type="primary" @click="$router.push('/energy/cost/record')">返回成本核算</el-button>
    </el-empty>
    <template v-else>
      <section class="report-form">
        <div class="form-title"><b>参数</b><span>REPORT PARAMS · 参数校验完全交由服务端</span></div>
        <el-select v-model="form.templateCode" placeholder="选择模板" @change="selectTemplate">
          <el-option
            v-for="item in templates"
            :key="item.templateCode"
            :label="templateLabel(item.templateCode)"
            :value="item.templateCode"
          >
            <span>{{ templateLabel(item.templateCode) }}</span>
            <small class="option-hint">{{ reportTemplateSectionSummary(item.sections) }}</small>
          </el-option>
        </el-select>
        <el-date-picker
          v-model="form.period"
          :type="daily ? 'date' : 'month'"
          :value-format="daily ? 'YYYY-MM-DD' : 'YYYY-MM'"
          placeholder="统计周期"
        />
        <el-select v-model="form.filters.zone">
          <el-option label="全部区域" value="ALL" />
          <el-option label="A 区" value="A" />
          <el-option label="B 区" value="B" />
        </el-select>
        <el-select v-model="form.filters.energyType">
          <el-option v-for="(label, value) in energyTypeLabels" :key="value" :label="label" :value="value" />
        </el-select>
        <el-input-number
          v-if="form.templateCode === 'EQUIPMENT_PROFILE'"
          v-model="form.filters.equipmentId"
          :min="1"
          placeholder="设备编号"
        />
        <el-button type="primary" :loading="loading" @click="preview">生成预览</el-button>
        <el-button :disabled="!snapshot || formalBlocked" @click="exportExcel">导出 Excel</el-button>
        <el-button :disabled="!snapshot || formalBlocked" @click="archive">冻结归档</el-button>
        <el-tooltip :content="subscription.label">
          <span><el-button :disabled="!subscription.enabled">{{ subscription.label || '报表订阅' }}</el-button></span>
        </el-tooltip>
      </section>
      <el-alert
        v-if="snapshot?.reportMeta?.period?.state === 'inProgress'"
        type="warning"
        :closable="false"
        :title="`进行中周期 · 截至 ${snapshot.reportMeta.period.asOf}`"
      />
      <el-alert
        v-if="formalBlocked"
        type="warning"
        :closable="false"
        title="当前成本版本或差异待复核：允许预览，正式导出与归档不可用"
      />
      <section class="layout">
        <ReportPreview :snapshot="snapshot" />
        <aside>
          <div class="guide">
            <b>口径复核</b>
            <p>顶部展示当前口径签名短码；同参数重复导出应与预览的完整签名一致。</p>
            <code>{{ snapshot?.signature || '尚未生成预览' }}</code>
          </div>
          <div class="guide">
            <b>版本相关性裁剪</b>
            <p>当前模板实际参与的版本由服务端在 versionSnapshots 中返回，页面不补写任何成本或建议版本号。</p>
          </div>
        </aside>
      </section>
      <ReportArchiveTable ref="archives" class="archives" />
    </template>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import downloadPlugin from '@/plugins/download'
import { archiveReport, getReportTemplates, previewReport } from '@/api/reports'
import useUserStore from '@/store/modules/user'
import ReportPreview from './components/ReportPreview.vue'
import ReportArchiveTable from './components/ReportArchiveTable.vue'
import { compactPayload, costBusinessAccess, costWriteFailurePolicy, energyTypeLabels, errorStatus, reportTemplateLabels, reportTemplateSectionSummary } from '../shared/act5'
const access = computed(() => costBusinessAccess(useUserStore().roles))
const templates = ref([])
const subscription = ref({ enabled: false, label: '报表订阅' })
const snapshot = ref(null)
const snapshotRequest = ref(null)
const loading = ref(false)
const archives = ref()
const form = reactive({ templateCode: '', period: '', filters: { zone: 'ALL', energyType: 'electricity', equipmentId: null } })
const daily = computed(() => form.templateCode === 'ENERGY_DAILY')
const templateLabel = (code) => reportTemplateLabels[code] || code
const formalBlocked = computed(() => {
  const cost = snapshot.value?.sections?.costSection
  if (!cost) return false
  if (cost.reviewState && !['reviewed', 'frozen'].includes(cost.reviewState)) return true
  return (cost.items || []).some((item) => item.reviewStatus === 'pending')
})
function selectTemplate(code) {
  const item = templates.value.find((value) => value.templateCode === code)
  form.period = item?.defaultPeriod || ''
  if (code !== 'EQUIPMENT_PROFILE') form.filters.equipmentId = null
  snapshot.value = null
  snapshotRequest.value = null
}
function payload() { return { templateCode: form.templateCode, period: form.period || null, filters: compactPayload(form.filters) } }
async function loadTemplates() {
  const data = (await getReportTemplates()).data || {}
  templates.value = data.items || []
  subscription.value = data.subscription || subscription.value
  if (templates.value.length) {
    form.templateCode = templates.value.find((item) => item.templateCode === 'ENERGY_MONTHLY')?.templateCode || templates.value[0].templateCode
    selectTemplate(form.templateCode)
  }
}
async function preview() {
  loading.value = true
  try {
    const request = payload()
    snapshot.value = (await previewReport(request)).data
    snapshotRequest.value = request
  } finally { loading.value = false }
}
async function exportExcel() {
  if (!snapshotRequest.value) return
  try {
    const result = await downloadPlugin.postBlob('/reports/export', snapshotRequest.value, `${snapshotRequest.value.templateCode}-${snapshotRequest.value.period}.xlsx`)
    if (snapshot.value?.signature && result.signature && snapshot.value.signature !== result.signature) ElMessage.error('导出签名与当前预览不一致，请刷新后复核')
  } catch (error) {
    if (costWriteFailurePolicy(errorStatus(error)).refresh) await preview()
  }
}
async function archive() {
  if (!snapshotRequest.value) return
  try {
    const detail = (await archiveReport(snapshotRequest.value)).data
    if (snapshot.value?.signature && detail?.fullSignature !== snapshot.value.signature) ElMessage.error('归档签名与当前预览不一致')
    else ElMessage.success('本次口径快照已冻结归档')
    await archives.value?.refresh?.()
  } catch (error) {
    if (costWriteFailurePolicy(errorStatus(error)).refresh) await preview()
  }
}
// REQ-073/074：无 report 权限（admin/ops/dispatch）时不发任何 /reports/* 请求，避免 403 与安全审计污染。
onMounted(() => { if(access.value.report)loadTemplates() })
</script>
<style scoped>
/* 色板与组件覆盖由 cockpit-tokens.scss 通过 .cockpit-page 提供，此处只写 layout */
.act5-page{
  min-height:calc(100vh - 84px);margin:-16px -16px 0;padding:16px 20px 40px;color:var(--ink);
  background:var(--hero-glow),var(--bg);
}
.page-head{display:flex;justify-content:space-between;align-items:flex-end;padding:8px 0 14px;border-bottom:1px solid var(--line)}
.page-title{display:flex;flex-direction:column;gap:4px}
.page-title .eyebrow{color:var(--cyan);font:10px var(--mono);letter-spacing:.14em}
.page-title b{font-family:var(--serif);font-size:22px;letter-spacing:.08em;color:var(--ink)}
.page-title small{color:var(--ink-3);font:11px var(--mono);letter-spacing:.06em}
.report-form{display:flex;flex-wrap:wrap;gap:10px;align-items:center;background:var(--panel);border:1px solid var(--line);padding:12px 14px;margin-top:12px}
.form-title{margin-right:auto;display:flex;flex-direction:column}
.form-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.form-title span{font-family:var(--mono);font-size:10px;color:var(--ink-3);letter-spacing:.08em}
.report-form :deep(.el-select),.report-form :deep(.el-date-editor){width:190px}
.option-hint{margin-left:12px;color:var(--ink-3);font-family:var(--mono);font-size:10px}
.layout{display:grid;grid-template-columns:minmax(0,1fr) 300px;gap:12px;margin-top:12px}
.layout aside{display:grid;align-content:start;gap:12px}
.guide{padding:14px 16px;background:var(--panel);border:1px solid var(--line);border-left:2px solid var(--cyan)}
.guide b{font-family:var(--serif);color:var(--ink);letter-spacing:.06em}
.guide p{color:var(--ink-2);line-height:1.6;margin:8px 0}
.guide code{display:block;word-break:break-all;color:var(--cyan);font-family:var(--mono);font-size:11px;padding:8px;background:var(--panel-2);border:1px solid var(--line-strong)}
.archives{margin-top:12px}
.report-forbidden{margin-top:16px;padding:36px 24px;background:var(--panel);border:1px solid var(--line);color:var(--ink-2)}
.report-forbidden :deep(.el-icon){color:var(--amber)}
@media(max-width:960px){.layout{grid-template-columns:1fr}}
</style>
