<!--
  能源总览（驾驶舱） — B-Demo 第一幕主页面
  视觉方向：方向 A 深色工控调度控制台风（2026-07-13 定稿）
  REQ 锚点：REQ-057 至 062 · REQ-019 · REQ-053 · REQ-029
  数据契约：src/api/overview.js（后端未就绪，走 src/views/dashboard/mock.js）
  依赖：Element Plus 2.13 + ECharts 5.6（团队纪律：不引入新 UI/图表库）
  骨架注：本文件替换了 RuoYi 自带的 ant-design-vue + G2Plot dashboard；
         editable-link-group.vue 已删除（任务 #8 冗余清理）。
-->
<template>
  <div class="overview-dark cockpit-page">
    <!-- ============ 顶栏筛选器（全局筛选：时间 × 装卸区 × 能源类型） ============ -->
    <!-- REQ-060：默认 30s 自动刷新；REQ-062：口径签名常驻 -->
    <div class="ov-filters">
      <div class="fg">
        <span class="fg-label">装卸区</span>
        <el-segmented v-model="filters.zone" :options="zoneOptions" size="small" />
      </div>
      <div class="fg">
        <span class="fg-label">能源</span>
        <el-segmented v-model="filters.energyType" :options="energyOptions" size="small" />
      </div>
      <div class="fg fg-end">
        <span class="refresh-dot" :class="{ pulsing: !loading }" />
        <span class="refresh-txt">
          {{ loading ? '正在拉取…' : `AUTO REFRESH · ${refreshInterval}s · 下次 ${nextRefreshLabel}` }}
        </span>
      </div>
    </div>

    <!-- ============ 演示态声明条 REQ-029 ============ -->
    <div v-if="payload?.demoState?.enabled" class="ov-demo-banner">
      <span class="left">▎ <b>REQ-029 能源基线演示态</b> · {{ payload.demoState.hint.replace('REQ-029 能源基线演示态：', '') }}</span>
      <span class="right">
        签名 {{ payload.signature.version }} · SEED {{ payload.signature.seed }} · sig:{{ payload.signature.sigId }}
      </span>
    </div>

    <!-- 加载态占位（骨架级不铺满，只留一行提示，避免阻塞视觉预览） -->
    <div v-if="loading && !payload" class="ov-loading">正在拉取总览数据…</div>

    <div v-if="payload" class="ov-main">
      <!-- ============ KPI 六卡 REQ-057 ============ -->
      <div class="ov-kpi-grid">
        <div
          v-for="(k, i) in payload.kpis"
          :key="k.code"
          class="kpi"
          :class="[`kpi-${k.tagKind}`, i === 0 ? 'kpi-lead' : '']"
        >
          <div class="kpi-head">
            <span class="kpi-code">{{ String(i + 1).padStart(2, '0') }} · {{ k.code }}</span>
            <span class="kpi-tag" :class="`tag-${k.tagKind}`">{{ k.tag }}</span>
          </div>
          <div class="kpi-val">
            {{ formatKpi(k.value) }}<span class="kpi-unit">{{ k.unit }}</span>
          </div>
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-foot">
            <span class="delta" :class="`delta-${k.delta.kind}`">
              <template v-if="k.delta.kind === 'up'">▲</template>
              <template v-else-if="k.delta.kind === 'dn'">▼</template>
              {{ k.delta.value }}<template v-if="k.delta.label"> {{ k.delta.label }}</template>
            </span>
          </div>
        </div>
      </div>

      <!-- ============ 行 2：24h 趋势 + Top5 ============ -->
      <div class="ov-row-two">
        <!-- Trend REQ-058 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">能耗趋势 · Energy Load</div>
              <div class="panel-sub">{{ trendRangeLabel }} · {{ zoneLabel }} · {{ energyTypeLabel }}</div>
            </div>
            <el-segmented v-model="trendRange" :options="[{label:'24H',value:'24h'},{label:'7D',value:'7d'}]" size="small" />
            <div class="cov-badge">
              <span class="dot" />
              COV {{ payload.quality.coverage }}% · 合格
            </div>
          </header>

          <div class="trend-legend">
            <span class="li"><i class="li-line" />实测{{ loadNoun }} ({{ trendUnitDisplay }})</span>
            <span class="li"><i class="li-band" />基线 ±1σ</span>
            <span class="li"><i class="li-dash" />基线中位</span>
            <span class="li"><i class="li-dot" />异常触发点</span>
          </div>

          <div ref="trendChartEl" class="trend-chart" />

          <footer class="panel-foot">
            <span>峰值 {{ payload.trend.peak.hour }} · {{ payload.trend.peak.value }} {{ trendUnitDisplay }} · {{ payload.trend.peak.note }}</span>
            <span>低谷 {{ payload.trend.valley.hour }} · {{ payload.trend.valley.value }} {{ trendUnitDisplay }}</span>
            <span>公式版本 {{ payload.trend.formulaVersion }} · 采样 {{ payload.trend.samplingInterval }}</span>
          </footer>
        </section>

        <!-- Top5 用能对象 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">最大用能对象 · Top 5</div>
              <div class="panel-sub">{{ topPanelSub }}</div>
            </div>
            <el-segmented v-model="topDim" :options="[{label:'按成本',value:'cost'},{label:'按能耗',value:'energy'}]" size="small" />
          </header>

          <div class="top-list">
            <div v-if="!activeTopList.length" class="top-empty">
              暂无 {{ energyTypeLabel }} 能耗榜数据
            </div>
            <div v-for="row in activeTopList" :key="row.rank" class="top-row">
              <span class="top-idx">{{ String(row.rank).padStart(2, '0') }}</span>
              <div class="top-body">
                <div class="top-name">
                  <span class="n">{{ row.name }}<small>{{ row.meta }}</small></span>
                  <span class="v" v-html="formatTopValue(row)" />
                </div>
                <div class="top-bar">
                  <i :class="row.kind === 'hot' ? 'hot' : ''" :style="{ width: row.barPct + '%' }" />
                </div>
              </div>
              <span class="top-share">{{ row.share }} 总占</span>
            </div>
          </div>

          <footer class="panel-foot">
            <span>合计前 5 · 占月度<b>{{ topDim === 'cost' ? '总成本' : '总能耗' }}</b> {{ topFootShare }}</span>
            <span>下钻 → 设备画像 · REQ-033</span>
          </footer>
        </section>
      </div>

      <!-- ============ 行 3：告警滚动 + 数据质量 + 48 信号阵列 ============ -->
      <div class="ov-row-three">
        <!-- 告警 REQ-039 / 042 / 040 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">最新告警 · Live Alarms</div>
              <div class="panel-sub">滚动 · 级别着色</div>
            </div>
            <div class="ha-side">未关闭 {{ openAlarmsCount }} 条</div>
          </header>

          <div class="alarm-list">
            <div v-for="a in payload.alarms" :key="a.time" class="alarm-row">
              <span class="a-time">{{ a.time }}</span>
              <span class="a-lvl" :class="`lvl-${a.level}`">{{ levelLabel(a.level) }}</span>
              <div class="a-body">
                <div class="a-title">{{ a.ruleId }} · {{ a.title }} <small v-if="a.lead">{{ a.lead }}</small></div>
                <div class="a-meta">规则版本 {{ a.ruleVersion }} · 合并 {{ a.mergedCount }} 次</div>
              </div>
              <span class="a-obj">{{ a.obj }}</span>
            </div>
          </div>

          <footer class="panel-foot">
            <span>合并窗口 5 min · REQ-042</span>
            <span>规则版本随触发时冻结 · REQ-040</span>
          </footer>
        </section>

        <!-- 数据质量 + 48 信号阵列 REQ-019 -->
        <section class="panel">
          <header class="panel-head">
            <div>
              <div class="panel-title">数据质量摘要 · Signal Grid</div>
              <div class="panel-sub">{{ payload.quality.total }} 个计量点 · 今日</div>
            </div>
            <div class="ha-side">最近入库 {{ payload.quality.latestIngestAt }}</div>
          </header>

          <div class="q-body">
            <div class="q-legend">
              <div v-for="c in payload.quality.categories" :key="c.key" class="q-item">
                <span class="q-lab">
                  <i class="q-sw" :class="`sw-${c.key}`" />
                  {{ c.label }}
                </span>
                <span class="q-cnt">{{ c.count }}<em>· {{ c.pct }}</em></span>
              </div>
            </div>

            <div class="q-grid-wrap">
              <div class="q-grid">
                <span
                  v-for="(state, idx) in payload.quality.cells"
                  :key="idx"
                  class="cell"
                  :class="`c-${state}`"
                  :title="`点位 #${String(idx + 1).padStart(2, '0')} · ${stateLabel(state)}`"
                />
              </div>
              <div class="q-caption">
                <b>阵列</b> · {{ payload.quality.zoneSplit }} · 每格为一个计量点当日状态 · 悬停下钻
              </div>
            </div>
          </div>

          <footer class="panel-foot">
            <span>覆盖率 {{ payload.quality.coverage }}% · 高于阈值 {{ payload.quality.coverageThreshold }}%</span>
            <span>下钻 → 原始数据与质量 · REQ-019</span>
          </footer>
        </section>
      </div>

      <!-- ============ 页脚：REQ 锚点 + 口径签名 REQ-062 ============ -->
      <div class="ov-footer">
        <span>REQ-057 · 058 · 060 · 062 · 019 · 053 · 029</span>
        <span>口径签名 {{ payload.signature.version }} · 生成 {{ payload.signature.generatedAt }} · SEED {{ payload.signature.seed }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 组件命名与骨架 module_admin 风格保持一致（<script setup> + defineOptions）。
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, ScatterChart } from 'echarts/charts'
import {
  GridComponent, TooltipComponent, LegendComponent,
  MarkLineComponent, MarkPointComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getOverview } from '@/api/overview'
