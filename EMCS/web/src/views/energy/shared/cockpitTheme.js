// 驾驶舱主题 · ECharts 配色的 JS 镜像
// 与 web/src/assets/styles/cockpit-tokens.scss 同源，改一处必改两处。
// 图表组件用法：
//   const theme = useChartTheme()
//   watch(() => theme.value, render, { deep: false })
//   render() 内消费 theme.value.ink / palette 等
//
// 主题状态来自 settings store（顶栏夜览开关 → useDark() → html.dark 类），
// 这里 computed 到同一 reactive 源，切换时会自动触发依赖它的 watch。

import { computed } from 'vue'
import useSettingsStore from '@/store/modules/settings'

// 亮色 palette（与 .cockpit-page 缺省值同源）
const LIGHT = Object.freeze({
  bg: '#F5F7FA',
  panel: '#FFFFFF',
  panel2: '#FAFBFC',
  line: '#E4E7ED',
  lineStrong: '#DCDFE6',
  ink: '#1F2937',
  ink2: '#606266',
  ink3: '#909399',
  cyan: '#409EFF',
  amber: '#E6A23C',
  red: '#F56C6C',
  lime: '#67C23A',
  violet: '#909399',
  palette: ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#79BBFF', '#95D475', '#EEBE77'],
  splitLine: '#EBEEF5',
  areaTint: '#ECF5FF',
  redTint: '#FEF0F0',
  amberTint: '#FDF6EC',
  limeTint: '#F0F9EB',
  tooltipBg: '#FFFFFF',
  tooltipBorder: '#DCDFE6',
  tooltipInk: '#303133'
})

// 暗色 palette（与 html.dark .cockpit-page 覆盖值同源）
const DARK = Object.freeze({
  bg: '#0F1A2B',
  panel: '#16233A',
  panel2: '#1A2C48',
  line: '#284062',
  lineStrong: '#2C4569',
  ink: '#E6EDF6',
  ink2: '#B6C4D4',
  ink3: '#8FA3BD',
  cyan: '#409EFF',
  amber: '#E6A23C',
  red: '#F56C6C',
  lime: '#67C23A',
  violet: '#909399',
  palette: ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399', '#79BBFF', '#95D475', '#EEBE77'],
  splitLine: 'rgba(64, 158, 255, 0.14)',
  areaTint: 'rgba(64, 158, 255, 0.14)',
  redTint: 'rgba(245, 108, 108, 0.14)',
  amberTint: 'rgba(230, 162, 60, 0.14)',
  limeTint: 'rgba(103, 194, 58, 0.14)',
  tooltipBg: '#16233A',
  tooltipBorder: '#2C4569',
  tooltipInk: '#CBD5E1'
})

// 响应式返回当前主题 palette；组件消费 theme.value.xxx
export function useChartTheme() {
  const settings = useSettingsStore()
  return computed(() => (settings.isDark ? DARK : LIGHT))
}

// 语义→系列色的稳定映射（peak/flat/valley/anomaly/normal），页面直接消费避免散乱记色号
export function chartSeriesColors(theme) {
  return {
    peak: theme.amber,
    flat: theme.cyan,
    valley: theme.lime,
    anomaly: theme.red,
    normal: theme.cyan,
    warning: theme.amber,
    accent: theme.violet
  }
}

// ECharts 轴与提示的常用共通配置（避免每个组件重写一遍）
export function chartAxisTheme(theme) {
  return {
    axisLine: { lineStyle: { color: theme.lineStrong } },
    axisLabel: { color: theme.ink3, fontSize: 12 },
    splitLine: { lineStyle: { color: theme.splitLine } }
  }
}

export function chartTooltipTheme(theme) {
  return {
    backgroundColor: theme.tooltipBg,
    borderColor: theme.tooltipBorder,
    textStyle: { color: theme.tooltipInk }
  }
}

// 供非 setup 场景（如工具函数）取当前静态 palette，不建立响应
export function currentChartPalette() {
  return useSettingsStore().isDark ? DARK : LIGHT
}

export { LIGHT as CHART_PALETTE_LIGHT, DARK as CHART_PALETTE_DARK }
