<!--
  AI 自主巡检报告页
  REQ-AGENT-TBD（PRD 增补待办）· 契约：docs/agent-ai-design.md §6、§8
  规则：
    - 权限窄授权：admin / energy_mgr（对齐 K.6，与报表页同处理）
    - findings 直接展示后端业务名称链（区 → 设备 → 计量对象），前端不拼接不翻译编号
    - related_ids 只作跳转参数使用，不写入正文
    - 双主题走 cockpit-tokens；severity 左侧色条：high 红 / mid 琥珀 / low 青
-->
<template>
  <div class="act-inspect-page cockpit-page">
    <header class="page-head">
      <div class="page-title">
        <span class="eyebrow">AI INSPECTION · 自主巡检</span>
        <b>AI 自主巡检报告</b>
        <small>每日 07:00 自动巡检 · 覆盖数据质量 / 告警 / 能耗成本 / 设备画像四条链</small>
      </div>
      <div class="page-actions">
        <el-button :loading="runLoading" :disabled="!access" type="primary" @click="onRunNow">
          立即巡检
        </el-button>
      </div>
    </header>

    <el-empty
      v-if="!access"
      description="AI 巡检仅能源管理员与系统管理员可用（PRD 附录 K.6 · 契约同报表页）"
      class="forbidden"
    >
      <template #image><el-icon :size="56"><Lock /></el-icon></template>
    </el-empty>

    <section v-else class="layout">
      <aside class="report-list panel">
        <div class="panel-head">
          <b>历史报告</b>
          <small>{{ list.length }} 份</small>
        </div>
        <ul v-if="list.length" class="report-items">
          <li
            v-for="item in list"
            :key="item.id"
            class="report-item"
            :class="{ 'is-active': current?.id === item.id, 'is-failed': item.status === 'failed' }"
            @click="selectReport(item.id)"
          >
            <div class="ri-head">
              <span class="ri-date">{{ item.report_date || item.reportDate || '—' }}</span>
              <span class="ri-trigger">{{ triggerLabel(item.trigger_type || item.triggerType) }}</span>
            </div>
            <div class="ri-status">
              <span class="ri-dot" :class="statusClass(item.status)" />
              {{ statusLabel(item.status) }}
              <span v-if="item.created_at || item.createdAt" class="ri-time">
                {{ formatTime(item.created_at || item.createdAt) }}
              </span>
            </div>
            <p v-if="item.summary" class="ri-summary">{{ item.summary }}</p>
          </li>
        </ul>
        <div v-else-if="listLoading" class="ri-empty">加载中…</div>
        <div v-else class="ri-empty">暂无巡检报告，点击「立即巡检」触发一次</div>
      </aside>

      <section class="report-detail">
        <div v-if="detailLoading" class="detail-empty">报告加载中…</div>
        <div v-else-if="!current" class="detail-empty">左侧选择一份报告查看详情</div>
        <template v-else>
          <div class="detail-head panel">
            <div class="detail-title">
              <span class="eyebrow">REPORT · {{ current.report_date || current.reportDate }}</span>
              <b>{{ current.summary || '本次巡检摘要暂缺' }}</b>
              <small>
                {{ triggerLabel(current.trigger_type || current.triggerType) }} ·
                {{ statusLabel(current.status) }} ·
                {{ (current.model_name || current.modelName) || '模型未知' }} ·
                用时 {{ ((current.elapsed_ms || current.elapsedMs || 0) / 1000).toFixed(1) }} 秒
              </small>
            </div>
          </div>

          <div class="stat-grid">
            <article class="stat-card">
              <span>检查项</span>
              <b>{{ stats.checks ?? '—' }}</b>
              <small>本轮工具调用次数</small>
            </article>
            <article class="stat-card stat-high">
              <span>高危 finding</span>
              <b>{{ stats.high ?? 0 }}</b>
              <small>需立即处置</small>
            </article>
            <article class="stat-card stat-mid">
              <span>中危 finding</span>
              <b>{{ stats.mid ?? 0 }}</b>
              <small>建议本班处理</small>
            </article>
            <article class="stat-card stat-low">
              <span>低危 / 观察</span>
              <b>{{ stats.low ?? 0 }}</b>
              <small>纳入趋势观察</small>
            </article>
            <article class="stat-card">
              <span>数据完整率</span>
              <b>{{ formatPct(stats.completeness) }}</b>
              <small>近 7 天覆盖度</small>
            </article>
          </div>

          <div v-if="current.status === 'failed'" class="failed-alert">
            <el-alert
              type="error"
              :closable="false"
              :title="'本次巡检执行失败'"
              description="LLM 或工具链异常，未产出完整报告。可稍后手动触发重试。"
            />
          </div>

          <div v-else-if="!findings.length" class="detail-empty">
            本次巡检未发现异常，全部指标处于正常区间。
          </div>

          <div v-else class="findings">
            <article
              v-for="(f, idx) in findings"
              :key="idx"
              class="finding"
              :class="severityClass(f.severity)"
            >
              <header class="finding-head">
                <span class="fh-severity">{{ severityLabel(f.severity) }}</span>
                <span class="fh-category">{{ f.category || '未分类' }}</span>
              </header>
              <p v-if="f.evidence" class="finding-evidence">{{ f.evidence }}</p>
              <p v-if="f.suggestion" class="finding-suggestion">
                <b>建议</b>{{ f.suggestion }}
              </p>
              <div v-if="normalizeRelated(f).length" class="finding-related">
                <span class="fr-label">相关业务</span>
                <router-link
                  v-for="(rel, ridx) in normalizeRelated(f)"
                  :key="ridx"
                  :to="rel.to"
                  class="fr-link"
                >{{ rel.label }}</router-link>
              </div>
            </article>
          </div>
        </template>
      </section>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import useUserStore from '@/store/modules/user'