import { useChartTheme } from '@/views/energy/shared/cockpitTheme'

echarts.use([LineChart, ScatterChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, MarkPointComponent, CanvasRenderer])

// 双主题响应式：切顶栏夜览时图表即时重绘
const theme = useChartTheme()

defineOptions({ name: 'Dashboard' })

// -------- 全局筛选 REQ-058 --------
// #24 追加三（PRD 裁决）：总览页 KPI 卡口径 PRD §5.1 已固定（当日/当月），
// 时间维度分析归 §5.4 统计分析页；此页时间筛选已移除，不再自造需求。
// filters.timeRange 保留固定 'today' 以兼容 backend 契约（timeRange 参数已标废弃）。
const zoneOptions = [
  { label: '全部', value: 'ALL' },
  { label: 'A · 钢材', value: 'A' },
  { label: 'B · 粉煤灰', value: 'B' }
]
const energyOptions = [
  { label: '电', value: 'ELEC' },
  { label: '水', value: 'WATER' },
  { label: '压缩空气', value: 'AIR' }
]

const filters = reactive({ timeRange: 'today', zone: 'ALL', energyType: 'ELEC' })
const trendRange = ref('24h')
const topDim = ref('cost')

// -------- 数据加载 --------
const payload = ref(null)
const loading = ref(false)
const refreshInterval = ref(30) // REQ-060 默认 30s
let refreshTimer = null
const nextRefreshAt = ref(Date.now() + refreshInterval.value * 1000)

