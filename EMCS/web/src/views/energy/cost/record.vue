<!-- 成本核算中心｜PRD §5.9 · REQ-051–056、062、073/074 -->
<template>
  <div class="cockpit-page act5-page cost-page">
    <header class="page-head">
      <div class="page-title">
        <span class="eyebrow">第五幕 · 成本核算</span>
        <b>成本核算中心</b>
        <small>月度发现 → 峰平谷定位 → 冻结版本反查</small>
      </div>
      <div class="page-tag">管理核算口径，不替代财务结算</div>
    </header>

    <section class="filter-bar">
      <div class="filter-title"><b>筛选</b><span>SELECTORS · 由服务端 filters 提供默认值</span></div>
      <el-date-picker v-model="filters.statMonth" type="month" value-format="YYYY-MM" placeholder="统计月" @change="reload" />
      <el-select v-model="filters.zone" @change="reload">
        <el-option label="全部区域" value="ALL" />
        <el-option label="A 区" value="A" />
        <el-option label="B 区" value="B" />
      </el-select>
      <el-select v-model="filters.energyType" @change="reload">
        <el-option v-for="(label, value) in energyTypeLabels" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="filters.groupBy" @change="reload">
        <el-option label="按区域" value="area" />
        <el-option label="按设备" value="equipment" />
        <el-option label="按能源类型" value="energyType" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
      <el-button v-if="access.report" @click="goReports">报表导出中心</el-button>
    </section>

    <el-alert v-if="view.period" :closable="false" type="info" class="period-alert">
      <template #title>
        {{ view.period.label }} · {{ periodStateLabels[view.period.state] || view.period.state }}
        <span v-if="view.period.state === 'inProgress'"> · 截至 {{ view.period.asOf }}</span>
        <span v-if="view.period.state === 'partial'"> · 数据覆盖 {{ view.period.dataStart }}～{{ view.period.dataEnd }}</span>
      </template>
    </el-alert>

    <section class="summary-grid">
      <article class="summary-cell">
        <span>本期成本</span>
        <b>¥{{ money(view.summary?.totalCost) }}</b>
        <small>{{ costStatusLabels[view.summary?.status] || view.summary?.status || '—' }}</small>
      </article>
      <article class="summary-cell">
        <span>本期用量</span>
        <b>{{ number(view.summary?.usageQty) }}</b>
        <small>{{ energyTypeLabels[filters.energyType] || '—' }}</small>
      </article>
      <article class="summary-cell">
        <span>环比变化</span>
        <b>{{ percent(view.summary?.momPct) }}</b>
        <small>前期 ¥{{ money(view.summary?.previousMonthCost) }}</small>
      </article>
      <article class="summary-cell version-cell">
        <span>当前成本版本</span>
        <b>{{ view.summary?.currentCostVersion || '—' }}</b>
        <small>口径签名 {{ view.signature?.slice?.(0, 18) || view.signature || '—' }}</small>
      </article>
    </section>

    <CostMonthTrend :items="view.monthTrend || []" @drill="drill" @select="selectMonth" />

    <section v-if="view.anomalyEvidence" class="anomaly-evidence">
      <header>
        <b>规则 {{ view.anomalyEvidence.ruleCode }} · 冻结异常证据</b>
        <span>证据来源：{{ anomalyEvidenceSourceLabels[view.anomalyEvidence.source] || view.anomalyEvidence.source }}</span>
      </header>
      <div class="evidence-grid">
        <article><span>基线月份</span><b>{{ view.anomalyEvidence.baselineMonths?.join(' / ') || '—' }}</b></article>
        <article><span>基线峰段占比</span><b>{{ number(view.anomalyEvidence.baselinePeakShare) }}%</b></article>
        <article><span>报告月峰段占比</span><b>{{ number(view.anomalyEvidence.reportPeakShare) }}%</b></article>
        <article class="rise"><span>抬升</span><b>{{ number(view.anomalyEvidence.diffPp) }}pp</b><small>阈值 {{ number(view.anomalyEvidence.thresholdPp) }}pp</small></article>
      </div>
    </section>

    <section class="two-column">
      <TouCompositionChart :composition="view.touComposition || {}" />
      <section class="panel cost-warnings-panel">
        <header class="panel-head">
          <div class="panel-title">成本提示</div>
          <span class="panel-sub">HINTS · 仅展示服务端真实校验</span>
        </header>
        <el-alert
          v-for="warning in view.costWarnings || []"
          :key="`${warning.type}-${warning.eventId || ''}`"
          :title="warning.message || warning.note || warning.type"
          :type="warning.type === 'tariffMissing' ? 'error' : 'warning'"
          :closable="false"
          show-icon
        />
        <div v-if="!view.costWarnings?.length" class="empty-line">当前筛选无成本提示</div>
        <h4 class="section-heading">月度累计成本 Top 5</h4>
        <div
          v-for="item in view.topCostObjects || []"
          :key="`${item.objectType}-${item.objectId}`"
          class="cost-row"
        >
          <b>{{ item.objectName || item.objectCode }}</b>
          <span>¥{{ money(item.totalCost) }}</span>
        </div>
        <template v-if="(view.peakWindows || []).length">
          <h4 class="section-heading">峰段高成本时窗</h4>
          <small class="peak-windows-sub">R10 规则定位出的峰段成本高发时窗证据（REQ-052），按成本降序</small>
          <div
            v-for="window in sortedPeakWindows"
            :key="`${window.start}-${window.object?.type}-${window.object?.id}`"
            class="cost-row"
          >
            <b>{{ formatWindowRange(window.start, window.end) }}</b>
            <span>{{ formatObjectRef(window.object) }} · ¥{{ money(window.cost) }} · 单价版本 {{ window.tariffVersion }}</span>
          </div>
        </template>
      </section>
    </section>

    <section class="panel table-panel">
      <header class="panel-head">
        <div class="panel-title">成本分组</div>
        <span class="panel-sub">GROUPS · 点击对象启动三步反查</span>
        <span class="count-label">Top {{ view.topCostObjects?.length || 0 }} 由后端排序</span>
      </header>
      <el-table v-loading="loading" :data="view.groups || []" class="dark-table" @row-click="openTrace">
        <el-table-column prop="objectCode" label="对象编码" min-width="120" />
        <el-table-column prop="objectName" label="对象名称" min-width="170" />
        <el-table-column label="区域" width="110">
          <template #default="{ row }">{{ row.area?.name || row.area?.code || '全站' }}</template>
        </el-table-column>
        <el-table-column label="介质" width="120">
          <template #default="{ row }">{{ energyTypeLabels[row.energyType] || row.energyType }}</template>
        </el-table-column>
        <el-table-column label="用量" width="130" align="right">
          <template #default="{ row }">{{ number(row.usageQty) }}</template>
        </el-table-column>
        <el-table-column label="成本" width="140" align="right">
          <template #default="{ row }">¥{{ money(row.totalCost) }}</template>
        </el-table-column>
        <el-table-column label="环比" width="100" align="right">
          <template #default="{ row }">{{ percent(row.momPct) }}</template>
        </el-table-column>
        <el-table-column prop="currentCostVersion" label="当前版本" width="100" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">{{ costStatusLabels[row.status] || row.status }}</template>
        </el-table-column>
      </el-table>
    </section>

    <el-alert
      v-if="view.summary?.status === 'pendingRecompute'"
      class="pending-output"
      type="warning"
      :closable="false"
      title="当前成本版本重算待复核：页面预览可用，正式导出与归档暂不可用"
      show-icon
    />
    <section class="config-grid">
      <TariffVersionTable ref="tariffTable" :energy-type="filters.energyType" :effective-on="view.period?.periodEnd" @changed="configurationChanged" />
      <AllocationRuleTable ref="allocationTable" @changed="configurationChanged" />
    </section>
    <RecomputeDiffTable
      ref="recomputeTable"
      :stat-month="filters.statMonth"
      :energy-type="filters.energyType"
      @changed="recomputeChanged"
      @trace-diff="traceDiff"
    />

    <CostTraceDrawer
      v-model="traceVisible"
      :trace="trace"
      :loading="traceLoading"
      :can-create-suggestion="access.suggestion"
      @version-change="changeTraceVersion"
      @create-suggestion="prepareSuggestion"
      @suggestion="goSuggestion"
    />
    <el-dialog
      v-if="access.suggestion"
      v-model="suggestionVisible"
      title="将成本异常转为节能建议"
      width="640px"
      append-to-body
      :close-on-click-modal="false"
      class="cockpit-modal"
    >
      <el-alert type="info" :closable="false" title="财务窄授权：可信成本版本、口径签名与关联差异由服务端二次校验" />
      <el-form label-position="top" class="suggestion-form">
        <el-form-item label="建议标题" required><el-input v-model="suggestionForm.title" /></el-form-item>
        <el-form-item label="来源说明" required><el-input v-model="suggestionForm.sourceDescription" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="建议措施" required><el-input v-model="suggestionForm.measureContent" type="textarea" :rows="3" /></el-form-item>
        <div class="factor-inputs">
          <el-form-item v-for="item in factorFields" :key="item.key" :label="item.label" required>
            <el-input-number v-model="suggestionForm.priorityFactors[item.key]" :min="0" :max="100" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="suggestionVisible=false">取消</el-button>
        <el-button type="primary" :loading="suggestionSaving" @click="submitSuggestion">创建人工建议</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import useUserStore from '@/store/modules/user'
