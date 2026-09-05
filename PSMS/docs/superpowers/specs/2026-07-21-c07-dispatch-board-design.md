# C07 派工看板设计规格

## 1. 背景与基点

C06 已在 `demo/c06-task-decomposition` 完成 UI-004 任务拆解，最终提交为 `d07512e66194ef7ded1f7238bfd34dc0bf024919`。C07 从现有 UI-005 路由 `/dispatch/work-orders` 接手，把当前 `PageScaffold` 替换为可演示的派工看板。

C07 的输入事实是 C06 已确认的任务定义：

- Plan 已从 `CONFIRMED` 进入 `DECOMPOSED`。
- C06 WorkOrder 已从 `DRAFT` 进入 `READY`。
- C06 WorkNode 仍为 `WAITING`。
- C06 不分配资源实例，`resourceId` 和 `teamId` 仍为空。

C07 的目标是完成“READY 工单 -> 资源实例绑定 -> 下发 -> 执行反馈骨架”的演示闭环，不实现后续 UI-006 到 UI-013 的正式业务。

## 2. 已确认方案

采用“同一 Store 中的 C07 派工投影与命令服务”。

UI-005 只读取 C06 所属 WorkOrder/WorkNode 和 AREA-A 资源，新增 C07 feature 模块处理派工看板、资源候选、API-008/API-009 Gateway、命令和页面 workflow。所有领域事实继续写入既有 C03 Store 的 `workOrder` slice 和 `configAudit.commandAudit`，不新增 Store、Provider、fixture 或依赖。

资源实例绑定使用 API-008 证明 Mock 传输边界，但不再对 READY 工单重复执行 DO-005 `assign` 状态迁移。原因是 C06 已按冻结状态机完成 `DRAFT + assign -> READY`，而冻结 DO-005 不支持 `READY + assign`。C07 的“分配”语义限定为给 READY 工单写入 `resourceId/teamId`，状态仍保持 `READY`；真正状态推进由 API-009 下发时执行 `READY + dispatch -> DISPATCHED`。

## 3. 目标

1. 实现 UI-005 派工看板页面内容，保留路由 `/dispatch/work-orders`、页面权限 `dispatch:view` 和 C01 smoke 稳定性。
2. 从 C06 READY 工单读取待派工队列，只处理 C06 ownership 记录，不改写历史 `WO-001..012`。
3. 展示 Plan、C06 任务树、资源实例候选、分配状态、下发状态、执行反馈和审计反馈。
4. 支持 DB-01 资源实例绑定、DB-02 工单下发、DB-03 接单、DB-04 开始/暂停/完成演示反馈、DB-05 异常入口五类操作。
5. 严格调用 API-008 和 API-009，不新增 endpoint，不修改 OpenAPI、错误码、权限目录或状态机目录。
6. 复用 C03 命令执行、权限、幂等、版本快照、追加审计、API-025 reset 和 SCN-05 联锁阻断。
7. 产出 focused/full tests、4 条 C07 E2E、8 张截图、coverage、verification 和 handoff。

## 4. 非目标

1. 不实现 UI-006 公路预约、UI-007 全流程监控、UI-008 异常处置、UI-009 联锁恢复或 UI-013 审计详情。
2. 不实现真实资源优化、容量排程、设备控制、人员班组排班或外部系统对接。
3. 不新增 API、公共枚举、公共错误码、状态机 transition、权限码、角色、fixture、依赖或根 Store slice。
4. 不生成 160 个箱、车、任务或资源对象；AT-TOS-004 仍只作为验收峰值基准展示。
5. 不把 UI-005 的执行反馈伪装成真实现场回执；接单、开始、暂停、完成均明确为 Demo 本地反馈。
6. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、C04/C05/C06 已交付页面主体。

## 5. 权威契约与冲突规则

优先级固定为：

1. 用户已确认的 C07 范围：以派工流程为主，页面像回事，保持演示可解释。
2. 本设计规格和后续实施计划。
3. C06 handoff 与最终源码。
4. 冻结 baseline、OpenAPI、state machine、permission catalog 和 C03 Store。

冲突处理规则：