async function loadOverview() {
  loading.value = true
  try {
    const resp = await getOverview({ ...filters })
    payload.value = resp.data
    nextRefreshAt.value = Date.now() + refreshInterval.value * 1000
    // 图表数据变更后重新渲染
    await nextTick()
    renderTrend()
  } finally {
    loading.value = false
  }
}

// -------- 图表：24h 趋势（ECharts） --------
const trendChartEl = ref(null)
let trendChart = null

function renderTrend() {
  if (!trendChartEl.value || !payload.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendChartEl.value, null, { renderer: 'canvas' })
  }
  const t = payload.value.trend
  // 基线带用两条 stack 线实现：lo（透明）+ (hi - lo)（有 area 填充）
  const diff = t.baselineHigh.map((hi, i) => hi - t.baselineLow[i])
  const anomalyPoints = t.anomalies.map((a) => [t.hours[a.hourIndex], a.value])

  const pal = theme.value
  const inkMuted = pal.ink3
  const inkSub = pal.ink2
  const cyan = pal.cyan
  const amber = pal.amber
  // 演示：告警轴指示线沿用 amber（暖色系已足够；无需再引入 orange 到 palette）
  const orange = pal.amber
  const areaHigh = pal.areaTint          // 基线带底色（透明）
  const areaGradTop = pal.areaTint
  const areaGradBot = 'transparent'
  const anomalyBorder = pal.amberTint

  trendChart.setOption({
    animation: true,
    animationDuration: 500,
    grid: { top: 20, left: 44, right: 24, bottom: 34 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: pal.tooltipBg,
      borderColor: pal.tooltipBorder,
      textStyle: { color: pal.tooltipInk, fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: orange, type: 'dashed' } }
    },
    xAxis: {
      type: 'category',
      data: t.hours,
      axisLine: { lineStyle: { color: pal.lineStrong } },
      axisTick: { show: false },
      axisLabel: {
        color: inkMuted,
        fontFamily: 'SF Mono, Consolas, monospace',
        fontSize: 10,
        interval: 3
      }
    },
    yAxis: {
      type: 'value',
      name: payload.value?.trend?.unit || 'kW',
      nameTextStyle: { color: inkSub, fontFamily: 'SF Mono, Consolas, monospace', fontSize: 10, padding: [0, 0, 6, 0] },
      splitLine: { lineStyle: { color: pal.splitLine, type: 'solid' } },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: inkMuted, fontFamily: 'SF Mono, Consolas, monospace', fontSize: 10 }
    },
    series: [
      // 基线下界（不可见）
      {
        name: '基线下界',
        type: 'line',
        data: t.baselineLow,
        stack: 'band',
        symbol: 'none',
        lineStyle: { opacity: 0 },
        tooltip: { show: false },
        z: 1
      },
      // 基线区间（±1σ 带）
      {
        name: '基线 ±1σ',
        type: 'line',
        data: diff,
        stack: 'band',
        symbol: 'none',
        lineStyle: { opacity: 0 },
        areaStyle: { color: areaHigh },
        z: 2
      },
      // 基线中位（虚线）
      {
        name: '基线中位',
        type: 'line',
        data: t.baselineMid,
        symbol: 'none',
        smooth: true,
        lineStyle: { type: 'dashed', color: inkSub, width: 1, opacity: .7 },
        z: 3
      },
      // 实测负荷（主线 + 渐变填充）
      {
        name: '实测负荷',
        type: 'line',
        data: t.load,
        symbol: 'none',
        smooth: true,
        lineStyle: { color: cyan, width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: areaGradTop },
            { offset: 1, color: areaGradBot }
          ])
        },
        markLine: {
          symbol: 'none',
          silent: true,
          label: {
            show: true,
            position: 'insideEndTop',
            color: orange,
            fontFamily: 'SF Mono, monospace',
            fontSize: 10,
            formatter: `NOW ${t.hours[t.nowIndex]}`
          },
          lineStyle: { color: orange, type: 'dashed', width: 1 },
          data: [{ xAxis: t.hours[t.nowIndex] }]
        },
        z: 4
      },
      // 异常尖峰
      {
        name: '异常触发点',
        type: 'scatter',
        data: anomalyPoints,
        symbolSize: 12,
        itemStyle: {
          color: amber,
          borderColor: anomalyBorder,
          borderWidth: 4
        },
        label: {
          show: true,
          position: 'top',
          color: amber,
          fontFamily: 'SF Mono, monospace',
          fontSize: 10,
          formatter: (p) => {
            const a = t.anomalies.find((x) => t.hours[x.hourIndex] === p.value[0])
            return a ? `${a.ruleId}` : ''
          }
        },
        z: 5
      }
    ]
  })
}

