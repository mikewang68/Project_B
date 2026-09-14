# 前端统一视觉与 Sidebar 导航重构说明

> 范围：仅前端视觉语言统一 + 左侧 Sidebar 导航 Bug 修复。**未改动任何后端代码、API Contract、业务流程、props/emits、REST/WebSocket 与各端联动。**
> 依据：《六大模块前端统一视觉与交互规范.md》（品牌主色 `#409EFF`、深海军蓝导航、浅色企业级后台、深色调度大屏、150–250ms 动效、禁紫粉霓虹）。
> 技术栈不变：Vue3 + TypeScript + Element Plus + ECharts；构建工具链：工程自带 Node 24.18.0 + pnpm 10.34.5。

---

## 1. Design Tokens（统一变量层）

位置：`frontend/src/styles/tokens.scss`（在原有变量体系上重写，未新建第二套主题）。

### 1.1 新增规范变量 `--app-*`

| 分组 | 变量 | 值 |
| --- | --- | --- |
| 品牌 | `--app-color-primary` / `-hover` / `-active` / `-light` | `#409EFF` / `#66B1FF` / `#337ECC` / `#ECF5FF` |
| 导航 | `--app-nav-bg` / `-deep` / `-text` / `-text-secondary` / `-text-active` / `-item-hover` / `-item-active` | `#24364A` / `#1F3043` / `#B8C7D9` / `#8FA5BC` / `#FFFFFF` / `rgba(64,158,255,.14)` / `#409EFF` |
| 容器 | `--app-page-bg` / `-surface` / `-surface-secondary` / `-selected-bg` | `#F5F7FA` / `#FFFFFF` / `#FAFBFC` / `#ECF5FF` |
| 文字 | `--app-text-title` / `-primary` / `-regular` / `-secondary` / `-disabled` | `#1F2937` / `#303133` / `#606266` / `#909399` / `#C0C4CC` |
| 边框 | `--app-border-color` / `-border-light` / `-divider` | `#DCDFE6` / `#E4E7ED` / `#EBEEF5` |
| 状态 | success / warning / danger / info | `#67C23A` / `#E6A23C` / `#F56C6C` / `#909399` |
| 交互 | hover-bg / clickable-hover-bg / focus-shadow | `#F5F7FA` / `#ECF5FF` / `0 0 0 2px rgba(64,158,255,.12)` |
| 阴影 | card / card-hover | `0 2px 10px rgba(31,45,61,.06)` / `0 4px 14px rgba(31,45,61,.10)` |

### 1.2 兼容别名

历史 `--color-*` 变量名**保留为指向新色板的别名**，使既有模块 SCSS 自动跟随，避免大面积改类名。

### 1.3 圆角 / 阴影收敛（消费级圆润 → 企业级克制）

- 按钮圆角 11px → 8px；卡片 18px → 12px；大容器 22px → 14px。
- 厚重阴影 `0 10px 30px` 系列 → 规范轻阴影。

---

## 2. Element Plus 主题统一

通过 Element 官方 **CSS 变量通道**（`--el-color-primary` 及 `light-3/5/7/8/9`、`dark-2` 阶梯，success/warning/danger/error/info 同步）统一，不逐个组件暴力覆盖。同时在 tokens.scss 末尾对关键组件做轻量收敛：

- `Button`：primary 品牌蓝实底 / hover `#66B1FF` / active `#337ECC`；danger 仅用于删除、终止、作废等危险语义；普通次按钮白底灰边。
- `Table`：表头 `#FAFBFC`、表头字 `#909399`（600）、正文 `#606266`、分隔 `#EBEEF5`、行 hover/选中 `#ECF5FF`、操作链接 `#409EFF`。
- `Input/Select/Textarea`：边框 `#DCDFE6`、hover `#C0C4CC`、focus 品牌蓝 + 规范 focus shadow、placeholder `#C0C4CC`、disabled `#F5F7FA`。
- `Dialog/Drawer`：白底、轻边框、圆角收敛、Footer 主操作靠右。
- `Pagination/Tabs/Tag/Message/Loading`：跟随同一套主色与状态色。

---

## 3. Sidebar 视觉调整（`styles/main.scss` + `components/layout/AppSidebar.vue`）

- 背景由**白底**改为深海军蓝 `#24364A`，深层/分组块 `#1F3043`；普通文字 `#B8C7D9`、辅助 `#8FA5BC`、选中白字。
- 菜单项高度统一、图标文字垂直居中、圆角 8px（非药丸化）；active = 品牌蓝实底白字 + 右侧 active dot；hover = `rgba(64,158,255,.14)`，动效 180ms。
- **删除旧 `transform: translateX(2px)` hover 位移**（规范禁止左右位移 / 缩放 / glow）。
- 品牌块、场区选择器、侧栏底部"边缘服务"、展示端入口全部适配深色；顶部 Header 由半透明毛玻璃改为纯白。
- 布局尺寸（侧栏宽 252px、顶栏高、1260/980/680 响应式、980 以下抽屉化 + 遮罩）**全部保留**，未改信息架构。

