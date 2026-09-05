# C09 安全联锁设计规格

## 1. 背景与基点

C08 已完成 UI-008 异常处置，最终提交为 `632fa3def12e93754f42eb3c8f16c8d80c207c69`。C08 对 INTERLOCK 类型异常只提供 UI-009 入口：

```text
/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling
```

C09 从现有 UI-009 路由 `/safety/interlocks` 接手，把当前 `PageScaffold` 替换为可演示的安全联锁工作台。C09 只拥有 DO-010 联锁对象，不回写 C08 异常状态，不实现真实 PLC/ECS/设备复位。

## 2. 已确认方案

采用“同一 Store 中的 C09 联锁投影与命令服务”。

UI-009 读取冻结 DO-010 联锁台账，并可用 query 中的 `exceptionId`、`scenarioId`、`from` 显示来源上下文。由于冻结 DO-010 没有 `exceptionId` 外键字段，C09 不把 UI-008 传入的异常 ID 写入联锁对象，也不伪造关联。页面只显示“来源上下文”和“Demo 非外键说明”，联锁事实仍以 DO-010 为准。

C09 使用 API-016 查询联锁列表，使用 API-017 执行联锁命令。API 响应只证明 transport 和对象身份，不覆盖 Store。领域状态变更由 C09 command service 在严格校验后写入既有 `interlock.interlocks` slice，并追加 C09 command audit。

## 3. 目标

1. 实现 UI-009 安全联锁页面内容，保留路由 `/safety/interlocks`、页面权限 `interlock:view` 和 C01 smoke 稳定性。
2. 读取、筛选并展示 DO-010 联锁台账，覆盖 `LOCKED`、`RESET_REQUESTED`、`APPROVED`、`RESTORED` 等冻结 fixture 状态。
3. 支持 SI-01 触发/回执、SI-02 申请复位、SI-03 审批、SI-04 恢复、SI-05 申请/审批旁路五类操作。
4. 严格调用 API-016 和 API-017，不新增 endpoint，不修改 OpenAPI、错误码、权限目录或状态机目录。
5. 复用 C03 命令执行、权限、幂等、版本快照、追加审计、API-025 reset 和 SCN-05 FORCE_STOP 安全阻断。
6. 从 C08 入口带入上下文，但不声明冻结联锁与异常存在生产外键。
7. 产出 focused/full tests、4 条 C09 E2E、8 张截图、coverage、verification 和 handoff。

## 4. 非目标

1. 不创建新的联锁对象，不补写 `exceptionId`、`workOrderId`、`deviceCommandId` 等 DO-010 不存在字段。
2. 不实现真实 PLC/ECS/设备复位、现场传感器控制、安全旁路授权系统或外部通知。
3. 不绕过 `FORCE_STOP`；FORCE_STOP 只能演示安全流程和阻断语义，不能被 C09 静默解除。
4. 不修改 C08 DispatchException、C07 WorkOrder/WorkNode/Resource、Plan 或 RecommendationDraft 事实。
5. 不新增 API、公共枚举、公共错误码、状态机 transition、权限码、角色、fixture、依赖或根 Store slice。
6. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、C04-C08 已交付页面主体。

## 5. 权威契约与冲突规则

优先级固定为：

1. 用户已确认的 C09 范围：补 UI-009 安全联锁闭环，页面足以演示。
2. 本设计规格和后续实施计划。
3. C08 handoff 与最终源码。
4. 冻结 baseline、OpenAPI、state machine、permission catalog 和 C03 Store。

冲突处理规则：

- 冻结 DO-010 没有异常外键，C09 不得把 C08 query 伪装为生产关联。
- API-016/API-017 的响应只证明严格 transport 和对象身份，不覆盖 Store 事实。
- 冻结 fixture 没有 `TRIGGERED`、`ACTION_ISSUED` 或 `WAITING_RECEIPT` 联锁。SI-01 的触发/回执能力可以在单元测试中用严格 DO-010 测试对象覆盖，但 E2E 主链不得伪造生产 fixture。
- `FORCE_STOP` 的 `APPROVED -> RESTORED` 可以按冻结状态机演示恢复动作，但页面必须明确“Demo 恢复记录，不代表真实设备已复位”。
- 任何 baseline hash 不匹配时停止，不自行接受新基线。