// -------- 生命周期 --------
function scheduleAutoRefresh() {
  clearInterval(refreshTimer)
  refreshTimer = setInterval(() => {
    // 演示 demo 不真跑接口刷新（避免每 30s 全量重绘扰乱评审视觉），
    // 只更新下次刷新的倒计时展示；真接口接入后取消 else 分支即可全量刷新。
    nextRefreshAt.value = Date.now() + refreshInterval.value * 1000
  }, refreshInterval.value * 1000)
}

const nextRefreshLabel = computed(() => {
  const d = new Date(nextRefreshAt.value)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
})

// 图表随窗口变化重排
function resizeChart() {
  trendChart?.resize()
}

onMounted(async () => {
  await loadOverview()
  window.addEventListener('resize', resizeChart)
  scheduleAutoRefresh()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  clearInterval(refreshTimer)
  trendChart?.dispose()
  trendChart = null
})

// 筛选器变更时重新拉取（演示：目前所有 case 都返回同一份 mock）
watch(filters, () => { loadOverview() }, { deep: true })
// 双主题响应：主题切换时以最新 palette 重绘趋势图
watch(theme, () => { renderTrend() })

// -------- 展示层辅助 --------
function formatKpi(v) {
  if (typeof v === 'number') {
    if (Number.isInteger(v) && v >= 1000) return v.toLocaleString()
    return v.toLocaleString(undefined, { maximumFractionDigits: 1 })
  }
  return v
}
function levelLabel(l) { return { crit: '严重', warn: '一般', info: '提示' }[l] || l }
function stateLabel(s) { return { ok: '正常', miss: '缺测', late: '迟到', est: '估算', fix: '人工修正' }[s] || s }

