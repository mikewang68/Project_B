import { onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue'
import { init, type EChartsCoreOption, type EChartsType } from 'echarts/core'

/** 统一的浅色工业 SaaS 图表基座：圆润 tooltip、极淡网格、自动 resize/dispose */
export function useEChart(source: Ref<EChartsCoreOption>, deep = false) {
  const el = ref<HTMLDivElement>()
  const chart = shallowRef<EChartsType>()

  function render(option: EChartsCoreOption): void {
    if (!chart.value) return
    chart.value.setOption(option, { notMerge: true })
  }

  function resize(): void { chart.value?.resize() }

  onMounted(() => {
    if (!el.value) return
    chart.value = init(el.value)
    render(source.value)
    window.addEventListener('resize', resize)
  })

  watch(source, (v) => render(v), { deep })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    chart.value?.dispose()
  })

  /** 绑定点击事件（数据下钻） */
  function onChartClick(handler: (params: { componentType: string; name?: string; dataIndex?: number; data?: unknown }) => void): void {
    chart.value?.on('click', handler as never)
  }

  return { el, chart, onChartClick }
}

/** 统一 tooltip 样式（规范第 12 节：白底、#303133 文字、克制阴影） */
export const softTooltip = {
  trigger: 'axis',
  backgroundColor: '#ffffff',
  borderColor: '#dcdfe6',
  borderWidth: 1,
  borderRadius: 8,
  padding: [8, 12],
  textStyle: { color: '#303133', fontSize: 11 },
  extraCssText: 'box-shadow: 0 2px 10px rgba(31, 45, 61, 0.10);',
}

/** 统一图表色板（语义色，浅色页与深色大屏共用同一套品牌/状态色） */
export const CHART_COLORS = {
  primary: '#409eff',
  primarySoft: '#79bbff',
  success: '#67c23a',
  danger: '#f56c6c',
  warning: '#e6a23c',
  info: '#909399',
  muted: '#909399',
  softGrid: '#ebeef5',
  axisLine: '#dcdfe6',
  axisLabel: '#909399',
  tooltipText: '#303133',
}

/** 规范第 12 节统一 ECharts 色序 */
export const CHART_PALETTE = [
  '#409eff',
  '#67c23a',
  '#e6a23c',
  '#f56c6c',
  '#909399',
  '#79bbff',
  '#95d475',
  '#eebe77',
]

/** 统一浅色页坐标轴样式 */
export const chartAxis = {
  axisLine: { lineStyle: { color: CHART_COLORS.axisLine } },
  axisTick: { lineStyle: { color: CHART_COLORS.axisLine } },
  axisLabel: { color: CHART_COLORS.axisLabel, fontSize: 11 },
  splitLine: { lineStyle: { color: CHART_COLORS.softGrid } },
}
