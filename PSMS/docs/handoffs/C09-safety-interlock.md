# C09 安全联锁交接

## 交付范围

- 页面：`UI-009 安全联锁`
- 路由：`/safety/interlocks`
- 领域对象：严格冻结 DO-010 `Interlock`
- API：API-016 `GET /mock/interlocks`、API-017 `POST /mock/interlocks/:id/command`
- 业务动作：SI-01 至 SI-05
- 分支：`demo/c09-safety-interlock`

C09 只实现安全联锁 Demo 工作台、严格 mock transport、命令状态迁移、权限/幂等/版本/审计和证据。它不实现真实设备复位、PLC/ECS 控制、现场传感器控制或生产旁路授权。

## 公共入口

`src/features/safety-interlock/index.ts` 统一导出：

- 常量：`SAFETY_INTERLOCK_FEATURE`、`C09_AUDIT_PREFIX`、`c09ProgressStates`、`INTERLOCK_SOURCE_DISCLOSURE`、`FORCE_STOP_WARNING`
- 查询：`parseInterlockQueryContext`
- selectors：`selectSafetyInterlockBoard`、`selectInterlockKpis`
- Gateway：`createSafetyInterlockGateway`、`SafetyInterlockGateway`、严格 request/result types
- workflow：`createSafetyInterlockWorkflowStore` 与 workflow types
- commands：`createSafetyInterlockCommandService`、`SafetyInterlockCommandService`、严格 payload schema/types
- UI projection/types：`SafetyInterlockBoard`、`InterlockLedgerItem`、KPI、进度、动作可用性与 query context types

共享 runtime 在 `src/runtime/DemoRuntimeContext.tsx` 暴露：

```text
runtime.safetyInterlock.gateway
runtime.safetyInterlock.workflow
runtime.safetyInterlock.commands
useSafetyInterlockWorkflow(selector)
```

`DemoRuntime`、`SafetyInterlockRuntime` 和 `createDemoRuntime()` 已包含以上入口。页面状态继续通过共享 `runtime.store` 和 `useDemoSelector()` 读取，不存在第二个 Store/Provider。

## 查询与 selectors

`parseInterlockQueryContext()` 只接受并清洗：

- 来源显示：`exceptionId`、`scenarioId`、`from`
- 筛选：`status`、`actionLevel`、`riskType`、`receiptStatus`

`exceptionId/scenarioId/from` 不做对象查找，不表示 DO-009 与 DO-010 存在生产外键。

`selectSafetyInterlockBoard(state, context)`：

- 先应用 AREA-A data scope，再筛选与投影。
- 严格输出 DO-010 的既有字段；显式剔除伪造 `exceptionId`、`workOrderId`、`deviceCommandId` 等字段。
- 派生 KPI、冻结状态流、四个业务进度、FORCE_STOP/回执失败标志、复位申请摘要、审批链、动作可用性和返回 UI-008 URL。
- 返回值深冻结，不允许 UI 修改事实。

`selectInterlockKpis(state)` 只对可见严格 DO-010 记录计数。

## Gateway 行为

`createSafetyInterlockGateway(fetcher)` 提供：

- `listInterlocks()` → API-016 `GET /mock/interlocks`
- `commandInterlock(interlockId, body)` → API-017 `POST /mock/interlocks/:id/command`

API-017 body 严格为：

```text
action
reason
approvalUserId?  
resetRequest?
```

不接受 legacy command shape、扩展动作或异常/工单/设备外键。Gateway 验证 success/error envelope、API identity、scenarioId 和 path object identity；API 返回对象只作为 transport/identity 证据，绝不覆盖 Store。内部 `x-demo-c09-fault` 仅用于确定性 network/malformed 测试，不是公共 API。

## 命令语义

所有命令都执行：当前快照与版本捕获 → 权限/data scope → 业务校验 → API-017 → 再校验版本 → 单次 DO-010 commit → 追加 DO-013 审计。失败不会产生部分 DO-010 写入，也不会修改 DO-009。

| 业务动作 | 命令方法 | 冻结状态机命令 | 主要校验 |
|---|---|---|---|
| SI-01 | `triggerInterlock()` / `receiptInterlock()` | `trigger` / `receipt` | `interlock:view`、reason、当前状态/版本 |
| SI-02 | `requestReset()` | `requestReset` | `interlock:reset`、reason、`resetRequest.requested === true` |
| SI-03 | `approveInterlock()`（RESET_REQUESTED） | `approve` | `interlock:approve`、reason、有效且 ACTIVE 的审批用户 |
| SI-04 | `restoreInterlock()` | `restore` | `interlock:reset`、reason、冻结状态允许 |
| SI-05 | `requestOverride()` / `approveInterlock()`（OVERRIDE_PENDING） | `requestOverride` / `approve` | `interlock:request-override` 或 `interlock:approve`、reason、审批用户 |

