# C07 派工看板实施对话提示词

你现在开始独立实施 **C07：派工看板 / UI-005**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C07 设计规格与实施计划已经确认；请使用 `superpowers:executing-plans` 按计划 Task 1 到 Task 8 顺序执行。除非我明确要求，不要派生子任务或使用多代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C06 最终源码与交接提交：`d07512e66194ef7ded1f7238bfd34dc0bf024919`
- C07 设计规格：`docs/superpowers/specs/2026-07-21-c07-dispatch-board-design.md`
- C07 实施计划：`docs/superpowers/plans/2026-07-21-c07-dispatch-board.md`
- C07 工作分支：`demo/c07-dispatch-board`

开始前必须：

1. 进入工程，确认当前分支从 `demo/c06-task-decomposition` 的 `d07512e66194ef7ded1f7238bfd34dc0bf024919` 派生，工作区干净。
2. 新建或切换到 `demo/c07-dispatch-board`，不要覆盖 C06 分支。
3. 通读以下文件：
   - `docs/handoffs/C06-task-decomposition.md`
   - `docs/superpowers/specs/2026-07-21-c07-dispatch-board-design.md`
   - `docs/superpowers/plans/2026-07-21-c07-dispatch-board.md`
4. 严格执行计划中的 8 个 Task 和全部检查步骤；先产生有效红灯，再写最小实现，再运行相关回归并按计划提交。
5. 不要重新规划，不要再次请求确认已经冻结的 UI-005 范围、API-008/API-009 语义、资源边界、截图数量或状态迁移边界。

## 二、冻结基线闸门

写 C07 源码前计算并核对：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一 hash 不匹配时，给出期望值、实际值、文件状态和最近相关提交后停止；不得自行修订、重建或接受新基线。

源码修改前执行并记录：

