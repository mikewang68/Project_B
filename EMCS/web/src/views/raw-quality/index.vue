<!--
  原始数据与质量（第二幕主页面）
  视觉方向：方向 A 深色调度控制台风（沿用 dashboard tokens）
  REQ 锚点：REQ-010 采集字段 · REQ-011 幂等键（点位+时间）· REQ-012/020 重算标记
           REQ-013 采集任务状态 · REQ-014 P0 断网缓存与补传可视
           REQ-018 质量标记枚举 · REQ-019 覆盖率 95/80 两档
           REQ-022 原始/换算/修正/估算对应 · REQ-023 质量总览
  数据契约：src/api/rawQuality.js（mock：./mock.js，种子 42）
  演示叙事：INJ-01 A 区电表 07-06 09:20-13:40 断传→补传→重算
-->
<template>
  <div class="rq-dark cockpit-page">
    <!-- ============ 顶栏筛选 + 演示态签名 ============ -->
    <div class="rq-filters">
      <div class="fg">
        <span class="fg-label">装卸区</span>
        <el-segmented v-model="filters.zone" :options="zoneOptions" size="small" />
      </div>
      <div class="fg">
        <span class="fg-label">能源类型</span>
        <el-segmented v-model="filters.energyType" :options="energyOptions" size="small" />
      </div>
      <div class="fg">
        <span class="fg-label">时间窗</span>
        <span class="fg-static">{{ payload?.filters?.timeStart?.slice(0, 16) }} → {{ payload?.filters?.timeEnd?.slice(5, 16) }}</span>
      </div>
      <div class="fg fg-end">
        <span class="refresh-dot" :class="{ pulsing: !loading }" />
        <span class="refresh-txt">{{ loading ? '拉取中…' : `DEMO_NOW · ${payload?.signature?.generatedAt || ''}` }}</span>
      </div>
    </div>

    <!-- 演示态声明 -->
    <div v-if="payload?.demoState?.enabled" class="rq-banner">
      <span class="left">▎ <b>{{ payload.demoState.reqAnchor }} 断网缓存 P0</b> · {{ payload.demoState.hint }}</span>
      <span class="right">签名 {{ payload.signature.version }} · SEED {{ payload.signature.seed }} · sig:{{ payload.signature.sigId }}</span>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon style="margin-top:16px"><el-button @click="loadSummary" :loading="loading">重新加载</el-button></el-alert>
    <div v-if="loading && !payload" class="rq-loading">正在拉取数据…</div>

    <div v-if="payload" class="rq-main">
      <!-- ============ 左侧：点位树 ============ -->
      <aside class="rq-tree-panel">
        <div class="tree-head">
          <span class="tree-title">采集点</span>
          <span class="tree-sub">{{ groupedPoints.length }} 台设备 · {{ filteredPoints.length }} 点位</span>
        </div>
        <div class="tree-body">
          <!-- 过滤结果空态（如"B 区 · 水"组合下可能无点位） -->
          <div v-if="!groupedPoints.length" class="tree-empty">
            <div>{{ zoneLabel }}下无 <b>{{ energyTypeLabel }}</b> 类点位</div>
            <div class="te-hint">试试切换能源类型或装卸区</div>
          </div>
          <div v-for="g in groupedPoints" :key="g.deviceId" class="tree-group">
            <div class="tree-device">
              <span class="tree-zone" :class="`zone-${g.zone.toLowerCase()}`">{{ g.zone }}</span>
              {{ g.deviceName }}
              <small>{{ g.energyType === 'ELEC' ? '电' : g.energyType === 'WATER' ? '水' : '气' }}</small>
            </div>
            <ul class="tree-points">
              <li
                v-for="p in g.points"
                :key="p.pointId"
                :class="{ active: filters.pointId === p.pointId }"
                @click="selectPoint(p.pointId)"
              >
                <span class="pt-id">{{ p.pointId }}</span>
                <span class="pt-name">{{ p.pointName }}</span>
                <span class="pt-int">{{ p.samplingInterval }}</span>
              </li>
            </ul>
          </div>
        </div>
      </aside>

      <!-- ============ 右主区 ============ -->
      <section class="rq-content">
        <!-- 覆盖率 + 质量分布 -->
        <div class="rq-summary">
          <div class="cov-card" :class="`band-${payload.coverage.band}`">
            <div class="cov-head">
              <span class="cov-code">01 · COVERAGE</span>
              <span class="cov-band">{{ bandLabel(payload.coverage.band) }}</span>
            </div>
            <div class="cov-val">
              {{ payload.coverage.pct }}<span class="cov-unit">%</span>
            </div>
            <div class="cov-label">
              阈值 {{ payload.coverage.threshold.warn }}% / {{ payload.coverage.threshold.serious }}% · 缺失 {{ payload.coverage.missingSlotCount }} 槽 · REQ-019
            </div>
          </div>

          <div class="q-bar-card">
            <div class="q-bar-head">
              <span class="q-bar-title">质量分布 · {{ payload.selected.pointName }}</span>
              <span class="q-bar-sub">附录 C 八态定义 · 当前构造前 6 态</span>
            </div>
            <div class="q-bar-track">
              <div
                v-for="q in payload.qualityBreakdown"
                :key="q.quality"
                class="q-bar-seg"
                :class="`seg-${q.quality}`"
                :style="{ flexGrow: q.count || 1 }"
                :title="`${qualityLabel(q.quality)} · ${q.count} 条 · ${q.pct}%`"
              >
                <span v-if="q.pct >= 5" class="seg-txt">{{ qualityLabel(q.quality) }} {{ q.pct }}%</span>
              </div>
            </div>
            <div class="q-bar-legend">
              <span v-for="q in payload.qualityBreakdown" :key="q.quality" class="lg-item">
                <i class="lg-dot" :class="`sw-${q.quality}`" />
                {{ qualityLabel(q.quality) }} <b>{{ q.count }}</b>
              </span>
            </div>
          </div>
        </div>

        <!-- 重算提示条（有待重算就显示） -->
        <div v-if="payload.recomputeHints.length" class="rq-recompute">
          <div v-for="h in payload.recomputeHints" :key="h.periodKey" class="rc-row" :class="`rc-${h.status}`">
            <span class="rc-icon">▎</span>
            <div class="rc-body">
              <div class="rc-title">
                <template v-if="h.status === 'pending'">
                  {{ h.periodKey }} · {{ h.scope === 'day' ? '日' : '月' }}统计待重算
                  <small>由 {{ h.triggeredBy }} 触发 · REQ-012/020</small>
                </template>
                <template v-else-if="h.status === 'done'">
                  {{ h.periodKey }} · 重算完成 · v{{ h.oldVersion?.replace('v', '') || '1.0' }} → v{{ h.newVersion?.replace('v', '') || '1.1' }}
                  <small>{{ h.finishedAt }}</small>
                </template>
              </div>
              <div v-if="h.diffSummary" class="rc-diff">
                {{ h.diffSummary.metric }}：
                <b>{{ h.diffSummary.oldValue.toLocaleString() }} → {{ h.diffSummary.newValue.toLocaleString() }}</b>
                <span class="rc-delta">Δ +{{ h.diffSummary.deltaPct }}%</span>
              </div>
            </div>
            <el-button
              v-if="h.status === 'pending'"
              size="small"
              class="rc-btn"
              @click="doRecompute(h)"
              :loading="recomputing === h.periodKey"
            >一键重算</el-button>
          </div>
        </div>

        <!-- 原始读数曲线 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">原始读数曲线 · {{ payload.selected.pointName }}</div>
              <div class="panel-sub">
                {{ payload.selected.samplingInterval }} 采样 · 质量着色 · 附录 C 八态 · REQ-010/011/018
              </div>
            </div>
            <div class="head-side">
              <span class="task-state" :class="`ts-${payload.selected.currentTaskState}`">
                任务态 · {{ taskStateLabel(payload.selected.currentTaskState) }}
              </span>
            </div>
          </header>

          <div class="q-legend">
            <span v-for="q in chartLegend" :key="q.key" class="lg">
              <i class="lg-sw" :style="{ background: q.color }" />
              {{ q.label }}
            </span>
          </div>

          <!-- 数据不足空态（REQ-019 <80% 阈值时明示"不出正式结论"） -->
          <div v-if="!payload.readings.length" class="trend-empty">
            <div class="te-icon">▨</div>
            <div class="te-title">数据不足 · 不出正式结论</div>
            <div class="te-desc">
              {{ payload.selected.pointName }} 当日采样覆盖率
              <b>{{ payload.coverage.pct }}%</b>
              低于 <b>{{ payload.coverage.threshold.serious }}%</b> 严重阈值 ·
              该点位不进入正式统计（REQ-019）
            </div>
          </div>
          <div v-else ref="trendChartEl" class="trend-chart" />

          <footer class="panel-foot">
            <span>覆盖率 {{ payload.coverage.pct }}% · 缺失槽 {{ payload.coverage.missingSlotCount }}</span>
            <span>幂等键 = 点位 + 采样时间 · REQ-011</span>
            <span>公式 {{ payload.signature.formulaVersion }}</span>
          </footer>
        </section>

        <!-- 采集任务状态表 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">采集任务状态 · REQ-013</div>
              <div class="panel-sub">数据源 · 最近成功/失败 · 影响点位</div>
            </div>
            <div class="head-side">
              <el-button
                v-if="canBackfill"
                size="small"
                type="primary"
                @click="showBackfill = true"
              >执行补传</el-button>
            </div>
          </header>

          <div class="tbl-wrap">
            <table class="rq-tbl">
              <thead>
                <tr>
                  <th>任务</th><th>数据源</th><th>状态</th>
                  <th>最近成功</th><th>失败批次</th><th>影响点位</th><th>最近错误</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in payload.tasks" :key="t.taskId">
                  <td>
                    <div class="tk-name">{{ t.taskName }}</div>
                    <div class="tk-id">{{ t.taskId }}</div>
                  </td>
                  <td>{{ dataSourceLabel(t.dataSource) }}</td>
                  <td>
                    <span class="tk-state" :class="`ts-${t.currentState}`">
                      {{ taskStateLabel(t.currentState) }}
                    </span>
                  </td>
                  <td>{{ t.lastSuccessAt?.slice(11, 16) || '—' }}</td>
                  <td>{{ t.failureBatchCount }}</td>
                  <td>{{ t.affectedPoints.slice(0, 2).join(', ') }}<span v-if="t.affectedPoints.length > 2"> +{{ t.affectedPoints.length - 2 }}</span></td>
                  <td>{{ t.lastError || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 补传批次历史 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">补传批次历史 · REQ-014 P0</div>
              <div class="panel-sub">缓存起止 · 批次 · 重复处理 · 失败原因</div>
            </div>
          </header>

          <div v-if="!payload.backfillBatches.length" class="tbl-empty">
            暂无补传记录 — 演示态：从"执行补传"发起 INJ-01 修复
          </div>
          <div v-else class="tbl-wrap">
            <table class="rq-tbl">
              <thead>
                <tr>
                  <th>批次号</th><th>触发人</th><th>触发时间</th>
                  <th>缓存起止</th><th>点位数</th><th>写入 / 重复</th><th>状态</th><th>影响周期</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="b in payload.backfillBatches" :key="b.batchId">
                  <td>{{ b.batchId }}</td>
                  <td>{{ b.triggeredBy }}</td>
                  <td>{{ b.triggeredAt?.slice(11, 16) || '—' }}</td>
                  <td>{{ b.cacheStart?.slice(11, 16) || '—' }} → {{ b.cacheEnd?.slice(11, 16) || '—' }}</td>
                  <td>{{ b.pointIds.length }}</td>
                  <td>{{ b.recordsIngested }} / {{ b.dupHandledCount }}</td>
                  <td>
                    <span class="bf-status" :class="`bs-${b.status}`">
                      {{ backfillStatusLabel(b.status) }}
                    </span>
                  </td>
                  <td>{{ b.affectedPeriods.join(', ') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <div class="rq-footer">
          <span>REQ-010 · 011 · 012 · 013 · 014 · 018 · 019 · 020 · 022 · 023 · 062</span>
          <span>第二幕 · 原始数据与质量 · RAW DATA & QUALITY</span>
          <span>口径签名 {{ payload.signature.version }} · SEED {{ payload.signature.seed }}</span>
        </div>
      </section>
    </div>

    <!-- ============ 补传弹窗 ============ -->
    <el-dialog
      v-model="showBackfill"
      title="执行补传 · REQ-014"
      width="480px"
      class="cockpit-modal"
      align-center
    >
      <div class="bf-form">
        <div class="bf-row"><label>目标点位</label><span>{{ payload?.selected?.pointName || '—' }}（{{ payload?.selected?.pointId || '—' }}）</span></div>
        <div class="bf-row"><label>缓存起止</label><span>{{ formatOutageWindow(backfillWindow) }}</span></div>
        <div class="bf-row"><label>受影响周期</label><span>{{ affectedPeriodsText }}</span></div>
        <div class="bf-row"><label>幂等键</label><span>点位 + 采样时间（REQ-011）</span></div>
        <div class="bf-hint">
          补传写入采用幂等键去重，同点位同采样时间不会重复计入统计增量；
          写入后自动派生日/月重算提示。
        </div>
      </div>
      <template #footer>
        <el-button @click="showBackfill = false">取消</el-button>
        <el-button type="primary" :loading="backfilling" @click="doBackfill">确认补传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart, ScatterChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, MarkAreaComponent, MarkLineComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getRawQualitySummary, postBackfill, postRecompute } from '@/api/rawQuality'
import { deriveBackfillWindow } from './backfillWindow'
import { useChartTheme, chartSeriesColors } from '@/views/energy/shared/cockpitTheme'

echarts.use([LineChart, ScatterChart, GridComponent, TooltipComponent, MarkAreaComponent, MarkLineComponent, CanvasRenderer])

// 双主题响应式：切顶栏夜览时图表即时重绘
const theme = useChartTheme()

defineOptions({ name: 'RawQuality' })

// -------- 筛选 --------
const zoneOptions = [
  { label: '全部', value: 'ALL' }, { label: 'A · 钢材', value: 'A' }, { label: 'B · 粉煤灰', value: 'B' }
]
const energyOptions = [
  { label: '电', value: 'ELEC' }, { label: '水', value: 'WATER' }, { label: '压缩空气', value: 'AIR' }
]
// 初始 pointId 留空 —— 由自愈逻辑挑第一个点位（backend 命名策略不定，避免硬编码打不中）
const filters = reactive({ zone: 'ALL', energyType: 'ELEC', pointId: null })

// -------- 数据 --------
const payload = ref(null)
const loading = ref(false)
const loadError = ref('')
let requestSequence = 0
// 一次性自愈 guard：首屏 pointId 命中不了时自动选 points[0]，只触发一次避免循环
let hasAutoSelected = false

async function loadSummary() {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = ''
  try {
    const resp = await getRawQualitySummary({ ...filters })
    if (sequence !== requestSequence) return
    payload.value = resp.data
    // A（QA 2026-07-14 联调复检裁决）：命中不到点位时自动选第一个
    if (!hasAutoSelected && !payload.value?.selected && payload.value?.points?.length) {
      hasAutoSelected = true
      filters.pointId = payload.value.points[0].pointId
      const resp2 = await getRawQualitySummary({ ...filters })
      if (sequence !== requestSequence) return
      payload.value = resp2.data
    }
    await nextTick()
    renderTrend()
  } catch (error) {
    if (sequence === requestSequence) loadError.value = error.message || '数据加载失败，请重试'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function selectPoint(id) {
  filters.pointId = id
  loadSummary()
}

// -------- 能源类型本地过滤（#24 追加：切换 energyType 前端过滤 points 树）
//  状态点（*-STATUS）与环境点（energyType 为空/null）不属于任何能源介质，任何筛选下常显
const filteredPoints = computed(() => {
  if (!payload.value?.points) return []
  return payload.value.points.filter((p) => !p.energyType || p.energyType === filters.energyType)
})

// -------- 分组点位（用过滤结果分组）--------
const groupedPoints = computed(() => {
  if (!filteredPoints.value.length) return []
  const map = new Map()
  for (const p of filteredPoints.value) {
    if (!map.has(p.deviceId)) {
      map.set(p.deviceId, {
        deviceId: p.deviceId, deviceName: p.deviceName,
        zone: p.zone, energyType: p.energyType, points: []
      })
    }
    map.get(p.deviceId).points.push(p)
  }
  return Array.from(map.values())
})

// -------- 演示交互 --------
const showBackfill = ref(false)
const backfilling = ref(false)
const recomputing = ref(null)

const canBackfill = computed(() =>
  payload.value?.tasks.some((t) => t.currentState === 'failed')
)

const backfillWindow = computed(() => deriveBackfillWindow(
  payload.value?.readings,
  payload.value?.selected?.samplingInterval
))

async function doBackfill() {
  if (!backfillWindow.value) {
    ElMessage.error('当前无连续缺测窗，无法执行补传')
    return
  }
  backfilling.value = true
  try {
    await postBackfill({
      pointId: payload.value.selected.pointId,
      cacheStart: backfillWindow.value.cacheStart,
      cacheEnd: backfillWindow.value.cacheEnd
    })
    ElMessage.success('补传写入成功 · 已派生日/月统计待重算')
    showBackfill.value = false
    await loadSummary()
  } finally {
    backfilling.value = false
  }
}

async function doRecompute(hint) {
  recomputing.value = hint.periodKey
  try {
    await postRecompute({ periodKey: hint.periodKey, scope: hint.scope })
    ElMessage.success(`${hint.periodKey} 重算完成 · 版本已递增`)
    await loadSummary()
  } finally {
    recomputing.value = null
  }
}

// C6：从当前 miss 段派生 affectedPeriods（日 + 月），与 backend 派生逻辑一致
const affectedPeriodsText = computed(() => {
  const miss = payload.value?.readings?.filter((r) => r.quality === 'miss') || []
  if (!miss.length) return '（当前无缺测窗）'
  const days = [...new Set(miss.map((r) => r.ts.slice(0, 10)))].sort()
  const months = [...new Set(days.map((d) => d.slice(0, 7)))].sort()
  const dayLabels = days.map((d) => `${d} 日统计`).join(' · ')
  const monthLabels = months.map((m) => `${m} 月统计`).join(' · ')
  return `${dayLabels} · ${monthLabels}`
})

function formatOutageWindow(window) {
  if (!window) return '（当前无断传）'
  return `${window.cacheStart.slice(0, 16)} → ${window.cacheEnd.slice(0, 16)}`
}

// -------- 曲线（ECharts，质量着色） --------
const trendChartEl = ref(null)
let trendChart = null

// 质量色沿用统一主题，运行蓝、待机/维护黄、停机灰。
const qualityColorMap = computed(() => {
  const t = theme.value
  return {
    ok: t.lime, miss: t.red, late: t.amber, est: t.violet,
    fix: t.cyan,
    jump: t.amber,
    dup: t.ink3, frozen: t.ink3
  }
})
const qualityColors = [
  { key: 'ok', label: '正常' }, { key: 'miss', label: '缺测' },
  { key: 'late', label: '迟到' }, { key: 'est', label: '估算' },
  { key: 'fix', label: '人工修正/补传' }, { key: 'jump', label: '异常跳变' }
]

// #24 追加：装卸区/能源类型中文标签（空态提示复用）
const zoneLabel = computed(() => ({ ALL: '全区', A: 'A 区（钢材）', B: 'B 区（粉煤灰）' })[filters.zone] || filters.zone)
const energyTypeLabel = computed(() => ({ ELEC: '电', WATER: '水', AIR: '压缩空气' })[filters.energyType] || filters.energyType)

// #24-2 图例跟随点位类型：STATUS 点位用状态色，其它用质量色
const isStatusPointReactive = computed(() => {
  const sel = payload.value?.selected
  const rs = payload.value?.readings || []
  return (sel?.pointId || '').endsWith('-STATUS') || rs.some((r) => r.statusValue != null)
})
const chartLegend = computed(() => {
  if (isStatusPointReactive.value) {
    const t = theme.value
    return [
      { key: 'running', label: '运行', color: t.cyan },
      { key: 'standby', label: '待机', color: t.amber },
      { key: 'stopped', label: '停机', color: t.ink3 },
      { key: 'maintenance', label: '维护', color: t.amber }
    ]
  }
  return qualityColors.map((q) => ({ ...q, color: qualityColorMap.value[q.key] }))
})

function computeMissAreas(readings) {
  // 计算 miss 连续段作 markArea 阴影
  const missTint = theme.value.redTint
  const areas = []
  let start = null
  for (let i = 0; i < readings.length; i++) {
    if (readings[i].quality === 'miss') {
      if (start === null) start = readings[i].ts.slice(11, 16)
    } else if (start !== null) {
      areas.push([{ xAxis: start, itemStyle: { color: missTint } }, { xAxis: readings[i - 1].ts.slice(11, 16) }])
      start = null
    }
  }
  if (start !== null) {
    areas.push([{ xAxis: start, itemStyle: { color: missTint } }, { xAxis: readings[readings.length - 1].ts.slice(11, 16) }])
  }
  return areas
}

// #24-2 状态点位（*-STATUS）色带渲染：响应主题
const statusColorMap = computed(() => {
  const t = theme.value
  return {
    running: t.cyan, standby: t.amber, stopped: t.violet,
    maintenance: t.amber
  }
})
const statusLabel = (v) => ({
  running: '运行', standby: '待机', stopped: '停机', maintenance: '维护'
})[v] || v || '未知'

// 连续同 status 段合并成一片色带
function computeStatusRuns(readings) {
  const runs = []
  let start = 0
  for (let i = 1; i <= readings.length; i++) {
    if (i === readings.length || readings[i].statusValue !== readings[start].statusValue) {
      runs.push({
        startTs: readings[start].ts.slice(11, 16),
        endTs: readings[i - 1].ts.slice(11, 16),
        value: readings[start].statusValue,
        span: i - start,
        startIdx: start,
        endIdx: i - 1
      })
      start = i
    }
  }
  return runs
}

function isStatusPoint() {
  const sel = payload.value?.selected
  const rs = payload.value?.readings || []
  return (sel?.pointId || '').endsWith('-STATUS') || rs.some((r) => r.statusValue != null)
}

// x 轴标签自适应稀疏化（FX-38）：分钟级采样点跨长窗口时,固定 interval 会把
// 几十个 HH:MM 标签挤成一团。只在整点出标签,整点数 >8 时按 2h/3h… 递进。
function planMinuteAxisInterval(labels) {
  const hourlyCount = labels.filter((v) => String(v).endsWith(':00')).length
  const step = Math.max(1, Math.ceil(hourlyCount / 8))
  return (_idx, value) => {
    const v = String(value)
    if (!v.endsWith(':00')) return false
    return Number(v.slice(0, 2)) % step === 0
  }
}

function renderTrend() {
  if (!trendChartEl.value || !payload.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartEl.value, null, { renderer: 'canvas' })
  }
  trendChart.clear()  // 切点位时切换渲染模式，清掉旧 series
  const readings = payload.value.readings
  const xLabels = readings.map((r) => r.ts.slice(11, 16))
  const pal = theme.value
  const qc = qualityColorMap.value
  const sc = statusColorMap.value

  // 状态点位（*-STATUS）走色带渲染分支
  if (isStatusPoint()) {
    const runs = computeStatusRuns(readings)
    // x 轴只标各状态段的起止时刻（用户裁决 2026-07-20）：段起点 + 最末端点;
    // 相邻边界距离小于总长 5% 时跳过后者防重叠,hideOverlap 兜底
    const boundarySet = new Set()
    {
      const minGap = Math.max(2, Math.round(readings.length * 0.05))
      let last = -Infinity
      const candidates = [...runs.map((r) => r.startIdx), readings.length - 1]
      for (const idx of candidates) {
        if (idx - last >= minGap) { boundarySet.add(idx); last = idx }
      }
      // 最末端点若因间距被跳过,回头替换掉挡它的前一个,保证窗口终点始终可见
      if (!boundarySet.has(readings.length - 1)) {
        const arr = [...boundarySet].sort((a, b) => a - b)
        boundarySet.delete(arr[arr.length - 1])
        boundarySet.add(readings.length - 1)
      }
    }
    trendChart.setOption({
      animation: true, animationDuration: 500,
      grid: { top: 40, left: 60, right: 24, bottom: 34 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: pal.tooltipBg, borderColor: pal.tooltipBorder,
        textStyle: { color: pal.tooltipInk, fontSize: 12 },
        formatter: (params) => {
          const p = params[0]; if (!p) return ''
          const rec = readings[p.dataIndex]; if (!rec) return ''
          return `${rec.ts.slice(11, 16)}<br/>
            状态 <b style="color:${sc[rec.statusValue]||pal.ink2}">${statusLabel(rec.statusValue)}</b><br/>
            质量 ${rec.quality || '—'}`
        }
      },
      xAxis: {
        type: 'category', data: xLabels,
        axisLine: { lineStyle: { color: pal.lineStrong } }, axisTick: { show: false },
        axisLabel: { color: pal.ink3, fontFamily: 'SF Mono, monospace', fontSize: 10, interval: (idx) => boundarySet.has(idx), hideOverlap: true }
      },
      yAxis: {
        type: 'category', data: ['运行状态'],
        axisLine: { show: false }, axisTick: { show: false },
        axisLabel: { color: pal.ink2, fontFamily: 'PingFang SC, sans-serif', fontSize: 11 }
      },
      series: [{
        // 空 host line，只承载 markArea；不显示线本身
        name: '状态带', type: 'line',
        data: readings.map(() => 0),
        symbol: 'none', lineStyle: { opacity: 0 },
        markArea: {
          silent: false,
          data: runs.map((r) => [
            {
              xAxis: r.startTs,
              itemStyle: {
                color: sc[r.value] || pal.ink3,
                opacity: 0.85, borderColor: pal.panel, borderWidth: 1
              },
              name: r.span >= 4 ? statusLabel(r.value) : ''
            },
            { xAxis: r.endTs }
          ]),
          label: {
            show: true, position: 'inside', color: pal.bg,
            fontFamily: 'SF Mono, monospace', fontSize: 10, fontWeight: 600
          }
        }
      }]
    })
    return
  }

  // 默认：数值曲线（#24-1 主曲线画 delta 增量 —— 用户走查主问题修复）
  const lineData = readings.map((r) => r.delta)
  const scatterData = readings.map((r) => ({
    value: [r.ts.slice(11, 16), r.delta ?? null],
    itemStyle: { color: qc[r.quality] || pal.ink3 },
    quality: r.quality
  })).filter((d) => d.value[1] !== null)

  trendChart.setOption({
    animation: true,
    animationDuration: 500,
    grid: { top: 20, left: 60, right: 24, bottom: 34 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: pal.tooltipBg,
      borderColor: pal.tooltipBorder,
      textStyle: { color: pal.tooltipInk, fontSize: 12 },
      formatter: (params) => {
        const p = params[0] || params[1]
        if (!p) return ''
        const rec = readings[p.dataIndex]
        if (!rec) return ''
        return `${rec.ts.slice(11, 16)}<br/>
          <b>增量 ${rec.delta ?? '—'} ${rec.unit}</b>（15min）<br/>
          累计 ${rec.cumulative ?? '—'} ${rec.unit}<br/>
          质量 <b style="color:${qc[rec.quality]}">${rec.quality}</b>${rec.isBackfill ? ' · 补传' : ''}`
      }
    },
    xAxis: {
      type: 'category',
      data: xLabels,
      axisLine: { lineStyle: { color: pal.lineStrong } },
      axisTick: { show: false },
      axisLabel: { color: pal.ink3, fontFamily: 'SF Mono, monospace', fontSize: 10, interval: planMinuteAxisInterval(xLabels), hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      name: (payload.value.selected?.unit || '') + ' (15min 增量)',
      nameTextStyle: { color: pal.ink2, fontFamily: 'SF Mono, monospace', fontSize: 10, padding: [0, 0, 6, 0] },
      splitLine: { lineStyle: { color: pal.splitLine } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: pal.ink3, fontFamily: 'SF Mono, monospace', fontSize: 10 }
    },
    series: [
      {
        name: '增量',
        type: 'line',
        data: lineData,
        symbol: 'none',
        smooth: true,
        connectNulls: false,
        lineStyle: { color: pal.cyan, width: 1.8 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: pal.areaTint },
            { offset: 1, color: 'transparent' }
          ])
        },
        markArea: {
          silent: true,
          data: computeMissAreas(readings),
          label: {
            show: true, color: pal.red, fontFamily: 'SF Mono, monospace', fontSize: 10,
            position: 'insideTop', formatter: '缺测段'
          }
        },
        z: 2
      },
      {
        name: '质量点',
        type: 'scatter',
        data: scatterData,
        symbolSize: 8,
        itemStyle: { borderColor: pal.panel, borderWidth: 1 },
        z: 3
      }
    ]
  })
}

// -------- 标签映射 --------
function bandLabel(b) { return { ok: '合格', degraded: '降级', insufficient: '数据不足' }[b] || b }
function qualityLabel(q) {
  return { ok: '正常', miss: '缺测', late: '迟到', est: '估算', fix: '补传/修正', jump: '跳变', dup: '重复', frozen: '冻结疑似' }[q] || q
}
function taskStateLabel(s) { return { running: '运行', degraded: '降级', failed: '失败' }[s] || s }
function dataSourceLabel(s) { return { gateway: '采集网关', base: '数据底座', manual: '人工补录' }[s] || s }
function backfillStatusLabel(s) { return { queued: '排队', running: '执行中', succeeded: '成功', failed: '失败' }[s] || s }

function resizeChart() { trendChart?.resize() }
onMounted(async () => {
  await loadSummary()
  window.addEventListener('resize', resizeChart)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  trendChart?.dispose()
  trendChart = null
})

// 主题切换即时重绘
watch(theme, () => { renderTrend() })
watch(() => filters.zone, () => loadSummary())
// #24 追加：切能源类型不打后端接口（本地过滤 points 树）；
// 但如果当前选中点不在过滤结果里（比如 selected 是水表、切到"电"），自愈到过滤后第一个
watch(() => filters.energyType, () => {
  const current = payload.value?.selected?.pointId
  const stillIn = filteredPoints.value.some((p) => p.pointId === current)
  if (!stillIn && filteredPoints.value.length) {
    selectPoint(filteredPoints.value[0].pointId)
  }
})
</script>

<style scoped>
/* ==================== 页面布局（token 走 cockpit-page 全局主题） ==================== */
/* .rq-dark 只负责布局特化与页专色；主题 token 由 .cockpit-page 提供 */
.rq-dark{
  /* 页专色（cockpit-tokens 未覆盖，页级双主题双写） */
  --orange:var(--amber);
  --blue:var(--cyan);

  min-height:calc(100vh - 84px);
  background:var(--bg);
  color:var(--ink);
  padding:16px 20px 40px;
  margin:-16px -16px 0;
  font-family:"PingFang SC","Hiragino Sans GB","Microsoft YaHei",system-ui,sans-serif;
  font-size:13px; line-height:1.5; letter-spacing:.01em;
}
html.dark .rq-dark{
  --orange:var(--amber);
  --blue:var(--cyan);
}
.rq-dark :deep(.el-segmented){
  --el-segmented-bg-color:var(--panel-2);
  --el-segmented-item-selected-color:var(--cyan);
  --el-segmented-item-selected-bg-color:var(--cyan-tint);
  --el-segmented-color:var(--ink-2);
  --el-border-color:var(--line-strong);
  border:1px solid var(--line-strong); border-radius:2px;
}
.rq-dark :deep(.el-segmented .el-segmented__item-label){
  font-family:var(--mono); font-size:11px; letter-spacing:.06em;
}
.rq-dark :deep(.el-button){
  font-family:var(--mono); font-size:11px; letter-spacing:.06em;
}

/* ==================== 顶栏 ==================== */
.rq-filters{
  display:flex; align-items:center; gap:20px; flex-wrap:wrap;
  padding:12px 14px; background:var(--panel);
  border:1px solid var(--line); border-radius:2px;
}
.fg{display:flex; align-items:center; gap:8px;}
.fg-label{font-family:var(--mono); font-size:12px; letter-spacing:.14em; color:var(--ink-3); text-transform:uppercase;}
.fg-static{font-family:var(--mono); font-size:11px; color:var(--ink-2); padding:4px 10px; border:1px solid var(--line-strong); border-radius:2px; background:var(--panel-2);}
.fg-end{margin-left:auto; display:flex; align-items:center; gap:8px; font-family:var(--mono); font-size:11px; color:var(--ink-2);}
.refresh-dot{width:6px; height:6px; border-radius:50%; background:var(--lime); box-shadow:none;}
.refresh-dot.pulsing{animation:pulse 2s infinite;}
@keyframes pulse{0%,100%{opacity:1} 50%{opacity:.35}}
@media (prefers-reduced-motion: reduce){
  .refresh-dot.pulsing{animation:none;}
}
.refresh-txt{color:var(--ink-2);}

/* ==================== 演示态条 ==================== */
.rq-banner{
  margin-top:12px; padding:8px 14px;
  background:var(--amber-tint); border:1px solid color-mix(in srgb, var(--amber) 25%, transparent);
  border-radius:2px; display:flex; justify-content:space-between; align-items:center;
  font-family:var(--mono); font-size:12px; letter-spacing:.08em; color:var(--amber);
  flex-wrap:wrap; gap:8px;
}
.rq-banner .left b{color:var(--ink); font-family:"PingFang SC",system-ui,sans-serif; font-weight:400;}
.rq-banner .right{color:var(--ink-3);}
.rq-loading{margin-top:24px; padding:24px; text-align:center; font-family:var(--mono); color:var(--ink-3); background:var(--panel); border:1px solid var(--line);}

/* ==================== 主布局 ==================== */
.rq-main{margin-top:12px; display:grid; grid-template-columns:280px 1fr; gap:12px;}
.rq-tree-panel{background:var(--panel); border:1px solid var(--line); border-radius:2px; padding:12px; height:calc(100vh - 220px); overflow:auto;}
.rq-content{display:flex; flex-direction:column; gap:12px; min-width:0;}

/* ==================== 点位树 ==================== */
.tree-head{display:flex; justify-content:space-between; align-items:baseline; padding-bottom:8px; border-bottom:1px solid var(--line); margin-bottom:8px;}
.tree-title{font-family:var(--serif); font-size:14px; color:var(--ink); letter-spacing:.06em;}
.tree-sub{font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.08em;}
.tree-body{display:flex; flex-direction:column; gap:12px;}
.tree-group{}
.tree-device{
  display:flex; align-items:center; gap:6px;
  font-size:12px; color:var(--ink); margin-bottom:4px;
  padding:4px 0; border-bottom:1px solid var(--line);
}
.tree-device small{margin-left:auto; color:var(--ink-3); font-family:var(--mono); font-size:12px;}
.tree-zone{
  padding:0 5px; font-family:var(--mono); font-size:12px; letter-spacing:.1em;
  border:1px solid var(--line-strong); border-radius:1px; color:var(--ink-2);
}
.tree-zone.zone-a{color:var(--cyan); border-color:color-mix(in srgb, var(--cyan) 40%, transparent); background:var(--cyan-tint);}
.tree-zone.zone-b{color:var(--orange); border-color:color-mix(in srgb, var(--orange) 40%, transparent); background:color-mix(in srgb, var(--orange) 6%, transparent);}
.tree-points{list-style:none; padding:0; margin:0; display:flex; flex-direction:column; gap:2px;}
.tree-points li{
  display:grid; grid-template-columns:60px 1fr 46px; gap:6px; align-items:center;
  padding:5px 8px; border-radius:2px; cursor:pointer;
  transition:background .15s;
}
.tree-points li:hover{background:var(--panel-2);}
.tree-points li.active{background:var(--cyan-tint); box-shadow:inset 2px 0 0 var(--cyan);}
.pt-id{font-family:var(--mono); font-size:12px; color:var(--ink-2); letter-spacing:.06em;}
.pt-name{font-size:12px; color:var(--ink); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;}
.pt-int{font-family:var(--mono); font-size:12px; color:var(--ink-3); text-align:right;}
.tree-empty{
  padding:20px 12px; text-align:center;
  color:var(--ink-2); font-size:12px; line-height:1.7;
  border:1px dashed var(--line); border-radius:2px;
  background:var(--cyan-tint);
}
.tree-empty b{color:var(--amber); font-weight:400; font-family:var(--mono);}
.te-hint{margin-top:6px; font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.06em;}

/* ==================== 覆盖率 + 质量分布 ==================== */
.rq-summary{display:grid; grid-template-columns:220px 1fr; gap:12px;}
.cov-card{background:var(--panel); border:1px solid var(--line); border-radius:2px; padding:14px; position:relative; overflow:hidden;}
.cov-card::before{content:""; position:absolute; left:0; top:0; bottom:0; width:2px; background:var(--lime);}
.cov-card.band-degraded::before{background:var(--amber);}
.cov-card.band-insufficient::before{background:var(--red);}
.cov-head{display:flex; justify-content:space-between; font-family:var(--mono); font-size:12px; letter-spacing:.14em; color:var(--ink-3); text-transform:uppercase; margin-bottom:8px;}
.cov-band{padding:1px 6px; border:1px solid var(--line-strong); border-radius:1px; color:var(--ink-2);}
.band-degraded .cov-band{color:var(--amber); border-color:color-mix(in srgb, var(--amber) 35%, transparent); background:var(--amber-tint);}
.band-insufficient .cov-band{color:var(--red); border-color:color-mix(in srgb, var(--red) 40%, transparent); background:var(--red-tint);}
.cov-val{font-family:var(--mono); font-size:32px; color:var(--ink); display:flex; align-items:baseline; gap:6px; font-variant-numeric:tabular-nums;}
.cov-unit{font-size:11px; color:var(--ink-3);}
.cov-label{font-family:var(--mono); font-size:12px; color:var(--ink-3); margin-top:8px; letter-spacing:.04em;}

.q-bar-card{background:var(--panel); border:1px solid var(--line); border-radius:2px; padding:14px;}
.q-bar-head{display:flex; justify-content:space-between; align-items:baseline; margin-bottom:8px;}
.q-bar-title{font-family:var(--serif); font-size:14px; color:var(--ink); letter-spacing:.04em;}
.q-bar-sub{font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.08em;}
.q-bar-track{display:flex; height:14px; border:1px solid var(--line); border-radius:1px; overflow:hidden; margin-bottom:10px;}
.q-bar-seg{display:flex; align-items:center; justify-content:center; overflow:hidden;}
.seg-txt{font-family:var(--mono); font-size:12px; color:color-mix(in srgb, var(--bg) 70%, transparent); letter-spacing:.04em;}
.seg-ok{background:var(--lime);}
.seg-miss{background:var(--red);}
.seg-late{background:var(--amber);}
.seg-est{background:var(--violet);}
.seg-fix{background:var(--blue);}
.seg-jump{background:var(--orange);}
.seg-dup, .seg-frozen{background:var(--ink-3);}
.q-bar-legend{display:flex; gap:12px; flex-wrap:wrap; font-family:var(--mono); font-size:12px; color:var(--ink-2); letter-spacing:.05em;}
.lg-item{display:flex; align-items:center; gap:5px;}
.lg-item b{color:var(--ink); font-weight:400;}
.lg-dot{width:8px; height:8px; border-radius:1px;}
.sw-ok{background:var(--lime); box-shadow:none;}
.sw-miss{background:var(--red);}
.sw-late{background:var(--amber);}
.sw-est{background:var(--violet);}
.sw-fix{background:var(--blue);}
.sw-jump{background:var(--orange);}
.sw-dup, .sw-frozen{background:var(--ink-3);}

/* ==================== 重算提示条 ==================== */
.rq-recompute{display:flex; flex-direction:column; gap:8px;}
.rc-row{
  display:grid; grid-template-columns:16px 1fr auto; gap:10px; align-items:center;
  padding:10px 14px; background:var(--panel); border:1px solid var(--line);
  border-left:3px solid var(--amber); border-radius:2px;
}
.rc-row.rc-done{border-left-color:var(--lime);}
.rc-icon{color:var(--amber); font-weight:600;}
.rc-done .rc-icon{color:var(--lime);}
.rc-title{font-size:13px; color:var(--ink);}
.rc-title small{margin-left:8px; font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.06em;}
.rc-diff{font-family:var(--mono); font-size:11px; color:var(--ink-2); letter-spacing:.04em; margin-top:4px;}
.rc-diff b{color:var(--ink);}
.rc-delta{color:var(--red); margin-left:8px;}

/* ==================== Panel ==================== */
.panel{background:var(--panel); border:1px solid var(--line); border-radius:2px; padding:16px 18px 12px;}
.panel-head{display:flex; align-items:center; gap:12px; padding-bottom:8px; border-bottom:1px solid var(--line); margin-bottom:10px;}
.panel-title{font-family:var(--serif); font-size:15px; color:var(--ink); letter-spacing:.06em;}
.panel-sub{font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.12em; text-transform:uppercase; margin-top:2px;}
.head-side{margin-left:auto; display:flex; align-items:center; gap:10px;}
.panel-foot{
  margin-top:10px; padding-top:10px; border-top:1px dashed var(--line);
  display:flex; justify-content:space-between; flex-wrap:wrap; gap:6px;
  font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.05em;
}
.task-state{padding:3px 8px; border:1px solid var(--line-strong); border-radius:1px; font-family:var(--mono); font-size:12px; letter-spacing:.08em;}
.task-state.ts-running{color:var(--lime); border-color:color-mix(in srgb, var(--lime) 35%, transparent); background:var(--lime-tint);}
.task-state.ts-degraded{color:var(--amber); border-color:color-mix(in srgb, var(--amber) 35%, transparent); background:var(--amber-tint);}
.task-state.ts-failed{color:var(--red); border-color:color-mix(in srgb, var(--red) 40%, transparent); background:var(--red-tint);}

/* ==================== 曲线图例 ==================== */
.q-legend{display:flex; gap:14px; padding:6px 0 8px; font-family:var(--mono); font-size:12px; color:var(--ink-2); letter-spacing:.06em; flex-wrap:wrap;}
.lg{display:flex; align-items:center; gap:5px;}
.lg-sw{width:10px; height:10px; border-radius:50%;}
.trend-chart{width:100%; height:300px;}
.trend-empty{
  width:100%; min-height:220px;
  display:flex; flex-direction:column; align-items:center; justify-content:center;
  gap:10px; padding:32px 20px;
  background:repeating-linear-gradient(45deg, transparent 0 8px, var(--red-tint) 8px 16px);
  border:1px dashed color-mix(in srgb, var(--red) 30%, transparent); border-radius:2px;
}
.te-icon{font-family:var(--mono); font-size:26px; color:var(--red); opacity:.6;}
.te-title{font-family:var(--serif); font-size:14px; color:var(--ink); letter-spacing:.06em;}
.te-desc{font-size:12px; color:var(--ink-2); text-align:center; line-height:1.6; max-width:520px;}
.te-desc b{color:var(--red); font-weight:400; font-family:var(--mono); font-size:11px;}

/* ==================== 表格 ==================== */
.tbl-wrap{overflow-x:auto;}
.rq-tbl{width:100%; border-collapse:collapse; font-size:12px;}
.rq-tbl th, .rq-tbl td{padding:8px 10px; text-align:left; border-bottom:1px solid var(--line);}
.rq-tbl th{font-family:var(--mono); font-size:12px; color:var(--ink-3); text-transform:uppercase; letter-spacing:.12em; font-weight:400;}
.rq-tbl td{color:var(--ink);}
.rq-tbl .tk-name{color:var(--ink);}
.rq-tbl .tk-id{font-family:var(--mono); font-size:12px; color:var(--ink-3); margin-top:2px;}
.tk-state{padding:1px 6px; border:1px solid var(--line-strong); border-radius:1px; font-family:var(--mono); font-size:12px; letter-spacing:.08em;}
.tk-state.ts-running{color:var(--lime); border-color:color-mix(in srgb, var(--lime) 35%, transparent); background:var(--lime-tint);}
.tk-state.ts-degraded{color:var(--amber); border-color:color-mix(in srgb, var(--amber) 35%, transparent); background:var(--amber-tint);}
.tk-state.ts-failed{color:var(--red); border-color:color-mix(in srgb, var(--red) 40%, transparent); background:var(--red-tint);}
.bf-status{padding:1px 6px; border:1px solid var(--line-strong); border-radius:1px; font-family:var(--mono); font-size:12px; letter-spacing:.08em;}
.bs-succeeded{color:var(--lime); border-color:color-mix(in srgb, var(--lime) 35%, transparent); background:var(--lime-tint);}
.bs-failed{color:var(--red); border-color:color-mix(in srgb, var(--red) 40%, transparent); background:var(--red-tint);}
.bs-running, .bs-queued{color:var(--cyan); border-color:color-mix(in srgb, var(--cyan) 40%, transparent); background:var(--cyan-tint);}
.tbl-empty{padding:20px; text-align:center; font-family:var(--mono); color:var(--ink-3); font-size:11px; letter-spacing:.05em; border:1px dashed var(--line); border-radius:2px;}

/* ==================== 页脚 ==================== */
.rq-footer{
  margin-top:8px; padding:12px 4px 0; border-top:1px solid var(--line);
  display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px;
  font-family:var(--mono); font-size:12px; color:var(--ink-3); letter-spacing:.08em;
}

/* ==================== 弹窗 ==================== */
/* el-dialog 皮由 cockpit-tokens.scss 的 .cockpit-modal 统一供，不再页级覆盖 */
.bf-form{display:flex; flex-direction:column; gap:10px;}
.bf-row{display:grid; grid-template-columns:110px 1fr; gap:12px; padding:6px 0; border-bottom:1px solid var(--line);}
.bf-row label{font-family:var(--mono); font-size:11px; color:var(--ink-3); letter-spacing:.08em;}
.bf-row span{font-size:12px; color:var(--ink);}
.bf-hint{margin-top:8px; padding:10px; background:var(--panel-2); border:1px solid var(--line); border-radius:2px; font-size:11px; color:var(--ink-2); line-height:1.5;}

/* ==================== 响应式 ==================== */
@media (max-width: 1280px){
  .rq-main{grid-template-columns:1fr;}
  .rq-tree-panel{height:auto; max-height:280px;}
  .rq-summary{grid-template-columns:1fr;}
}
</style>
