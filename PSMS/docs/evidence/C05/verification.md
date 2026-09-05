# C05 最终验证记录

## 结论

C05 的冻结 focused Vitest、全量 Vitest、生产构建、4 条 C05 Playwright 与完整 23 条 Playwright 均以最终实现重新执行并通过。六份冻结基线哈希保持 `6/6 MATCH`，8 张截图完成 `8/8` 原始分辨率复核，baseline、依赖/锁文件和 UI-004 无差异。

全局 TypeScript 检查如实为退出码 `1`：冻结依赖没有 React/ReactDOM declaration，最终为 `360` 条且仅包含 TS2604、TS7016、TS7026；C05 非 React primary diagnostics 为 `0`。未修改依赖、声明、`tsconfig`，也未以 `any` 或 suppression 绕过诊断。

## 分支、提交与环境

- 分支：`demo/c05-recommendation`。
- Task 8 文档生成前实现 HEAD：`b8dcf0f418e7c239106cfd54a50f64ffa78b1879`。
- 最终验证窗口：`2026-07-21 14:33:10`～`14:45`（Asia/Shanghai）。
- Node：`v24.18.0`；pnpm：`11.10.0`，均与仓库声明一致。
- Vitest：`4.1.10`；Playwright：`1.61.1`；Vite：`8.1.4`；TypeScript：`7.0.2`。
- 每条 Node 命令只在当前进程 PATH 前置既有精确 runtime：`tmp/c01-node-v24.18.0/node-v24.18.0-win-x64`，未改项目配置。
- 构建保留既有 `>500 kB` chunk advisory。

实现提交链：

| 提交 | 说明 |
| --- | --- |
| `e3901fc8f09ca3dcddbd94076509c1b702b5814c` | `test(c05): record recommendation preflight` |
| `bf7d381c7b016886770b96c4c86c913146581cb6` | `fix(c05): preserve resolved SCN-02 plan fault` |
| `b4f317334ef6472a6864dd249562a77b4efd4219` | `feat(c05): add deterministic recommendation core` |
| `43fad9a841b3a5d17e31fb21c5dbcfc6ba227bfe` | `feat(c05): add recommendation projections and gateway` |
| `784f4a4e8028072ce1e4973807dfe3d85a0efff4` | `feat(c05): add recommendation command pipeline` |
| `5f8b4b275acfcab14bc708237d3a6197e29d0bf5` | `feat(c05): add reception recommendation workspace` |
| `0012d889f2d265b78b66caabd45d285eec110acc` | `fix(c05): show confirmed adjustment provenance` |
| `b8dcf0f418e7c239106cfd54a50f64ffa78b1879` | `test(c05): cover recommendation demo flows` |

Task 8 的 coverage、verification、TSC 输出和 handoff 由最终 `docs(c05): add recommendation evidence and handoff` 提交承载；其完整哈希以提交后的仓库 HEAD 为准，避免在提交内容中制造自引用哈希。

## 命令与实测结果

| 命令 | 退出码 | 实测结果 |
| --- | ---: | --- |
| Task 8 冻结的精确 16-file focused Vitest 命令 | 0 | `16/16` 文件，`97/97` 测试 |
| `pnpm test -- --run` | 0 | `42/42` 文件，`322/322` 测试 |
| `pnpm build` | 0 | Vite `1875 modules transformed`，构建成功 |
| `node_modules\.bin\playwright.CMD test e2e/ui-003-reception-recommendation.spec.ts` | 0 | C05 `4/4` |
| `pnpm test:e2e` | 0 | 全量 `23/23`，单 worker，`3.7m` |
| `node_modules\.bin\tsc.CMD --noEmit` | 1 | `360` 条 React/JSX declaration 级联；C05 非 React primary `0` |
| 六文件 `Get-FileHash -Algorithm SHA256` | 0 | `6/6 MATCH` |
| 设计提交至 HEAD 的 protected-scope diff | 0 | baseline/package/lock/UI-004 `0` |
| C05 production clock/random/mutation 扫描 | 0 | 时钟/随机 `0`；`replaceDomainState` 仅命令服务 3 处，页面/组件 `0` |
| `git diff --check` | 0 | 无 whitespace error |