import { getCostMonthView, getCostTrace } from '@/api/cost'
import { createCostSuggestion } from '@/api/suggestions'
import CostMonthTrend from './components/CostMonthTrend.vue'
import TouCompositionChart from './components/TouCompositionChart.vue'
import CostTraceDrawer from './components/CostTraceDrawer.vue'
import TariffVersionTable from './components/TariffVersionTable.vue'
import AllocationRuleTable from './components/AllocationRuleTable.vue'
import RecomputeDiffTable from './components/RecomputeDiffTable.vue'
import { anomalyEvidenceSourceLabels, compactPayload, costBusinessAccess, costDrillTarget, costFiltersFromRoute, costStatusLabels, costTraceTarget, energyTypeLabels, periodStateLabels } from '../shared/act5'

const route = useRoute()
const router = useRouter()
const filters = reactive({ statMonth: '', zone: '', energyType: '', groupBy: '' })
const view = ref({})
const loading = ref(false)
const traceVisible = ref(false)
const traceLoading = ref(false)
const trace = ref(null)
const suggestionVisible = ref(false)
const suggestionSaving = ref(false)
const suggestionContext = ref(null)
const suggestionForm = reactive({ title: '', sourceDescription: '', measureContent: '', priorityFactors: { energyScale: 50, costImpact: 50, duration: 50, implementationDifficulty: 50, safetyImpact: 50 } })
const factorFields = [{key:'energyScale',label:'能耗规模'},{key:'costImpact',label:'成本影响'},{key:'duration',label:'持续时间'},{key:'implementationDifficulty',label:'实施难度'},{key:'safetyImpact',label:'安全影响'}]
const tariffTable = ref()
const allocationTable = ref()
const recomputeTable = ref()
const access = computed(() => costBusinessAccess(useUserStore().roles))
let activeTraceQuery = null
let requestSequence = 0