---

## 4. Sidebar 导航 Bug 审计结论与修复

### 4.1 审计方式

完整核对 `router/index.ts`、`App.vue`、`AppSidebar.vue`、所有 `RouterLink` / `href` / `route.name` / `meta` / `redirect` / `children` / catch-all。

### 4.2 实际根因（不是路由表配错）

路由表与 Sidebar 数据源本身**一一对应、无重名、无缺失、无跳错**；active 也已由 `RouterLink` 的 `router-link-active`（Router 为唯一来源）驱动，方向正确。真正问题有三处：

1. **Standalone 页面缺"返回后台"入口**：安全大屏 `BigScreenHeader`、移动端 `MobileLayout` 都没有显式返回管理端的控件，只依赖浏览器历史，直接打开 URL 时无历史可回。→ 已补显式入口，固定指向 `/overview`（不使用 `history.back()`）。
2. **hover 位移**：菜单项 hover 时左右抖动（视觉层"像没点中/错位"），已删除。
3. **可测性缺失**：此前没有路由/侧栏测试，导航正确性无回归保障。→ 已补测试（见第 9 节）。

> 结论：不存在"点了完全没反应 / 跳错页面"的坏链；本次修复的是 standalone 返回能力、hover 交互体感，并为全部菜单补齐自动化回归。未发现需要改动路由 path 的情况，因此**未改动任何成熟路由路径**。

### 4.3 完整 Sidebar → Route 映射表（以 `router/index.ts` 实际配置为准）

| Sidebar 菜单 | path | route name | 独立布局 | 状态 |
| --- | --- | --- | --- | --- |
| 安全态势 | `/overview` | overview | 管理外壳 | 正常 |
| 人员定位 | `/people` | people | 管理外壳 | 正常 |
| 电子围栏 | `/fences` | fences | 管理外壳 | 正常 |
| 设备防碰撞 | `/devices` | devices | 管理外壳 | 正常 |
| AI违规识别 | `/ai` | ai-review | 管理外壳 | 正常 |
| 告警中心 | `/alarms` | alert-center | 管理外壳 | 正常 |
| 统计分析 | `/analytics` | analytics | 管理外壳 | 正常 |
| 规则配置 | `/rules` | rules | 管理外壳 | 正常 |
| 运维监控 | `/operations` | operations | 管理外壳 | 正常 |
| 安全大屏（全屏，新窗口） | `/safety-screen` | safety-screen | `meta.standalone=true` | 正常 + 已补返回后台 |
| 移动端告警处置（新窗口） | `/mobile/home` | mobile-home（`/mobile`→`/mobile/home`；children home/alerts/alert/:id） | `meta.standalone=true` | 正常 + 已补返回后台 |

其它：`/`、`/demo` → `/overview`；catch-all `/:pathMatch(.*)*` → `/overview`（不产生空白页）。

### 4.4 active 机制

不维护本地 selectedMenu，统一由 `currentRoute` + `router-link-active` 计算；浏览器前进/后退、F5、代码路由跳转均自动同步（已自动化 + 浏览器实测）。

### 4.5 未开放 / 占位菜单

**无**。所有可见菜单均有真实目标；展示端两个入口为 `target="_blank" rel="noopener"` 新窗口打开 standalone 页，属预期行为。

---

## 5. 浅色 / 深色页面清单

### 5.1 标准浅色企业级（页面底 `#F5F7FA`、白卡片）

`/overview` `/people` `/fences` `/devices` `/ai` `/alarms` `/analytics` `/rules` `/operations`，统一卡片（白底、`#E4E7ED` 边、轻阴影、圆角 12）、按钮、表格、表单、状态 Tag、Drawer/Dialog、空/错/Loading。

### 5.2 规范深色（调度大屏，`styles/screen.scss`）

`/safety-screen`：背景 `#0F1B2D`、面板 `#17263A`、面板边 `rgba(64,158,255,.28)`、主文字 `#FFFFFF`、次文字 `#AFC2D8`，沿用同一套品牌/状态色；去除原先的浅色面板与任何紫粉/霓虹/发光。

### 5.3 移动端（`styles/mobile.scss`）

不套桌面尺寸，保留手机壳、触控尺寸、底部 tab 与信息密度，仅把品牌蓝、状态色、文字层级、卡片/Tag 统一到规范色板；状态栏新增低调"后台"返回入口。

---

## 6. ECharts 统一