import { getInspection, listInspections, runInspection } from '@/api/agent'

const userStore = useUserStore()
const access = computed(() => {
  const roles = new Set(userStore.roles || [])
  return roles.has('admin') || roles.has('energy_mgr')
})

const list = ref([])
const listLoading = ref(false)
const current = ref(null)
const detailLoading = ref(false)
const runLoading = ref(false)

const findings = computed(() => {
  const raw = current.value?.findings || current.value?.findings_json || []
  return Array.isArray(raw) ? raw : []
})
const stats = computed(() => {
  const raw = current.value?.stats || current.value?.stats_json || {}
  const bySeverity = countBySeverity(findings.value)
  return {
    checks: raw.checks ?? raw.tool_calls ?? raw.toolCalls,
    high: raw.high ?? bySeverity.high,
    mid: raw.mid ?? bySeverity.mid,
    low: raw.low ?? bySeverity.low,
    completeness: raw.completeness ?? raw.completeness_ratio ?? raw.coverage ?? raw.data_quality_coverage
  }
})

function countBySeverity(items) {
  return items.reduce((acc, f) => {
    const level = normalizeSeverity(f.severity)
    acc[level] = (acc[level] || 0) + 1
    return acc
  }, { high: 0, mid: 0, low: 0 })
}

function normalizeSeverity(s) {
  const v = String(s || '').toLowerCase()
  if (['high', 'critical', 'severe', 'p0', 'p1'].includes(v)) return 'high'
  if (['mid', 'medium', 'warn', 'warning', 'p2'].includes(v)) return 'mid'
  return 'low'
}
function severityClass(s) { return `sev-${normalizeSeverity(s)}` }
function severityLabel(s) {
  const map = { high: '高危', mid: '中危', low: '低危' }
  return map[normalizeSeverity(s)]
}

function statusLabel(s) {
  return ({ success: '完成', failed: '失败', running: '执行中' }[s]) || (s || '未知')
}
function statusClass(s) { return `is-${s || 'unknown'}` }

function triggerLabel(t) {
  return ({ scheduled: '定时', manual: '手动', schedule: '定时' }[t]) || (t || '—')
}