## 6. 页面范围与布局

UI-009 路由：

```text
/safety/interlocks
```

允许 query：

```text
exceptionId
scenarioId
from
status
actionLevel
riskType
receiptStatus
```

页面采用现有 Ant Design 工作台风格，不引入新视觉库。

核心区域：

- 顶部上下文：来源模块、exceptionId、scenarioId、from、Demo 非外键说明、返回 UI-008 链接。
- KPI 条：锁定中、待审批、已批准、已恢复、FORCE_STOP、回执失败。
- 左侧联锁台账：联锁编号、风险类型、动作级别、状态、回执状态、版本、更新时间。
- 中央详情：状态流、输入快照、复位申请、审批链、FORCE_STOP 安全说明、当前命令反馈。
- 右侧处置面板：触发、回执、申请复位、审批、恢复、申请旁路、审批旁路。
- 底部审计：traceId、auditLogId、命令结果、最近 C09 审计。

响应式：

- 1440x900 使用台账、详情、处置三栏。
- 1280x720 使用台账 + 详情主列，处置面板可堆叠或抽屉化。
- 不允许横向滚动、按钮裁切、中文缺字、状态标签遮挡。

## 7. 数据所有权

C09 只处理严格 DO-010：

```text
id
interlockNo
riskType
actionLevel
status
inputSnapshot
receiptStatus
resetRequest
approvalChain
version
createdAt
updatedAt
```

允许写入：

- DO-010 的 `status`、`receiptStatus`、`resetRequest`、`approvalChain`、`version`、`updatedAt`。
- 追加 C09 command audit。
- C09 page workflow：选中联锁、筛选条件、处置抽屉、reason、approvalDraft、resetRequestDraft、最近错误。

禁止写入：

- DispatchException、WorkOrder、WorkNode、Resource、Plan、RecommendationDraft 事实。
- baseline、权限目录、状态机目录、公共 Schema。
- DO-010 之外的新字段。

## 8. API-016 与 API-017

### 8.1 API-016 查询联锁

```text
GET /mock/interlocks
operationId = GET_mock_interlocks
x-api-id = API-016
```

Gateway 严格校验 envelope、apiId、operationId、scenarioId、now 和 Page<Interlock> 结构。查询结果只用于 transport 校验和页面刷新，不覆盖 Store。页面主事实仍来自唯一 Store。

### 8.2 API-017 联锁命令

```text
POST /mock/interlocks/:id/command
operationId = POST_mock_interlocks_id_command
x-api-id = API-017
```

命令 body 限定为：

```ts
{
  action: 'TRIGGER' | 'RECEIPT' | 'REQUEST_RESET' | 'APPROVE' | 'RESTORE' | 'REQUEST_OVERRIDE';
  reason: string;
  approvalUserId?: string;
  resetRequest?: Record<string, unknown>;
}
```

API-017 成功后，命令服务再次校验版本和状态机 transition，在一次 Store commit 内更新联锁状态、回执、复位申请、审批链、version 和 updatedAt。

## 9. 五类 C09 操作

| 动作 | 权限 | API | 状态结果 |
| --- | --- | --- | --- |
| SI-01 触发/回执 | `interlock:view` | API-017 | `TRIGGERED -> ACTION_ISSUED -> WAITING_RECEIPT -> LOCKED` |
| SI-02 申请复位 | `interlock:reset` | API-017 | `LOCKED -> RESET_REQUESTED` |
| SI-03 审批复位 | `interlock:approve` | API-017 | `RESET_REQUESTED -> APPROVED` |
| SI-04 恢复 | `interlock:reset` | API-017 | `APPROVED -> RESTORED` |
| SI-05 申请/审批旁路 | `interlock:request-override` / `interlock:approve` | API-017 | `LOCKED -> OVERRIDE_PENDING -> OVERRIDDEN` |

