# C08 / UI-008 最终验证

- 生成时间：`2026-07-21 21:40:03 +08:00`
- 分支：`demo/c08-exception-handling`
- 最终证据提交前 HEAD：`c4beddcd0eba0ba9a58f113f73b40a4eaf8c2b5b`
- 起始 HEAD：`331cdab5179dd840002dca6fa3db93b8dd961705`
- 运行环境：Windows / PowerShell，Node `v24.14.0`，pnpm `11.9.0`
- 仓库要求 Node `24.18.0`；本次未安装、升级或修改依赖。

## 命令结果

| 时间（+08:00） | 命令 | 退出码 | 实测结果 |
|---|---|---:|---|
| 21:24 | `vitest run src/features/exception-handling ...ExceptionHandlingPage.*.test.tsx` | 0 | 8/8 文件，44/44 测试通过。 |
| 21:25 | `pnpm test -- --run --maxWorkers=1`（首次） | 1 | 66/68 文件、545/547 测试通过；C08 长链路和既有 UI-002 用例在全量负载下各触发 30 秒超时。 |
| 21:27 | 两条超时用例分别隔离复跑 | 0 | C08 1/1 通过（测试 11.46 秒）；既有 UI-002 1/1 通过（测试 17.79 秒）。 |
| 21:28 | `pnpm test -- --run --maxWorkers=1`（C08 局部 60 秒上限后） | 1 | 67/68 文件、546/547 测试通过；C08 全部通过，唯一失败是预检已记录的 `PlanLedgerPage.render` 既有 30 秒超时。 |
| 21:29 | `pnpm build` | 0 | Vite 生产构建通过，1922 modules transformed。 |
| 21:30 | `playwright test e2e/ui-008-exception-handling.spec.ts` | 0 | C08 4/4 通过，用时 50.7 秒。 |
| 21:30–21:37 | `pnpm test:e2e` | 0 | 全量 Playwright 35/35 通过，单 worker，用时 6.7 分钟。 |
| 21:38 | `tsc --noEmit` | 1 | 512 条诊断落盘至 `tsc-after.txt`；全为既知 React/JSX 声明类，C08 非 React 主诊断 0。 |
| 21:39 | 六项 SHA-256 复核 | 0 | 6/6 MATCH。 |
| 21:39 | 禁区扫描与 `git diff --check` | 0 | 禁止范围命中 0；空白错误 0。 |
| 21:44 | C08 focused 最终复跑、coverage JSON 解析与 `git diff --check` | 0 | 当前代码 8/8 文件、44/44 测试通过；JSON 有效；空白错误 0。 |

## Vitest 分类

- C08 focused：44/44 通过，包括 selectors、query context、API-014/API-015 Gateway、EX-01..EX-05、跨页集成和 UI-008 页面。
- 全量第二次执行：546/547 通过；唯一未在全量负载中通过的是 C08 开始前已记录的 UI-002 `round-trips the SCN-02 missing-field exception filter through URL replacement and remount` 30 秒超时。
- 同一 UI-002 用例隔离复跑 1/1 通过，测试时间 17.79 秒。
- 未修改全局 Vitest timeout/config。仅为新增的 C08 七步动作长链路设置局部 60 秒上限；其隔离实测为 11.46 秒，并在第二次全量执行中通过。

## TypeScript 分类

| 诊断码 | before | after | 分类 |
|---|---:|---:|---|
| TS2604 | 29 | 36 | 既知 React/JSX 声明类 |
| TS7016 | 81 | 89 | 既知 React/JSX 声明类 |
| TS7026 | 357 | 387 | 既知 React/JSX 声明类 |
| 其他 | 0 | 0 | 无 |
| 合计 | 467 | 512 | `tsc` 退出 1，未伪装为通过 |

新增诊断来自新建 UI-008 TSX 页面、组件和测试继续命中仓库既有的 React 类型声明缺口。C08 非 React primary diagnostics 为 0。

## 冻结哈希

| 文件 | SHA-256 | 结果 |
|---|---|---|
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

## 截图

- 文件数：8/8；文件名严格匹配冻结清单。
- 视口：1440×900、1280×720；全页 PNG 原始宽度分别为 1440、1280。
- E2E 对 `document` 与 `body` 横向溢出均断言不超过 1px。
- 已逐张检查按钮裁切、文本重叠、状态流可读性和 UI-009 入口，结果见 `screenshot-index.md`。

## 变更范围与禁区

- C08 实现集中在 `src/features/exception-handling`、UI-008 页面/测试、Gateway Mock 支持、共享 runtime、C07 DB-05 兼容测试、C08 E2E 与证据文档。
- `WorkOrder`、`WorkNode`、`Resource`、`Plan`、`RecommendationDraft` 和 `Interlock` Store 事实未被 C08 命令修改；集成测试覆盖 EX-01/EX-02 后的上游不变性。
- 无 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、`src/auth/permissionCatalog.ts`、`src/commands/stateMachines.ts` 或公共 contracts schema 修改。
- 无 UI-009 页面或联锁恢复实现；INTERLOCK 仅阻断 C08 写动作并链接 UI-009。
- DO-009 未增加或伪造 `workOrderId`、`planId`、`resourceId` 或联锁外键。
- 未合并、未推送。

## 提交链（最终证据提交前）

1. `91044bb` `test(c08): record exception handling preflight`
2. `91a3059` `feat(c08): add exception handling projections`
3. `a0f5672` `feat(c08): add exception handling api gateway`
4. `ecacd1e` `feat(c08): add exception handling commands`
5. `55800d0` `feat(c08): implement exception handling page`
6. `920b8c9` `test(c08): verify exception cross-page integration`
7. `a02302e` `test(c08): cover exception handling demo flows`
8. `c4beddc` `test(c08): stabilize exception action full-suite timeout`

最终证据提交将另行追加；由于 Git commit 无法在自身内容中稳定自引用，本文记录的是执行所有验证命令时的完整 HEAD。

## 已知限制

- 全局 TypeScript 仍因仓库既有 React/JSX 声明缺口退出 1，实际分类已完整记录。
- 全量单 worker Vitest 仍会使一条既有 UI-002 用例触发 30 秒超时；该用例隔离通过，C08 focused 与 C08 全量参与部分均通过。
- Node `v24.14.0` 低于仓库声明的 `24.18.0`，但构建与 E2E 均在该实测环境完成。
- UI-009 仍是后续模块入口；C08 不实现联锁恢复。
