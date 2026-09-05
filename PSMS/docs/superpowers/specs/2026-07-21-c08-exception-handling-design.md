# C08 异常处置设计规格

## 1. 背景与基点

C07 已完成 UI-005 派工看板，最终提交为 `a232b5ea6f53c6b724cbc9db0840d729c48f1526`。C07 的 DB-05 只提供只读异常入口：

```text
/monitor/exceptions?workOrderId={id}&planId={planId}&scenarioId={scenarioId}&from=dispatch-board
```

C08 从现有 UI-008 路由 `/monitor/exceptions` 接手，把当前 `PageScaffold` 替换为可演示的异常处置工作台。C08 只拥有 DO-009 异常对象，不反向改写 UI-005 的 WorkOrder/WorkNode 执行反馈，也不实现 UI-009 安全联锁恢复。

## 2. 已确认方案

采用“同一 Store 中的 C08 异常处置投影与命令服务”。

UI-008 读取冻结 DO-009 异常台账，并可用 query 中的 `workOrderId`、`planId`、`scenarioId`、`from` 显示来源上下文。由于冻结 DO-009 没有 `workOrderId`、`planId` 或外键字段，C08 不把 UI-005 传入的工单 ID 写入异常对象，也不伪造关联。页面只显示“来源上下文”和“Demo 过滤提示”，异常事实仍以 DO-009 为准。

C08 使用 API-014 查询异常列表，使用 API-015 执行异常命令。API 响应只证明 transport 和对象身份，不覆盖 Store。领域状态变更由 C08 command service 在严格校验后写入既有 `exception.exceptions` slice，并追加 C08 command audit。

## 3. 目标

1. 实现 UI-008 异常处置页面内容，保留路由 `/monitor/exceptions`、页面权限 `monitor:view` 和 C01 smoke 稳定性。
2. 读取、筛选并展示 DO-009 异常台账，覆盖 `OPEN`、`ACKNOWLEDGED`、`HANDLING`、`PENDING_REVIEW`、`CLOSED`、`REOPENED` 等状态。
3. 支持 EX-01 确认异常、EX-02 分派处理、EX-03 提交处理、EX-04 复核/关闭、EX-05 重开五类操作。
4. 严格调用 API-014 和 API-015，不新增 endpoint，不修改 OpenAPI、错误码、权限目录或状态机目录。
5. 复用 C03 命令执行、权限、幂等、版本快照、追加审计、API-025 reset 和 SCN-05 联锁只读提示。
6. 从 C07 DB-05 入口带入上下文，但不声明冻结异常与工单存在生产外键。
7. 产出 focused/full tests、4 条 C08 E2E、8 张截图、coverage、verification 和 handoff。

## 4. 非目标

1. 不创建新的异常对象，不补写 `workOrderId`、`planId`、`resourceId` 等 DO-009 不存在字段。
2. 不实现真实维修派单、设备复位、短信/IM 通知、外部工单系统或联锁解除。
3. 不实现 UI-009 安全联锁命令；INTERLOCK 类型异常只提供 UI-009 链接。
4. 不修改 C07 WorkOrder/WorkNode/Resource 事实，不把异常处置结果回写派工看板。
5. 不新增 API、公共枚举、公共错误码、状态机 transition、权限码、角色、fixture、依赖或根 Store slice。
6. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、C04-C07 已交付页面主体。

## 5. 权威契约与冲突规则

优先级固定为：

1. 用户已确认的 C08 范围：补 UI-008 异常处置闭环，页面足以演示。
2. 本设计规格和后续实施计划。
3. C07 handoff 与最终源码。
4. 冻结 baseline、OpenAPI、state machine、permission catalog 和 C03 Store。

冲突处理规则：

