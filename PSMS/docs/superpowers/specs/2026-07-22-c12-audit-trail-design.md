# C12 审计日志 / UI-013 设计规格

## 1. 背景与基点

C11 已完成 UI-011 统计报表，交接基点为分支 `demo/c11-reporting-dashboard`、提交 `507a715046dc7e9858e638b07bb56e920e70da37`。C12 已从该干净基点创建分支 `demo/c12-audit-trail`，目标是把冻结占位页 `/governance/audit` 实现为 C03-C11 关键命令、权限拒绝、版本冲突、业务错误和 API trace 的统一只读审计展示页。

本页面只展示冻结 DO-013、共享 Store 中既有 command audit 和 C12 本次 API 读取观察。它不实现真实审计后台、日志采集、外部 SIEM/ELK/Kafka、数据库、消息队列、不可篡改链、电子签名、合规归档、导出、打印或下载。

## 2. 已解除的契约闸门

最初交接标题把审计页面写成 UI-012。冻结 `page-task-matrix.csv`、`traceability.csv`、`openapi.yaml`、路由目录和权限目录一致表明：

```text
UI-012 = 系统配置 /settings/system / API-022, API-023
UI-013 = 审计日志 /governance/audit / API-024
```

用户已确认本轮 C12 按冻结基线实施 UI-013，原“UI-012 审计追踪”为编号误写。所有页面、测试、截图、证据和交接材料使用 `UI-013`；不修改 baseline。

用户同时确认：DO-013 主键使用 `id`；`auditLogId` 仅表示 API success/error envelope 的审计身份，不作为 DO-013 字段或主键别名。重置后幂等命中数为 0，不伪造 `idempotent: true` 或新的 DO-013 记录；只有 runtime 未来产生明确幂等反馈时才展示幂等命中。

## 3. 已确认方案

采用“API-024 严格读取 + DO-013/runtime 双源只读投影”。

- API-024 负责 transport、envelope 和 API identity 校验；成功响应不能覆盖 Store 事实。
- `state.configAudit.audit` 是冻结 DO-013 历史台账。
- `state.configAudit.commandAudit` 是 C03-C11 实际运行产生的 DO-013 + metadata 包装记录。
- C12 feature-local workflow 保存本次 API-024 的 trace/audit identity、技术状态、筛选、选中记录、详情抽屉和展开链路；这些观察不写入 DO-013。
- 同一 DO-013 `id` 只展示一次；如同一记录同时存在于冻结台账和 command audit，command metadata 只增强结果分类，不改变 DO-013 字段。
- 冻结 DO-013 没有结果字段，统一分类为“已记录”，不推断为成功。
- 显式成功、权限拒绝、版本冲突、业务错误和幂等命中只从明确 runtime metadata 或明确 workflow feedback 得出。
- C12 不为“访问审计页”追加自审计记录，避免页面读取改变正在展示的事实。

Store-only 方案因无法完整验证 API-024 network/malformed/business-error 而不采用；预置综合审计样本会伪造历史操作，违反硬边界，也不采用。

## 4. 目标

1. 实现 UI-013 审计日志页并保留冻结 `audit:view` 路由权限。
2. 通过 API-024 严格验证冻结 DO-013 读取链路，同时以唯一 Store 为领域事实来源。
3. 合并展示冻结 DO-013 和 C03-C11 runtime command audit，并提供来源、结果和 trace 解释。
4. 支持来源模块、动作、对象类型、结果、actorId、objectId、traceId 和时间文本组合筛选。
5. 支持审计详情抽屉和按 traceId 聚合的链路视图。
6. 覆盖 loading、empty、filter-empty、forbidden、not-found、network-error、malformed-response、business-error、stale-trace/trace-not-found 和 success feedback。
7. reset 后恢复 SCN-01 冻结台账与 C12 本地状态，不写回 C04-C11 上游对象。
8. 产出 focused/full tests、C12 E2E、恰好 8 张截图、coverage、verification 和 C12→C13 handoff。

## 5. 非目标与禁止范围

