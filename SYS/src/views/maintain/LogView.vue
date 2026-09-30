<script setup lang="ts">
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { useSysStore } from '@/stores/sys'
import { useAuthStore } from '@/stores/auth'
import { sysApi } from '@/api/sys'
import type { LogKind } from '@/sys/types'
import type {
  DiagQuery,
  DiagSeverity,
  LogDiagnosisReport,
  LogStatistics,
} from '@/sys/insight'

const sys = useSysStore()
const auth = useAuthStore()
const can = (code: string) => auth.isSuperAdmin || auth.permCodes.has(code)

function loadLogs() {
  sys.reloadLogs().catch((e) => ElMessage.error(e instanceof Error ? e.message : '日志加载失败'))
}
// 无 keep-alive 时 onActivated 不会触发，必须在 onMounted 首次加载；
// 若后续启用 keep-alive，跳过挂载后的首次 activated 避免重复请求
let mountedOnce = false
onMounted(() => { mountedOnce = true; loadLogs(); void loadStats() })
onActivated(() => { if (!mountedOnce) return; loadLogs() })

const filters = ref({
  kind: '' as '' | LogKind,
  module: '',
  result: '',
  keyword: '',
  range: [] as string[],
})

const MODULE_LABEL: Record<string, string> = {
  auth: '登录认证', dict: '数据字典', config: '系统配置', log: '日志管理',
  user: '用户管理', role: '角色管理',
}
const ACTION_LABEL: Record<string, string> = {
  login: '登录', logout: '退出', add: '新增', edit: '编辑', delete: '删除', export: '导出',
  status: '启停用', resetPassword: '重置密码', assignRoles: '分配角色', assignPerms: '分配权限',
}
const moduleOptions = Object.entries(MODULE_LABEL).map(([v, l]) => ({ v, l }))