const number = (value) => value == null ? '—' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const money = number
const percent = (value) => value == null ? '—' : `${Number(value) >= 0 ? '+' : ''}${Number(value).toFixed(2)}%`

// 后端 peakWindows[].object = { type, id, code, name }；直接插值会渲染 [object Object]
const formatObjectRef = (object) => {
  if (!object || typeof object !== 'object') return object || '全站'
  if (object.name && object.code) return `${object.name}（${object.code}）`
  return object.name || object.code || '全站'
}
// start/end 为 ISO 时间串（"2026-06-17T09:00:00"）；同日只写一次日期
const parseIsoParts = (value) => {
  if (!value || typeof value !== 'string') return null
  const [datePart, timePart = ''] = value.split('T')
  const [, month, day] = datePart.split('-')
  const hhmm = timePart.slice(0, 5)
  return month && day ? { date: `${month}-${day}`, time: hhmm } : null
}
const formatWindowRange = (start, end) => {
  const s = parseIsoParts(start)
  const e = parseIsoParts(end)
  if (!s || !e) return `${start || ''} → ${end || ''}`
  if (s.date === e.date) return `${s.date} ${s.time} → ${e.time}`
  return `${s.date} ${s.time} → ${e.date} ${e.time}`
}
// 后端顺序不承诺按成本降序；页面按 REQ-052 "定位高成本时窗" 语义显式再排一次
const sortedPeakWindows = computed(() => [...(view.value.peakWindows || [])].sort((a, b) => Number(b.cost || 0) - Number(a.cost || 0)))

