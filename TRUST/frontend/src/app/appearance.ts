import { reactive } from "vue";

export type ThemeId =
  | "tech-blue"
  | "forest-green"
  | "purple-elegant"
  | "dark-pro";
export type LayoutId = "side" | "top" | "compact";

export interface ThemeOption {
  id: ThemeId;
  name: string;
  description: string;
  colors: [string, string];
}

export interface LayoutOption {
  id: LayoutId;
  name: string;
  description: string;
}

export const themes: ThemeOption[] = [
  {
    id: "tech-blue",
    name: "科技蓝",
    description: "深海军蓝导航 · 科技蓝主色（默认）",
    colors: ["#24364a", "#409eff"],
  },
  {
    id: "forest-green",
    name: "护眼墨绿",
    description: "墨绿导航 · 统一科技蓝主色",
    colors: ["#20382f", "#409eff"],
  },
  {
    id: "purple-elegant",
    name: "雅致深灰",
    description: "深灰导航 · 统一科技蓝主色",
    colors: ["#2c313a", "#409eff"],
  },
  {
    id: "dark-pro",
    name: "暗夜黑",
    description: "深色海军蓝 · 暗光护眼",
    colors: ["#1f3043", "#409eff"],
  },
];

export const layouts: LayoutOption[] = [
  { id: "side", name: "左侧菜单", description: "经典纵向导航" },
  { id: "top", name: "顶部导航", description: "横向菜单 · 内容全宽" },
  { id: "compact", name: "图标窄栏", description: "窄侧栏 · 悬停提示" },
];

const STORAGE_KEY = "b-project-appearance";
const COOKIE_KEY = "b-project-appearance";
const DEFAULT_THEME: ThemeId = "tech-blue";
const DEFAULT_LAYOUT: LayoutId = "side";

interface AppearancePreference {
  theme: ThemeId;
  layout: LayoutId;
}

function isTheme(value: unknown): value is ThemeId {
  return themes.some((theme) => theme.id === value);
}

function isLayout(value: unknown): value is LayoutId {
  return layouts.some((layout) => layout.id === value);
}

function readPreference(): AppearancePreference {
  const fallback = { theme: DEFAULT_THEME, layout: DEFAULT_LAYOUT };
  try {
    const cookie = document.cookie
      .split("; ")
      .find((item) => item.startsWith(`${COOKIE_KEY}=`));
    const raw = cookie
      ? decodeURIComponent(cookie.slice(COOKIE_KEY.length + 1))
      : localStorage.getItem(STORAGE_KEY);
    if (!raw) return fallback;
    const value = JSON.parse(raw) as Partial<AppearancePreference>;
    return {
      theme: isTheme(value.theme) ? value.theme : DEFAULT_THEME,
      layout: isLayout(value.layout) ? value.layout : DEFAULT_LAYOUT,
    };
  } catch {
    return fallback;
  }
}

function applyTheme(theme: ThemeId) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme =
    theme === "dark-pro" ? "dark" : "light";
}

const initial = readPreference();
applyTheme(initial.theme);
const preference = reactive<AppearancePreference>(initial);

window.addEventListener("storage", (event) => {
  if (event.key !== STORAGE_KEY) return;
  const current = readPreference();
  preference.theme = current.theme;
  preference.layout = current.layout;
  applyTheme(current.theme);
});

function persist() {
  const value = JSON.stringify(preference);
  try {
    localStorage.setItem(STORAGE_KEY, value);
  } catch {
    // The appearance still applies for the current page when storage is unavailable.
  }
  document.cookie = `${COOKIE_KEY}=${encodeURIComponent(value)}; Path=/; Max-Age=31536000; SameSite=Lax`;
}

export function useAppearance() {
  function setTheme(theme: ThemeId) {
    preference.theme = theme;
    applyTheme(theme);
    persist();
  }

  function setLayout(layout: LayoutId) {
    preference.layout = layout;
    persist();
  }

  function reset() {
    preference.theme = DEFAULT_THEME;
    preference.layout = DEFAULT_LAYOUT;
    applyTheme(DEFAULT_THEME);
    persist();
  }

  return { preference, themes, layouts, setTheme, setLayout, reset };
}
