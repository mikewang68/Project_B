# C11 统计报表预检

## 结论

C11 权威基线闸门通过，可以在 `demo/c11-reporting-dashboard` 实施 UI-011。C10 基点、六份冻结 hash、UI-011 路由、DO-012、API-020、权限和共享 Store 均与已确认设计一致。API-021 按已确认结论保留冻结契约但不实施、不调用。

预检没有修改业务源码、baseline、依赖、公共契约、权限/状态机目录或 C10 实现。全量 Playwright 重拍的 53 个 C01/C04-C10 历史 PNG 已在确认无其他差异后恢复到 HEAD。

## 仓库与隔离

| 项目 | 实测 |
| --- | --- |
| 工程 | `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo` |
| 分支 | `demo/c11-reporting-dashboard` |
| 预检前 HEAD | `2ba356f77149fae0bb4d228ce3d145eb22de3827` |
| C10 基点 | `7fb529c50059b39f088859d42f421d423e7780f7` |
| C10 是否为祖先 | 是，`git merge-base --is-ancestor` exit 0 |
| C10 之后 merge commit | `0` |
| 工作区 | 预检前干净；预检后仅新增本目录证据 |
| 合并/推送 | 均未执行 |

`GIT_DIR` 与 `GIT_COMMON` 都是工程根目录下 `.git`，当前不是 linked worktree。用户已明确指定在该工程目录新建 C11 分支，因此按 `using-git-worktrees` 的用户偏好路径在当前专用分支工作，不创建额外 worktree。依赖目录已存在，未安装或修改依赖。

## 冻结 hash

| 文件 | 期望 SHA-256 | 实际 SHA-256 | 状态 |
| --- | --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

结果：`6/6 MATCH`。

## 契约一致性

- UI-011 冻结路由是 `/reports/operations`，页面是 `src/pages/reports/OperationReportPage.tsx`，E2E 是 `e2e/ui-011-reports-operations.spec.ts`。
- DO-012 仅包含 `id/reportType/period/generateStatus/metrics/generatedAt`；`id` 是权威标识，不新增 `reportId/version/updatedAt`。
- API-020 是 `GET /mock/reports`，operationId `GET_mock_reports`，查询参数为 `type/period/dimensions`。
- API-021 是冻结导出接口；C11 禁止实现导出，因此不调用、不实现 Gateway、不新增按钮。
- 既有权限目录包含 `report:view` 和 `report:generate`，无需新增权限码。
- 共享 Store 已有唯一 `report.reports` slice，C11 不新增 fixture、Store 或 Provider。
- 生成/刷新使用 DO-012 `generatedAt` 作为并发令牌，只允许更新目标 DO-012、C11 workflow 和 command audit。

## 环境

| 项目 | 实测 | 声明/说明 |
| --- | --- | --- |
| `node --version` | `v22.23.1` | `package.json` 声明 `24.18.0` |
| pnpm 命令警告中的 Node | `v24.14.0` | pnpm shim 的运行时与 shell `node` 解析不同 |
| pnpm | `11.9.0` | 仓库声明 `11.10.0` |

环境版本低于声明，但现有依赖、测试、build 和 Playwright 均可执行；未修改版本声明或锁文件。

## TypeScript 基线

命令：`node_modules\.bin\tsc.CMD --noEmit`

- exit code：`1`
- 当前进程输出：709 行，603 条主诊断。
- `TS7016=106`、`TS7026=447`、`TS2604=50`、其他诊断码 `0`。
- 所有主诊断都位于 `.tsx` 或直接属于 React/JSX 声明链。
- 非 React/JSX 主诊断：`0`；C11 非 React主诊断：`0`。
- 与 C10 after 证据的诊断次数不同，但诊断类别和边界相同；未出现新类别或 `.ts` 主诊断。

执行摘要与代表性输出见 [tsc-before.txt](./tsc-before.txt)。

## Vitest 基线

命令：`pnpm test -- --run --maxWorkers=1`

- 首次全量：`84/85` 文件、`662/663` 测试通过，exit code `1`，131.15 秒。
- 唯一失败：既有 `PlanLedgerPage.render.test.tsx` 的 SCN-02 URL round-trip 用例，30 秒 timeout；无 C11 代码。
- 隔离复跑该文件：`1/1` 文件、`11/11` 测试通过，exit code `0`，35.83 秒；用例阶段 28.88 秒。
- 根因分类：全量单 worker 下既有 UI-002 慢测的环境时序抖动，与 C10 preflight 已记录模式一致；不修改 UI-002、timeout 或测试配置。
- C10 最终 handoff 仍记录新鲜全量 `85/85`、`663/663` 通过。

## Build 基线

命令：`pnpm build`

- exit code：`0`
- Vite：`8.1.4`
- modules：`1951`
- 用时：929ms
- 既有 warning：engine 版本和大 chunk；未修改构建配置。

## Playwright 基线

命令：`pnpm test:e2e`

- exit code：`0`
- `43/43` 通过，1 worker。
- 总耗时：451.5 秒。
- 既有 console warning：Ant Design Message 在 render 中调用、List deprecated。
- 运行产生的 53 个历史 PNG 内容差异已在白名单核对后恢复；没有保留 C01/C04-C10 改动。

## 预检边界

- 不修改 baseline、fixture、依赖或 lockfile。
- 不修改公共契约、错误码、权限目录或状态机目录。
- 不修改 C10 业务实现。
- 不实现 API-021 或任何导出能力。
- 不写回 C04-C10 上游业务对象。
- 不合并、不推送。