async function load(query = {}) {
  const sequence = ++requestSequence
  loading.value = true
  try {
    const response = await getCostMonthView(compactPayload(query))
    if (sequence !== requestSequence) return
    view.value = response.data || {}
    Object.assign(filters, costFiltersFromRoute({}, view.value.filters || {}))
  } finally { if (sequence === requestSequence) loading.value = false }
}
async function reload() {
  const query = compactPayload({ ...filters, focus: route.query.focus, sourceEventId: route.query.sourceEventId })
  const current = compactPayload(route.query)
  if (JSON.stringify(query) === JSON.stringify(current)) return load(query)
  await router.replace({ query })
}
async function drill(drillParams) {
  const target = costDrillTarget(drillParams)
  await router.push(target)
}
async function selectMonth(statMonth) { filters.statMonth = statMonth; await reload() }
async function openTrace(row) {
  activeTraceQuery = costTraceTarget({ ...row, statMonth: filters.statMonth, energyType: row.energyType || filters.energyType })
  traceVisible.value = true
  await fetchTrace(activeTraceQuery)
}
async function fetchTrace(query) {
  traceLoading.value = true
  try { trace.value = (await getCostTrace(query)).data }
  finally { traceLoading.value = false }
}
async function changeTraceVersion(costVersion) { await fetchTrace({ ...activeTraceQuery, costVersion }) }
function configurationChanged() { recomputeTable.value?.refresh?.() }
async function recomputeChanged() { await reload(); await Promise.all([tariffTable.value?.refresh?.(), allocationTable.value?.refresh?.()]) }
async function traceDiff(diff, recompute, costVersion) {
  activeTraceQuery = costTraceTarget({ statMonth: filters.statMonth, objectType: diff.objectType, objectId: diff.objectId, energyType: filters.energyType }, costVersion)
  traceVisible.value = true
  await fetchTrace(activeTraceQuery)
}
function prepareSuggestion(sourceContext) {
  if (!access.value.suggestion) return
  suggestionContext.value = sourceContext
  Object.assign(suggestionForm, {
    title: `${sourceContext.statMonth} ${energyTypeLabels[sourceContext.energyType] || sourceContext.energyType}成本异常核查`,
    sourceDescription: `成本版本 ${sourceContext.costVersion} 的冻结证据待复核`,
    measureContent: '核查峰段用量、适用单价版本与分摊口径，并记录人工分析结论'
  })
  suggestionVisible.value = true
}
async function submitSuggestion() {
  const context = suggestionContext.value
  if (!context || !suggestionForm.title.trim() || !suggestionForm.sourceDescription.trim() || !suggestionForm.measureContent.trim()) { ElMessage.warning('请完整填写建议内容'); return }
  suggestionSaving.value = true
  try {
    const objectType = context.objectType
    const body = {
      title: suggestionForm.title,
      sourceDescription: suggestionForm.sourceDescription,
      objectType,
      objectId: context.objectId,
      areaId: objectType === 'system' ? null : context.areaId,
      equipmentId: objectType === 'equipment' ? context.objectId : null,
      measureContent: suggestionForm.measureContent,
      templateSnapshot: {
        templateCode: null, templateName: '成本异常人工分析', category: '成本优化', sourceRuleCode: null,
        applicableObjectType: objectType, actionContent: suggestionForm.measureContent,
        requiredData: '成本版本、用量、单价与分摊冻结证据', estimatedSaving: '待人工核验',
        costImpact: '待人工核验', reliabilityImpact: '仅人工分析', verificationMethod: '对比后续成本版本与用量证据',
        defaultImplementationDifficulty: suggestionForm.priorityFactors.implementationDifficulty,
        defaultSafetyImpact: suggestionForm.priorityFactors.safetyImpact, enabled: true, version: 1
      },
      priorityFactors: { ...suggestionForm.priorityFactors }
    }
    const data = (await createCostSuggestion(body, context)).data || {}
    const suggestionId = data.suggestion?.suggestionId || data.relatedSuggestionId
    if (!suggestionId) throw new Error('接口未返回建议编号')
    suggestionVisible.value = false
    trace.value = { ...trace.value, relatedSuggestionId: suggestionId }
    ElMessage.success(data.created ? '成本异常已转为人工建议' : '该成本证据已有建议，已打开原记录')
    goSuggestion(suggestionId)
  } finally { suggestionSaving.value = false }
}
function goSuggestion(suggestionId) { router.push({ path: '/energy/alert/suggestion', query: { suggestionId } }) }
function goReports() { router.push('/energy/cost/report') }
watch(() => route.query, (query) => load(costFiltersFromRoute(query, {})), { deep: true, immediate: true })
</script>

