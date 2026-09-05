# C13 系统配置最终验证

## 结论

C13 已按批准设计与详细计划完成 UI-012 `/settings/system`。页面以 `state.systemConfig.configVersions` 为唯一 DO-015 可变领域事实，只编辑冻结的 8 个安全字段；API-022 是读取观察，API-023 只发送 `edit` 命令。页面提供 5 个配置分组、固定页面内变更摘要、严格校验、权限/离线边界、故障反馈、原子 DO-015 + DO-013 提交，以及真实的 transport/domain 双审计身份。

C13 聚焦 Vitest、生产构建、C13 Playwright 和全量 Playwright 均通过。项目原生全量 Vitest 为 843/846，3 项失败全部是 30 秒聚合负载 timeout，没有断言失败；原文件隔离复跑 17/17 通过。未修改 timeout 或测试配置。

## 范围与提交拓扑

| 项目 | 实测 |
| --- | --- |
| 分支 | `demo/c13-system-settings` |
| C13A 基点 | `a35a93916fa80d1506cdcbd6460b53504e0a88f9` |
| 页面实现提交 | `4d6b3a0818c702cf498823f4cc7587f856e4defe` |
| E2E 提交 | `636954d6d953045556f2c243125d036e3b8aac52` |
| 截图/实现尖端 | `922169bb8ebebbc6d50cbd633bff305f2834bc10` |
| C13A 是否为祖先 | 是 |
| C13A 之后 merge commit | `0` |
| 合并/推送 | 均未执行 |

最终 evidence/handoff 由本文件所在提交补齐；最终分支尖端以交付消息为准。

## TDD 证据

- 核心 RED：[`settings-core-red.txt`](./settings-core-red.txt)，覆盖投影、查询、草稿、Gateway、fault、workflow、命令原子性和外部 reset integration。
- 页面 RED：[`settings-page-red.txt`](./settings-page-red.txt)，初始 3 文件中 15 项因 scaffold 失败；后续空 Store + 默认 ID 回归先红再修复。
- 浏览器 RED：[`settings-e2e-red.txt`](./settings-e2e-red.txt)，首次 5 项中 2 项失败，分别驱动跨分组字段错误汇总和确定性 API-022 fault 控制。
- 最终 C13/contract 聚焦回归：16 个文件、212/212 项通过，18.31 秒。
- 页面/feature 最终回归：10 个文件、64/64 项通过；更大的页面/core/runtime 合并回归曾为 12 个文件、103/103 项通过。
- C13 Playwright：5/5 通过，31.6 秒。

## 最终命令结果

| 闸门 | 结果 |
| --- | --- |
| C13 + contracts focused Vitest | exit 0；16/16 文件、212/212 测试通过；18.31 秒 |
| coverage attempt | exit 1；缺少冻结依赖 `@vitest/coverage-v8`，未生成百分比 |
| 项目原生全量 Vitest | exit 1；110/113 文件、843/846 测试通过；165.13 秒；3 项均为 30 秒 timeout |
| 3 个 timeout 文件隔离复跑 | exit 0；3/3 文件、17/17 测试通过；37.84 秒 |
| production build | exit 0；Vite 8.1.4，2,584 modules，构建阶段 1.20 秒 |
| C13 Playwright | exit 0；5/5 通过；31.6 秒 |
| 全量 Playwright | exit 0；53/53 通过，1 worker，9.4 分钟 |
| `tsc --noEmit --pretty false` | exit 1；1191 行、1057 条主诊断，仅冻结 React/JSX 三类 |
| 0.3.0 SHA-256 | `6/6 MATCH` |
| `git diff --check` | 通过，无输出 |
| merge count | `0` |

首次使用 `npm run build` 被本机 PowerShell 的 `npm.ps1` 执行策略在进入项目脚本前拦截；使用同一 npm 的 Windows `npm.CMD run build` 后构建成功。这是启动入口限制，不是项目构建失败。

## 全量 Vitest 聚合 timeout

全量运行中的 3 个失败均为 `Test timed out in 30000ms`：

1. `src/app/__tests__/routeRender.test.tsx`：遍历 13 个业务路由；
2. `src/pages/__tests__/AuditLogPage.action.test.tsx`：既有 UI-013 动作流；
3. `src/pages/__tests__/PlanLedgerPage.render.test.tsx`：既有 UI-002 SCN-02 URL/remount 流。

三文件原样、同一命令隔离复跑为 3/3 文件、17/17 测试通过，没有断言失败。C13 未提高全局或局部 timeout，也未删减测试。

## TypeScript 诊断口径

最终编译器仍 exit 1，详见 [`tsc-after.txt`](./tsc-after.txt)。

