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
  bg: '#F4F7FA',
  panel: '#FFFFFF',
  panel2: '#F1F5F9',
  line: '#E2E8F0',
  lineStrong: '#CBD5E1',
  ink: '#0F172A',
  ink2: '#475569',
  ink3: '#64748B',
  cyan: '#0284C7',
  amber: '#B45309',
  red: '#DC2626',
  lime: '#16A34A',
  violet: '#7C3AED',
  splitLine: 'rgba(15, 23, 42, 0.08)',
  areaTint: 'rgba(2, 132, 199, 0.08)',
  redTint: 'rgba(220, 38, 38, 0.08)',
  amberTint: 'rgba(180, 83, 9, 0.08)',
  limeTint: 'rgba(22, 163, 74, 0.08)',
  tooltipBg: '#FFFFFF',
  tooltipBorder: '#CBD5E1',
  tooltipInk: '#0F172A'
})

// 暗色 palette（与 html.dark .cockpit-page 覆盖值同源）
const DARK = Object.freeze({
  bg: '#0B0F14',
  panel: '#131922',
  panel2: '#1A222E',
  line: '#233042',
  lineStrong: '#324256',
  ink: '#E7ECF3',
  ink2: '#93A6BC',
  ink3: '#5A6E84',
  cyan: '#38BDF8',
  amber: '#F5A524',
  red: '#EF4444',
  lime: '#4ADE80',
  violet: '#A78BFA',
  splitLine: 'rgba(56, 189, 248, 0.08)',
  areaTint: 'rgba(56, 189, 248, 0.08)',
  redTint: 'rgba(239, 68, 68, 0.06)',
  amberTint: 'rgba(245, 165, 36, 0.07)',
  limeTint: 'rgba(74, 222, 128, 0.05)',
  tooltipBg: '#131922',
  tooltipBorder: '#324256',
  tooltipInk: '#E7ECF3'
})

// 响应式返回当前主题 palette；组件消费 theme.value.xxx
export function useChartTheme() {
  const settings = useSettingsStore()
  return computed(() => (settings.isDark ? DARK : LIGHT))
}

// 语义→系列色的稳定映射（peak/flat/valley/anomaly/normal），页面直接消费避免散乱记色号
export function chartSeriesColors(theme) {
  return {
    peak: theme.red,
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
    axisLabel: { color: theme.ink3, fontSize: 10 },
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