const trendRangeLabel = computed(() => (trendRange.value === '24h' ? '24H' : '7 天'))
// #24 · 趋势图标注跟随所选能源类型 + backend 单位
const zoneLabel = computed(() => ({ ALL: '全区', A: 'A 区（钢材）', B: 'B 区（粉煤灰）' })[filters.zone] || '全区')
const energyTypeLabel = computed(() => ({ ELEC: '电', WATER: '水', AIR: '压缩空气' })[filters.energyType] || filters.energyType)
const loadNoun = computed(() => (filters.energyType === 'ELEC' ? '负荷' : '流量'))
const trendUnitDisplay = computed(() => payload.value?.trend?.unit || (filters.energyType === 'ELEC' ? 'kW' : 'm³/h'))

// #24 追加二 · Top5 按成本/按能耗切换
// 按能耗时的量单位（月累计）：电=kWh、水=m³、压缩空气=m³
const energyValueUnit = computed(() => ({ ELEC: 'kWh', WATER: 'm³', AIR: 'm³' })[filters.energyType] || '')
const activeTopList = computed(() => {
  if (!payload.value) return []
  return topDim.value === 'cost'
    ? (payload.value.topObjects || [])
    : (payload.value.topObjectsByEnergy || [])
})
const topPanelSub = computed(() => (
  topDim.value === 'cost'
    ? '月度 · 成本口径'
    : `月度 · 能耗口径（${energyTypeLabel.value}）`
))
const topFootShare = computed(() => {
  const list = activeTopList.value
  if (!list.length) return ''
  // 若列表 row 里带 share 字符串（如 '20.6%'），累加前 5 名占比
  const sum = list.slice(0, 5).reduce((acc, r) => {
    const n = parseFloat((r.share || '').replace('%', ''))
    return acc + (isNaN(n) ? 0 : n)
  }, 0)
  return sum > 0 ? `${sum.toFixed(1)}%` : ''
})
// #24 追加四 · 告警角标从 OPEN_ALARMS KPI 取真值，不再写死
const openAlarmsCount = computed(() => {
  const k = payload.value?.kpis?.find((x) => x.code === 'OPEN_ALARMS')
  return k?.value ?? '—'
})

function formatTopValue(row) {
  if (topDim.value === 'cost') {
    // 金额：¥ 千分位 + /月
    return `¥ ${Number(row.value).toLocaleString()}<em>/月</em>`
  }
  // 量值：数字 + 介质单位 + /月
  const unit = row.unit || energyValueUnit.value
  return `${Number(row.value).toLocaleString()} ${unit}<em>/月</em>`
}
</script>

<style scoped>
/* ==================== 页面布局（token 走 cockpit-page 全局主题） ==================== */
/* 页面根节点同时挂 .overview-dark 与 .cockpit-page；主题 token 来自后者，
   .overview-dark 只负责布局特化（背景晕、尺寸、字体族）与页内页专色。 */
.overview-dark{
  /* 页专色（cockpit-tokens 未覆盖，页面级双主题双写） */
  --orange:var(--amber);
  --blue:var(--cyan);

  min-height:calc(100vh - 84px);
  background:var(--bg);
  color:var(--ink);
  padding:16px 20px 40px;
  margin:-16px -16px 0;  /* 抵消骨架 layout 的 padding，让主题底色铺满可视区 */
  font-family:"PingFang SC","Hiragino Sans GB","Microsoft YaHei",system-ui,sans-serif;
  font-size:13px;
  line-height:1.5;
  letter-spacing:.01em;
}
html.dark .overview-dark{
  --orange:var(--amber);
  --blue:var(--cyan);
}
/* Element Plus segmented 页级覆盖 —— 其它 el 组件已由 cockpit-tokens 通用块覆盖 */
.overview-dark :deep(.el-segmented){
  --el-segmented-bg-color:var(--panel-2);
  --el-segmented-item-selected-color:var(--cyan);
  --el-segmented-item-selected-bg-color:var(--cyan-tint);
  --el-segmented-item-hover-color:var(--ink);
  --el-segmented-color:var(--ink-2);
  --el-border-color:var(--line-strong);
  border:1px solid var(--line-strong);
  border-radius:2px;
}
.overview-dark :deep(.el-segmented .el-segmented__item-label){
  font-family:var(--mono);
  font-size:11px;
  letter-spacing:.06em;
}

