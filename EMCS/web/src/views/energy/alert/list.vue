<!--
  异常告警｜第三幕入口
  REQ-039 三档级别 · REQ-040 规则版本冻结 · REQ-041 状态流转
  REQ-042 合并抑制 · REQ-043 通知留痕 · REQ-044 统计复盘
-->
<template>
  <div class="act3-page alert-page cockpit-page">
    <header class="filter-bar">
      <div class="filter-title">
        <b>异常告警</b>
        <span>ALERT REVIEW · REQ-039–044</span>
      </div>
      <el-select v-model="filters.level" clearable placeholder="级别" size="small">
        <el-option v-for="item in levelOptions" :key="item.value" v-bind="item" />
      </el-select>
      <el-select v-model="filters.status" clearable placeholder="状态" size="small">
        <el-option v-for="item in statusOptions" :key="item.value" v-bind="item" />
      </el-select>
      <el-input v-model="filters.ruleCode" clearable placeholder="规则编号" size="small" />
      <el-select v-model="filters.zone" clearable placeholder="装卸区" size="small">
        <el-option label="全部区域" value="ALL" />
        <el-option label="A 区" value="A" />
        <el-option label="B 区" value="B" />
      </el-select>
      <el-button type="primary" size="small" :loading="loading" @click="search">查询</el-button>
    </header>

    <section class="metric-grid">
      <article class="metric">
        <span>事件总数</span><b>{{ statistics.total ?? '—' }}</b><small>筛选口径</small>
      </article>
      <article class="metric metric-ok">
        <span>关闭率</span><b>{{ formatRate(statistics.closeRate) }}</b><small>REQ-044</small>
      </article>
      <article class="metric metric-warn">
        <span>误报率</span><b>{{ formatRate(statistics.falsePositiveRate) }}</b><small>误报关闭单列</small>
      </article>
      <article class="metric">
        <span>平均处理时长</span><b>{{ formatHours(statistics.averageHandleHours) }}</b><small>从产生到关闭</small>
      </article>
    </section>

    <section class="panel list-panel">
      <header class="panel-head">
        <div>
          <div class="panel-title">告警事件列表</div>
          <div class="panel-sub">规则版本冻结 · 合并次数可追溯</div>
        </div>
        <span class="count-label">{{ total }} EVENTS</span>
      </header>

      <el-table v-loading="loading" :data="items" class="dark-table" empty-text="暂无数据，检查筛选条件或规则状态" @row-click="openDetail">
        <el-table-column label="级别" width="78">
          <template #default="{ row }">
            <span class="level-tag" :class="`level-${row.level}`">{{ levelLabels[row.level] || row.level }}</span>
          </template>
        </el-table-column>
        <el-table-column label="规则" min-width="190">
          <template #default="{ row }">
            <div class="primary-cell">{{ row.ruleCode }} · {{ row.ruleName }}</div>
            <div class="secondary-cell">{{ row.ruleCategory }} · v{{ row.ruleVersion }}</div>
          </template>
        </el-table-column>
        <el-table-column label="涉及对象" min-width="170">
          <template #default="{ row }">
            <div class="primary-cell">{{ row.object?.name || row.object?.code }}</div>
            <div class="secondary-cell">{{ row.area?.name || row.area?.code }} · {{ row.object?.code }}</div>
          </template>
        </el-table-column>
        <el-table-column label="首次 / 最近触发" min-width="205">
          <template #default="{ row }">
            <div class="mono-cell">{{ formatDateTime(row.firstOccurredAt) }}</div>
            <div class="secondary-cell">{{ formatDateTime(row.lastOccurredAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="occurCount" label="合并" width="72" align="center" />
        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <span class="status-tag">{{ alertStatusLabels[row.status] || row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="assignedTo" label="处理人" width="110" show-overflow-tooltip />
      </el-table>

      <el-pagination
        v-if="total"
        v-model:current-page="filters.pageNum"
        v-model:page-size="filters.pageSize"
        class="pager"
        layout="prev, pager, next, total"
        :total="total"
        @current-change="loadAlerts"
      />
    </section>

    <el-drawer v-model="detailVisible" size="min(920px, 92vw)" custom-class="cockpit-modal" destroy-on-close @closed="clearDetailQuery">
      <template #header>
        <div v-if="detail" class="drawer-title">
          <span class="level-tag" :class="`level-${detail.event.level}`">{{ levelLabels[detail.event.level] }}</span>
          <div>
            <b>{{ detail.event.ruleCode }} · {{ detail.event.ruleName }}</b>
            <small>事件 {{ detail.event.eventId }} · {{ alertStatusLabels[detail.event.status] }}</small>
          </div>
        </div>
      </template>

      <div v-if="detail" class="detail-body">
        <section class="detail-actions">
          <!-- REQ-039 仅设备类告警可跳设备画像；区域/点位类禁用并说明原因 -->
          <el-tooltip :disabled="profileJumpEnabled" content="仅设备类告警可进入设备画像" placement="top">
            <span>
              <el-button type="primary" :disabled="!profileJumpEnabled" @click="goProfile">进入设备画像</el-button>
            </span>
          </el-tooltip>
          <!-- REQ-045：适用性和关联 ID 完全使用后端详情结果，不按规则号推断。 -->
          <el-button v-if="detail.relatedSuggestionId" type="primary" @click="viewRelatedSuggestion">
            查看关联建议
          </el-button>
          <el-button v-else-if="suggestionConversionEnabled" type="primary" :loading="converting" @click="convertToSuggestion">
            转为节能建议
          </el-button>
          <span v-else class="suggestion-unavailable">当前告警无适用建议模板</span>
          <!-- REQ-056：成本现场完全消费后端 costDeepLink，不按 R10 在前端拼月份/区域。 -->
          <el-button v-if="detail.costDeepLink" type="primary" plain @click="goCostEvidence">查看成本证据</el-button>
          <el-button v-for="action in availableActions" :key="action.value" @click="openTransition(action.value)">
            {{ action.label }}
          </el-button>
        </section>

        <section class="detail-grid">
          <article class="panel">
            <header class="panel-head"><div><div class="panel-title">触发规则快照</div><div class="panel-sub">历史事件按触发时版本展示 · REQ-040</div></div></header>
            <dl class="facts">
              <div><dt>规则版本</dt><dd>v{{ detail.ruleSnapshot?.version }}</dd></div>
              <div><dt>判定级别</dt><dd>{{ levelLabels[detail.ruleSnapshot?.level] || detail.ruleSnapshot?.level }}</dd></div>
              <div><dt>生效时间</dt><dd>{{ formatDateTime(detail.ruleSnapshot?.effectiveFrom) }}</dd></div>
              <div class="wide"><dt>判定表达式</dt><dd>{{ detail.ruleSnapshot?.expression || '—' }}</dd></div>
              <div v-for="item in thresholdEntries" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ displayValue(item[1]) }}</dd></div>
            </dl>
          </article>

          <article class="panel">
            <header class="panel-head"><div><div class="panel-title">对象与作业对照</div><div class="panel-sub">REQ-031/035</div></div></header>
            <dl class="facts">
              <div><dt>区域</dt><dd>{{ detail.event.area?.name || detail.event.area?.code }}</dd></div>
              <div><dt>对象</dt><dd>{{ detail.event.object?.name || detail.event.object?.code }}</dd></div>
              <div><dt>首次触发</dt><dd>{{ formatDateTime(detail.event.firstOccurredAt) }}</dd></div>
              <div><dt>合并次数</dt><dd>{{ detail.event.occurCount }}</dd></div>
            </dl>
            <div class="match-state" :class="{ missed: !detail.workOrderComparison?.matched }">
              {{ detail.workOrderComparison?.matched ? '异常窗存在匹配工单' : '异常窗无匹配工单' }}
            </div>
            <div v-for="order in detail.workOrderComparison?.orders || []" :key="order.orderNo" class="compact-row">
              <b>{{ order.orderNo }}</b><span>{{ formatDateTime(order.startTime) }} → {{ formatDateTime(order.endTime) }}</span>
            </div>
          </article>
        </section>

        <section class="panel">
          <header class="panel-head"><div><div class="panel-title">触发时刻曲线快照</div><div class="panel-sub">真实聚合 / 原始数据重建</div></div></header>
          <div v-if="detail.curveSnapshot?.points?.length" ref="curveChartEl" class="curve-chart" />
          <div v-else class="empty-state">暂无曲线快照</div>
        </section>

        <section class="detail-grid">
          <article class="panel">
            <header class="panel-head"><div><div class="panel-title">处置流转时间线</div><div class="panel-sub">操作人 · 时间 · 备注</div></div></header>
            <el-timeline v-if="detail.transitions?.length">
              <el-timeline-item v-for="flow in detail.transitions" :key="`${flow.occurredAt}-${flow.toStatus}`" :timestamp="formatDateTime(flow.occurredAt)">
                <b>{{ alertStatusLabels[flow.fromStatus] || flow.fromStatus }} → {{ alertStatusLabels[flow.toStatus] || flow.toStatus }}</b>
                <div class="timeline-meta">{{ flow.operator }} · {{ flow.remark || '无备注' }}</div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="empty-state">暂无处置流转记录</div>
          </article>

          <article class="panel">
            <header class="panel-head"><div><div class="panel-title">通知记录</div><div class="panel-sub">渠道 · 结果 · 重试 · REQ-043</div></div></header>
            <div v-if="detail.notifications?.length" class="notice-list">
              <div v-for="notice in detail.notifications" :key="`${notice.channel}-${notice.sentAt}`" class="compact-row">
                <b>{{ notice.channel }}</b>
                <span>{{ notice.targetRole }} · {{ formatDateTime(notice.sentAt) }} · {{ notice.result }} · 重试 {{ notice.retryCount }}</span>
              </div>
            </div>
            <div v-else class="empty-state">暂无通知记录</div>
          </article>
        </section>

        <section v-if="detail.event.closeReason" class="close-note">
          关闭类型 {{ detail.event.closeType }} · {{ detail.event.closeReason }} · {{ formatDateTime(detail.event.closedAt) }}
        </section>
      </div>
    </el-drawer>

    <el-dialog v-model="transitionVisible" :title="transitionTitle" width="520px" append-to-body>
      <el-form label-position="top">
        <el-form-item v-if="transitionForm.toStatus === 'dispatched'" label="人工处置人" required>
          <el-input v-model="transitionForm.assignedTo" placeholder="请输入处置人账号" />
        </el-form-item>
        <el-form-item v-if="['closed', 'false_closed'].includes(transitionForm.toStatus)" label="关闭原因" required>
          <el-input v-model="transitionForm.closeReason" type="textarea" :rows="3" placeholder="请输入核查结论与关闭原因" />
        </el-form-item>
        <el-form-item label="处置备注" required>
          <el-input v-model="transitionForm.remark" type="textarea" :rows="3" placeholder="记录本次人工处置说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transitionVisible = false">取消</el-button>
        <el-button type="primary" :loading="transitioning" @click="submitTransition">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getAlertDetail, getAlerts as fetchAlerts, transitionAlert } from '@/api/alerts'
import { createSuggestionFromAlert } from '@/api/suggestions'
import { useChartTheme } from '@/views/energy/shared/cockpitTheme'
import {
  alertStatusLabels,
  buildProfileQuery,
  buildTransitionPayload,
  canJumpToProfile,
  compactQuery,
  levelLabels,
  validateTransition
} from '@/views/energy/shared/act3'
import { canConvertAlert, suggestionDetailTarget } from '@/views/energy/shared/act4'

echarts.use([LineChart, GridComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])
defineOptions({ name: 'EnergyAlertList' })

// 双主题响应式：切顶栏夜览时曲线快照即时重绘
const theme = useChartTheme()

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const items = ref([])
const total = ref(0)
const statistics = ref({})
const detail = ref(null)
const detailVisible = ref(false)
const transitionVisible = ref(false)
const transitioning = ref(false)
const converting = ref(false)
const curveChartEl = ref(null)
let curveChart = null

const filters = reactive({ level: '', status: '', ruleCode: '', zone: 'ALL', pageNum: 1, pageSize: 20 })
const transitionForm = reactive({ toStatus: '', assignedTo: '', remark: '', closeReason: '', closeType: '' })

const levelOptions = Object.entries(levelLabels).map(([value, label]) => ({ value, label }))
const statusOptions = Object.entries(alertStatusLabels).map(([value, label]) => ({ value, label }))
const thresholdEntries = computed(() => Object.entries(detail.value?.ruleSnapshot?.thresholds || {}))
const transitionTitle = computed(() => alertStatusLabels[transitionForm.toStatus] || '告警处置')
const profileJumpEnabled = computed(() => canJumpToProfile(detail.value))
const suggestionConversionEnabled = computed(() => canConvertAlert(detail.value))
const availableActions = computed(() => {
  const status = detail.value?.event?.status
  const actions = {
    new: [{ value: 'ack', label: '确认告警' }, { value: 'dispatched', label: '确认并派发人工处置' }],
    ack: [{ value: 'dispatched', label: '派发人工处置' }],
    dispatched: [{ value: 'processing', label: '开始人工处理' }],
    processing: [{ value: 'closed', label: '关闭告警' }, { value: 'false_closed', label: '误报关闭' }, { value: 'escalated', label: '升级告警' }]
  }
  return actions[status] || []
})

async function loadAlerts() {
  loading.value = true
  try {
    const response = await fetchAlerts(compactQuery(filters))
    items.value = response.data?.items || []
    total.value = response.data?.total || 0
    statistics.value = response.data?.statistics || {}
  } finally {
    loading.value = false
  }
}

function search() {
  filters.pageNum = 1
  loadAlerts()
}

async function openDetail(rowOrId, syncQuery = true) {
  const eventId = typeof rowOrId === 'object' ? rowOrId.eventId : rowOrId
  if (!eventId) return
  const response = await getAlertDetail(eventId)
  detail.value = response.data
  detailVisible.value = true
  if (syncQuery && String(route.query.eventId || '') !== String(eventId)) {
    await router.replace({ query: { ...route.query, eventId } })
  }
  await nextTick()
  renderCurve()
  if (route.query.action === 'dispatch') {
    if (['new', 'ack'].includes(detail.value?.event?.status)) openTransition('dispatched')
    const query = { ...route.query }
    delete query.action
    await router.replace({ query })
  }
}

function clearDetailQuery() {
  detail.value = null
  curveChart?.dispose()
  curveChart = null
  if (route.query.eventId) {
    const query = { ...route.query }
    delete query.eventId
    delete query.action
    router.replace({ query })
  }
}

function goProfile() {
  if (!profileJumpEnabled.value) return
  router.push({ path: '/energy/analysis/profile', query: buildProfileQuery(detail.value) })
}

function viewRelatedSuggestion() {
  if (!detail.value?.relatedSuggestionId) return
  router.push(suggestionDetailTarget(detail.value.relatedSuggestionId))
}

function goCostEvidence() {
  if (!detail.value?.costDeepLink) return
  router.push(detail.value.costDeepLink)
}

async function convertToSuggestion() {
  if (!suggestionConversionEnabled.value || !detail.value?.event?.eventId) return
  converting.value = true
  try {
    const response = await createSuggestionFromAlert(detail.value.event.eventId, {})
    const suggestion = response.data?.suggestion
    if (!suggestion?.suggestionId) throw new Error('接口未返回建议编号')
    ElMessage.success(response.data?.created ? '已从真实告警生成节能建议' : '已打开原有关联建议')
    await router.push(suggestionDetailTarget(suggestion.suggestionId))
  } finally {
    converting.value = false
  }
}

function openTransition(toStatus) {
  Object.assign(transitionForm, {
    toStatus,
    assignedTo: detail.value?.event?.assignedTo || '',
    remark: '',
    closeReason: '',
    closeType: toStatus === 'false_closed' ? 'false_positive' : toStatus === 'closed' ? 'valid' : ''
  })
  transitionVisible.value = true
}

async function submitTransition() {
  const error = validateTransition(transitionForm.toStatus, transitionForm)
  if (error) {
    ElMessage.error(error)
    return
  }
  transitioning.value = true
  try {
    await transitionAlert(detail.value.event.eventId, buildTransitionPayload(transitionForm))
    ElMessage.success('人工处置状态已更新')
    transitionVisible.value = false
    await Promise.all([openDetail(detail.value.event.eventId, false), loadAlerts()])
  } finally {
    transitioning.value = false
  }
}

function renderCurve() {
  const snapshot = detail.value?.curveSnapshot
  if (!curveChartEl.value || !snapshot?.points?.length) return
  curveChart ||= echarts.init(curveChartEl.value)
  const pal = theme.value
  curveChart.setOption({
    grid: { top: 24, left: 58, right: 24, bottom: 36 },
    tooltip: { trigger: 'axis', backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder, textStyle: { color: pal.tooltipInk } },
    xAxis: {
      type: 'category', data: snapshot.points.map((point) => point.ts),
      axisLabel: { color: pal.ink3, formatter: (value) => String(value).slice(11, 16) },
      axisLine: { lineStyle: { color: pal.lineStrong } }
    },
    yAxis: {
      type: 'value', name: snapshot.unit,
      nameTextStyle: { color: pal.ink2 }, axisLabel: { color: pal.ink3 },
      splitLine: { lineStyle: { color: pal.splitLine } }
    },
    series: [{
      type: 'line', symbol: 'none', smooth: true,
      data: snapshot.points.map((point) => point.value),
      lineStyle: { color: pal.cyan, width: 2 },
      areaStyle: { color: pal.areaTint },
      markLine: snapshot.thresholdLine == null ? undefined : {
        symbol: 'none', lineStyle: { color: pal.red, type: 'dashed' },
        data: [{ yAxis: snapshot.thresholdLine, name: '触发阈值' }]
      }
    }]
  })
}

function formatDateTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function displayValue(value) { return typeof value === 'object' ? JSON.stringify(value) : (value ?? '—') }
function formatRate(value) {
  if (value == null) return '—'
  return `${Number(value).toFixed(1)}%`
}
function formatHours(value) { return value == null ? '—' : `${Number(value).toFixed(1)} h` }
function resizeChart() { curveChart?.resize() }

// 主题切换即时重绘
watch(theme, () => { renderCurve() })

watch(() => route.query.eventId, (eventId) => {
  if (eventId && String(detail.value?.event?.eventId || '') !== String(eventId)) openDetail(eventId, false)
  if (!eventId && detailVisible.value) detailVisible.value = false
}, { immediate: true })

onMounted(() => {
  loadAlerts()
  window.addEventListener('resize', resizeChart)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  curveChart?.dispose()
})
</script>

<style scoped>
/* 页面布局：主题 token 由 .cockpit-page 提供；.act3-page 只做布局特化。 */
.act3-page{
  min-height:calc(100vh - 84px);margin:-16px -16px 0;padding:16px 20px 40px;color:var(--ink);
  background:radial-gradient(900px 420px at 100% 0,var(--red-tint),transparent 60%),var(--bg);
  font-family:"PingFang SC",system-ui,sans-serif;font-size:13px;
}
.filter-bar{display:flex;align-items:center;gap:10px;padding:12px 14px;background:var(--panel);border:1px solid var(--line);}
.filter-title{margin-right:auto;display:flex;flex-direction:column}.filter-title b{font-family:var(--serif);font-size:17px;letter-spacing:.08em}.filter-title span,.panel-sub{font-family:var(--mono);font-size:10px;color:var(--ink-3);letter-spacing:.1em}
.filter-bar :deep(.el-select){width:120px}.filter-bar :deep(.el-input){width:120px}
/* el-input/select 皮由 cockpit-tokens 通用块统一供，不再页级覆盖 */
.metric-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:12px}.metric{border:1px solid var(--line);border-left:2px solid var(--cyan);background:var(--panel);padding:12px 14px;display:grid;grid-template-columns:1fr auto;gap:4px}.metric-ok{border-left-color:var(--lime)}.metric-warn{border-left-color:var(--amber)}.metric span{color:var(--ink-2)}.metric b{font:24px var(--mono)}.metric small{grid-column:1/-1;color:var(--ink-3);font:10px var(--mono)}
.panel{background:var(--panel);border:1px solid var(--line);padding:14px 16px}.list-panel{margin-top:12px}.panel-head{display:flex;align-items:center;border-bottom:1px dashed var(--line);padding-bottom:8px;margin-bottom:10px}.panel-title{font-family:var(--serif);font-size:15px;letter-spacing:.06em}.count-label{margin-left:auto;color:var(--ink-3);font:10px var(--mono)}
/* .dark-table 由 cockpit-tokens 通用块提供变量映射；此处只补页面级 hover 指针 */
.dark-table{cursor:pointer}.primary-cell{color:var(--ink)}.secondary-cell{color:var(--ink-3);font:10px var(--mono);margin-top:2px}.mono-cell{font-family:var(--mono)}
.level-tag,.status-tag{display:inline-block;padding:2px 7px;border:1px solid var(--line-strong);font:10px var(--mono)}.level-severe{color:var(--red);border-color:color-mix(in srgb, var(--red) 45%, transparent);background:var(--red-tint)}.level-normal{color:var(--amber);border-color:color-mix(in srgb, var(--amber) 40%, transparent);background:var(--amber-tint)}.level-notice{color:var(--cyan);border-color:color-mix(in srgb, var(--cyan) 40%, transparent);background:var(--cyan-tint)}.status-tag{color:var(--ink-2)}
.pager{justify-content:flex-end;margin-top:12px}.pager :deep(button),.pager :deep(.number){background:var(--panel-2)!important;color:var(--ink-2)!important}
.drawer-title{display:flex;align-items:center;gap:12px}.drawer-title div{display:flex;flex-direction:column}.drawer-title b{color:var(--ink);font-family:var(--serif);font-size:17px}.drawer-title small{color:var(--ink-3);font:10px var(--mono);margin-top:3px}.detail-body{display:flex;flex-direction:column;gap:12px}.detail-actions{display:flex;gap:8px;flex-wrap:wrap}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.facts{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:0}.facts div{border-bottom:1px dashed var(--line);padding-bottom:6px}.facts .wide{grid-column:1/-1}.facts dt{color:var(--ink-3);font:10px var(--mono)}.facts dd{margin:3px 0 0;color:var(--ink)}
.suggestion-unavailable{align-self:center;color:var(--ink-3);font:10px var(--mono)}
.match-state{margin-top:12px;padding:8px;border:1px solid color-mix(in srgb, var(--lime) 35%, transparent);color:var(--lime);font-family:var(--mono)}.match-state.missed{border-color:color-mix(in srgb, var(--red) 40%, transparent);color:var(--red);background:var(--red-tint)}.compact-row{display:flex;justify-content:space-between;gap:10px;padding:8px 0;border-bottom:1px dashed var(--line)}.compact-row b{color:var(--ink)}.compact-row span{color:var(--ink-3);font:10px var(--mono);text-align:right}.curve-chart{height:260px}.empty-state{padding:24px;text-align:center;color:var(--ink-3);border:1px dashed var(--line)}.timeline-meta{color:var(--ink-3);font-size:11px;margin-top:3px}.close-note{padding:10px 12px;border:1px solid var(--line-strong);color:var(--ink-2);font-family:var(--mono)}
@media(max-width:900px){.metric-grid,.detail-grid{grid-template-columns:1fr 1fr}.filter-title{width:100%}.filter-bar{flex-wrap:wrap}}
</style>

<!-- 抽屉皮已迁至 cockpit-tokens.scss 的 .cockpit-modal，本页 <el-drawer custom-class="cockpit-modal" /> 直接消费，页级 <style> 全局皮删除 -->
