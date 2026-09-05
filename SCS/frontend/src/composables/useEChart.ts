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

/** 统一 tooltip 样式 */
export const softTooltip = {
  trigger: 'axis',
  backgroundColor: '#ffffff',
  borderColor: '#e6eaf0',
  borderWidth: 1,
  borderRadius: 12,
  padding: [8, 12],
  textStyle: { color: '#172033', fontSize: 11 },
  extraCssText: 'box-shadow: 0 10px 30px rgb(31 45 67 / 10%);',
}

export const CHART_COLORS = {
  primary: '#315fa8',
  danger: '#d6474f',
  warning: '#c58a2a',
  muted: '#929bad',
  softGrid: '#eef1f5',
  axisLabel: '#929bad',
}