function formatPct(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const num = Number(v)
  const pct = num > 1 ? num : num * 100
  return `${pct.toFixed(1)}%`
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return String(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// related_ids 兼容三种形态：
//   1) [{label, path}]                 —— 后端直接给业务标签+路径（首选）
//   2) [{type:'alert'|'equipment'|..., id, label?}] —— 前端按类型映射到已知五幕页面
//   3) [123, 'AL-XXX']                 —— 只有 id，展示为"相关编号"链接列表（跳转到默认列表页）
function normalizeRelated(f) {
  const raw = f.related_ids || f.relatedIds || []
  if (!Array.isArray(raw)) return []
  return raw
    .map((item) => {
      if (item && typeof item === 'object' && item.path) {
        return { label: item.label || item.path, to: item.path }
      }
      if (item && typeof item === 'object' && item.type) {
        return routeByType(item)
      }
      // 裸编号：兜底跳到告警列表
      return { label: `编号 ${item}`, to: '/energy/alert/list' }
    })
    .filter(Boolean)
}

function routeByType(item) {
  const map = {
    alert: '/energy/alert/list',
    event: '/energy/alert/list',
    suggestion: '/energy/alert/suggestion',
    workorder: '/energy/alert/suggestion',
    equipment: '/energy/analysis/profile',
    profile: '/energy/analysis/profile',
    cost: '/energy/cost/record',
    quality: '/raw-quality',
    raw_quality: '/raw-quality',
    overview: '/dashboard'
  }
  const path = map[item.type] || '/dashboard'
  return { label: item.label || `${item.type} ${item.id ?? ''}`.trim(), to: path }
}

async function loadList(preferId) {
  listLoading.value = true
  try {
    const res = await listInspections({ pageNum: 1, pageSize: 30 })
    // 后端契约：{code,msg,data:{total,page_num,page_size,items:[]}}——items 必须在 res.data 之前取，
    // 否则 res.data 作为对象命中兜底、Array.isArray 判否变空列表（历史报告 0 份的根因）
    const d = res?.data ?? res
    const rows = d?.items || d?.rows || res?.rows || res?.items || (Array.isArray(d) ? d : [])
    list.value = Array.isArray(rows) ? rows : []
    if (list.value.length) {
      const target = preferId
        ? list.value.find((r) => r.id === preferId)
        : list.value[0]
      if (target) await loadDetail(target.id)
      else current.value = null
    } else {
      current.value = null
    }
  } catch (err) {
    // 全局拦截器已弹错，此处保底
    console.error('[aiInspection] list failed', err)
  } finally {
    listLoading.value = false
  }
}

async function loadDetail(id) {
  detailLoading.value = true
  try {
    const res = await getInspection(id)
    current.value = res?.data || res
  } finally {
    detailLoading.value = false
  }
}

function selectReport(id) {
  if (current.value?.id === id) return
  loadDetail(id)
}

async function onRunNow() {
  runLoading.value = true
  try {
    const res = await runInspection()
    const newId = res?.data?.id || res?.id
    ElMessage.success('本次巡检已完成，报告已刷新')
    await loadList(newId)
  } catch (err) {
    // 全局提示已处理；失败也刷新列表以显示失败态记录
    await loadList()
  } finally {
    runLoading.value = false
  }
}

onMounted(() => { if (access.value) loadList() })
</script>

<style scoped>
.act-inspect-page {
  min-height: calc(100vh - 84px);
  margin: -16px -16px 0;
  padding: 16px 20px 40px;
  color: var(--ink);
  background: var(--hero-glow), var(--bg);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  padding: 8px 0 14px;
  border-bottom: 1px solid var(--line);
}
.page-title { display: flex; flex-direction: column; gap: 4px; }
.page-title .eyebrow { color: var(--cyan); font: 10px var(--mono); letter-spacing: .14em; }
.page-title b { font-family: var(--serif); font-size: 22px; letter-spacing: .08em; color: var(--ink); }
.page-title small { color: var(--ink-3); font: 11px var(--mono); letter-spacing: .06em; }

.forbidden { margin-top: 16px; padding: 36px 24px; background: var(--panel); border: 1px solid var(--line); color: var(--ink-2); }
.forbidden :deep(.el-icon) { color: var(--amber); }

.layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 12px;
  margin-top: 12px;
}

.panel { background: var(--panel); border: 1px solid var(--line); }

.report-list { display: flex; flex-direction: column; max-height: calc(100vh - 200px); }
.report-list .panel-head {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}
.report-list .panel-head b { font-family: var(--serif); color: var(--ink); letter-spacing: .06em; }
.report-list .panel-head small { color: var(--ink-3); font-family: var(--mono); font-size: 11px; }

.report-items { list-style: none; margin: 0; padding: 0; overflow-y: auto; flex: 1; }
.report-item {
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  transition: background .15s;
}
.report-item:hover { background: var(--cyan-tint); }
.report-item.is-active { background: var(--cyan-tint); border-left: 3px solid var(--cyan); padding-left: 11px; }
.report-item.is-failed { border-left: 3px solid var(--red); padding-left: 11px; }
.report-item.is-active.is-failed { background: var(--red-tint); }

.ri-head { display: flex; justify-content: space-between; align-items: baseline; }
.ri-date { font-family: var(--serif); font-size: 14px; color: var(--ink); letter-spacing: .06em; }
.ri-trigger { color: var(--ink-3); font-family: var(--mono); font-size: 10px; }
.ri-status {
  margin-top: 4px;
  display: flex; align-items: center; gap: 6px;
  color: var(--ink-2); font-size: 11px; font-family: var(--mono);
}
.ri-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-3); }
.ri-dot.is-success { background: var(--lime); }
.ri-dot.is-failed { background: var(--red); }
.ri-dot.is-running { background: var(--amber); }
.ri-time { margin-left: auto; color: var(--ink-3); }
.ri-summary { color: var(--ink-2); font-size: 12px; margin: 6px 0 0; line-height: 1.5; }
.ri-empty { padding: 32px 14px; color: var(--ink-3); font-size: 12px; text-align: center; }

