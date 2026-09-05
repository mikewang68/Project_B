# C08 异常处置交接

## 交付范围

C08 已将 `/monitor/exceptions` 的 `UI-008 异常处置` 从骨架页实现为可操作 Demo。实现严格使用冻结 DO-009 字段与状态机，覆盖来源上下文、KPI、异常台账、详情/证据、六个动作、C08 审计、六类页面状态、四个业务进度和 UI-009 联锁入口。

## 公共 runtime 入口

`src/runtime/DemoRuntimeContext.tsx` 的 `DemoRuntime` 公开：

- `exceptionHandling.gateway`
- `exceptionHandling.workflow`
- `exceptionHandling.commands`
- `useExceptionHandlingWorkflow(selector)`
- `ExceptionHandlingRuntime` 类型

`createDemoRuntime(fetcher, options)` 仍只创建一个共享 Store 和一个 `DemoRuntimeProvider`。API-025 成功重置会清空 C08 workflow、命令回放缓存和 C08 ID 序列。

`src/features/exception-handling/index.ts` 导出 constants、selectors、query parser、Gateway、workflow、commands 和相关类型。

## 查询与 selectors

- `parseExceptionQueryContext(search)`：解析 `workOrderId`、`planId`、`scenarioId`、`from` 以及 `status/level/type/owner` 筛选。
- `selectExceptionHandlingBoard(state, context)`：返回严格 DO-009 台账、来源标签、KPI、到期状态、证据数、状态流、动作可用性和 UI-009 链接。
- `selectExceptionKpis(state)`：按当前数据域和 `session.demoTime` 计算 KPI。

所有 projection 深冻结。AREA-A 可见性在筛选和计数之前应用。

## API-014 / API-015 Gateway

- `listExceptions()` → `GET /mock/exceptions`，要求 `API-014 / GET_mock_exceptions` 严格成功体。
- `commandException(exceptionId, input)` → `POST /mock/exceptions/:id/command`，要求 `API-015 / POST_mock_exceptions_id_command` 且返回恰好一个同 ID DO-009。
- 命令体严格为 `{ action, reason, owner?, evidence? }`，拒绝空 ID、未知 action、空 reason、错误 evidence 和多余字段。
- Gateway 响应只作为 transport/command 事实验证，绝不覆盖 Store。
- UI-008 会合并 React 严格模式下同一轮 API-014 请求；显式“重新加载”创建新请求。

## EX-01..EX-05 命令语义

| 动作 | 服务方法 | 冻结命令 | 状态迁移 | 权限 |
|---|---|---|---|---|
| EX-01 | `ackException` | `ack` | OPEN/REOPENED → ACKNOWLEDGED | `exception:ack` |
| EX-02 | `assignException` | `assign` | ACKNOWLEDGED → HANDLING | `exception:handle` |
| EX-03 | `submitExceptionHandling` | `handle` | HANDLING → PENDING_REVIEW | `exception:handle` |
| EX-04 | `reviewException` | `review` | PENDING_REVIEW → HANDLING | `exception:review` |
| EX-04 | `closeException` | `close` | PENDING_REVIEW → CLOSED | `exception:close` |
| EX-05 | `reopenException` | `reopen` | CLOSED → REOPENED | `exception:reopen` |

执行顺序保持 `authorize → validate → API-015 → version/scenario recheck → 单次 DO-009 commit → 单条 C08 audit`。版本漂移返回 `DEMO-VERSION-001` 且不产生 C08 domain write。相同 commandId 重放返回第一次冻结结果，不重复 API、commit、workflow 通知或 audit。

EX-02 需要 owner，EX-03 需要非空 evidence，所有动作需要 reason。C08 不新增 ESCALATE 命令；`ESCALATED` 只按冻结状态展示。

## 权限与数据域

- UI-008 路由权限：`monitor:view`。
- `DISPATCHER`、`SHIFT_LEADER` 可按动作权限执行 EX 命令。
- `BUSINESS` 可读但动作只读。
- `SAFETY` 不具备 UI-008 路由权限，RouteBoundary 在 API 调用前返回 403。
- AREA-B-only 会在 API-014 前安全返回 not-found；`AREA-A`、`GLOBAL`、`*` 可见。

权限目录没有为 C08 修改。

## C07 来源上下文边界

UI-005 DB-05 生成：

`/monitor/exceptions?workOrderId=...&planId=...&scenarioId=...&from=dispatch-board`

这些值只用于 UI-008 展示和返回链接。冻结 DO-009 没有生产 `workOrderId`、`planId`、`resourceId` 或联锁外键；C08 不解析、写入或宣称这些关系。即使来源 ID 不存在，仍只作为安全显示文本。

C08 命令不会修改 WorkOrder、WorkNode、Resource、Plan、RecommendationDraft 或 Interlock 事实。

## UI-009 边界

INTERLOCK 类型或 SCN-05 `INTERLOCK_FORCE_STOP` 会以 `TOS-IL-001` 阻断 C08 写动作。UI-008 仅提供：

`/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling`

C08 不请求、批准、清除、覆盖、复位或恢复联锁；UI-009 仍未在本模块实现。

## 页面状态与恢复

- 技术状态：loading、empty、business-error、network-error、forbidden、not-found。
- 业务进度：OPEN_QUEUE、HANDLING、REVIEW、CLOSED。
- API-014 网络/格式失败可“重新加载”。
- API-015 网络/业务失败保留 reason、owner、evidence 草稿，并可“重试原动作”。
- 双击由页面 in-flight guard 和命令幂等共同防重。

## 证据

- [预检](../evidence/C08/preflight.md)
- [覆盖清单](../evidence/C08/coverage.json)
- [最终验证](../evidence/C08/verification.md)
- [TypeScript before](../evidence/C08/tsc-before.txt)
- [TypeScript after](../evidence/C08/tsc-after.txt)
- [E2E 红测](../evidence/C08/exception-e2e-red.txt)
- [截图索引](../evidence/C08/screenshot-index.md)
- C08 focused：8 文件、44 测试通过。
- C08 E2E：4/4；全量 Playwright：35/35。
- 截图：8/8，覆盖四状态和两个视口。
- 冻结哈希：6/6 MATCH。

## 已知限制

- 全局 `tsc --noEmit` 仍受仓库既有 React/JSX 类型声明缺口影响；after 为 512 条且全部属于 TS2604/TS7016/TS7026，C08 非 React 诊断为 0。
- 全量单 worker Vitest 为 546/547；唯一失败是预检已存在的 UI-002 30 秒超时，同一用例隔离通过。
- 运行时 Node 为 `v24.14.0`，低于声明的 `24.18.0`；未修改依赖。
- UI-009 仍为骨架/后续模块，本次没有实现联锁恢复。

## C09 建议入口

C09/UI-009 应从上述 `/safety/interlocks` query 读取 `exceptionId/scenarioId/from` 作为来源展示上下文，但不要把它们扩展成 DO-009 外键。联锁查询、审批、恢复和审计应在 DO-010/SM-007 自身边界内实现；完成后可提供安全返回 UI-008 的链接。不要从 UI-009 直接改写 C08 workflow、回放缓存或 DO-009 状态。
