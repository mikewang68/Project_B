# C01 工程骨架交接

## 1. 任务与交付结论

- 任务编号：`TASK-P0-001 建立精确工程基线`（C01）。
- 分支：`demo/c01-engineering-skeleton`。
- 工程目录：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`。
- 范围：React/Vite/TypeScript 可运行空壳、精确依赖与锁文件、13 个稳定路由、统一页面骨架、最小 Ant Design 布局、404、路由错误页、Vitest/Testing Library/Playwright 基础设施、离线审计与验证证据。
- 结论：冻结安装、单元测试、生产构建、真实 Chromium E2E 和双视口检查均已通过；未实现 C02 及后续业务功能。

## 2. 输入文件 SHA-256

| 输入文件 | SHA-256 |
|---|---|
| `B项目-生产调度管理模块网页Demo技术方案设计文档-v0.3-20260716.docx` | `9DAC0C92728CD68C159AD1C1C431A905DB1833FC08CBA98BF027AE05F7D16263` |
| `B项目-生产调度管理模块网页Demo页面与功能任务卡-v0.1-20260716.docx` | `77B03D17C804333CAC88F52C7326B73DF9D0EBA1F12C80663E88618E182F7A42` |
| `B项目-生产调度管理模块网页Demo Mock接口与数据契约-v0.1-20260716.docx` | `659128A65643E8ACB4402D3F28509185BCEB6503CD3D1F9FDE47AC4EE201BF19` |
| `B项目-生产调度管理模块网页Demo开发任务分解与验收清单-v0.1-20260716.docx` | `E3AC81720AAC774B3E171B604B827BF276E6266D9A02C01AB84EFE15224680DA` |
| `B项目-生产调度管理模块网页Demo开发附件包-v0.1-20260716.zip` | `03F183239B3BBACA50FA65D0EC9FDB558309111AE17011C8E80960F783B747F8` |

## 3. 六个机器基线 SHA-256

六个文件已原样解压到 `docs/baseline/`，实现过程中未修改内容。

| 基线文件 | SHA-256 |
|---|---|
| `README.md` | `52089817BC1DD71DBE9F148E39DF6B87DA4E5F124A564849100D98508ADE8EB0` |
| `package-baseline.json` | `CA501290C82F2742CB555C099B04C85CD505BF156B38D7B6A9062BCD1F4C1810` |
| `openapi.yaml` | `395429C7C73FB887EF52A6075D1919F44C09A771995D5B89AA29DBE59E03F030` |
| `demo-fixtures.json` | `864905B76EA6D2D88A898C3D08471523B35B872A62F376E628EF76301F6B673B` |
| `page-task-matrix.csv` | `C2FC700D07D962A03441824A1FA5B6B30564FDA9B73F85F31F8C6C7745F90C3B` |
| `traceability.csv` | `A83E7DF6C0963A184527EE55BDECDBD6F7691874502DC55C400B4EA860491DD2` |

## 4. 固定工具链与精确依赖

工具链：Node.js `24.18.0`，Corepack `0.35.0`，pnpm `11.10.0`。`packageManager` 为 `pnpm@11.10.0`，`engines.node` 为 `24.18.0`。

运行依赖：

| 包 | 版本 | 包 | 版本 |
|---|---:|---|---:|
| `@dnd-kit/core` | 6.3.1 | `@dnd-kit/sortable` | 10.0.0 |
| `antd` | 6.5.1 | `dayjs` | 1.11.21 |
| `echarts` | 6.1.0 | `maplibre-gl` | 5.24.0 |
| `react` | 19.2.7 | `react-dom` | 19.2.7 |
| `react-router-dom` | 7.18.0 | `zod` | 4.4.3 |
| `zustand` | 5.0.14 |  |  |

开发依赖：

| 包 | 版本 | 包 | 版本 |
|---|---:|---|---:|
| `@playwright/test` | 1.61.1 | `@testing-library/dom` | 10.4.1 |
| `@testing-library/jest-dom` | 6.9.1 | `@testing-library/react` | 16.3.2 |
| `@testing-library/user-event` | 14.6.1 | `@vitejs/plugin-react` | 6.0.3 |
| `jsdom` | 29.1.1 | `msw` | 2.15.0 |
| `typescript` | 7.0.2 | `vite` | 8.1.4 |
| `vitest` | 4.1.10 |  |  |

固定脚本：`dev=vite`、`test=vitest run`、`test:e2e=playwright test`、`build=vite build`、`preview=vite preview`。

## 5. 新增文件清单

本目录原先不存在，本次交付均为新增文件；未覆盖用户既有文件。

- 根配置：`.gitignore`、`index.html`、`package.json`、`pnpm-lock.yaml`、`pnpm-workspace.yaml`、`vite.config.ts`、`playwright.config.ts`、`tsconfig.json`、`tsconfig.node.json`。
- 应用入口与路由：`src/main.tsx`、`src/vite-env.d.ts`、`src/app/App.tsx`、`src/app/router.tsx`、`src/app/routeCatalog.ts`。
- 布局与样式：`src/layouts/SkeletonLayout.tsx`、`src/components/skeleton/PageScaffold.tsx`、`src/styles/theme.ts`、`src/styles/global.css`。
- 状态页：`src/pages/NotFoundPage.tsx`、`src/pages/RouteErrorPage.tsx`。
- 13 个页面：
  - `src/pages/dispatch/OverviewPage.tsx`
  - `src/pages/dispatch/PlanLedgerPage.tsx`
  - `src/pages/dispatch/ReceptionRecommendationPage.tsx`
  - `src/pages/dispatch/TaskDecompositionPage.tsx`
  - `src/pages/dispatch/DispatchBoardPage.tsx`
  - `src/pages/yard/RoadAppointmentPage.tsx`
  - `src/pages/monitor/OperationMonitorPage.tsx`
  - `src/pages/monitor/ExceptionHandlingPage.tsx`
  - `src/pages/safety/SafetyInterlockPage.tsx`
  - `src/pages/operations/OfflineSyncPage.tsx`
  - `src/pages/reports/OperationReportPage.tsx`
  - `src/pages/settings/SystemSettingsPage.tsx`
  - `src/pages/governance/AuditLogPage.tsx`
- 单元测试：`src/test/setup.ts`、`src/app/__tests__/dependencyBaseline.test.ts`、`routeCatalog.test.ts`、`appRender.test.tsx`、`routeRender.test.tsx`、`offlineResources.test.ts`。
- E2E：`e2e/globalSetup.ts`、`e2e/route-smoke.spec.ts`。
- 本地资源占位目录：`public/geo/.gitkeep`（只固定目录，不包含地图或业务数据）。
- 原样基线：`docs/baseline/` 下六个文件。
- 设计与计划：`docs/superpowers/specs/2026-07-18-c01-engineering-skeleton-design.md`、`docs/superpowers/plans/2026-07-18-c01-engineering-skeleton.md`。
- 证据：`docs/evidence/C01/tdd-red.md`、`verification.md` 和四张 PNG 截图。
- 交接：`docs/handoffs/C01-engineering-skeleton.md`。

## 6. 13 个稳定路由

`src/app/routeCatalog.ts` 是编号、名称、业务路由、冒烟路径、目标文件和导航分组的单一来源。

| 编号 | 中文名称 | 业务路由 | 冒烟路径 | 页面文件 |
|---|---|---|---|---|
| UI-001 | 调度总览 | `/dispatch/overview` | `/dispatch/overview` | `src/pages/dispatch/OverviewPage.tsx` |
| UI-002 | 计划接收台账 | `/dispatch/plans` | `/dispatch/plans` | `src/pages/dispatch/PlanLedgerPage.tsx` |
| UI-003 | 接车计划推荐 | `/dispatch/plans/:planId/recommendation` | `/dispatch/plans/PLAN-DEMO-001/recommendation` | `src/pages/dispatch/ReceptionRecommendationPage.tsx` |
| UI-004 | 任务拆解 | `/dispatch/plans/:planId/tasks` | `/dispatch/plans/PLAN-DEMO-001/tasks` | `src/pages/dispatch/TaskDecompositionPage.tsx` |
| UI-005 | 派工看板 | `/dispatch/work-orders` | `/dispatch/work-orders` | `src/pages/dispatch/DispatchBoardPage.tsx` |
| UI-006 | 公路预约与叫号 | `/yard/appointments` | `/yard/appointments` | `src/pages/yard/RoadAppointmentPage.tsx` |
| UI-007 | 全流程监控 | `/monitor/operations` | `/monitor/operations` | `src/pages/monitor/OperationMonitorPage.tsx` |
| UI-008 | 异常处置 | `/monitor/exceptions` | `/monitor/exceptions` | `src/pages/monitor/ExceptionHandlingPage.tsx` |
| UI-009 | 安全联锁 | `/safety/interlocks` | `/safety/interlocks` | `src/pages/safety/SafetyInterlockPage.tsx` |
| UI-010 | 离线同步 | `/operations/offline-sync` | `/operations/offline-sync` | `src/pages/operations/OfflineSyncPage.tsx` |
| UI-011 | 统计报表 | `/reports/operations` | `/reports/operations` | `src/pages/reports/OperationReportPage.tsx` |
| UI-012 | 系统配置 | `/settings/system` | `/settings/system` | `src/pages/settings/SystemSettingsPage.tsx` |
| UI-013 | 审计日志 | `/governance/audit` | `/governance/audit` | `src/pages/governance/AuditLogPage.tsx` |

根路径 `/` 重定向至 `/dispatch/overview`；未知路径进入 404；路由渲染异常进入错误页。

## 7. 最终验证结果

| 命令 | 真实结果 |
|---|---|
| `corepack enable` | 退出码 0 |
| `corepack prepare pnpm@11.10.0 --activate` | 退出码 0 |
| `pnpm install --frozen-lockfile` | 退出码 0；锁文件未变化；643 ms |
| `pnpm test` | 退出码 0；5/5 测试文件、9/9 用例通过；11.70 s |
| `pnpm build` | 退出码 0；1489 模块；869 ms |
| `pnpm test:e2e` | 退出码 0；14/14 用例通过；58.2 s |

TDD 红灯、缺陷闭环、离线审计和构建提示详见 `docs/evidence/C01/verification.md`。

## 8. 截图与证据路径

- `docs/evidence/C01/C01-overview-1440x900.png`
- `docs/evidence/C01/C01-404-1440x900.png`
- `docs/evidence/C01/C01-overview-1280x720.png`
- `docs/evidence/C01/C01-404-1280x720.png`
- `docs/evidence/C01/tdd-red.md`
- `docs/evidence/C01/verification.md`

四张截图已在显式等待项目标题、导航容器和 13 个导航链接全部可见后重新生成并人工查看：侧栏、顶栏、内容区和 404 无明显溢出、遮挡、空白页或异常留白；中文显示正常。

## 9. 已知限制与明确未实现范围

- 共享入口包压缩前约 665.78 kB，触发 Vite 默认 500 kB 非阻断提示；13 个页面已按路由拆包，真实业务进入后再依据访问路径优化共享依赖。
- 诊断命令 `tsc --noEmit` 当前退出码 1：机器基线未包含 `@types/react` 与 `@types/react-dom`。C01 受“依赖必须与 `package-baseline.json` 完全一致”约束，未越权加包、未伪造宽泛声明，也不声称类型检查通过；C02 开始前应先申请批准精确类型包并增加正式类型门禁。
- 新机器需要单独执行 `pnpm exec playwright install chromium` 安装浏览器缓存；浏览器二进制不提交到仓库。
- E2E 固定使用本机 `127.0.0.1:4173`；开发脚本默认使用 Vite 的 5173 端口。
- `pnpm-workspace.yaml` 明确禁止执行 MSW 2.15.0 的可选安装脚本；C01 不初始化 Service Worker。后续若批准浏览器端 MSW，应先依据对应任务审查该设置。
- 未实现 DO-001～DO-014、API-001～API-025、SCN-01～SCN-07、MSW handlers、Store、状态机、命令管线、乐观并发、RBAC、数据域、职责分离、脱敏、审计业务或任何页面业务功能。
- 未放置业务字段、筛选、表格、表单、图表、地图、业务按钮、Mock 数据或真实接口调用。

## 10. C02 的稳定起点与禁止改动项

C02 可直接依赖：

- 精确工具链、`package.json`、`pnpm-lock.yaml` 和五个固定脚本；
- `src/app/routeCatalog.ts` 的 13 条路由元数据；
- `src/app/router.tsx` 的根重定向、13 路由、404 与错误边界；
- `SkeletonLayout`、`PageScaffold`、主题、全局样式和所有固定页面文件路径；
- Vitest/Testing Library/Playwright 配置与 C01 回归测试；
- `docs/baseline/openapi.yaml`、`demo-fixtures.json`、`page-task-matrix.csv`、`traceability.csv` 作为后续实施输入。

未经批准的基线变更，C02 禁止：

- 修改 `docs/baseline/` 六个原样文件；
- 改动 Node/pnpm/依赖版本、脚本或锁文件；
- 改动 13 个页面编号、中文名称、路由、冒烟路径或页面文件位置；
- 删除或绕过根路径重定向、404、路由错误页、离线边界与 C01 回归测试；
- 在页面内另行定义与权威文档冲突的字段、枚举、接口路径、任务编号或样例数据；
- 为追求视觉效果引入外部字体、CDN、远程图片、在线地图或第三方后台模板。

C02 的建议起点：先读取获批的 C02 任务卡及四个机器基线文件，并先处理 React 类型包的基线修订审批；明确该任务获准实现的类型、Schema、Mock、状态或页面范围后，以 `routeCatalog.ts` 和现有页面文件为稳定接入点，先补失败测试，再做最小实现，并持续运行 C01 的 9 个单元断言和 14 个浏览器回归用例。

## 11. Git 状态与提交建议

- 仓库：`production-dispatch-demo` 子目录中的独立 Git 仓库。
- 父级 B项目空 `.git`：未删除、未修复、未写入。
- 当前分支：`demo/c01-engineering-skeleton`。
- 提交哈希：无，**未提交**。
- 原因：本机 Git 未配置 `user.name` 与 `user.email`，首次提交被 Git 拒绝；未伪造身份。
- 工作区：全部 C01 交付文件已暂存，尚无提交。

建议由用户在本地确认身份后执行：

```powershell
git config user.name "<姓名>"
git config user.email "<邮箱>"
git add .
git commit -m "feat(c01): scaffold production dispatch demo shell"
```
