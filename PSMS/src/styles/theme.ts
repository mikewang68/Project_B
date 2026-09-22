import { theme as antdTheme, type ThemeConfig } from 'antd';
import type { ThemeId } from '../stores/preference';

/**
 * antd 主题配置：4 套皮肤 × 统一设计令牌。
 *
 * 对应《统一换肤设置同步指南（IAM / SYS 基线）》第 4 / 5 节。
 *
 * 关键设计说明：
 *  基线指南第 5 节给的是 **Element Plus 覆盖层**（`.el-*` 选择器），
 *  对使用 antd 的 PSMS 不适用。antd 6 的绝大多数组件视觉由 ThemeConfig
 *  的 design token 驱动，因此这里用 **令牌** 而非 CSS 覆盖来实现同样的目标效果：
 *  颜色、圆角、边框、表面、导航底色全部通过 token 下发，
 *  业务页面无需重复写表头色 / hover 色。
 *
 *  4 套皮肤的品牌主色与状态语义色**完全一致**（均为 #409EFF / #67C23A /
 *  #E6A23C / #F56C6C），差异只体现在导航与表面的明暗色调上 —— 与基线要求一致。
 */

/** 单套皮肤的表面/文字/边框取值（与 tokens.css 的同名皮肤块保持同步） */
interface ThemeSurface {
  navBg: string;
  navDeep: string;
  navText: string;
  navTextActive: string;
  navItemHover: string;
  topbarBg: string;
  topbarBorder: string;
  contentBg: string;
  cardBg: string;
  cardBorder: string;
  textStrong: string;
  textBase: string;
  textMuted: string;
  textDisabled: string;
  border: string;
  borderSecondary: string;
  divider: string;
  stripeBg: string;
  selectedBg: string;
  headerBg: string;
}

/** 全皮肤统一的品牌与状态语义色（不得随皮肤变化） */
const BRAND = {
  primary: '#409EFF',
  success: '#67C23A',
  warning: '#E6A23C',
  error: '#F56C6C',
  info: '#909399',
} as const;

const LIGHT_SURFACE_BASE = {
  topbarBg: '#FFFFFF',
  topbarBorder: '#E4E7ED',
  contentBg: '#F5F7FA',
  cardBg: '#FFFFFF',
  cardBorder: '#E4E7ED',
  textStrong: '#1F2937',
  textBase: '#303133',
  textMuted: '#909399',
  textDisabled: '#C0C4CC',
  border: '#DCDFE6',
  borderSecondary: '#E4E7ED',
  divider: '#EBEEF5',
  stripeBg: '#FAFAFA',
  selectedBg: '#ECF5FF',
  navText: '#B8C7D9',
  navTextActive: '#FFFFFF',
  navItemHover: 'rgba(64, 158, 255, 0.14)',
  headerBg: '#FAFBFC',
} as const;

const SURFACES: Record<ThemeId, ThemeSurface> = {
  'tech-blue': { ...LIGHT_SURFACE_BASE, navBg: '#24364A', navDeep: '#1F3043' },
  'forest-green': {
    ...LIGHT_SURFACE_BASE,
    navBg: '#20382F',
    navDeep: '#1A2E27',
    navText: '#BCCFC7',
  },
  'purple-elegant': {
    ...LIGHT_SURFACE_BASE,
    navBg: '#2C313A',
    navDeep: '#242932',
    navText: '#C0C7D1',
  },
  'dark-pro': {
    navBg: '#1F3043',
    navDeep: '#1A293B',
    navText: '#9FB2C8',
    navTextActive: '#FFFFFF',
    navItemHover: 'rgba(64, 158, 255, 0.16)',
    topbarBg: '#16233A',
    topbarBorder: '#243B5B',
    contentBg: '#0F1A2B',
    cardBg: '#16233A',
    cardBorder: '#243B5B',
    textStrong: '#E6EDF6',
    textBase: '#CBD5E1',
    textMuted: '#8FA3BD',
    textDisabled: '#6B809C',
    border: '#2C4569',
    borderSecondary: '#284062',
    divider: '#243B5B',
    stripeBg: '#182842',
    selectedBg: 'rgba(64, 158, 255, 0.16)',
    headerBg: '#1A2C48',
  },
};