- 冻结状态机不支持 `READY + assign`，所以 C07 资源绑定不得声明为 DO-005 状态迁移。
- API-008/API-009 的响应只证明严格 transport 和对象身份，不覆盖 Store 事实。
- 若冻结资源没有可用实例，页面展示不可分配原因，不伪造资源，不借用 AREA-B/C。
- SCN-05 `INTERLOCK_FORCE_STOP` 阻断 DB-01 到 DB-04 全部写动作，返回 `TOS-IL-001`，只提供 UI-009 入口。
- 任何 baseline hash 不匹配时停止，不自行接受新基线。

## 6. 页面范围与布局

UI-005 路由：

```text
/dispatch/work-orders
```

允许 query：

```text
planId
scenarioId
from
status
resourceType
```

页面采用现有 Ant Design 工作台风格，不引入新视觉库。

核心区域：

- 顶部上下文：计划编号、批次、车次、货类、C06 规则版本、Plan 状态、来源 UI-004、scenario。
- KPI 条：READY、已绑定资源、已下发、执行中、已完成、异常入口数量。
- 左侧队列：C06 工单列表，按 sequence 排序，显示阶段、状态、资源类型、绑定资源、版本、计划时间。
- 中央任务详情：依赖链、WorkNode 状态、上游/下游、C06 provenance、当前命令反馈。
- 右侧资源池：AREA-A 可见资源，按 `resourceType`、`status`、`workArea` 筛选，显示 AVAILABLE/BUSY/OFFLINE/MAINTENANCE/LOCKED。
- 底部审计与操作区：绑定资源、下发工单、接单、开始、暂停、完成、异常入口。

响应式：

- 1440x900 使用三栏看板。
- 1280x720 使用队列 + 详情主列，资源池放入抽屉或下方区域。
- 不允许横向滚动、按钮裁切、中文缺字、状态标签遮挡。

## 7. 数据所有权

C07 只处理 C06-owned WorkOrder：

```text
workOrder.id startsWith C06-WO-
workOrder.workOrderNo startsWith C06-WO-
workOrder.ruleVersion = C06-DEMO-RULE-1.0
workOrder.planId = selected planId
```

C07 只处理 C06-owned WorkNode：

```text
node.id startsWith C06-NODE-
node.nodeNo startsWith C06-N-
node.workOrderNo matches a C06 workOrderNo
```

允许写入：

- C06 WorkOrder 的 `resourceId`、`teamId`、`status`、`ackStatus`、`version`、`updatedAt`。
- C06 WorkNode 的 `status`、`actualStartTime`、`actualFinishTime`、`version`、`updatedAt`。
- 追加 C07 command audit。
- C07 page workflow：选中工单、选中资源、资源抽屉、执行反馈面板、最近错误。

禁止写入：

- 历史 `WO-001..012`、`NODE-001..012`。
- Plan、RecommendationDraft、Resource 实例事实。
- baseline、权限目录、状态机目录、公共 Schema。

## 8. API-008 与 API-009

### 8.1 API-008 分配

```text
POST /mock/work-orders/:id/assign
operationId = POST_mock_work_orders_id_assign
x-api-id = API-008
```

C07 Gateway 严格校验 envelope、apiId、operationId、scenarioId、now、path workOrderId 和返回对象身份。API-008 成功后，命令服务再次校验版本和资源状态，在一次 Store commit 内写入 `resourceId/teamId`，WorkOrder 状态保持 `READY`，WorkNode 状态保持 `WAITING`。

资源绑定条件：

- 工单必须是 C06-owned。
- 工单状态必须是 `READY`。
- 工单尚未 `DISPATCHED` 或更后。
- 资源必须在 AREA-A/GLOBAL/`*` 可见范围内。
- 资源类型必须匹配 C06 task projection 的 requiredResourceType。
- 资源状态必须是 `AVAILABLE`。
- 资源版本必须与命令快照一致。

### 8.2 API-009 下发

```text
POST /mock/work-orders/:id/dispatch
operationId = POST_mock_work_orders_id_dispatch
x-api-id = API-009
```

API-009 成功后，命令服务执行冻结 DO-005 `READY + dispatch -> DISPATCHED`，同时把 WorkNode 从 `WAITING` 推进为 `READY`，表示现场可接单。下发必须要求 `resourceId` 和 `teamId` 非空。

## 9. 五类 C07 操作