1. 不实现真实审计后台、日志采集 Agent、外部 SIEM、ELK、Kafka、数据库或消息队列。
2. 不实现真实不可篡改链、电子签名、合规归档、导出、打印、下载、清理或删除。
3. 不新增依赖、fixture、第二套 Store、第二套 Provider、公共权限码、公共错误码、公共 API 或公共状态机 transition。
4. 不修改 `docs/baseline/**`、OpenAPI、公共请求/响应契约、冻结 fixture、`package.json` 或 `pnpm-lock.yaml`。
5. 不修改权限/角色目录或状态机目录；页面使用现有 `UI-013 + audit:view` 判定。
6. 不写回 Plan、Recommendation、WorkOrder、WorkNode、DispatchException、Interlock、OfflinePacket、Report、UserRole 或其他上游对象。
7. 不把 API-024 成功响应直接写入 Store，不接受部分 items，不用响应覆盖领域事实。
8. 不补造冻结记录缺失的 result、metadata、request/response 或链路节点。
9. 不使用真实当前时间、随机数或外部服务；所有时间来自冻结记录、共享 Demo 时钟或现有 runtime 事实。

## 6. 权威契约与字段口径

冻结 UI-013 契约为：

```text
route       = /governance/audit
page        = src/pages/governance/AuditLogPage.tsx
pageId      = UI-013
permission  = audit:view
apiId       = API-024
method      = GET
path        = /mock/audit-logs
operationId = GET_mock_audit_logs
```

冻结 DO-013 字段严格为：

```text
id
actorId
operatorTerminal
action
objectType
objectId
before
after
reason
traceId
occurredAt
```

DO-013 不包含 `auditLogId`、`result`、`errorCode`、`module` 或 `idempotent`。这些信息只能来自 envelope、现有 `CommandAuditEntry.metadata` 或 future explicit workflow feedback，并在 C12 投影类型中与 `record` 分层保存。

如果实施期间发现以上 page/API/DO-013 identity、fixture、Store schema 或权限目录发生新的冲突，必须重新进入契约一致性闸门，不自行猜测。

## 7. 功能单元与职责

采用与 C08-C11 一致的 feature-local slice：

```text
src/features/audit-trail/auditTypes.ts
src/features/audit-trail/auditProjection.ts
src/features/audit-trail/auditQueries.ts
src/features/audit-trail/auditGateway.ts
src/features/audit-trail/auditRuntime.ts
src/features/audit-trail/index.ts
src/features/audit-trail/audit-trail.css
src/features/audit-trail/components/*
src/features/audit-trail/__tests__/*
src/pages/governance/AuditLogPage.tsx
src/pages/__tests__/AuditLogPage.*.test.tsx
src/pages/__tests__/auditTrailTestHarness.tsx
e2e/ui-013-governance-audit.spec.ts
```

- `auditTypes.ts`：投影记录、结果分类、来源、筛选、trace 链路、API 观察、workflow 和 Gateway 类型。
- `auditProjection.ts`：严格投影 DO-013，合并 command metadata，按 `record.id` 去重，分类结果，映射来源模块并生成 KPI/详情/trace 链路。
- `auditQueries.ts`：解析并清洗 URL 参数及 feature-local 筛选；枚举白名单，文本 trim、去控制字符并限制长度。
- `auditGateway.ts`：构造 API-024 读取并严格验证 success/error envelope、API/scenario identity、DO-013 items、traceId 和 auditLogId。
- `auditRuntime.ts`：管理 filters、selectedAuditId、expandedTraceId、detailDrawerOpen、readState、lastFeedback 和 reset。
- `AuditLogPage.tsx`：订阅共享 Store 与 C12 workflow，调用 Gateway，组合 KPI、筛选、台账、详情和 trace 组件。
- `components/*`：按上下文、KPI、筛选、台账、详情、链路和读取反馈拆分，避免页面文件承载投影规则。

`DemoRuntimeContext` 只增加一个 C12 runtime 实例及订阅 hook，并把它纳入既有 reset 协调；不创建新的 React Provider。

## 8. 数据模型、去重与分类

### 8.1 领域审计记录

每条页面审计记录始终持有严格 DO-013 `record`。可选增强信息分为：

