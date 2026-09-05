# C12 审计日志预检

## 结论

C12 权威基线闸门通过，可以在 `demo/c12-audit-trail` 实施 UI-013。C11 基点、六份冻结 hash、UI-013 路由、DO-013、API-024、权限目录和共享 Store 均与已批准设计一致。

已解除并记录的编号闸门：UI-012 是系统配置；本轮 C12 实施 UI-013 审计日志。DO-013 主键为 `id`；`auditLogId` 仅作为 API envelope 审计身份。重置后幂等命中数为 0，不伪造幂等反馈或新 DO-013。

预检没有修改业务源码、baseline、依赖、公共契约、权限/状态机目录或 C11 实现。全量 Playwright 重拍的 58 个 C01/C04-C11 历史 PNG 已在确认全部属于本次运行后恢复到 HEAD。

## 仓库与隔离

| 项目 | 实测 |
| --- | --- |
| 工程 | `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo` |
| 分支 | `demo/c12-audit-trail` |
| 预检前 HEAD | `eff8df677430b83cab4d0e976f964aaf3b4695cf` |
| C11 基点 | `507a715046dc7e9858e638b07bb56e920e70da37` |
| C11 是否为祖先 | 是，`git merge-base --is-ancestor` exit 0 |
| C11 之后 merge commit | `0` |
| 工作区 | 预检前干净；预检后仅新增本目录证据 |
| 合并/推送 | 均未执行 |

`GIT_DIR` 与 `GIT_COMMON` 均为工程根目录 `.git`，当前不是 linked worktree。用户已明确指定在该工程目录创建 `demo/c12-audit-trail`，因此在当前专用分支工作，不创建额外 worktree。`node_modules` 已存在，未安装或修改依赖。

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

- UI-013 冻结路由是 `/governance/audit`，页面是 `src/pages/governance/AuditLogPage.tsx`，E2E 是 `e2e/ui-013-governance-audit.spec.ts`。
- UI-012 冻结为系统配置 `/settings/system`，不作为本轮审计页面编号。
- DO-013 严格字段为 `id/actorId/operatorTerminal/action/objectType/objectId/before/after/reason/traceId/occurredAt`。
- API-024 是 `GET /mock/audit-logs`，operationId `GET_mock_audit_logs`，没有冻结查询参数。
- 既有权限目录包含 `audit:view`；不新增或修改权限码。
- 共享 Store 已有唯一 `configAudit.audit` 和 `configAudit.commandAudit`；C12 不新增 fixture、Store 或 Provider。
- `CommandAuditEntry.metadata` 可明确区分 SUCCESS、DENIED 和 FAILED；版本冲突使用既有 `DEMO-VERSION-001`。
- 现有幂等重放直接复用首次结果，不追加新 DO-013，且没有 `idempotent: true` 实例；C12 初始/重置 KPI 必须为 0。

## 环境

| 项目 | 实测 | 声明/说明 |
| --- | --- | --- |
| `node --version` | `v22.23.1` | `package.json` 声明 `24.18.0` |
| pnpm 命令警告中的 Node | `v24.14.0` | pnpm shim 的运行时与 shell `node` 解析不同 |
| pnpm | `11.9.0` | 仓库声明 `11.10.0` |

环境版本低于声明，但既有依赖、测试、build 和 Playwright 均可执行；未修改版本声明或锁文件。

## TypeScript 基线

命令：`node_modules\.bin\tsc.CMD --noEmit`

- exit code：`1`
- 当前进程输出：810 行，693 条主诊断。
- `TS7016=117`、`TS7026=518`、`TS2604=58`、其他诊断码 `0`。
- 所有主诊断均位于 `.tsx` 或直接属于缺失 React/JSX 声明链。
- 非 React/JSX 主诊断：`0`；C12 非 React 主诊断：`0`。
- 与 C11 证据的次数不同，但诊断类别和边界相同；未出现新类别或 `.ts` 主诊断。

摘要与代表性输出见 [tsc-before.txt](./tsc-before.txt)。

## Vitest 基线

命令：`pnpm test -- --run --maxWorkers=1`

- 首次全量：`94/95` 文件、`719/720` 测试通过，exit code `1`，147.84 秒。
- 唯一失败：既有 `PlanLedgerPage.render.test.tsx` 的 SCN-02 URL round-trip 用例在全量单 worker 下 30 秒 timeout；无 C12 生产代码。
- 隔离复跑：`1/1` 测试通过、同文件其余 10 项跳过，exit code `0`，26.36 秒；用例阶段 19.22 秒。
- 分类：历史 UI-002 慢测的全量环境时序超时，没有断言失败，不修改 UI-002、timeout 或测试配置。

## Build 基线

命令：`pnpm build`

- exit code：`0`
- Vite：`8.1.4`
- modules：`2555`
- 用时：1.16 秒
- 既有 warning：engine 版本和大 chunk；未修改构建配置。

## Playwright 基线

命令：`pnpm test:e2e`

- exit code：`0`
- `45/45` 通过，1 worker。
- 总耗时：9.1 分钟。
- 既有 console warning：Ant Design List deprecated；Message 在 render 中调用。
- 本次运行重拍的 58 个 C01/C04-C11 历史 PNG 已在白名单核对后恢复；没有保留上游截图差异。

## 预检边界

- 不修改 baseline、fixture、依赖或 lockfile。
- 不修改公共契约、错误码、权限目录或状态机目录。
- 不修改 C11 业务实现。
- 不伪造 DO-013、幂等命中或生产审计能力。
- 不写回 C04-C11 上游业务对象或 `configAudit`。
- 不合并、不推送。