| 动作 | 权限 | API | 结果 |
| --- | --- | --- | --- |
| DB-01 绑定资源 | `dispatch:assign` | API-008 | `resourceId/teamId` 写入，WorkOrder 保持 `READY` |
| DB-02 下发工单 | `dispatch:send` | API-009 | WorkOrder `READY -> DISPATCHED`，WorkNode `WAITING -> READY` |
| DB-03 接单 | `dispatch:send` | 无新 API | WorkOrder `DISPATCHED -> ACKNOWLEDGED`，`ackStatus -> ACKNOWLEDGED` |
| DB-04 执行反馈 | `dispatch:pause` / `dispatch:send` | 无新 API | `ACKNOWLEDGED -> IN_PROGRESS -> PAUSED/COMPLETED`，同步 WorkNode 状态 |
| DB-05 异常入口 | `dispatch:view` | 无写入 | 跳转 `/monitor/exceptions?workOrderId=...&from=dispatch-board` |

DB-03/DB-04 是 Demo 本地执行反馈骨架，只使用冻结 DO-005 transition 和 C03 命令执行，不新增外部 API。页面必须标明这是演示反馈，不代表现场系统回执。

## 10. 权限、版本、幂等、审计

- 页面访问：`dispatch:view`。
- 资源绑定：`dispatch:assign`。
- 下发/接单/开始/完成：`dispatch:send`。
- 暂停：`dispatch:pause`。
- 重新绑定：`dispatch:reassign`，仅允许 READY 且未下发工单。

所有写动作必须按固定顺序执行：

```text
权限 -> 数据域 -> 请求/业务校验 -> API 或本地命令边界 -> 二次版本校验
-> 状态机 transition -> 单次原子 Store commit -> 追加一条审计
```

版本漂移统一返回 `DEMO-VERSION-001`，不产生部分写入。相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或审计。所有成功、拒绝和失败各追加一条 C07 审计，不覆盖 C04/C05/C06 审计。

## 11. 页面状态

技术/业务状态固定覆盖：

- `loading`：启动或命令处理中。
- `empty`：没有 C06 READY 或更后状态工单。
- `business-error`：Plan 未 DECOMPOSED、C06 任务不存在、资源类型缺失、工单状态不允许。
- `network-error`：API-008/API-009 transport 或 malformed envelope。
- `forbidden`：角色或数据域不允许，不泄露对象存在性。
- `not-found`：planId 不存在或越界。

业务进度状态：

- `READY_QUEUE`：C06 READY 队列可派工。
- `ASSIGNED`：已绑定资源，尚未下发。
- `DISPATCHED`：已下发，等待接单。
- `EXECUTING`：接单/执行/暂停/完成演示反馈。

## 12. E2E 与截图

新增 Windows 合法 E2E 文件：

```text
e2e/ui-005-dispatch-board.spec.ts
```

恰好 4 条 C07 E2E：

1. SCN-01：从 UI-002 -> UI-003 -> UI-004 READY -> UI-005，绑定资源并下发。
2. SCN-01：接单、开始、暂停、继续、完成，验证状态、版本和审计。
3. 权限、数据域、资源不可用、版本漂移、幂等重放，验证无部分写入。
4. API-008/API-009 network/malformed 和 SCN-05 联锁阻断，验证恢复、草稿保留和 UI-009 入口。

恰好 8 张截图：

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

截图检查必须覆盖横向滚动、裁切、重叠、中文缺字、按钮可见性、资源池可读性、状态标签和异常入口。

## 13. TDD 闸门

Gate A：派工投影、ownership、资源匹配、API-008/API-009 Gateway。

Gate B：DB-01 到 DB-04 命令、状态迁移、版本、幂等、审计、联锁、reset。

Gate C：UI-005 页面、权限、六类状态、四类业务进度、跨页入口和响应式。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。

## 14. 最终验收

- C07 focused Vitest 全绿。
- 全量 Vitest 以单 worker 记录真实结果，默认并发若仍触发既有 `PlanLedgerPage.render` 超时，按 C06 事实说明，不修改 timeout/config。
- Build 通过并记录 modules。
- C07 E2E 4/4，全量 Playwright 通过并记录数量。
- 全局 TypeScript 如仍因冻结 React/JSX 声明失败，按事实分类；C07 非 React primary diagnostics 必须为 0。
- 六份冻结 baseline hash 6/6 一致。
- `git diff --check` 通过。
- 禁止范围无修改。
- 工作区干净，分支保留不合并、不推送。
