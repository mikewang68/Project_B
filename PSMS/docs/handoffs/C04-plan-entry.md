# C04 调度总览与计划接收入口交接

## 交付结论

C04 已把 C02 浏览器 Mock、C03 根 Store、权限、命令执行器和追加写审计接入两张可连续演示的业务页面：`UI-001 /dispatch/overview` 与 `UI-002 /dispatch/plans`。正常确认、SCN-02 缺字段补录与异人复核、SCN-03 三次失败熔断与运维恢复三条流程均由真实浏览器 E2E 覆盖。UI-003 仍是既有骨架，本阶段只开放推荐入口。

## 公共入口

- `src/runtime/index.ts`：`DemoRuntimeProvider`、`createDemoRuntime`、`bootstrapDemoRuntime`、`useDemoRuntime`、`useDemoSelector`、`usePlanEntryWorkflow`、`enableMocking`，以及 `DemoRuntime` 类型。
- `src/features/plan-entry/index.ts`：命令服务、Gateway、query codec、共享 GET read-state hook、7 个 selector、页面 view-model 与 workflow store 的公共导出。
- `src/features/plan-entry/commands.ts`：`createPlanEntryCommandService`、`PlanEntryCommandService`、`PlanEntryCommandServiceDependencies`。
- `src/features/plan-entry/gateway.ts`：`createPlanEntryGateway`、`PlanEntryGateway` 与 API-003/004/025 请求类型。
- `src/features/plan-entry/query.ts`：`parsePlanEntryQuery`、`serializePlanEntryQuery`。
- `src/features/plan-entry/workflow.ts`：`createPlanEntryWorkflowStore`。

应用启动先从 URL 解析 `scenarioId`，通过严格 API-025 reset 成功后才创建并注入一个 session-aware runtime。它拥有唯一 `DemoStoreApi`、`PlanEntryGateway`、`PlanEntryWorkflowStore` 和 `PlanEntryCommandService`；Provider 仅在测试未注入 runtime 时 lazy 创建 fallback，因此生产启动不存在第二个 Store。页面不读取 fixture、不直接调用 Store mutation，也不以接口返回旧对象覆盖已提交 Store。

## Gateway 与浏览器 Mock

Gateway 只连接 5 个冻结 API：

| API | Gateway 方法 | HTTP |
| --- | --- | --- |
| API-001 | `getOverview` | `GET /mock/overview` |
| API-002 | `getPlans` | `GET /mock/plans` |
| API-003 | `syncPlans` | `POST /mock/plans/sync` |
| API-004 | `confirmPlan` | `POST /mock/plans/:planId/confirm` |
| API-025 | `resetDemo` | `POST /mock/demo/reset` |

`enableMocking` 在 React 挂载前启动现有 MSW worker；未处理的 `/mock/**` 请求作为错误报告，静态资源继续 bypass。Gateway 用冻结 Zod Schema 解析成功/失败信封。共享 read-state hook 让 API-001/API-002 的 pending、严格成功、结构化错误、malformed envelope 与 fetch rejection 驱动页面状态；成功信封的数据只作为可用性证明，页面业务对象仍完全来自 Store selector。API-001 网络态会让唯一 `PageStatePanel` 重试入口与 Store 派生的 `RiskInterfacePanel` 并存，因此接口不可用、最后成功时间和 TOS 风险不会被整页网络告警覆盖。写动作仍由命令服务统一串起授权、Schema、Mock、迁移、原子提交与审计，成功后只做后台 GET 状态刷新。

## Selector 边界

C04 精确导出 7 个纯 selector：

1. `selectOverviewKpis`
2. `selectVisibleYardObjects`
3. `selectOpenRisks`
4. `selectPlanLedger`
5. `selectInterfaceHealth`
6. `selectValidationIssues`
7. `selectPlanDetails`

数据域在读取、计数和聚合前过滤；详情 selector 还会先同时校验 session scope 与 query `workArea`，越界时不会读取 Plan 关联的 Waybill/audit。SCN-02 的 `trackNo` 缺失只作为页面投影存在；严格 Plan 始终留在合法 Store 中，补录成功后 workflow 的 `resolvedFields` 关闭缺失投影。台账行统一生成冻结 `exceptionTypes`，由同一字段支持 missing-field、scenario fault 与既有 conflict 筛选。详情 selector 同时输出字段来源、脱敏原始摘要、校验问题、重试历史、命令变更记录和 UI-ready 异人复核选项。

## URL 查询契约

UI-001 与 UI-002 共享 `PlanEntryQuery`：

```text
date, workArea, scenarioId, planBatchNo, trainNo,
status[], exceptionType[], page, pageSize=20,
sort, planId?, from=overview?
```

解析器对白名单日期、作业区、场景、状态、异常类型和排序做归一化；异常选项、query codec 与行投影共享一个中央枚举。序列化器省略默认/空筛选，稳定排序多值参数。页面筛选、所选计划和跨页来源都写入 URL，刷新、direct reload、组件 remount 与 SPA 往返均可恢复。业务对象本身不进入 URL。

## 页面与组件

UI-001 提供演示上下文、6 项 KPI、重点计划、风险、接口健康与简化场区态势，并把日期、作业区、场景、状态、`planId` 和 `from=overview` 带入 UI-002。API-001 网络错误时不渲染 KPI/计划数据区，但会同时显示网络恢复面板与接口健康/风险投影；业务错误仍保持整页错误态。

UI-002 提供接口健康、5 组 URL 受控筛选、计划台账、详情抽屉、校验问题、字段补录、确认与推荐入口。详情流程固定为：

```text
计划同步 → 自动校验 → 字段补录/冲突处理 → 计划确认 → 开放推荐入口
```