| 统计 | C13A before | C13 after | 差值 |
| --- | ---: | ---: | ---: |
| 主诊断 | 783 | 1057 | +274 |
| TS2604 | 65 | 65 | 0 |
| TS7016 | 126 | 134 | +8 |
| TS7026 | 592 | 858 | +266 |
| 其他诊断码 | 0 | 0 | 0 |

冻结安装没有 `@types/react`。新增计数全部来自 C13 新 TSX 扩大的同一声明缺失链；仅有的两个非 TSX 位置仍是 preflight 的 `routeCatalog.ts` 和 `plan-entry/readState.ts`。C13 非 React/JSX 诊断为 0，依赖与 lockfile 未修改。

## 覆盖率说明

官方 coverage 命令在收集前失败，信息为 `MISSING DEPENDENCY Cannot find dependency '@vitest/coverage-v8'`。冻结范围禁止安装 provider，因此 [`coverage.json`](./coverage.json) 的 `percentages` 为 `null`、`truthfulCoverageAvailable=false`；没有伪造 line/function/branch/statement 数字。

## Store、命令与审计事实

- 唯一 DO-015 owner 是 `state.systemConfig.configVersions`；Gateway、MockRuntime、workflow、draft 和页面都不拥有第二份领域配置。
- API-022 严格校验 API identity、scenario/time 和唯一 `CFG-001`，只写 feature-local observation，不覆盖 Store。
- 页面 API-023 只发送 `command: edit`、`expectedVersion`、非空 reason 和 8 字段白名单的非空 `changes`。
- 保存顺序为 authorize/validate → Gateway-first → Store re-read/version/lifecycle check → 一次原子 config + command audit commit。
- 成功把 DO-015 保持为 `DRAFT`、version 增 1，并生成独立的 transport receipt 与 `AUD-C13-*` 领域 DO-013。
- validation、permission、network、malformed、business、version 和 transition 失败保留 draft，不部分修改 DO-015；幂等 replay 不产生第二次副作用。
- 页面 reset 始终禁用并显示缺少 `demo:reset`；页面动作不会新增 API-025 请求。共享 runtime 的既有授权外部 reset 成功后会清理 C13 workflow/idempotency cache。
- 单元/集成测试对 C04–C12 上游 slices 做深快照前后不变断言。

## 权限与页面边界

- 路由沿用冻结 `SYS_ADMIN / INTERFACE_OPS / SAFETY`；DISPATCHER 在 API-022 前得到 403。
- 离线 session 可以读取 Store/API-022 观察，但不能进入 edit。
- 页面不提供 submit/approve/publish/rollback、生产连接参数、凭据、审批、发布、回滚或合规归档能力。
- 页面明确显示“Demo 系统配置视图，非生产配置中心”，摘要在页面内而非 Drawer/Modal。

## 冻结边界审计

| 检查 | 结果 |
| --- | --- |
| 六份 0.3.0 baseline SHA-256 | `6/6 MATCH` |
| `docs/baseline/**` | 相对 C13A 改动 0 |
| `package.json` / `pnpm-lock.yaml` | 改动 0 |
| public contracts / Store / state machines / RBAC / route catalog | 改动 0 |
| fixture 与 scenario metadata | 改动 0 |
| C04–C12 feature source | 改动 0 |
| C13 之外页面 source | 改动 0 |
| 第二个 `configVersions` owner | 0；声明仍只在 `src/stores/types.ts` |
| C13 production submit/approve/publish/rollback 调用 | 0 |
| C13 PNG | 恰好 8 |
| merge commit | 0 |

最终 hash：

```text
5df343d2ea74cc01cb9515205604b14fde0e4962b8a49518527e058192a9e3b3  README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  package-baseline.json
1ea80f23293e9d1e93e464478c1051ca97887e0b6e924a4b816ee264a4651e1e  openapi.yaml
4d206093e0d36a56071dd2eb21feb47a35f690d2f3cdfecb62b782362a50204a  demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  traceability.csv
```

## 视觉证据与已知告警

8 张截图尺寸为 4 张 1440×900 + 4 张 1280×720，全部按原始清晰度检查。无横向溢出、中文断字、遮挡、Drawer/Modal 或 debug UI；1440 摘要 sticky，1280 摘要在表单后正常流。逐图结论见 [`screenshot-index.md`](./screenshot-index.md)。

全量 Playwright 输出保留既有 Ant Design `List` deprecated 和 render 中调用 `Message` 的 warning；53 项全部通过。全量 E2E 会重拍历史截图，验证后已将 75 个 `docs/evidence/**.png` 生成差异恢复为已提交版本，没有触碰源代码。