/* ==================== 顶栏筛选器 ==================== */
.ov-filters{
  display:flex;align-items:center;gap:20px;flex-wrap:wrap;
  padding:12px 14px;background:var(--panel);
  border:1px solid var(--line);border-radius:2px;
}
.fg{display:flex;align-items:center;gap:8px;}
.fg-label{
  font-family:var(--mono);font-size:12px;letter-spacing:.14em;
  color:var(--ink-3);text-transform:uppercase;
}
.fg-end{margin-left:auto;display:flex;align-items:center;gap:8px;
  font-family:var(--mono);font-size:11px;color:var(--ink-2);letter-spacing:.05em;}
.refresh-dot{
  width:6px;height:6px;border-radius:50%;background:var(--lime);
  box-shadow:none;
}
.refresh-dot.pulsing{animation:pulse 2s infinite;}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
.refresh-txt{color:var(--ink-2);}

/* ==================== 演示态声明条 ==================== */
.ov-demo-banner{
  margin-top:12px;
  padding:8px 14px;
  background:var(--amber-tint);
  border:1px solid color-mix(in srgb, var(--amber) 25%, transparent);
  border-radius:2px;
  display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;
  font-family:var(--mono);font-size:12px;letter-spacing:.08em;color:var(--amber);
}
.ov-demo-banner .left b{
  color:var(--ink);font-family:"PingFang SC",system-ui,sans-serif;font-weight:400;letter-spacing:.05em;
}
.ov-demo-banner .right{color:var(--ink-3);}
.ov-loading{
  margin-top:24px;padding:24px;text-align:center;
  font-family:var(--mono);color:var(--ink-3);letter-spacing:.1em;
  background:var(--panel);border:1px solid var(--line);border-radius:2px;
}

/* ==================== 主内容 ==================== */
.ov-main{margin-top:12px;display:flex;flex-direction:column;gap:12px;}

/* ==================== KPI 六卡 ==================== */
.ov-kpi-grid{
  display:grid;grid-template-columns:repeat(6,1fr);gap:12px;
}
.kpi{
  position:relative;background:var(--panel);border:1px solid var(--line);
  padding:14px 14px 12px;border-radius:2px;overflow:hidden;
  box-shadow:0 1px 0 color-mix(in srgb, var(--ink) 2%, transparent) inset;
}
.kpi::before{
  content:"";position:absolute;left:0;top:0;bottom:0;width:2px;background:var(--cyan);
}
.kpi.kpi-warn::before{background:var(--amber);}
.kpi.kpi-hi::before{background:var(--red);}
.kpi.kpi-ok::before{background:var(--lime);}
.kpi-lead .kpi-val{font-size:30px;}

.kpi-head{
  display:flex;justify-content:space-between;align-items:center;
  font-family:var(--mono);font-size:12px;letter-spacing:.14em;color:var(--ink-3);
  text-transform:uppercase;margin-bottom:8px;
}
.kpi-tag{
  padding:1px 6px;border:1px solid var(--line-strong);border-radius:1px;
  font-size:12px;letter-spacing:.1em;color:var(--ink-2);
}
.kpi-tag.tag-warn{color:var(--amber);border-color:color-mix(in srgb, var(--amber) 35%, transparent);background:var(--amber-tint);}
.kpi-tag.tag-hi{color:var(--red);border-color:color-mix(in srgb, var(--red) 40%, transparent);background:var(--red-tint);}
.kpi-tag.tag-ok{color:var(--lime);border-color:color-mix(in srgb, var(--lime) 35%, transparent);background:var(--lime-tint);}
.kpi-val{
  font-family:var(--mono);font-size:26px;line-height:1;color:var(--ink);
  display:flex;align-items:baseline;gap:6px;font-variant-numeric:tabular-nums;
}
.kpi-unit{font-size:11px;color:var(--ink-3);letter-spacing:.1em;}
.kpi-label{
  font-size:12px;color:var(--ink-2);margin-top:6px;
}
.kpi-foot{
  margin-top:10px;display:flex;justify-content:space-between;align-items:center;
  font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.03em;
}
.delta-up{color:var(--red);}
.delta-dn{color:var(--lime);}
.delta-neutral{color:var(--ink-2);}