function buildTheme(id: ThemeId): ThemeConfig {
  const s = SURFACES[id];
  const dark = id === 'dark-pro';

  return {
    ...(dark ? { algorithm: antdTheme.darkAlgorithm } : {}),
    token: {
      colorPrimary: BRAND.primary,
      colorInfo: BRAND.primary,
      colorSuccess: BRAND.success,
      colorWarning: BRAND.warning,
      colorError: BRAND.error,

      colorText: s.textBase,
      colorTextSecondary: s.textMuted,
      colorTextTertiary: s.textMuted,
      colorTextQuaternary: s.textDisabled,
      colorTextHeading: s.textStrong,

      colorBorder: s.border,
      colorBorderSecondary: s.borderSecondary,
      colorSplit: s.divider,

      colorBgLayout: s.contentBg,
      colorBgContainer: s.cardBg,
      colorBgElevated: s.cardBg,

      borderRadius: 6,
      borderRadiusLG: 10,
      controlHeight: 36,
      boxShadowSecondary: dark
        ? '0 10px 28px rgba(0, 0, 0, 0.36)'
        : '0 10px 28px rgba(15, 23, 42, 0.08)',
      fontFamily:
        "'Source Han Sans SC', 'Microsoft YaHei', 'PingFang SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', Arial, sans-serif",
    },
    components: {
      Layout: {
        siderBg: s.navBg,
        headerBg: s.topbarBg,
        bodyBg: s.contentBg,
        headerHeight: 56,
        triggerBg: s.navDeep,
      },
      Menu: {
        darkItemBg: s.navBg,
        darkSubMenuItemBg: s.navDeep,
        darkItemColor: s.navText,
        darkItemHoverColor: s.navTextActive,
        darkItemHoverBg: s.navItemHover,
        darkItemSelectedBg: BRAND.primary,
        darkItemSelectedColor: s.navTextActive,
        itemHoverBg: s.selectedBg,
        itemSelectedBg: s.selectedBg,
      },
      Card: {
        headerBg: s.headerBg,
      },
      Table: {
        headerBg: s.headerBg,
        headerColor: s.textMuted,
        headerSplitColor: s.divider,
        borderColor: s.divider,
        rowHoverBg: s.selectedBg,
      },
      Button: {
        primaryShadow: '0 5px 14px rgba(64, 158, 255, 0.18)',
      },
      Tabs: {
        itemSelectedColor: BRAND.primary,
        itemHoverColor: BRAND.primary,
      },
      Pagination: {
        // 指南第 5 节：选中页使用品牌色实心 + 白色文字。
        // 注意 antd 的 itemActiveColor 默认值就是 colorPrimary，
        // 只覆盖 itemActiveBg 会得到"蓝底 + 蓝字"，页码数字完全不可见 ——
        // 必须同时把激活态文字色显式改为白色。
        itemActiveBg: BRAND.primary,
        itemActiveColor: '#FFFFFF',
        itemActiveColorHover: '#FFFFFF',
      },
      Tag: {
        defaultBg: s.headerBg,
        defaultColor: s.textMuted,
      },
    },
  };
}

/** 4 套皮肤的 antd 主题配置 */
export const appThemes: Record<ThemeId, ThemeConfig> = {
  'tech-blue': buildTheme('tech-blue'),
  'forest-green': buildTheme('forest-green'),
  'purple-elegant': buildTheme('purple-elegant'),
  'dark-pro': buildTheme('dark-pro'),
};

/**
 * 默认皮肤主题。
 *
 * 保留该导出名以兼容既有测试与组件对 `appTheme` 的引用；
 * 等价于 `appThemes['tech-blue']`。
 */
export const appTheme: ThemeConfig = appThemes['tech-blue'];

/** 按皮肤 id 取 antd 主题配置，未知 id 回落到默认皮肤 */
export function getAppTheme(id: ThemeId | undefined): ThemeConfig {
  if (!id) return appTheme;
  return appThemes[id] ?? appTheme;
}