页面状态覆盖 `loading`、`empty`、`business-error`、`network-error`、`forbidden`、`not-found`；version conflict 使用冻结的 `DEMO-VERSION-001`。网络态允许错误面板与接口健康/风险投影并存，但只保留一个重试入口；写动作统一服从 loading、接口熔断和 C03 `PermissionGate`，不存在错误面板重试旁路。

## 三条演示流程

### SCN-01 正常确认

DISPATCHER 从 UI-001 进入 PLAN-001，API-004 与 DO-001 迁移成功后，计划从 `PENDING_CONFIRM/v1` 原子变为 `CONFIRMED/v2`，追加一条最终审计，并显示 `/dispatch/plans/PLAN-001/recommendation` 入口。

### SCN-02 缺字段补录

PLAN-002 以 `trackNo` 缺失和 `TOS-EXT-002` 投影为 `BLOCKED`。UI 的复核人选项会排除当前 actor，从而在选择阶段阻止同人复核；未选择复核人提交时表单校验失败且保留其他输入。合法异人复核后执行 `adjust`，计划进入 `ADJUSTED/v2` 并清除缺失投影；再执行 `confirm`，进入 `CONFIRMED/v3`。只要命令携带 `reviewerId`，`adjust` 与 `confirm` 都在 Gateway 前执行相同的异人复核策略。补录和确认使用独立 commandId，各追加一条最终审计。命令级同人确认由 `src/features/plan-entry/__tests__/commands.test.ts` 单独证明：返回 `TOS-AUTH-001`、PLAN-002 状态不变、不调用 Gateway，并写入恰好一条 `DENIED` 审计。

### SCN-03 熔断恢复

SCN-03 direct reload 在 API-002 返回 `TOS-EXT-001` 后进入 network-error，保留唯一健康条与它的同步/恢复控制且不挂载 URL 中的详情 Drawer。三次唯一同步失败把 workflow 置为 `retryCount=3 / circuitOpen=true`，第四次不再请求 Gateway。DISPATCHER 不可恢复；页面可访问角色控件只提供 UI-002 冻结策略角色，切换到 `INTERFACE_OPS` 后才能执行 API-025。成功恢复原子重置到 SCN-01，恢复 Store/workflow/命令幂等状态与确定性计数，再次同步后显示健康和 `SYNCED`。

## 权限、命令与审计

- UI-001 合法读取角色：`BUSINESS`、`SHIFT_LEADER`、`DISPATCHER`。
- UI-002 合法读取角色：`DISPATCHER`、`INTERFACE_OPS`；`BUSINESS` 在业务模块加载前进入安全 403。
- 计划补录只对精确 DISPATCHER 开放；恢复入口只对精确 SCN-03 + INTERFACE_OPS 开放，并继续要求 `interface:retry`。
- 页面动作映射保持 `sync -> plan:view`、`adjust -> plan:adjust`、`confirm -> plan:confirm`、恢复 -> `interface:retry`。
- `reviewerId === actorId` 在职责分离阶段返回 `TOS-AUTH-001`，不修改领域状态并记录拒绝审计。
- commandId 重放返回冻结首次结果，不重复请求、提交或审计；版本不一致返回 `DEMO-VERSION-001`。
- API-025 reset 恢复 fixture、workflow、命令幂等缓存和序号，保留可追溯的最终 reset 审计，并持久化合法 C03 会话。

## 两项兼容补充

1. DO-001 只增加 `ADJUSTED + confirm -> CONFIRMED`，允许迁移总数从 52 变为 53；其他迁移不变。
2. SCN-02 只在 API-004 的 `supplements` 完整提供该场景声明的缺失字段且通过请求 Schema 时跳过 `TOS-EXT-002`；字段不全及其他 API/场景继续保持既有失败。

覆盖证据位于 `docs/evidence/C04/compatibility-coverage.json`，六份冻结机器基线没有修改。

## 测试与证据

- TDD RED：`runtime-red.txt`、`overview-red.txt`、`plan-ledger-red.txt`、`plan-ledger-review-red.txt`、`compatibility-red.txt`。
- 机器覆盖：`coverage.json`、`compatibility-coverage.json`。
- 浏览器场景：`e2e/ui-001-dispatch-overview.spec.ts` 精确 2 项，`e2e/ui-002-dispatch-plans.spec.ts` 精确 3 项。
- 截图：`docs/evidence/C04` 精确 12 张 PNG，覆盖两种视口、正常确认、缺字段/补录确认、熔断/恢复；逐张结果见 `screenshot-index.md`。
- 完整命令、测试计数、TypeScript 诊断、哈希与范围审计见 `verification.md`。

## 已知边界

1. UI-003 业务推荐仍未实现；C04 只导航到既有 `/dispatch/plans/:planId/recommendation` 骨架。
2. 浏览器使用本地 MSW 与确定性 fixture，不连接真实 95306 或生产后端。
3. 全局 TypeScript 检查仍保留 C01 缺少 React/ReactDOM declaration 引起的级联诊断；新增 hook 使最终总数为 `286`，且仍只包含 TS2604/TS7016/TS7026，C04 primary diagnostics 为 0；未获授权修改依赖、声明或 TypeScript 配置。
4. 最终验证环境为 Node `24.18.0` / pnpm `11.10.0`，与仓库声明一致；Node 通过既有 Codex runtime 目录按命令注入，不改项目配置。
5. 生产构建继续出现既有 `>500 kB` chunk advisory；本阶段未改打包策略。

## C05 推荐入口

C05 从 `/dispatch/plans/:planId/recommendation` 接手。推荐页应继续消费 C04 URL 中的 `planId` 与 C03 Store 真值，通过既有 `plan:recommend` 权限和统一命令管线实现候选推荐；不得复制 fixture、绕过 Gateway/Store/权限/审计，也不得把完整业务对象放入 URL。
