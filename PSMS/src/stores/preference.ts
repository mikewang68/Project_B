import { create } from 'zustand';

/**
 * 外观偏好 Store：统一皮肤（data-theme）与布局（data-layout）的切换与持久化。
 *
 * 对应《统一换肤设置同步指南（IAM / SYS 基线）》第 7 节。
 * 原基线使用 Pinia（Vue），此处按 PSMS 技术栈实现为 **Zustand** store，
 * 但皮肤 / 布局的 id、名称、色板、默认值与持久化机制与基线保持一致。
 *
 * - 皮肤：写入 <html data-theme>，由 tokens.css 中对应 [data-theme='xxx'] 变量块生效；
 *   同时 App.tsx 依据 theme 选择对应的 antd ThemeConfig，实现整站统一换肤。
 * - 布局：仅作用于后台布局容器 data-layout，由 SkeletonLayout 切换结构。
 * - 偏好持久化到 localStorage，刷新后保持。
 */

export type ThemeId = 'tech-blue' | 'forest-green' | 'purple-elegant' | 'dark-pro';
export type LayoutId = 'side' | 'top' | 'compact';

export interface ThemeOption {
  id: ThemeId;
  name: string;
  desc: string;
  /** 色块预览：[侧栏底色, 主色] */
  colors: [string, string];
}

export interface LayoutOption {
  id: LayoutId;
  name: string;
  desc: string;
}

export const THEME_OPTIONS: ThemeOption[] = [
  { id: 'tech-blue', name: '科技蓝', desc: '深海军蓝导航 · 科技蓝主色（默认）', colors: ['#24364a', '#409eff'] },
  { id: 'forest-green', name: '护眼墨绿', desc: '墨绿导航 · 统一科技蓝主色', colors: ['#20382f', '#409eff'] },
  { id: 'purple-elegant', name: '雅致深灰', desc: '深灰导航 · 统一科技蓝主色', colors: ['#2c313a', '#409eff'] },
  { id: 'dark-pro', name: '暗夜黑', desc: '深色海军蓝 · 暗光护眼', colors: ['#1f3043', '#409eff'] },
];

export const LAYOUT_OPTIONS: LayoutOption[] = [
  { id: 'side', name: '左侧菜单', desc: '经典纵向导航' },
  { id: 'top', name: '顶部导航', desc: '横向菜单 · 内容全宽' },
  { id: 'compact', name: '图标窄栏', desc: '窄侧栏 · 悬停展开' },
];

/** 按基线规则命名：b-<模块小写英文短名>-prefs */
const PREF_KEY = 'b-psms-prefs';
const DEFAULT_THEME: ThemeId = 'tech-blue';
const DEFAULT_LAYOUT: LayoutId = 'side';

interface Prefs {
  theme: ThemeId;
  layout: LayoutId;
}

const FALLBACK_PREFS: Prefs = { theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT };

function readPrefs(): Prefs {
  if (typeof localStorage === 'undefined') return FALLBACK_PREFS;
  try {
    const raw = localStorage.getItem(PREF_KEY);
    if (!raw) return FALLBACK_PREFS;
    const parsed = JSON.parse(raw) as Partial<Prefs>;
    const validTheme = THEME_OPTIONS.some((t) => t.id === parsed.theme);
    const validLayout = LAYOUT_OPTIONS.some((l) => l.id === parsed.layout);
    return {
      theme: validTheme ? (parsed.theme as ThemeId) : DEFAULT_THEME,
      layout: validLayout ? (parsed.layout as LayoutId) : DEFAULT_LAYOUT,
    };
  } catch {
    return FALLBACK_PREFS;
  }
}

function applyTheme(theme: ThemeId): void {
  if (typeof document === 'undefined') return;
  document.documentElement.setAttribute('data-theme', theme);
}

// 模块加载即应用一次，避免首屏闪烁（main.tsx 会 import App → 本文件）
applyTheme(readPrefs().theme);

interface PreferenceState {
  theme: ThemeId;
  layout: LayoutId;
  setTheme: (id: ThemeId) => void;
  setLayout: (id: LayoutId) => void;
  reset: () => void;
}

interface PreferenceStore extends PreferenceState {
  toggleTheme: () => void;
}

function persist(theme: ThemeId, layout: LayoutId): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(PREF_KEY, JSON.stringify({ theme, layout }));
  } catch {
    // 存储不可用时静默降级（隐私模式等）
  }
}

const initial = readPrefs();

export const usePreferenceStore = create<PreferenceStore>()((set, get) => ({
  theme: initial.theme,
  layout: initial.layout,

  setTheme: (id) => {
    applyTheme(id);
    persist(id, get().layout);
    set({ theme: id });
  },

  setLayout: (id) => {
    persist(get().theme, id);
    set({ layout: id });
  },

  /** 在默认皮肤与暗夜黑之间快速切换（顶栏快捷入口用） */
  toggleTheme: () => {
    const next: ThemeId = get().theme === 'dark-pro' ? DEFAULT_THEME : 'dark-pro';
    applyTheme(next);
    persist(next, get().layout);
    set({ theme: next });
  },

  reset: () => {
    applyTheme(DEFAULT_THEME);
    persist(DEFAULT_THEME, DEFAULT_LAYOUT);
    set({ theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT });
  },
}));

export { PREF_KEY, DEFAULT_THEME, DEFAULT_LAYOUT };
