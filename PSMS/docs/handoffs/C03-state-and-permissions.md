# C03 状态与权限内核交接

## 交付结论

C03 已交付一个确定性、原子、默认拒绝的状态与权限内核：12 个 Zustand 根切片、7 类状态机、统一命令管线、13 角色/45 权限、数据域/职责分离/在线/版本规则、追加写命令审计，以及最小动作与路由门禁。业务页面仍保持 C01 骨架状态。

## 公共入口

- `src/stores/index.ts`：`createDemoStore`、`createInitialState`、`validateDemoRootState`、12 个 slice factory、`selectSession`、`selectPlans`、`selectWorkOrders`、`selectActiveScenario`、`selectPlanWorkOrders`，以及根状态/Store 类型。
- `src/commands/index.ts`：`stateMachineCatalog`、`transitionState`、`createCommandExecutor`、`createCommandPermissionEvaluator` 和命令/结果/依赖类型。
- `src/auth/index.ts`：`authorize`、`authorizePage`、数据域过滤、三类脱敏、权限 catalog、会话装载和策略类型。
- `src/governance/audit.ts`：`validateCommandAuditEntry`、`createAuditLedger`、`createCommandAuditAppender`、`CommandAuditEntry`、`AuditLedger`。
- 默认导出：`PermissionGate`、`ForbiddenState`、`RoutePermissionBoundary`；相应 Props 类型为具名导出。

## Store 与原子边界

唯一根 Store 恰好包含 12 个切片：

| 切片 | 数据归属 |
| --- | --- |
| `session` | 演示参与者、角色、数据域、班次、在线状态、场景与固定时间 |
| `scenario` | SCN-01～SCN-07、当前故障与 reset point |
| `plan` | DO-001 Plan、DO-002 Waybill |
| `recommendation` | 经验证 API 结果形成的推荐草稿 |
| `workOrder` | DO-005 WorkOrder、DO-006 WorkNode |
| `resource` | DO-003 Track、DO-004 Material、DO-007 Resource |
| `vehicle` | DO-008 Appointment |
| `exception` | DO-009 DispatchException |
| `interlock` | DO-010 Interlock |
| `offline` | DO-011 OfflinePacket |
| `report` | DO-012 Report |
| `configAudit` | DO-013 AuditLog、DO-014 UserRole 与 C03 commandAudit wrapper |

`replaceDomainState` 先在 Store 外构造、深拷贝并验证完整候选，再单次替换根状态；异常或验证失败不产生部分提交。`resetFromSnapshot` 对七个场景使用同一原子边界，Store 不持有 fixture/Mock runtime 的可变引用。

## 状态机与命令管线

`stateMachineCatalog` 固定为 `DO-001`、`DO-005`、`DO-008`、`DO-009`、`DO-010`、`DO-011`、`SM-007`，共 52 条显式允许迁移。`transitionState` 是纯函数，对未知状态、未知命令和禁止迁移返回结构化拒绝。

命令执行顺序固定为：

```text
permission -> schema -> Mock -> transition -> atomic commit -> audit
```

所有最终成功与失败恰好追加一次审计；权限拒绝发生在 schema/Mock/Store 之前。已解析的 `commandId` 重放返回首次结果，不再次调用 Mock、提交或审计。版本不一致返回 `DEMO-VERSION-001`。

## 角色、权限和判定顺序

13 个冻结角色：`DISPATCHER`、`SHIFT_LEADER`、`OPERATOR`、`MAINTAINER`、`WAREHOUSE`、`GATE_GUARD`、`DRIVER`、`SAFETY`、`BUSINESS`、`REGULATOR`、`SYS_ADMIN`、`INTERFACE_OPS`、`AUDITOR`。

45 个权限码：