.report-detail { display: flex; flex-direction: column; gap: 12px; min-height: 240px; }
.detail-empty { padding: 40px; text-align: center; color: var(--ink-3); font-size: 12px; background: var(--panel); border: 1px solid var(--line); }

.detail-head { padding: 14px 18px; }
.detail-title { display: flex; flex-direction: column; gap: 4px; }
.detail-title .eyebrow { color: var(--cyan); font: 10px var(--mono); letter-spacing: .14em; }
.detail-title b { font-family: var(--serif); font-size: 16px; color: var(--ink); line-height: 1.5; }
.detail-title small { color: var(--ink-3); font: 11px var(--mono); letter-spacing: .06em; }

.stat-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
.stat-card {
  background: var(--panel);
  border: 1px solid var(--line);
  padding: 12px 14px;
  display: flex; flex-direction: column; gap: 4px;
  border-top: 2px solid var(--cyan);
}
.stat-card span { color: var(--ink-3); font: 10px var(--mono); letter-spacing: .1em; }
.stat-card b { font-family: var(--serif); font-size: 22px; color: var(--ink); letter-spacing: .04em; }
.stat-card small { color: var(--ink-3); font-size: 11px; }
.stat-card.stat-high { border-top-color: var(--red); }
.stat-card.stat-high b { color: var(--red); }
.stat-card.stat-mid { border-top-color: var(--amber); }
.stat-card.stat-mid b { color: var(--amber); }
.stat-card.stat-low { border-top-color: var(--lime); }
.stat-card.stat-low b { color: var(--lime); }

.failed-alert { margin-top: 4px; }

.findings { display: flex; flex-direction: column; gap: 10px; }
.finding {
  background: var(--panel);
  border: 1px solid var(--line);
  border-left: 4px solid var(--cyan);
  padding: 12px 14px;
}
.finding.sev-high { border-left-color: var(--red); background: linear-gradient(90deg, var(--red-tint), var(--panel) 40%); }
.finding.sev-mid { border-left-color: var(--amber); background: linear-gradient(90deg, var(--amber-tint), var(--panel) 40%); }
.finding.sev-low { border-left-color: var(--lime); }

.finding-head { display: flex; gap: 12px; align-items: center; margin-bottom: 6px; }
.fh-severity {
  font-family: var(--mono);
  font-size: 10px;
  letter-spacing: .12em;
  padding: 2px 8px;
  border: 1px solid currentColor;
}
.finding.sev-high .fh-severity { color: var(--red); }
.finding.sev-mid .fh-severity { color: var(--amber); }
.finding.sev-low .fh-severity { color: var(--lime); }
.fh-category { color: var(--ink); font-family: var(--serif); font-size: 14px; letter-spacing: .06em; }

.finding-evidence { color: var(--ink-2); font-size: 13px; line-height: 1.6; margin: 4px 0; }
.finding-suggestion { color: var(--ink); font-size: 13px; line-height: 1.6; margin: 6px 0; }
.finding-suggestion b { color: var(--cyan); margin-right: 6px; font-weight: 600; }

.finding-related { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line); }
.fr-label { color: var(--ink-3); font: 10px var(--mono); letter-spacing: .1em; }
.fr-link {
  color: var(--cyan);
  font-size: 12px;
  text-decoration: none;
  padding: 2px 8px;
  border: 1px solid var(--line-strong);
  background: var(--panel-2);
}
.fr-link:hover { border-color: var(--cyan); background: var(--cyan-tint); }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 860px) {
  .layout { grid-template-columns: 1fr; }
  .report-list { max-height: 320px; }
}
</style>