const filteredLogs = computed(() => {
  return sys.logs.filter((l) => {
    // 证据视图：只显示选中的证据日志
    if (evidenceIds.value && !evidenceIds.value.includes(l.id)) return false
    if (filters.value.kind && l.kind !== filters.value.kind) return false
    if (filters.value.module && l.module !== filters.value.module) return false
    if (filters.value.result && l.result !== filters.value.result) return false
    if (filters.value.range && filters.value.range.length === 2) {
      const t = new Date(l.createdAt).getTime()
      if (t < new Date(filters.value.range[0]).getTime() || t > new Date(filters.value.range[1]).getTime() + 86400000) return false
    }
    if (filters.value.keyword) {
      const kw = filters.value.keyword.toLowerCase()
      const hay = `${l.username} ${l.target || ''} ${l.detail || ''}`.toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

function resetFilter() {
  filters.value = { kind: '', module: '', result: '', keyword: '', range: [] }
}

function fmtTime(iso: string) {
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 导出当前筛选结果为 CSV：由后端按筛选条件生成（带 BOM，Excel 中文不乱码），后端自记导出日志 */
async function exportCsv() {
  if (filteredLogs.value.length === 0) {
    ElMessage.warning('当前没有可导出的日志')
    return
  }
  try {
    const resp = await sysApi.exportLogs({
      kind: filters.value.kind || undefined,
      module: filters.value.module || undefined,
      result: filters.value.result || undefined,
      keyword: filters.value.keyword || undefined,
      begin: filters.value.range?.[0] || undefined,
      end: filters.value.range?.[1] || undefined,
    })
    const blob = await resp.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `系统日志_${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success(`已导出 ${filteredLogs.value.length} 条日志`)
    sys.reloadLogs().catch(() => undefined)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败')
  }
}

// ============================================================================
// 日志概览 + 智能诊断（规则引擎，零外部 AI）
// ============================================================================
function localIso(d: Date) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const diag = ref({
  range: [localIso(new Date(Date.now() - 6 * 86400000)), localIso(new Date())] as string[],
  module: '',
  user: '',
})

function diagParams(): DiagQuery {
  return {
    from: diag.value.range?.[0] || undefined,
    to: diag.value.range?.[1] || undefined,
    module: diag.value.module || undefined,
    user: diag.value.user || undefined,
  }
}

const stats = ref<LogStatistics | null>(null)
const statsLoading = ref(false)
async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await sysApi.logStatistics(diagParams())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '统计加载失败')
  } finally {
    statsLoading.value = false
  }
}

const report = ref<LogDiagnosisReport | null>(null)
const diagLoading = ref(false)
async function runDiagnosis() {
  diagLoading.value = true
  report.value = null
  try {
    report.value = await sysApi.logDiagnosis(diagParams())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '诊断失败')
  } finally {
    diagLoading.value = false
  }
}

// 证据日志定位
const evidenceIds = ref<string[] | null>(null)
function viewEvidence(ids: string[]) {
  evidenceIds.value = ids
}
function clearEvidence() {
  evidenceIds.value = null
}

function sevType(s: DiagSeverity): 'danger' | 'warning' | 'info' | 'success' {
  return s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : s === 'LOW' ? 'info' : 'success'
}
function sevLabel(s: DiagSeverity) {
  return s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : s === 'LOW' ? '低' : '信息'
}
</script>

<template>
  <div class="log-page">
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" size="default">
        <el-form-item label="类别">
          <el-select v-model="filters.kind" placeholder="全部" clearable style="width:130px">
            <el-option label="登录日志" value="login" />
            <el-option label="操作日志" value="operation" />
          </el-select>
        </el-form-item>
        <el-form-item label="模块">
          <el-select v-model="filters.module" placeholder="全部" clearable style="width:130px">
            <el-option v-for="o in moduleOptions" :key="o.v" :label="o.l" :value="o.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="filters.result" placeholder="全部" clearable style="width:110px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="fail" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="filters.range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            style="width:240px"
          />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" placeholder="用户/对象/详情" clearable style="width:170px" />
        </el-form-item>
        <el-form-item>
          <el-button :icon="'RefreshLeft'" @click="resetFilter">重置</el-button>
          <el-button
            v-if="can('sys:log:list:export')"
            type="primary"
            :icon="'Download'"
            @click="exportCsv"
          >导出 CSV</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 日志概览与智能诊断 -->
    <el-card shadow="never" class="insight-card">
      <template #header>
        <div class="insight-head">
          <span class="panel-title">日志概览与智能诊断</span>
          <el-tag size="small" type="info" effect="plain">规则引擎 · 离线可运行 · 零外部 AI</el-tag>
        </div>
      </template>

      <el-form :inline="true" size="default">
        <el-form-item label="时间">
          <el-date-picker
            v-model="diag.range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DD"
            style="width:240px"
          />
        </el-form-item>
        <el-form-item label="模块">
          <el-select v-model="diag.module" placeholder="全部" clearable style="width:130px">
            <el-option v-for="o in moduleOptions" :key="o.v" :label="o.l" :value="o.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户">
          <el-input v-model="diag.user" placeholder="用户名（精确）" clearable style="width:150px" />
        </el-form-item>
        <el-form-item>
          <el-button :icon="'View'" :loading="statsLoading" @click="loadStats">查看概览</el-button>
          <el-button type="primary" :icon="'MagicStick'" :loading="diagLoading" @click="runDiagnosis">开始诊断</el-button>
        </el-form-item>
      </el-form>

      <!-- 概览统计 -->
      <div v-if="stats" v-loading="statsLoading" class="stats-area">
        <div class="stats-row">
          <div class="kpi"><span class="kpi-num">{{ stats.total }}</span><span class="kpi-label">总操作</span></div>
          <div class="kpi kpi-ok"><span class="kpi-num">{{ stats.success }}</span><span class="kpi-label">成功</span></div>
          <div class="kpi kpi-bad"><span class="kpi-num">{{ stats.fail }}</span><span class="kpi-label">失败</span></div>
          <div class="kpi" :class="{ 'kpi-bad': stats.failureRate >= 20 }">
            <span class="kpi-num">{{ stats.failureRate }}%</span><span class="kpi-label">失败率</span>
          </div>
        </div>
        <el-row :gutter="14" class="dist-row">
          <el-col :span="12">
            <div class="dist-title">模块分布</div>
            <div v-for="m in stats.byModule" :key="m.name" class="dist-line">
              <span class="dist-name">{{ MODULE_LABEL[m.name] || m.name }}</span>
              <el-progress
                :percentage="Math.round(m.total * 100 / (stats.total || 1))"
                :stroke-width="10"
                class="dist-bar"
              />
              <span class="dist-meta">{{ m.total }} 条 · 失败率 {{ m.failureRate }}%</span>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="dist-title">用户分布（前 8）</div>
            <div v-for="u in stats.byUser.slice(0, 8)" :key="u.name" class="dist-line">
              <span class="dist-name">{{ u.name }}</span>
              <el-progress
                :percentage="Math.round(u.total * 100 / (stats.total || 1))"
                :stroke-width="10"
                class="dist-bar"
              />
              <span class="dist-meta">{{ u.total }} 条 · 失败率 {{ u.failureRate }}%</span>
            </div>
          </el-col>
        </el-row>
      </div>

      <!-- 诊断发现 -->
      <div v-if="report" v-loading="diagLoading" class="findings-area">
        <el-divider content-position="left">
          诊断结果（{{ report.timeRange.from }} 至 {{ report.timeRange.to }}）
        </el-divider>
        <div
          v-for="f in report.findings"
          :key="f.ruleId"
          class="finding-card"
          :class="'sev-' + f.severity.toLowerCase()"
        >
          <div class="finding-head">
            <el-tag :type="sevType(f.severity)" size="small" effect="dark">{{ sevLabel(f.severity) }}</el-tag>
            <span class="finding-rule">{{ f.ruleId }}</span>
            <span class="finding-title">{{ f.title }}</span>
          </div>
          <div class="finding-desc">{{ f.description }}</div>
          <div class="finding-foot">
            <el-button
              v-if="f.evidenceLogIds.length"
              link
              type="primary"
              size="small"
              @click="viewEvidence(f.evidenceLogIds)"
            >查看证据日志（{{ f.evidenceLogIds.length }}）</el-button>
            <span class="finding-rec">建议：{{ f.recommendation }}</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span class="panel-title">日志列表（{{ filteredLogs.length }} 条，最新在前）</span>
        <el-button
          v-if="evidenceIds"
          size="small"
          type="warning"
          plain
          style="margin-left:12px"
          @click="clearEvidence"
        >退出证据视图</el-button>
      </template>
      <el-table :data="filteredLogs" border stripe size="default" height="600">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="类别" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.kind === 'login' ? 'warning' : 'primary'">
              {{ row.kind === 'login' ? '登录' : '操作' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="模块" width="100">
          <template #default="{ row }">{{ MODULE_LABEL[row.module] || row.module }}</template>
        </el-table-column>
        <el-table-column label="动作" width="80">
          <template #default="{ row }">{{ ACTION_LABEL[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column prop="target" label="对象" min-width="150" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="结果" width="80" fixed="right">
          <template #default="{ row }">
            <el-tag size="small" :type="row.result === 'success' ? 'success' : 'danger'">
              {{ row.result === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的日志" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.log-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.filter-card :deep(.el-card__body) {
  padding-bottom: 2px;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
.insight-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.stats-row {
  display: flex;
  gap: 14px;
  margin: 6px 0 18px;
  flex-wrap: wrap;
}
.kpi {
  flex: 1;
  min-width: 110px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  text-align: center;
  .kpi-num {
    display: block;
    font-size: 24px;
    font-weight: 700;
    color: var(--iam-text-strong);
    line-height: 1.2;
  }
  .kpi-label {
    font-size: 12px;
    color: var(--iam-text-muted);
  }
  &.kpi-ok {
    background: var(--el-color-success-light-9);
    .kpi-num { color: var(--el-color-success); }
  }
  &.kpi-bad {
    background: var(--el-color-danger-light-9);
    .kpi-num { color: var(--el-color-danger); }
  }
}
.dist-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
  margin-bottom: 8px;
}
.dist-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.dist-name {
  width: 84px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--iam-text-base);
}
.dist-bar {
  flex: 1;
}
.dist-meta {
  width: 132px;
  flex-shrink: 0;
  font-size: 11px;
  color: var(--iam-text-muted);
  text-align: right;
}
.finding-card {
  border: 1px solid var(--el-border-color);
  border-left-width: 4px;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 10px;
}
.finding-card.sev-high { border-left-color: var(--el-color-danger); }
.finding-card.sev-medium { border-left-color: var(--el-color-warning); }
.finding-card.sev-low { border-left-color: var(--el-color-info); }
.finding-card.sev-info { border-left-color: var(--el-color-success); }
.finding-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.finding-rule {
  font-family: monospace;
  font-size: 12px;
  color: var(--iam-text-muted);
}
.finding-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
.finding-desc {
  font-size: 13px;
  color: var(--iam-text-base);
  line-height: 1.6;
  margin-bottom: 6px;
}
.finding-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.finding-rec {
  font-size: 12.5px;
  color: var(--iam-text-muted);
}
</style>