- 基座 `composables/useEChart.ts`：导出统一 `CHART_COLORS`、色序 `CHART_PALETTE = [#409EFF,#67C23A,#E6A23C,#F56C6C,#909399,#79BBFF,#95D475,#EEBE77]`、`chartAxis`（轴字 `#909399`、轴线 `#DCDFE6`、网格 `#EBEEF5`）；tooltip 白底 `#303133` 字、`#DCDFE6` 边、圆角 8、轻阴影。
- 风险四级语义：一般 `#EEBE77`（warning 浅）、预警 `#E6A23C`、严重 `#F56C6C`、紧急 `#D63B3B`（danger 强化，以更深色 + 字重区分，**不使用紫/品红/荧光**）。
- 已改造组件：`TrendChart`、`overview/RiskDistributionChart`、`overview/RiskTrendChart`、`analytics/RiskLevelChart`、`analytics/RiskTypeChart`、`analytics/AlertTrendChart`、`collision/DistanceTrendChart`、`screen/BigScreenTrend`（深色轴字/网格/面积色）。
- SVG 证据示意图同步换色：`ai/AIDetectionImage.vue`、`alerts/AlertEvidence.vue`、`mobile/MobileEvidenceUpload.vue`。

---

## 7. 替换 / 移除的旧颜色

- 旧主色 `#315fa8` 系（含 `#284f8d/#3f70bd/#6484b5` 等阶梯、紫蓝 `#5566b2/#5b5fb2`）→ 品牌蓝 `#409EFF` 阶梯。
- 旧 success `#2d8b6f` 青绿 → `#67C23A`；旧 warning `#c58a2a` 琥珀 → `#E6A23C`；旧 danger `#d6474f` 偏玫红 → `#F56C6C`。
- 旧标题 `#172033`、正文 `#4d5a70/#647085`、弱文字 `#929bad` → `#1F2937/#606266/#909399`。
- 旧边框/分隔 `#e6eaf0/#edf0f4` 等 → `#E4E7ED/#EBEEF5`；近白蓝灰背景统一到 `#FAFBFC/#F5F7FA`。
- 9 个模块 SCSS 一次性查表迁移 **503 处**，其后再做一轮近白灰蓝收敛；全项目终检 `purple/violet/magenta/#7c3aed/#8b5cf6` 等紫粉荧光 **0 命中**。

---

## 8. 业务无侵入确认

- 未改 `backend/` 任何文件；未改 API 路径 / DTO / 状态机；未删 `mock/*`（仍可作 fixture/fallback）。
- 组件仅改样式与静态展示色，**props/emits/业务逻辑/REST/WebSocket/三端联动保持不变**。
- 共享地图 `shared/SafetyMapCanvas.vue` 仅用于人员/围栏浅色页，大屏使用独立 `screen/BigScreenMap`，故无需为深色额外改造，未引入回归。

---

## 9. 测试与构建（工程自带 Node 24.18.0 + pnpm 10.34.5）

新增：

- `src/router/navigation.spec.ts`：9 业务路径解析、name 无重复、根/`/demo`/catch-all 重定向、standalone 与移动子路由。
- `src/components/layout/AppSidebar.spec.ts`：渲染 9 个业务链接且 href 正确、无无目标占位、2 个展示端新窗口入口、路由变化时 active 由 Router 同步。

结果：

- `pnpm typecheck`（vue-tsc -b）：**通过**。
- `pnpm test`（vitest run）：**21 文件 / 77 用例全部通过**（含新增 10 条导航用例）。
- `pnpm build`（vue-tsc -b && vite build）：**成功**。仅存在 echarts 主 chunk >500kB 的既有体积提示（非本次引入、非错误）。

浏览器人工验收（dev + 后端本地联起）：

- 九个业务菜单逐一可达、URL 正确；Back / Forward / F5 正常，F5 后当前菜单 active 正确高亮。
- 窄屏（抽屉侧栏）汉堡可开、链接可点、遮罩可关；standalone 大屏 / 移动端**无双层 Sidebar**，新增"返回后台 / ←后台"均正确回到 `/overview` 并恢复管理外壳。
- 逐页抽查 `/overview /people /operations /alarms /analytics /ai /rules /safety-screen /mobile/home`：卡片、表格、表单、Tag、图表、深色大屏、移动端色语言均符合规范，无紫粉霓虹。

---

## 10. 已知视觉技术债 / 后续建议

1. 内置浏览器视口固定较窄（约 603px），1366/1440/1920 三宽度未逐像素人工比对；布局为流式且未改尺寸断点，建议在标准显示器再做一次走查。
2. 模块 SCSS 中仍以 `var(--color-*)` 别名消费为主，后续可逐步把组件内引用迁移到语义更清晰的 `--app-*`，并把零星内联色继续收敛进 Tokens（当前已无旧主色/紫粉残留）。
3. echarts 主 chunk 体积偏大，后续可用 `manualChunks` 按需分包（与本次视觉无关）。
4. 大屏地图基础坐标、移动端照片/视频等仍为 Demo 数据，属前序阶段既定范围，本次未改。
5. 风险等级"严重 / 紧急"目前用同色系深浅 + 字重区分；若后续需要更强区分，建议在规范内用图标/描边，仍不要引入新色相。