幂等重放复用同一 commandId/result，不重复 API、commit、workflow 或 audit。版本漂移返回 `DEMO-VERSION-001`；scenario identity 不一致返回 `DEMO-SCENARIO-001`。C09 审计 action 仅为 `SI-01` 至 `SI-05`。

## 页面与状态

UI-009 包含：来源上下文、6 个 KPI、联锁台账、详情/状态流、输入快照、复位/审批摘要、7 个处置按钮、命令反馈与 C09 审计。

页面状态：

- `loading`
- `empty`
- `business-error`
- `network-error`
- `forbidden`
- `not-found`

业务进度：

- `LOCKED`
- `RESETTING`
- `RESTORED`
- `OVERRIDE`

1440 视口使用台账/详情/处置三栏；1280 视口使用台账+详情两列，并把处置面板整行下置。

## 来源上下文边界

UI-008 入口保持：

```text
/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling
```

C09 展示 `Demo 来源上下文，非生产外键`，并提供返回 UI-008 链接。query 中的 `exceptionId` 不进入 DO-010、API-017、命令 payload 或审计对象事实。C09 命令测试和跨页集成测试确认 DispatchException 在 SI 命令前后完全不变。

## FORCE_STOP 边界

- `FORCE_STOP` 只派生安全警告、KPI/台账标识和 Demo 恢复声明。
- 冻结状态机允许 `APPROVED -> RESTORED` 时，C09 只登记 DO-010 Demo 恢复记录。
- 页面明确：Demo 恢复记录不代表真实设备已复位，本页不执行 PLC/ECS 控制。
- 没有 `FORCE_RESTORE`、静默旁路、真实设备命令或外部安全系统调用。

## 权限与 data scope

未修改冻结权限目录。使用既有权限：

- 页面读：`interlock:view`
- 申请复位/登记恢复：`interlock:reset`
- 审批：`interlock:approve`
- 申请旁路：`interlock:request-override`

UI-009 的既有可访问角色为 `SAFETY`、`MAINTAINER`、`DISPATCHER`。BUSINESS 在路由边界 403，页面 API 不触发；AREA-B 等越界 data scope 返回安全 not-found，不泄露对象存在性。

## API-025 reset

`runtime.commands.resetScenario(scenarioId)` 成功后：

- 共享 Store 恢复场景 fixture。
- C09 workflow 清空 selected/drawer/mode/reason/approval/reset draft/error。
- C09 command sequence、trace sequence、audit sequence、processed command/replay cache 复位。
- reset 后下一条 C09 命令重新从 `CMD-C09-001`、`TRACE-C09-001`、`AUD-C09-001` 开始。

## 验证与证据

- 预检：[C09 preflight](../evidence/C09/preflight.md)
- selector/command 红态：[interlock-core-red.txt](../evidence/C09/interlock-core-red.txt)
- 页面红态：[interlock-page-red.txt](../evidence/C09/interlock-page-red.txt)
- E2E 红态：[interlock-e2e-red.txt](../evidence/C09/interlock-e2e-red.txt)
- 覆盖清单：[coverage.json](../evidence/C09/coverage.json)
- 最终验证：[verification.md](../evidence/C09/verification.md)
- TypeScript 前后：[tsc-before.txt](../evidence/C09/tsc-before.txt)、[tsc-after.txt](../evidence/C09/tsc-after.txt)
- 截图索引：[screenshot-index.md](../evidence/C09/screenshot-index.md)
- E2E：`e2e/ui-009-safety-interlock.spec.ts`，4/4
- 截图：8/8

最终实测：C09 focused 8/8 文件、55/55 测试；全量 Vitest 605/606（唯一已知 UI-002 timeout，隔离 11/11）；build 1937 modules；C09 E2E 4/4；全量 Playwright 39/39；冻结 hash 6/6；C09 非 React diagnostics 0。

## 已知限制

- 当前 Node `24.14.0` / pnpm `11.9.0` 低于仓库声明；未修改依赖。
- 全量 tsc 仍有 559 条既有 React/JSX 类型声明诊断；C09 非 React `.ts` 为 0。
- 全量 Vitest 中 UI-002 30 秒性能波动仍存在，隔离复跑通过。
- 全量 Playwright 有既有 Ant Design List/Message 警告，39/39 通过。
- UI-009 是 Demo，不连接真实 PLC/ECS/设备，不证明生产恢复或旁路授权。

## C10 建议入口

C10 应从冻结 UI-010 `/operations/offline-sync`、DO-011 与 API-018/API-019 开始；复用共享 runtime、命令执行、权限/版本/幂等/审计和 API-025 reset 模式。不要把 C09 的 `exceptionId` 来源上下文延伸为 C10 外键，不要修改 C09/DO-010 事实，也不要借离线同步引入真实 PLC/ECS 控制。

本分支没有合并或推送。
