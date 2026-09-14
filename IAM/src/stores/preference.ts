/**
 * 外观偏好 Store：统一皮肤（data-theme）与布局（data-layout）的切换与持久化。
 *
 * - 皮肤：写入 <html data-theme>，由 tokens.scss 中对应 [data-theme='xxx'] 变量块生效，
 *   同时覆盖 Element Plus 主色，实现整站统一换肤。
 * - 布局：仅作用于后台布局容器 data-layout，由 AdminLayout 的样式切换结构。
 * - 偏好持久化到 localStorage，刷新/重新登录保持。
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'

export type ThemeId = 'tech-blue' | 'forest-green' | 'purple-elegant' | 'dark-pro'
export type LayoutId = 'side' | 'top' | 'compact'

export interface ThemeOption {
  id: ThemeId
  name: string
  desc: string
  /** 色块预览：[侧栏底色, 主色] */
  colors: [string, string]
}

export interface LayoutOption {
  id: LayoutId
  name: string
  desc: string
}

export const THEME_OPTIONS: ThemeOption[] = [
  { id: 'tech-blue', name: '科技蓝', desc: '深海军蓝导航 · 科技蓝主色（默认）', colors: ['#24364a', '#409eff'] },
  { id: 'forest-green', name: '护眼墨绿', desc: '墨绿导航 · 统一科技蓝主色', colors: ['#20382f', '#409eff'] },
  { id: 'purple-elegant', name: '雅致深灰', desc: '深灰导航 · 统一科技蓝主色', colors: ['#2c313a', '#409eff'] },
  { id: 'dark-pro', name: '暗夜黑', desc: '深色海军蓝 · 暗光护眼', colors: ['#1f3043', '#409eff'] },
]

export const LAYOUT_OPTIONS: LayoutOption[] = [
  { id: 'side', name: '左侧菜单', desc: '经典纵向导航' },
  { id: 'top', name: '顶部导航', desc: '横向菜单 · 内容全宽' },
  { id: 'compact', name: '图标窄栏', desc: '窄侧栏 · 悬停展开' },
]

const PREF_KEY = 'b-iam-prefs'
const DEFAULT_THEME: ThemeId = 'tech-blue'
const DEFAULT_LAYOUT: LayoutId = 'side'

interface Prefs {
  theme: ThemeId
  layout: LayoutId
}

function readPrefs(): Prefs {
  const fallback: Prefs = { theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT }
  try {
    const raw = localStorage.getItem(PREF_KEY)
    if (!raw) return fallback
    const parsed = JSON.parse(raw) as Partial<Prefs>
    const validTheme = THEME_OPTIONS.some((t) => t.id === parsed.theme)
    const validLayout = LAYOUT_OPTIONS.some((l) => l.id === parsed.layout)
    return {
      theme: validTheme ? (parsed.theme as ThemeId) : DEFAULT_THEME,
      layout: validLayout ? (parsed.layout as LayoutId) : DEFAULT_LAYOUT,
    }
  } catch {
    return fallback
  }
}

function applyTheme(theme: ThemeId) {
  document.documentElement.setAttribute('data-theme', theme)
}

// 模块加载即应用一次，避免首屏闪烁（main.ts 会间接 import 本文件）
applyTheme(readPrefs().theme)

export const usePreferenceStore = defineStore('preference', () => {
  const initial = readPrefs()
  const theme = ref<ThemeId>(initial.theme)
  const layout = ref<LayoutId>(initial.layout)

  function persist() {
    try {
      localStorage.setItem(PREF_KEY, JSON.stringify({ theme: theme.value, layout: layout.value }))
    } catch {
      // ignore
    }
  }

  function setTheme(id: ThemeId) {
    theme.value = id
    applyTheme(id)
    persist()
  }

  function setLayout(id: LayoutId) {
    layout.value = id
    persist()
  }

  function reset() {
    setTheme(DEFAULT_THEME)
    setLayout(DEFAULT_LAYOUT)
  }

  return { theme, layout, setTheme, setLayout, reset }
})