- 冻结 DO-009 没有外键字段，C08 不得把 C07 query 伪装为生产关联。
- API-014/API-015 的响应只证明严格 transport 和对象身份，不覆盖 Store 事实。
- DO-009 状态机没有“升级” transition。`ESCALATED` 只作为冻结状态可展示，不在 C08 新增升级动作。
- `INTERLOCK_FORCE_STOP` 属于 UI-009 安全联锁演示边界。C08 可显示强制联锁提示和 UI-009 入口，但不得解除、覆盖或复位联锁。
- 任何 baseline hash 不匹配时停止，不自行接受新基线。

## 6. 页面范围与布局

UI-008 路由：

```text
/monitor/exceptions
```

允许 query：

```text
workOrderId
planId
scenarioId
from
status
level
type
owner
```

页面采用现有 Ant Design 工作台风格，不引入新视觉库。

核心区域：

- 顶部上下文：来源模块、workOrderId、planId、scenarioId、Demo 非外键说明、返回 UI-005 / UI-009 链接。
- KPI 条：未确认、处理中、待复核、已关闭、超期、联锁类。
- 左侧异常台账：异常编号、类型、等级、状态、负责人、截止时间、版本、证据数量。
- 中央详情：状态流、证据列表、影响说明、来源上下文、处理记录摘要、当前命令反馈。
- 右侧处置面板：确认、分派处理、提交处理、复核退回、关闭、重开。
- 底部审计：traceId、auditLogId、命令结果、最近 C08 审计。

响应式：

- 1440x900 使用台账、详情、处置三栏。
- 1280x720 使用台账 + 详情主列，处置面板可堆叠或抽屉化。
- 不允许横向滚动、按钮裁切、中文缺字、状态标签遮挡。

## 7. 数据所有权

C08 只处理严格 DO-009：

```text
id
exceptionNo
type
level
status
owner
dueAt
evidence
version
createdAt
updatedAt
```

允许写入：

- DO-009 的 `status`、`owner`、`evidence`、`version`、`updatedAt`。
- 追加 C08 command audit。
- C08 page workflow：选中异常、筛选条件、处置抽屉、reason、ownerDraft、evidenceDraft、最近错误。

禁止写入：

- WorkOrder、WorkNode、Resource、Plan、RecommendationDraft、Interlock 事实。
- baseline、权限目录、状态机目录、公共 Schema。
- DO-009 之外的新字段。

## 8. API-014 与 API-015

### 8.1 API-014 查询异常

```text
GET /mock/exceptions
operationId = GET_mock_exceptions
x-api-id = API-014
```

Gateway 严格校验 envelope、apiId、operationId、scenarioId、now 和 Page<DispatchException> 结构。查询结果只用于 transport 校验和页面刷新，不覆盖 Store。页面主事实仍来自唯一 Store。

### 8.2 API-015 异常命令

```text
POST /mock/exceptions/:id/command
operationId = POST_mock_exceptions_id_command
x-api-id = API-015
```

命令 body 限定为：

```ts
{
  action: 'ACK' | 'ASSIGN' | 'HANDLE' | 'REVIEW' | 'CLOSE' | 'REOPEN';
  reason: string;
  owner?: string;
  evidence?: string[];
}
```

API-015 成功后，命令服务再次校验版本和状态机 transition，在一次 Store commit 内更新异常状态、owner/evidence、version 和 updatedAt。

## 9. 五类 C08 操作

| 动作 | 权限 | API | 状态结果 |
| --- | --- | --- | --- |
| EX-01 确认异常 | `exception:ack` | API-015 | `OPEN/REOPENED -> ACKNOWLEDGED` |
| EX-02 分派处理 | `exception:handle` | API-015 | `ACKNOWLEDGED -> HANDLING`，可更新 owner |
| EX-03 提交处理 | `exception:handle` | API-015 | `HANDLING -> PENDING_REVIEW`，追加 evidence |
| EX-04 复核/关闭 | `exception:review` / `exception:close` | API-015 | `PENDING_REVIEW -> HANDLING` 或 `PENDING_REVIEW -> CLOSED` |
| EX-05 重开 | `exception:reopen` | API-015 | `CLOSED -> REOPENED` |

