# C10 离线同步预检

## 结论

C10 契约一致性闸门通过，可以从 `demo/c10-offline-sync` 开始实现 UI-010。C09 基点、冻结 hash、DO-011/API-018/API-019/fixture/权限/状态机均与已确认规格一致。预检没有修改业务源码、baseline、依赖、公共契约、权限/状态机目录或 C09 实现。

## 仓库与提交

| 项目 | 实测 |
| --- | --- |
| 工程 | `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo` |
| 分支 | `demo/c10-offline-sync` |
| 预检前 HEAD | `e6b889d66f14e590a33fb3ee99d453a6374578ae` |
| C09 基点 | `de81e5a22cf1972133cbdee03e3b281723aca2da` |
| C09 是否为祖先 | 是，`git merge-base --is-ancestor` exit 0 |
| C09 之后 merge commit | `0` |
| 工作区 | 预检前干净；预检后仅新增本目录证据 |
| 合并/推送 | 均未执行 |

当前 checkout 是用户指定工程目录中的专用 C10 分支，不是 linked worktree；依赖目录已存在，因此未重新安装或修改依赖。

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

- DO-011 仅包含 `id/offlinePackageNo/terminalId/workOrderNo/packageVersion/serverVersion/validation/mergeStatus/version/createdAt/updatedAt`。
- API-018 为 `GET /mock/offline-packets`，operationId `GET_mock_offline_packets`。
- API-019 为 `POST /mock/offline-packets/:id/command`，operationId `POST_mock_offline_packets_id_command`。
- 冻结 OFF-001..OFF-004、SCN-06 `OFFLINE_VERSION_CONFLICT` 与 `TOS-OFF-001` 均匹配规格。
- DO-011 transition 完整覆盖 upload/validate/merge/reject/retry；现有权限包含 `offline:view`、`offline:retry`、`offline:resolve`。
- 冻结公共 API019Request 保持旧形态；C09 已证明 feature-local handler Schema 是现有扩展点。C10 只在 feature 内实现已确认的 `{ action, reason, validation? }`，不修改公共契约。
- 既有 mock 可能让 API-018 或 API-019 命中 SCN-06 fault；C10 页面必须保留 Store 投影并显示恢复引导。

## 环境

| 项目 | 实测 | 声明/说明 |
| --- | --- | --- |
| `node --version` | `v22.23.1`，来自 `D:\hermes\node\node.exe` | `package.json` 声明 `24.18.0` |
| pnpm 命令警告中的 Node | `v24.14.0` | Codex pnpm fallback shim 的运行时报告与 shell `node` 解析不同 |
| pnpm | `11.9.0` | 仓库声明 `11.10.0` |

环境版本低于声明，但现有依赖、测试、build 和 Playwright 均可执行；未修改版本声明或锁文件。

## TypeScript 基线

命令：`node_modules\.bin\tsc.CMD --noEmit`

- exit code：`1`
- 主诊断：`174`
- `TS7026`：119
- `TS7016`：38
- `TS2604`：17
- 所有诊断都位于 `.tsx` React/JSX 声明链；没有非 React `.ts` 主诊断。
- UI-010 当前 scaffold 有 1 条既有 `.tsx` JSX 诊断。
- C10 非 React 主诊断基线：`0`。

完整输出：[tsc-before.txt](./tsc-before.txt)。

## Vitest 基线

第一次按交接命令 `pnpm test -- --run --maxWorkers=1` 执行时，外层命令在 180.5 秒被终止；当时观察到 `routeRender.test.tsx` 和既有 UI-002 `PlanLedgerPage.render.test.tsx` 慢测超时。

隔离复跑：

- `routeRender.test.tsx`：1/1 文件，3/3 测试通过，16.47 秒。
- `PlanLedgerPage.render.test.tsx`：1/1 文件，11/11 测试通过，36.27 秒。

随后使用等价直接单 worker 命令 `node_modules\.bin\vitest.CMD run --maxWorkers=1` 完整运行：

- 文件：`76/76` 通过。
- 测试：`606/606` 通过。
- exit code：`0`。
- 总耗时：`505.81s`。

本次没有复现 C09 交接中的最终 UI-002 失败；初次外层终止与两个隔离文件均属环境性能波动。

## Build 基线

命令：`pnpm build`

- exit code：`0`
- modules：`1937`
- Vite：`8.1.4`
- 用时：`11.15s`
- 既有 warning：engine 版本、CSS post 插件耗时、大 chunk；未修改 build 配置。

## Playwright 基线

命令：`pnpm test:e2e`

- exit code：`0`
- `39/39` 通过，1 worker。
- 总耗时：`7.2m`。
- 既有 console warning：Ant Design Message 在 render 调用、List deprecated。

全量 Playwright 会重拍历史 C01/C04-C09 截图。预检开始时工作区干净，因此已把本次运行产生的历史 PNG 内容差异恢复到 HEAD；没有保留或提交任何 C01-C09 证据修改。

## 预检边界

- 不修改 baseline、fixture、依赖或 lockfile。
- 不修改公共契约、错误码、权限目录或状态机目录。
- 不修改 C09 业务实现。
- 不创建真实终端、数据库、文件协议、队列或自动冲突合并。
- 不合并、不推送。