- `commandMetadata`：来自现有 `CommandAuditEntry.metadata`。
- `sourceModule`：根据现有审计 ID 前缀映射 `AUD-C03` 至 `AUD-C11`；无法确定时显示“冻结基线”或“未知来源”，不根据动作文本猜测模块。
- `resultCategory`：页面只读分类，不写回 DO-013。

合并顺序为冻结 `configAudit.audit` 在前、runtime `configAudit.commandAudit` 在后；以 `record.id` 去重并保持确定性排序。若同 ID 的 DO-013 字段不一致，投影抛出一致性错误，不静默选择其中一份。字段一致时只附加 command metadata。

### 8.2 结果分类

分类优先级固定为：

1. 明确 future workflow feedback `idempotent === true` → `IDEMPOTENT_HIT`。
2. metadata `errorCode === 'TOS-AUTH-001'` 或 `result === 'DENIED'` → `DENIED`。
3. metadata `errorCode === 'DEMO-VERSION-001'` → `VERSION_CONFLICT`。
4. metadata `result === 'FAILED'` 且有其他公共业务错误码 → `BUSINESS_ERROR`。
5. metadata `result === 'SUCCESS'` → `SUCCESS`。
6. 无 metadata 的冻结 DO-013 → `RECORDED`。

重置后的冻结场景没有显式幂等反馈，因此幂等命中数固定为 0。口径说明必须写明：“当前冻结场景未产生独立幂等审计记录；重放复用首次结果且不新增 DO-013。”

### 8.3 API 读取观察

API-024 每次读取产生一条 feature-local `AuditReadObservation`，包含 request kind、状态、traceId、auditLogId、errorCode/message 和 Demo 时钟（能够从已验证响应取得时）。该观察用于读取横幅和 API trace 解释，但：

- 不写入 `configAudit.audit` 或 `configAudit.commandAudit`。
- 不计入领域审计记录总数或空台账判定。
- 不伪装成 DO-013，不拥有 `record.id`。

## 9. Query、筛选与选中语义

页面接受以下 URL 参数：

```text
auditId
module
action
objectType
result
actorId
objectId
traceId
period
scenarioId
from
```

- `auditId` 只解析为 DO-013 `id`，用于详情深链。
- `module`、`result` 只接受 C12 feature-local 白名单枚举。
- `action`、`objectType`、`actorId`、`objectId`、`traceId` 和 `period` 做确定性文本匹配。
- `period` 只匹配 `occurredAt` 文本，不解析真实日历区间。
- `scenarioId`、`from` 仅用于 Demo 来源说明，不写入领域对象。
- API-024 冻结契约没有查询参数，因此上述筛选绝不发送到 Gateway。
- 数据范围和页面权限检查先于 API 调用与对象筛选；拒绝时不泄露记录是否存在。
- 有 `auditId` 但对象不可见或不存在时显示 not-found；普通筛选无结果显示 filter-empty。

## 10. API-024 Gateway

```text
GET /mock/audit-logs
operationId = GET_mock_audit_logs
apiId       = API-024
```

成功响应完整验证：

1. JSON 和 `ApiSuccessEnvelope`。
2. `data.apiId === 'API-024'`。
3. `data.operationId === 'GET_mock_audit_logs'`。
4. `data.scenarioId` 与当前共享 Store scenario 一致。
5. `data.now` 为非空确定性 Demo 时间。
6. `data.items` 是严格 DO-013 数组；如调用方指定 expectedAuditId，必须按 DO-013 `id` 找到该记录。
7. envelope `traceId`、`auditLogId` 均保留为读取身份，但不与 DO-013 `id` 比较或互换。

错误响应完整验证 `ApiErrorEnvelope` 并保留公共 `errorCode`、message、traceId、auditLogId。JSON 解析失败或 success data identity 不完整统一映射为 malformed-response，不接受部分数据。

Mock 只为 API-024 增加 C12 内部测试 fault：network、malformed、business。它沿用 C08-C11 专属 header 模式，不扩展 OpenAPI 或公共请求 schema。business fault 返回既有公共错误码和标准 error envelope。