所有动作使用冻结 DO-009 transition。C08 不新增 `ESCALATE` 动作；`ESCALATED` 只作为现有状态展示。

## 10. 权限、版本、幂等、审计

- 页面访问：`monitor:view`。
- 确认：`exception:ack`。
- 分派与提交处理：`exception:handle`。
- 复核退回：`exception:review`。
- 关闭：`exception:close`。
- 重开：`exception:reopen`。

所有写动作必须按固定顺序执行：

```text
权限 -> 数据域 -> 请求/业务校验 -> API-015 -> 二次版本校验
-> DO-009 transition -> 单次原子 Store commit -> 追加一条审计
```

版本漂移统一返回 `DEMO-VERSION-001`，不产生部分写入。相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或审计。所有成功、拒绝和失败各追加一条 C08 审计，不覆盖 C04-C07 审计。

## 11. 页面状态

技术/业务状态固定覆盖：

- `loading`：启动或查询/命令处理中。
- `empty`：筛选后没有异常。
- `business-error`：状态不允许、缺少 reason、owner 不合法、证据为空、INTERLOCK 类型需要 UI-009。
- `network-error`：API-014/API-015 transport 或 malformed envelope。
- `forbidden`：角色或数据域不允许，不泄露对象存在性。
- `not-found`：exceptionId 不存在或越界。

业务进度状态：

- `OPEN_QUEUE`：待确认。
- `HANDLING`：已确认/处理中。
- `REVIEW`：待复核。
- `CLOSED`：已关闭或重开入口。

## 12. E2E 与截图

新增 Windows 合法 E2E 文件：

```text
e2e/ui-008-exception-handling.spec.ts
```

恰好 4 条 C08 E2E：

1. SCN-01：从 UI-005 异常入口进入 UI-008，确认异常并分派处理。
2. SCN-01：提交处理、复核退回、再次提交并关闭，验证版本和审计。
3. 权限、数据域、非法状态、版本漂移、幂等重放，验证无部分写入。
4. API-014/API-015 network/malformed 和 INTERLOCK 类型异常，验证恢复、UI-009 入口和不解除联锁。

恰好 8 张截图：

```text
C08-UI008-SCN01-OPEN_QUEUE-1440x900.png
C08-UI008-SCN01-OPEN_QUEUE-1280x720.png
C08-UI008-SCN01-HANDLING-1440x900.png
C08-UI008-SCN01-HANDLING-1280x720.png
C08-UI008-SCN01-REVIEW-1440x900.png
C08-UI008-SCN01-REVIEW-1280x720.png
C08-UI008-SCN01-CLOSED-1440x900.png
C08-UI008-SCN01-CLOSED-1280x720.png
```

截图检查必须覆盖横向滚动、裁切、重叠、中文缺字、状态流可读性、处置按钮可见性和 UI-009 入口。

## 13. TDD 闸门

Gate A：异常投影、上下文解析、API-014/API-015 Gateway。

Gate B：EX-01 到 EX-05 命令、状态迁移、版本、幂等、审计、reset。

Gate C：UI-008 页面、权限、六类状态、四类业务进度、跨页入口和响应式。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。

## 14. 最终验收

- C08 focused Vitest 全绿。
- 全量 Vitest 以单 worker 记录真实结果，默认并发若仍触发既有 `PlanLedgerPage.render` 超时，按事实说明，不修改 timeout/config。
- Build 通过并记录 modules。
- C08 E2E 4/4，全量 Playwright 通过并记录数量。
- 全局 TypeScript 如仍因冻结 React/JSX 声明失败，按事实分类；C08 非 React primary diagnostics 必须为 0。
- 六份冻结 baseline hash 6/6 一致。
- `git diff --check` 通过。
- 禁止范围无修改。
- 工作区干净，分支保留不合并、不推送。