所有动作使用冻结 DO-010 transition。C09 不新增 `forceRestore`、`clearForceStop` 或真实设备控制动作。

## 10. 权限、版本、幂等、审计

- 页面访问：`interlock:view`。
- 申请复位与恢复：`interlock:reset`。
- 审批复位和审批旁路：`interlock:approve`。
- 申请旁路：`interlock:request-override`。

所有写动作必须按固定顺序执行：

```text
权限 -> 数据域 -> 请求/业务校验 -> API-017 -> 二次版本校验
-> DO-010 transition -> 单次原子 Store commit -> 追加一条审计
```

版本漂移统一返回 `DEMO-VERSION-001`，不产生部分写入。相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或审计。所有成功、拒绝和失败各追加一条 C09 审计，不覆盖 C04-C08 审计。

## 11. 页面状态

技术/业务状态固定覆盖：

- `loading`：启动或查询/命令处理中。
- `empty`：筛选后没有联锁。
- `business-error`：状态不允许、缺少 reason、审批人不合法、FORCE_STOP 真实复位不可宣称。
- `network-error`：API-016/API-017 transport 或 malformed envelope。
- `forbidden`：角色或数据域不允许，不泄露对象存在性。
- `not-found`：interlockId 不存在或越界。

业务进度状态：

- `LOCKED`：锁定/待复位申请。
- `RESETTING`：已申请/待审批/已批准。
- `RESTORED`：已恢复记录。
- `OVERRIDE`：旁路申请/已旁路。

## 12. E2E 与截图

新增 Windows 合法 E2E 文件：

```text
e2e/ui-009-safety-interlock.spec.ts
```

恰好 4 条 C09 E2E：

1. SCN-01：从 UI-008 INTERLOCK 入口进入 UI-009，对 `LOCKED` 联锁申请复位。
2. SCN-01：审批复位并恢复，验证版本、审批链、审计和 Demo 恢复声明。
3. 权限、数据域、非法状态、版本漂移、幂等重放，验证无部分写入。
4. API-016/API-017 network/malformed、FORCE_STOP 和旁路路径，验证恢复、阻断提示和不回写 UI-008。

恰好 8 张截图：

```text
C09-UI009-SCN01-LOCKED-1440x900.png
C09-UI009-SCN01-LOCKED-1280x720.png
C09-UI009-SCN01-RESETTING-1440x900.png
C09-UI009-SCN01-RESETTING-1280x720.png
C09-UI009-SCN01-RESTORED-1440x900.png
C09-UI009-SCN01-RESTORED-1280x720.png
C09-UI009-SCN01-OVERRIDE-1440x900.png
C09-UI009-SCN01-OVERRIDE-1280x720.png
```

截图检查必须覆盖横向滚动、裁切、重叠、中文缺字、状态流可读性、处置按钮可见性、FORCE_STOP 提示和返回 UI-008 入口。

## 13. TDD 闸门

Gate A：联锁投影、上下文解析、API-016/API-017 Gateway。

Gate B：SI-01 到 SI-05 命令、状态迁移、版本、幂等、审计、reset。

Gate C：UI-009 页面、权限、六类状态、四类业务进度、跨页入口和响应式。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。

## 14. 最终验收

- C09 focused Vitest 全绿。
- 全量 Vitest 以单 worker 记录真实结果，已知 UI-002 超时波动按事实说明，不修改 timeout/config。
- Build 通过并记录 modules。
- C09 E2E 4/4，全量 Playwright 通过并记录数量。
- 全局 TypeScript 如仍因冻结 React/JSX 声明失败，按事实分类；C09 非 React primary diagnostics 必须为 0。
- 六份冻结 baseline hash 6/6 一致。
- `git diff --check` 通过。
- 禁止范围无修改。
- 工作区干净，分支保留不合并、不推送。