API 成功只证明 transport 正常。页面继续从共享 Store 投影领域审计事实；响应 items 仅用于 identity/一致性验证，不覆盖 Store。

## 11. 页面信息架构与交互

页面沿用现有 Ant Design 工作台视觉，不新增依赖：

1. 页面身份与口径条：UI-013、冻结路由、API-024、当前场景、“Demo 审计投影，非真实生产日志”和幂等零口径。
2. KPI 区：领域审计记录总数、显式成功、权限拒绝、版本冲突、幂等命中、业务错误和最近 traceId。最近 traceId 优先显示最新一次已完成的 API-024 读取观察；没有读取观察时，按 occurredAt 与稳定输入顺序显示最后一条领域审计记录的 traceId。
3. 筛选区：来源模块、动作、对象类型、结果、actorId、objectId、traceId 和 period 文本。
4. 审计台账：`id`、来源、actor/action、objectType/objectId、结果、occurredAt、traceId；字段允许自然换行，不截断中文。
5. 详情抽屉：完整 DO-013、command metadata、来源说明、before/after JSON、权限/版本/幂等解释。缺失信息显示“未提供”，不补造 request/response。
6. trace 链路：按 traceId 聚合并按 occurredAt、稳定输入顺序排序；显示来源、结果和审计身份。只有一条时明确显示“当前链路共 1 条”。
7. API 读取反馈：loading、success 或错误横幅，展示读取 traceId/auditLogId，与领域台账分层。
8. reset：回到 SCN-01，清空筛选、选中、抽屉、展开链路和反馈，再重新读取 API-024。

不展示导出、打印、归档、清理、签名、验签或不可篡改承诺按钮。

## 12. 响应式规则

- 1440×900：页面身份、KPI、筛选、台账和右侧 trace 摘要形成完整演示视图；详情使用抽屉。
- 1280×720：筛选分两行，trace 摘要下移，KPI、筛选和台账首屏可见；详情仍使用浮层抽屉。
- 台账使用响应式列与自然换行，不依赖页面级横向滚动。
- 所有目标视口禁止横向滚动、中文截断、按钮遮挡、状态标签重叠和抽屉内容溢出。

## 13. 技术状态与失败原子性

| 状态 | 展示 | 写入规则 |
| --- | --- | --- |
| loading | API-024 核验提示；已存在 Store 台账不被清空 | 仅 C12 readState |
| empty | 领域审计数组为空 | 无领域写入；API 观察不改变空态 |
| filter-empty | Store 有审计但组合筛选无结果 | 仅保留 filters |
| forbidden | 冻结路由权限边界的 403 | 不调用 API-024，不泄露审计事实 |
| not-found | `auditId` 不可见或不存在 | 无领域写入 |
| network-error | 读取暂不可用与重试入口 | Store 台账继续可见 |
| malformed-response | 响应契约不完整与重试入口 | 不接受部分 items |
| business-error | message、errorCode、traceId、auditLogId | 无领域写入 |
| stale-trace / trace-not-found | 展开链路已随 reset/数据变化消失 | 清理或保留可解释反馈，不补造节点 |
| success | API 读取身份、筛选、详情、链路或 reset 反馈 | 仅 C12 workflow |

Gateway 必须在返回 success 前完成整个响应验证。任何 network、malformed、business、not-found、forbidden 或 stale trace 都不能修改 `configAudit` 或上游对象。

## 14. Reset 与数据所有权

成功 reset 使用现有 `runtime.commands.resetScenario('SCN-01')`，共享 runtime 负责从冻结 snapshot 重建唯一 Store。C12 只把自己的 workflow 恢复为：

```text
filters           = 默认空筛选
selectedAuditId   = undefined
expandedTraceId   = undefined
detailDrawerOpen  = false
readState         = idle/loading
lastFeedback      = undefined
pending           = false
readObservation   = undefined
```

随后重新调用 API-024。重置后的领域审计为冻结 9 条 DO-013，command audit 为空，幂等命中数为 0。不得为了满足 KPI 或演示截图预置拒绝、版本冲突、业务错误或幂等命中。

