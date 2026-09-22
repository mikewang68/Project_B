import type { ReactNode } from 'react';

import type { PageId } from '../auth/types';

/**
 * 侧栏页面图标（内联 SVG，不引入图标库依赖）。
 *
 * 为什么每个页面必须有独立图标：
 * antd 的 `Menu` 在折叠态（`inlineCollapsed` / `compact` 布局的 64px 窄栏）下
 * **只渲染 icon**，隐藏文字。若某个菜单项没有图标，该行在折叠态就是一片空白。
 * 因此这里给 13 个页面各配一个 16×16 的线性图标；展开态下图标也起到快速识别作用。
 *
 * 图形风格与 SkeletonLayout 内已有的 外观设置/勾选 图标保持一致：
 * 24 viewBox、`currentColor` 描边、1.7 线宽、圆角端点。
 */

function Glyph({ children }: { children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width="16"
      height="16"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  );
}

const NAV_ICONS: Record<PageId, ReactNode> = {
  // UI-001 调度总览：四宫格
  'UI-001': (
    <Glyph>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1.6" />
      <rect x="13.5" y="3.5" width="7" height="7" rx="1.6" />
      <rect x="3.5" y="13.5" width="7" height="7" rx="1.6" />
      <rect x="13.5" y="13.5" width="7" height="7" rx="1.6" />
    </Glyph>
  ),
  // UI-002 外部到发信息台账：表格
  'UI-002': (
    <Glyph>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
      <path d="M3.5 9.5h17M9.5 9.5v10" />
    </Glyph>
  ),
  // UI-003 生产准备建议：建议 / 灵感
  'UI-003': (
    <Glyph>
      <path d="M12 3.5a5.6 5.6 0 0 0-3.3 10.1V17h6.6v-3.4A5.6 5.6 0 0 0 12 3.5Z" />
      <path d="M9.6 20h4.8M10.6 17.9v2.1M13.4 17.9v2.1" />
    </Glyph>
  ),
  // UI-004 任务拆解：一拆多
  'UI-004': (
    <Glyph>
      <circle cx="12" cy="4.6" r="2" />
      <path d="M12 6.6v4M6 14.5v-1.9a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v1.9" />
      <circle cx="6" cy="17" r="2" />
      <circle cx="18" cy="17" r="2" />
    </Glyph>
  ),
  // UI-005 派工看板：看板列
  'UI-005': (
    <Glyph>
      <rect x="3.5" y="4.5" width="5" height="15" rx="1.5" />
      <rect x="9.5" y="4.5" width="5" height="10" rx="1.5" />
      <rect x="15.5" y="4.5" width="5" height="13" rx="1.5" />
    </Glyph>
  ),
  // UI-006 公路预约与叫号：车辆
  'UI-006': (
    <Glyph>
      <path d="M3.5 7h9v9.5h-9z" />
      <path d="M12.5 10.5h3.6l3.4 3.2v2.8h-7z" />
      <circle cx="7.2" cy="18.4" r="1.7" />
      <circle cx="16.6" cy="18.4" r="1.7" />
    </Glyph>
  ),
  // UI-007 全流程监控：趋势线
  'UI-007': (
    <Glyph>
      <path d="M3.5 18.5h17" />
      <path d="M4.5 15 9 10.5l3.5 3L20 6" />
    </Glyph>
  ),
  // UI-008 异常处置：警示
  'UI-008': (
    <Glyph>
      <path d="M12 4 21 19.5H3z" />
      <path d="M12 9.8v4.4M12 17h.01" />
    </Glyph>
  ),
  // UI-009 安全联锁：盾牌
  'UI-009': (
    <Glyph>
      <path d="M12 3.5 5 6.3v5.4c0 4 3 7.2 7 8.8 4-1.6 7-4.8 7-8.8V6.3z" />
      <path d="M9.4 11.9l1.8 1.8 3.4-3.3" />
    </Glyph>
  ),
  // UI-010 离线同步：双向循环
  'UI-010': (
    <Glyph>
      <path d="M19.5 12a7.5 7.5 0 0 1-12.4 5.6" />
      <path d="M4.5 12a7.5 7.5 0 0 1 12.4-5.6" />
      <path d="M17.6 3.4v3.3h-3.3M6.4 20.6v-3.3h3.3" />
    </Glyph>
  ),
  // UI-011 统计报表：柱状图
  'UI-011': (
    <Glyph>
      <path d="M3.5 20.5h17" />
      <path d="M6.5 20.5V14M11.5 20.5V6.5M16.5 20.5v-4.2" />
    </Glyph>
  ),
  // UI-012 系统配置：参数调节
  'UI-012': (
    <Glyph>
      <circle cx="12" cy="12" r="3.1" />
      <path d="M12 3.4v2.3M12 18.3v2.3M4.9 7.8l2 1.1M17.1 15.1l2 1.1M4.9 16.2l2-1.1M17.1 8.9l2-1.1" />
    </Glyph>
  ),
  // UI-013 审计日志：带勾的记录
  'UI-013': (
    <Glyph>
      <path d="M6 3.5h8l4 4v13H6z" />
      <path d="M14 3.5v4h4" />
      <path d="M9.2 14.4l1.9 1.9 3.7-3.6" />
    </Glyph>
  ),
};

/** 取某页面的侧栏图标；未登记时返回 undefined（antd 会退化为无图标菜单项） */
export function navIcon(pageId: PageId): ReactNode {
  return NAV_ICONS[pageId];
}