```text
audit:export, audit:verify, audit:view, demo:reset,
dispatch:assign, dispatch:pause, dispatch:reassign, dispatch:send, dispatch:view,
exception:ack, exception:close, exception:handle, exception:reopen, exception:review,
export:summary, interface:retry,
interlock:approve, interlock:request-override, interlock:reset, interlock:view,
monitor:view, offline:resolve, offline:retry, offline:view, overview:view,
plan:adjust, plan:confirm, plan:recommend, plan:view,
report:export, report:generate, report:view,
settings:approve, settings:edit, settings:publish, settings:rollback, settings:view,
task:decompose, task:edit, task:view,
yard:call, yard:gate, yard:release, yard:review, yard:submit
```

判定严格按六阶段短路：`PAGE -> ACTION -> DATA_SCOPE -> SEPARATION_OF_DUTIES -> ONLINE -> VERSION`。未知角色、页面、权限或数据域默认拒绝；未授权对象在计数/聚合前过滤。高风险同人申请/审批、管理员自批权限提升、审计员变更数据被职责分离规则拒绝；联锁审批/重置/越权、车辆放行和配置写操作要求在线。

脱敏格式固定为姓名 `张三 -> 张**`、手机号 `13800121234 -> 138****1234`、车牌 `川A·12345 -> 川A·***45`，返回新视图且不改源对象。

## 审计与会话

`CommandAuditEntry` 用 wrapper 保持严格 DO-013 不变：`record` 仍必须通过 `do013Schema`，命令角色、数据域、结果、错误码、客户端/服务端时间位于 `metadata`。ledger 深拷贝、冻结、拒绝非法/重复记录；成功、拒绝与最终失败均可追溯。

默认会话是 `USER-001 / DISPATCHER / AREA-A / online`，`demoTime` 来自 SCN-01 固定时钟。浏览器和 E2E 唯一覆盖键为：

```text
production-dispatch-demo:c03-session
```

无值或非法值回退默认会话。E2E 只通过此键种入已授权冻结角色。

## 最小权限 UI

- `PermissionGate` 支持 `hide`、`disable`、`explain`；禁用态同时设置 `disabled`、`aria-disabled` 与可访问原因。
- 13 条 route catalog 均有 `requiredPermission` 和 `allowedRoles`。
- 导航随当前会话过滤；直接访问无权路由返回安全 403，展示所需权限、当前数据域和首个可访问路由。
- 路由边界在授权前不加载或渲染被拒业务模块，也不向 403 传入 fixture 对象。

## 验证与证据

- Gate A：3/3 文件，91/91。
- Gate B：6/6 文件，41/41。
- 全量 Vitest：19/19 文件，165/165。
- 构建成功；Playwright 保持 14/14。
- C03 TypeScript primary diagnostics：0；全局 38 条与 C01 冻结基线内容一致。
- 覆盖矩阵：`docs/evidence/C03/state-machine-coverage.json`、`store-coverage.json`、`permission-coverage.json`。
- 完整验证：`docs/evidence/C03/verification.md`。

## 已知边界

1. 全局 `tsc` 仍因 C01 缺少 React/ReactDOM 类型声明产生 38 条既有诊断；C03 没有新增诊断，且按任务约束未改依赖或 TypeScript 配置。
2. 当前运行时 Node/pnpm 小版本低于仓库声明，且非 ASCII 路径下 `pnpm exec` 不解析本地 TypeScript/Vitest shim；验证使用同一锁定依赖的本地 CMD 入口。
3. 构建保留既有的 >500 kB 主包警告，C03 未调整页面打包策略。
4. 本阶段只有权限验证所需的导航、动作门禁和安全 403，没有实现 UI-001～UI-013 的业务交互或浏览器 Mock 启动接线。

## 下一业务页面入口

下一阶段建议从 `UI-001 /dispatch/overview` 开始：从 `src/stores/index.ts` 消费 selector，从 `src/commands/index.ts` 发起写命令，并以 `src/auth/index.ts` 和 `PermissionGate` 做页面/动作授权。页面不得复制 fixture、直接改 Store、绕过统一命令管线或自行生成系统时间；后续 UI-002～UI-013 沿用同一接入方式。