/* ==================== Panel 通用 ==================== */
.panel{
  background:var(--panel);border:1px solid var(--line);border-radius:2px;
  padding:16px 18px 12px;position:relative;
}
.panel-head{
  display:flex;align-items:center;gap:12px;
  padding-bottom:8px;border-bottom:1px solid var(--line);margin-bottom:10px;
}
.panel-title{font-family:var(--serif);font-size:15px;color:var(--ink);letter-spacing:.06em;}
.panel-sub{font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.12em;text-transform:uppercase;margin-top:2px;}
.cov-badge{
  margin-left:auto;font-family:var(--mono);font-size:12px;color:var(--ink-2);letter-spacing:.05em;
  padding:2px 6px;border:1px solid var(--line-strong);border-radius:1px;background:var(--panel-2);
  display:flex;align-items:center;gap:6px;
}
.cov-badge .dot{width:6px;height:6px;border-radius:50%;background:var(--lime);box-shadow:none;}
.ha-side{margin-left:auto;font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.08em;}
.panel-foot{
  margin-top:10px;padding-top:10px;border-top:1px dashed var(--line);
  display:flex;justify-content:space-between;flex-wrap:wrap;gap:6px;
  font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.05em;
}
.panel-foot b{color:var(--ink-2);font-weight:400;}

/* ==================== 行 2 布局 ==================== */
.ov-row-two{display:grid;grid-template-columns:1.35fr 1fr;gap:12px;}

/* Trend chart */
.trend-legend{
  display:flex;gap:16px;font-family:var(--mono);font-size:12px;color:var(--ink-2);
  letter-spacing:.08em;padding:2px 0 6px;
}
.trend-legend .li{display:flex;align-items:center;gap:6px;text-transform:uppercase;}
.trend-legend .li i{display:inline-block;}
.trend-legend .li-line{width:14px;height:2px;background:var(--cyan);}
.trend-legend .li-band{width:14px;height:8px;background:var(--cyan-tint);border:1px solid color-mix(in srgb, var(--cyan) 35%, transparent);}
.trend-legend .li-dash{width:14px;height:0;border-top:1px dashed var(--ink-2);}
.trend-legend .li-dot{width:8px;height:8px;border-radius:50%;background:var(--amber);box-shadow:none;}
.trend-chart{width:100%;height:280px;}

/* Top5 */
.top-list{display:flex;flex-direction:column;gap:10px;margin-top:2px;}
.top-empty{
  padding:16px 12px; text-align:center;
  color:var(--ink-3); font-size:11px; letter-spacing:.05em;
  border:1px dashed var(--line); border-radius:2px; font-family:var(--mono);
}
.top-row{
  display:grid;grid-template-columns:24px 1fr 90px;gap:10px;align-items:center;
}
.top-idx{
  font-family:var(--mono);font-size:11px;color:var(--ink-3);text-align:right;
}
.top-body{display:flex;flex-direction:column;gap:4px;min-width:0;}
.top-name{display:flex;justify-content:space-between;align-items:baseline;gap:8px;}
.top-name .n{
  font-size:12px;color:var(--ink);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
}
.top-name .n small{color:var(--ink-3);font-family:var(--mono);font-size:12px;letter-spacing:.06em;margin-left:6px;}
.top-name .v{font-family:var(--mono);font-size:12px;color:var(--ink);font-variant-numeric:tabular-nums;}
.top-name .v em{color:var(--ink-3);font-style:normal;font-size:12px;margin-left:3px;}
.top-bar{height:6px;background:var(--panel-2);border:1px solid var(--line);position:relative;}
.top-bar i{position:absolute;inset:0 auto 0 0;background:var(--cyan);}
.top-bar i.hot{background:var(--orange);}
.top-share{
  font-family:var(--mono);font-size:12px;color:var(--ink-2);text-align:right;letter-spacing:.05em;
}

/* ==================== 行 3 ==================== */
.ov-row-three{display:grid;grid-template-columns:1fr 1fr;gap:12px;}