精确 focused Vitest 命令：

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts src/features/recommendation/__tests__/gateway.test.ts src/features/recommendation/__tests__/selectors.test.ts src/features/recommendation/__tests__/workflow.test.ts src/features/recommendation/__tests__/commands.test.ts src/features/recommendation/components/__tests__/AdjustmentDrawer.test.tsx src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts src/runtime/__tests__/runtime.test.tsx src/features/plan-entry/__tests__/commands.test.ts src/app/__tests__/routeRender.test.tsx src/app/__tests__/routePermission.test.tsx
```

全量 Playwright 后 C01/C04/C05 PNG 会被测试重新生成。运行前工作区干净；验证 `23/23` 后恢复这些测试副作用，使旧阶段截图和已提交 C05 图不产生重复二进制改写。

浏览器级版本恢复仍只影响下一次 API-006：由于 Chromium 中 MSW Service Worker 先于 Playwright page route 响应 `/mock/**`，C05 E2E 在页面启动前安装一次性 `fetch` 边界注入，让应用收到计划规定的严格 409 `DEMO-VERSION-001` 信封，使用后自动关闭。真实 Store Plan/Track 版本变化在 focused 命令测试中分别证明会在 API 调用前被拒绝。

## TypeScript 实际状态

前置 C04 基线：

```text
TOTAL = 286
TS2604 = 13
TS7016 = 51
TS7026 = 222
C04_NON_REACT_PRIMARY = 0
```

C05 fresh capture（完整输出见 `tsc-after.txt`）：

```text
TOTAL = 360
TS2604 = 18
TS7016 = 63
TS7026 = 279
C05_COMPATIBILITY_TOTAL = 70
C05_NON_REACT_PRIMARY = 0
```

新增数量来自 C05 生产页面、组件和测试的 React/JSX declaration 级联。最终文件没有其他 TS code，故全局 TSC 未通过，但 C05 可修复的主诊断为 0。

## 冻结哈希

| 文件 | SHA-256 | 结果 |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

## 截图与原图复核

| 文件状态 | 视口 | 原始像素 | 结果 |
| --- | --- | --- | --- |
| SCN-01 CALCULATED | 1440×900 | 1440×1564 | PASS |
| SCN-01 CALCULATED | 1280×720 | 1280×2066 | PASS |
| SCN-01 CONFIRMED | 1440×900 | 1440×1575 | PASS |
| SCN-01 CONFIRMED | 1280×720 | 1280×2077 | PASS |
| SCN-01 ADJUSTMENT | 1440×900 | 1440×1564 | PASS |
| SCN-01 ADJUSTMENT | 1280×720 | 1280×2123 | PASS |
| SCN-02 CONFIRMED | 1440×900 | 1440×1575 | PASS |
| SCN-02 CONFIRMED | 1280×720 | 1280×2077 | PASS |

精确 `8/8` 均以原始分辨率打开检查：document/body 横向溢出 ≤1px；候选评分、时间轴、排除项、确认反馈和调整抽屉完整可读；中文字符正常；无裁切、重叠、瞬态动画残影或异常空白。逐图测试名和检查结论见 `screenshot-index.md`。

## 覆盖与范围审计

- `coverage.json` 枚举 UI-003、3 个 selector、API-005/API-006、SCN-01/SCN-02、6 种 UI 状态、权限、版本、幂等、审计、reset、SCN-02 兼容、4 条 E2E 与 8 张截图；`checks` 使用精确测试文件、名称和状态。
- 从设计提交 `5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61` 到实现 HEAD，`docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、`src/pages/dispatch/TaskDecompositionPage.tsx` 均无差异。
- C05 production 未使用 `Date.now`、`Math.random`、`randomUUID` 或 `resetFromSnapshot`；唯一 Store mutation 位于命令服务的三处原子 commit/audit 路径，页面和组件为 0。
- UI-004 仍为 C01 骨架；C05 只在推荐草稿 CONFIRMED 后开放 `/dispatch/plans/:planId/tasks`。
- 最终文档提交后 `git status --short --branch` 为空变更，工作区干净。