```powershell
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

C06 交接事实：focused Vitest 17/17 文件、228/228 通过；全量 Vitest 53/53 文件、447/447 在单 worker 下通过；默认并发命令仍可能触发现有 `PlanLedgerPage.render` 30 秒超时；build 通过 1,892 modules；C06 E2E 4/4；全量 Playwright 27/27；全局 TypeScript 因冻结 React/JSX 声明失败共 413 条，C06 非 React primary diagnostics 为 0。C07 开始前把实际结果写入 `docs/evidence/C07/preflight.md` 和 `tsc-before.txt`。

## 三、唯一业务范围

C07 只实现 UI-005：

```text
/dispatch/work-orders
```

完整演示主线：

```text
C04 已确认 Plan
-> C05 已确认接车推荐
-> C06 已确认任务拆解
-> Plan DECOMPOSED + C06 WorkOrder READY
-> UI-005 绑定 AREA-A 可用资源实例
-> API-009 下发工单
-> 接单 / 开始 / 暂停 / 完成 Demo 本地执行反馈
-> 异常或联锁只给 UI-008/UI-009 入口
```

不实现 UI-006 公路预约、UI-007 监控、UI-008 异常处置、UI-009 联锁恢复、UI-013 审计详情或任何后续模块正式业务。

## 四、硬性数据边界

- 延续唯一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store；不创建第二个 Store、Provider 或 fixture。
- 新建 `src/features/dispatch-board/**`。
- 只处理 C06-owned WorkOrder / WorkNode：

```text
WorkOrder.id/workOrderNo 以 C06-WO- 开头
WorkOrder.ruleVersion = C06-DEMO-RULE-1.0
WorkNode.id 以 C06-NODE- 开头
WorkNode.nodeNo 以 C06-N- 开头
WorkNode.workOrderNo 匹配 C06 WorkOrder
```

- 不改写 `WO-001` 到 `WO-012`、`NODE-001` 到 `NODE-012`。
- 不修改 Plan、RecommendationDraft、Resource 事实。
- 资源必须来自 AREA-A/GLOBAL/`*` 可见范围；只允许 `AVAILABLE` 且 resourceType 匹配的资源绑定。
- 不伪造资源、不借 AREA-B/C、不把 MAINTENANCE/LOCKED/OFFLINE 资源宣称为可分配。
- API-025 reset 必须恢复 fixture 并清空 C07 workflow、幂等缓存和临时选择。

## 五、API-008/API-009 语义

API-008：

```text
POST /mock/work-orders/:id/assign
operationId = POST_mock_work_orders_id_assign
x-api-id = API-008
```

API-009：

```text
POST /mock/work-orders/:id/dispatch
operationId = POST_mock_work_orders_id_dispatch
x-api-id = API-009
```

Gateway 只验证 transport、envelope、apiId、operationId、scenarioId、now 和对象身份；返回对象不得覆盖 Store。

最重要的边界：

- C06 已经使用冻结 DO-005 `DRAFT + assign -> READY`。
- 冻结状态机没有 `READY + assign`。
- 因此 C07 的 API-008“绑定资源实例”不得再次执行 DO-005 `assign` 状态迁移。
- API-008 成功后，只写 `resourceId/teamId/version/updatedAt`，WorkOrder 状态保持 `READY`，WorkNode 保持 `WAITING`。
- API-009 成功后，执行 DO-005 `READY + dispatch -> DISPATCHED`，WorkNode `WAITING -> READY`。

## 六、五个动作

1. `DB-01 / 绑定资源`：权限 `dispatch:assign`，调用 API-008，写入 resourceId/teamId，状态保持 READY。
2. `DB-02 / 下发工单`：权限 `dispatch:send`，调用 API-009，WorkOrder READY->DISPATCHED，WorkNode WAITING->READY。
3. `DB-03 / 接单`：权限 `dispatch:send`，无新 API，WorkOrder DISPATCHED->ACKNOWLEDGED，ackStatus->ACKNOWLEDGED。
4. `DB-04 / 执行反馈`：权限 `dispatch:send` 或 `dispatch:pause`，无新 API，执行 ACKNOWLEDGED->IN_PROGRESS->PAUSED/COMPLETED，并同步 WorkNode。
5. `DB-05 / 异常入口`：权限 `dispatch:view`，无 Store 写入，跳转 `/monitor/exceptions?workOrderId=...&planId=...&scenarioId=...&from=dispatch-board`。

DB-03/DB-04 是 Demo 本地反馈骨架，必须在页面和 handoff 中明确说明，不得声称为真实现场系统回执。

所有写动作使用 C03 `createCommandExecutor` 和 SM-007 技术命令管线。成功、拒绝、失败都追加一条 C07 audit，不覆盖 C04/C05/C06。

## 七、权限、版本、幂等、联锁

固定顺序：

```text
权限 -> 数据域 -> 请求/业务校验 -> API 或本地命令边界 -> 二次版本校验
-> 状态机 transition -> 单次原子 Store commit -> 追加一条审计
```

- 页面：`dispatch:view`
- 绑定：`dispatch:assign`
- 下发、接单、开始、完成：`dispatch:send`
- 暂停：`dispatch:pause`
- 重绑：`dispatch:reassign`，仅 READY 且未下发可用
- 版本漂移返回 `DEMO-VERSION-001`，不产生部分写入
- 相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或 audit
- SCN-05 `INTERLOCK_FORCE_STOP` 在 API 前以 `TOS-IL-001` 阻断 DB-01 到 DB-04，提供 UI-009 入口，不允许 C07 覆盖

## 八、UI-005 必须展示

- 精确页面标识：`UI-005`
- 页面标题：`派工看板`
- 当前路由：`/dispatch/work-orders`
- Plan/C06 上下文、规则版本、来源 UI-004
- READY、已绑定、已下发、执行中、已完成、异常入口 KPI
- C06 工单队列：sequence、阶段、状态、资源类型、绑定资源、版本、计划时间
- 工单详情：依赖链、WorkNode 状态、上游/下游、命令反馈、trace/audit
- 资源池：AREA、resourceType、resourceId、真实状态、可分配/不可分配原因
- 操作：绑定资源、下发、接单、开始、暂停、完成、异常入口
- 六类页面状态：loading、empty、business-error、network-error、forbidden、not-found
- 四类业务进度：READY_QUEUE、ASSIGNED、DISPATCHED、EXECUTING

1440x900 使用三栏看板；1280x720 使用队列+详情主列，资源池可放入抽屉或下方区域。两种视口都不得横向滚动、按钮裁切、文字重叠或中文缺字。

## 九、四道 TDD 闸门

Gate A：派工投影、ownership、资源匹配、API-008/API-009 Gateway。红灯输出：`docs/evidence/C07/dispatch-core-red.txt`。

Gate B：DB-01 到 DB-04 命令、状态迁移、版本、幂等、审计、联锁、reset。

Gate C：UI-005 页面、权限、六类状态、四类业务进度、跨页入口和响应式。红灯输出：`docs/evidence/C07/dispatch-page-red.txt`。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。红灯输出：`docs/evidence/C07/dispatch-e2e-red.txt`。

## 十、E2E 与截图

E2E 文件使用 Windows 合法名称：

```text
e2e/ui-005-dispatch-board.spec.ts
```

恰好 4 条 C07 E2E：

1. SCN-01：UI-002 -> UI-003 -> UI-004 READY -> UI-005，绑定资源并下发。
2. SCN-01：接单、开始、暂停、继续、完成，验证状态、版本和审计。
3. 权限、数据域、资源不可用、版本漂移、幂等重放，验证无部分写入。
4. API-008/API-009 network/malformed 和 SCN-05 联锁阻断，验证恢复、草稿保留和 UI-009 入口。

保存且只保存 8 张截图：

```text
C07-UI005-SCN01-READY_QUEUE-1440x900.png
C07-UI005-SCN01-READY_QUEUE-1280x720.png
C07-UI005-SCN01-ASSIGNED-1440x900.png
C07-UI005-SCN01-ASSIGNED-1280x720.png
C07-UI005-SCN01-DISPATCHED-1440x900.png
C07-UI005-SCN01-DISPATCHED-1280x720.png
C07-UI005-SCN01-EXECUTING-1440x900.png
C07-UI005-SCN01-EXECUTING-1280x720.png
```

逐张按原始分辨率检查横向滚动、裁切、重叠、中文缺字、资源池可读性、状态标签和异常入口。结果写入 `docs/evidence/C07/screenshot-index.md`。

## 十一、硬性禁止事项

- 不修改六份 baseline、依赖文件、lockfile、公共 API Schema、错误码、权限目录或状态机 catalog。
- 不新增 Store、Provider、fixture、endpoint、公共角色、权限码或业务枚举。
- 不改写历史工单/节点，不让 API 返回对象覆盖 Store。
- 不对 READY 工单执行 DO-005 `assign` 状态迁移。
- 不伪造资源、不跨数据域、不生成 160 个业务对象。
- 不实现 UI-006 到 UI-013 的正式业务。
- 不使用 `Date.now`、未注入当前时间、`Math.random`、随机 UUID 或 localStorage 作为领域事实。
- 不通过跳过测试、删除断言、放宽 Schema、修改超时或修改配置来制造通过。

## 十二、最终验证与交接

至少执行：

```powershell
pnpm vitest run src/features/dispatch-board src/pages/__tests__/DispatchBoardPage.render.test.tsx src/pages/__tests__/DispatchBoardPage.action.test.tsx src/pages/__tests__/DispatchBoardPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-005-dispatch-board.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit
git diff --check
```

并执行 Task 8 的 focused tests、六 hash 复核和禁止范围扫描。最终要求：

- C07 focused tests 全绿，报告实际文件数/测试数。
- 全量 Vitest 单 worker 全绿；若默认并发仍触发既有 `PlanLedgerPage.render` 超时，按事实说明，不修改 timeout/config。
- build 通过并报告 modules。
- C07 E2E 4/4，全量 Playwright 全绿并报告实际数量。
- C07 非 React primary diagnostics 为 0；全局 tsc 按实测记录，不伪装通过。
- 六 hash 6/6 不变，`git diff --check` 通过，禁止范围无修改。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C07/coverage.json` 完整列举 UI-005、selectors、API-008/API-009、DB-01..DB-05、权限、数据域、版本、幂等、SCN-05、reset、页面状态、业务进度、E2E 和截图。
- `docs/evidence/C07/verification.md` 记录环境、命令、时间、退出码、测试/build/E2E/tsc、hash、截图、范围和 diff check。
- 创建 `docs/handoffs/C07-dispatch-board.md`，列明 runtime 公共入口、selectors、Gateway、命令语义、资源绑定边界、执行反馈边界、权限、reset、证据、限制和 C08 建议入口。

最终报告必须包含：当前分支、完整 HEAD、完整提交链、变更文件分类、focused/full Vitest、build、C07 4/4 与全量 Playwright、tsc 实际状态、六 hash、8 张截图、coverage、verification、handoff 和已知限制。保留在 `demo/c07-dispatch-board`，不合并、不推送。

现在开始执行：先完成工作区、祖先提交、六 hash 和 C06 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重写方案，不要请求确认已冻结规格。