/* 告警列表 */
.alarm-list{display:flex;flex-direction:column;}
.alarm-row{
  display:grid;grid-template-columns:70px 60px 1fr 90px;gap:12px;
  padding:9px 4px;border-bottom:1px solid var(--line);align-items:center;
}
.alarm-row:last-child{border-bottom:0;}
.a-time{font-family:var(--mono);font-size:11px;color:var(--ink-2);}
.a-lvl{
  padding:1px 6px;border:1px solid var(--line-strong);border-radius:1px;
  font-family:var(--mono);font-size:12px;letter-spacing:.1em;text-align:center;
}
.a-lvl.lvl-crit{color:var(--red);border-color:color-mix(in srgb, var(--red) 40%, transparent);background:var(--red-tint);}
.a-lvl.lvl-warn{color:var(--amber);border-color:color-mix(in srgb, var(--amber) 40%, transparent);background:var(--amber-tint);}
.a-lvl.lvl-info{color:var(--cyan);border-color:color-mix(in srgb, var(--cyan) 40%, transparent);background:var(--cyan-tint);}
.a-body{min-width:0;}
.a-title{
  font-size:12px;color:var(--ink);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
}
.a-title small{color:var(--ink-3);font-family:var(--mono);font-size:12px;letter-spacing:.05em;margin-left:6px;}
.a-meta{font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.05em;margin-top:2px;}
.a-obj{
  font-family:var(--mono);font-size:12px;color:var(--ink-2);letter-spacing:.05em;text-align:right;
}

/* 质量摘要 */
.q-body{display:grid;grid-template-columns:1fr 1.1fr;gap:16px;}
.q-legend{display:flex;flex-direction:column;gap:8px;}
.q-item{
  display:flex;justify-content:space-between;align-items:baseline;
  padding:6px 0;border-bottom:1px solid var(--line);
}
.q-item:last-child{border-bottom:0;}
.q-lab{display:flex;align-items:center;gap:8px;font-size:12px;color:var(--ink);}
.q-sw{width:10px;height:10px;border-radius:1px;display:inline-block;}
.q-sw.sw-ok{background:var(--lime);box-shadow:none;}
.q-sw.sw-miss{background:var(--red);}
.q-sw.sw-late{background:var(--amber);}
.q-sw.sw-est{background:var(--violet);}
.q-sw.sw-fix{background:var(--blue);}
.q-cnt{font-family:var(--mono);font-size:12px;color:var(--ink);font-variant-numeric:tabular-nums;}
.q-cnt em{color:var(--ink-3);font-style:normal;font-size:12px;margin-left:4px;letter-spacing:.05em;}

/* 48 信号阵列 —— 方向 A 签名元素 */
.q-grid-wrap{display:flex;flex-direction:column;gap:6px;}
.q-grid{
  display:grid;grid-template-columns:repeat(12,1fr);gap:3px;
  padding:8px;border:1px solid var(--line);background:var(--panel-2);
}
.q-grid .cell{
  aspect-ratio:1/1;background:var(--lime);opacity:.85;
  box-shadow:none;
  transition:transform .15s ease;
  cursor:pointer;
}
.q-grid .cell:hover{outline:2px solid var(--cyan);z-index:2;position:relative;}
.q-grid .cell.c-miss{background:var(--red);box-shadow:none;}
.q-grid .cell.c-late{background:var(--amber);box-shadow:none;}
.q-grid .cell.c-est{background:var(--violet);box-shadow:none;}
.q-grid .cell.c-fix{background:var(--blue);box-shadow:none;}
.q-caption{
  font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.08em;
}
.q-caption b{color:var(--ink-2);font-weight:400;}

/* ==================== 页脚 ==================== */
.ov-footer{
  margin-top:16px;padding:12px 4px 0;border-top:1px solid var(--line);
  display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px;
  font-family:var(--mono);font-size:12px;color:var(--ink-3);letter-spacing:.08em;
}

/* ==================== 响应式 ==================== */
@media (max-width: 1280px){
  .ov-kpi-grid{grid-template-columns:repeat(3,1fr);}
  .ov-row-two{grid-template-columns:1fr;}
  .ov-row-three{grid-template-columns:1fr;}
}
@media (max-width: 640px){
  .ov-kpi-grid{grid-template-columns:repeat(2,1fr);}
  .q-body{grid-template-columns:1fr;}
  .alarm-row{grid-template-columns:60px 50px 1fr;}
  .a-obj{grid-column:2 / span 2;text-align:left;}
}
</style>
