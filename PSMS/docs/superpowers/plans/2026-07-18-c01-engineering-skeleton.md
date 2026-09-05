# C01 工程骨架实施计划

> **执行要求：** 按任务逐项实施并核验；每个步骤使用复选框（`- [ ]`）跟踪。

**目标：** 建立满足 `TASK-P0-001` 的精确、离线、可验证网页 Demo 工程空壳。

**架构：** 由 `routeCatalog.ts` 集中定义 13 个稳定页面元数据，Router、侧栏、页面骨架和测试共同消费。所有页面只呈现空壳说明，不引入任何 C02 业务模型、数据或接口。

**技术栈：** Node.js 24.18.0、pnpm 11.10.0、React 19.2.7、Vite 8.1.4、TypeScript 7.0.2、Ant Design 6.5.1、React Router 7.18.0、Vitest 4.1.10、Testing Library、Playwright 1.61.1。

## 全局约束

- `package.json` 中所有 npm 包与 `docs/baseline/package-baseline.json` 完全一致并使用精确版本。
- 父级空 `.git` 不读取以外写入；工程仅使用当前子目录的独立仓库和 `demo/c01-engineering-skeleton` 分支。
- 六个附件文件原样保存在 `docs/baseline/`。
- 13 个编号、路由、中文名称和页面文件保持冻结；参数路由冒烟值为 `PLAN-DEMO-001`。
- 运行时不依赖公网资源；不实现 C02 及后续业务功能。

---

### 任务 1：固化基线与精确工具链

**文件：** `docs/baseline/*`、`package.json`、`pnpm-lock.yaml`、`.gitignore`、`vite.config.ts`、`tsconfig.json`、`tsconfig.node.json`、`index.html`

- [x] 核对六个基线文件与附件包哈希，保持文件内容不变。
- [x] 使用官方 Node.js 24.18.0 便携运行时执行 `corepack enable` 和 `corepack prepare pnpm@11.10.0 --activate`。
- [x] 从 `package-baseline.json` 生成精确 `dependencies`、`devDependencies`、`engines.node`、`packageManager` 和五个固定脚本。
- [x] 正常执行一次 `pnpm install` 生成 `pnpm-lock.yaml`。

### 任务 2：建立并验证红灯测试

**文件：** `src/app/__tests__/dependencyBaseline.test.ts`、`src/app/__tests__/routeCatalog.test.ts`、`src/app/__tests__/appRender.test.tsx`、`src/app/__tests__/routeRender.test.tsx`、`src/app/__tests__/offlineResources.test.ts`、`src/test/setup.ts`

- [x] 先写依赖基线、13 路由唯一性、默认渲染、逐路由渲染、404 和离线资源约束测试。
- [x] 执行 `pnpm test`，确认测试因缺少生产实现而失败并记录失败原因。

### 任务 3：最小实现使测试转绿

**文件：** `src/main.tsx`、`src/app/App.tsx`、`src/app/router.tsx`、`src/app/routeCatalog.ts`、`src/layouts/SkeletonLayout.tsx`、`src/components/skeleton/PageScaffold.tsx`、`src/pages/**/*.tsx`、`src/styles/theme.ts`、`src/styles/global.css`

- [x] 实现单一目录、根路径重定向、13 条业务路由、404 和路由错误页。
- [x] 实现最小 Ant Design 桌面布局和只含元数据的页面骨架。
- [x] 执行 `pnpm test`，修正实现直至全部测试通过且无警告。

### 任务 4：端到端测试、构建与视觉证据

**文件：** `playwright.config.ts`、`e2e/route-smoke.spec.ts`、`docs/evidence/C01/*`

- [x] 配置 Playwright 测试服务器，逐一访问 13 个冒烟路径并断言页面编号。
- [x] 执行冻结安装、`pnpm test`、`pnpm build` 和 `pnpm test:e2e`，保存真实结果摘要。
- [x] 在 1440×900 与 1280×720 检查布局、13 路由和 404，保存代表性截图。

### 任务 5：交接与 Git 封板

**文件：** `docs/evidence/C01/verification.md`、`docs/handoffs/C01-engineering-skeleton.md`

- [x] 写入输入文件和六个基线文件 SHA-256、精确版本、文件清单、路由清单、验证结果、证据路径、限制与 C02 稳定起点。
- [x] 复核父级 `.git` 未变化、工程仓库状态、分支、提交哈希和工作区清洁度。
- [x] 尝试使用 `feat(c01): scaffold production dispatch demo shell` 提交；因 Git 身份未配置而明确记录“未提交”及建议命令。