## 15. TDD 与测试门

严格按 red-green-refactor 执行：

1. Gate A：DO-013 投影、字段严格性、去重、一致性错误、结果分类、来源映射、KPI 和幂等零口径。
2. Gate B：query 清洗、组合筛选、empty/filter-empty、auditId 选中和 trace 链路聚合。
3. Gate C：API-024 success/error、network、malformed、business、apiId/operationId/scenario/items identity 校验，确认响应不覆盖 Store。
4. Gate D：workflow 的 filters、详情、链路、读取反馈、stale trace 和 reset。
5. Gate E：页面 loading/empty/filter-empty/forbidden/not-found/三类 API 错误/success、按钮启停、详情和链路。
6. Gate F：C12 E2E、截图、focused/full 回归、build、TypeScript、冻结边界和上游对象快照。

focused Vitest 必须覆盖：

- 冻结 DO-013 与 command audit 双源投影。
- 成功、权限拒绝、版本冲突、业务错误和显式 future idempotent feedback 分类。
- 重置场景不伪造 idempotent 或 DO-013。
- API-024 success/error identity 与失败无写入。
- 详情抽屉、trace 链路、筛选、反馈、reset。
- `plan`、`recommendation`、`workOrder`、`resource`、`vehicle`、`exception`、`interlock`、`offline` 和 `report` 深快照不变。

新增 `e2e/ui-013-governance-audit.spec.ts`，覆盖：

- SCN-01 总览、组合筛选、详情和 trace 链路。
- 页面可解释冻结“已记录”与至少两类 runtime 关键结果；若 E2E 准备 runtime command audit，必须调用既有命令服务产生真实记录，不直接塞入伪造历史。
- network、malformed、business-error 失败时无部分写入。
- reset 恢复冻结 9 条 DO-013、清空 command audit 和 C12 workflow。
- C04-C11 上游对象深快照不变。

## 16. 截图与证据

恰好生成 8 张截图：

```text
C12-UI013-SCN01-OVERVIEW-1440x900.png
C12-UI013-SCN01-OVERVIEW-1280x720.png
C12-UI013-SCN01-FILTERED-1440x900.png
C12-UI013-SCN01-FILTERED-1280x720.png
C12-UI013-SCN01-DETAIL-1440x900.png
C12-UI013-SCN01-DETAIL-1280x720.png
C12-UI013-SCN01-TRACE-1440x900.png
C12-UI013-SCN01-TRACE-1280x720.png
```

证据文件：

```text
docs/evidence/C12/preflight.md
docs/evidence/C12/tsc-before.txt
docs/evidence/C12/audit-core-red.txt
docs/evidence/C12/audit-page-red.txt
docs/evidence/C12/audit-e2e-red.txt
docs/evidence/C12/screenshot-index.md
docs/evidence/C12/coverage.json
docs/evidence/C12/verification.md
docs/evidence/C12/tsc-after.txt
docs/handoffs/C12-audit-trail.md
```

`coverage.json` 若因冻结依赖缺少官方 Vitest V8 provider 无法可信生成百分比，必须明确记录 provider 缺失、执行结果和未生成可信百分比的原因，不修改依赖、不伪造数据。

## 17. 最终验收

- 当前分支为 `demo/c12-audit-trail`，不合并、不推送，最终工作区干净。
- 冻结 baseline 六份 SHA-256 为 6/6 匹配。
- `git diff --check` 通过；从 C11 基点起 merge commit 数量为 0。
- C12 focused Vitest 与全量 Vitest 执行并记录实际数量；历史慢测若超时，隔离复跑并如实记录。
- build、C12 Playwright 和全量 Playwright 通过并记录实际数量。
- 恰好 8 张 UI-013 截图，原始尺寸和逐张视觉检查通过。
- TypeScript 既有 React/JSX 声明类诊断按事实分类；C12 非 React/JSX 主诊断为 0。
- baseline、依赖、公共契约、权限/状态机目录、C11 业务实现和 C04-C11 上游对象均无越界修改。
- 页面明确说明这是 Demo 审计投影，不是生产审计、合规归档或不可篡改证明。