<style scoped>
/* 色板与 Element Plus 通用 :deep 覆盖由 cockpit-tokens.scss 通过 .cockpit-page 提供，此处只写 layout */
.act5-page{
  min-height:calc(100vh - 84px);margin:-16px -16px 0;padding:16px 20px 40px;color:var(--ink);
  background:var(--bg);
}
.page-head{display:flex;justify-content:space-between;align-items:flex-end;padding:8px 0 14px;border-bottom:1px solid var(--line)}
.page-title{display:flex;flex-direction:column;gap:4px}
.page-title .eyebrow{color:var(--amber);font:12px var(--mono);letter-spacing:.14em}
.page-title b{font-family:var(--serif);font-size:22px;letter-spacing:.08em;color:var(--ink)}
.page-title small{color:var(--ink-3);font:11px var(--mono);letter-spacing:.06em}
.page-tag{color:var(--amber);border:1px solid var(--amber);background:var(--amber-tint);padding:6px 10px;font:12px var(--mono)}
.filter-bar{display:flex;align-items:center;gap:10px;padding:12px 14px;background:var(--panel);border:1px solid var(--line);margin-top:12px;flex-wrap:wrap}
.filter-title{margin-right:auto;display:flex;flex-direction:column}
.filter-title b{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink)}
.filter-title span,.panel-sub,.count-label{font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.08em}
.filter-bar :deep(.el-select){width:150px}
.filter-bar :deep(.el-date-editor){width:160px}
.period-alert{margin-top:12px}
.summary-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:12px}
.summary-cell{border:1px solid var(--line);border-left:2px solid var(--cyan);background:var(--panel);padding:12px 14px;display:grid;grid-template-columns:1fr;gap:4px}
.summary-cell span{color:var(--ink-2);font:11px var(--mono)}
.summary-cell b{font:24px var(--mono);color:var(--ink)}
.summary-cell small{color:var(--ink-3);font:12px var(--mono)}
.summary-cell.version-cell{border-left-color:var(--amber)}
.anomaly-evidence{margin-top:12px;padding:14px 16px;background:var(--panel);border:1px solid var(--red);border-left:2px solid var(--red)}
.anomaly-evidence header{display:flex;justify-content:space-between;align-items:center}
.anomaly-evidence header b{font-family:var(--serif);color:var(--red);letter-spacing:.06em}
.anomaly-evidence header span{color:var(--ink-3);font:12px var(--mono)}
.evidence-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:12px}
.evidence-grid article{background:var(--panel-2);padding:10px 12px;border:1px solid var(--line)}
.evidence-grid span,.evidence-grid small{color:var(--ink-3);font:12px var(--mono);display:block}
.evidence-grid b{color:var(--ink);font:20px var(--mono);display:block;margin-top:4px}
.evidence-grid .rise b{color:var(--red)}
.two-column,.config-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px}
.panel-head{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--line);padding-bottom:8px;margin-bottom:10px}
.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em;color:var(--ink);margin-right:auto}
.count-label{margin-left:auto}
.cost-warnings-panel{display:flex;flex-direction:column;gap:8px}
.section-heading{margin:12px 0 4px;font-family:var(--serif);font-size:13px;letter-spacing:.05em;color:var(--ink-2)}
.peak-windows-sub{display:block;color:var(--ink-3);font:12px var(--mono);margin:0 0 8px}
.cost-row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--line);color:var(--ink)}
.cost-row span{color:var(--ink-2);font-family:var(--mono);font-size:11px}
.empty-line{padding:14px;text-align:center;color:var(--ink-3);border:1px dashed var(--line);font:12px var(--mono)}
.table-panel{margin-top:12px}
.pending-output{margin:12px 0}
.suggestion-form{margin-top:14px}
.factor-inputs{display:grid;grid-template-columns:repeat(5,1fr);gap:8px}
.factor-inputs :deep(.el-input-number){width:100%}
@media(max-width:1180px){.summary-grid{grid-template-columns:repeat(2,1fr)}.evidence-grid{grid-template-columns:repeat(2,1fr)}.two-column,.config-grid{grid-template-columns:1fr}.factor-inputs{grid-template-columns:repeat(3,1fr)}}
@media(max-width:640px){.summary-grid,.evidence-grid,.factor-inputs{grid-template-columns:1fr}}
</style>
