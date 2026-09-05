# C10 离线同步设计规格

## 1. 背景与基点

C09 已完成 UI-009 安全联锁，交接基点为分支 `demo/c09-safety-interlock`、提交 `de81e5a22cf1972133cbdee03e3b281723aca2da`。C10 从该干净基点创建分支 `demo/c10-offline-sync`，把现有 UI-010 占位页 `/operations/offline-sync` 替换为可演示的离线包同步工作台。

C10 只处理冻结 DO-011 `OfflinePacket`，使用 API-018 和 API-019。它不实现真实离线终端、本地数据库、文件上传协议、二进制解析、增量同步、后台队列或自动合并算法。

## 2. 已确认方案

采用“唯一 Store 中的 C10 独立功能切片”。

新增 `src/features/offline-sync/`，分别承载严格类型、DO-011 投影、query 水合、API-018/API-019 Gateway、OS-01 至 OS-05 命令和页面工作流。页面只组合投影和动作，不承载领域迁移规则。所有对象事实继续来自 `runtime.store.offline.packets`，不新增第二套 fixture、Store 或 Provider。

API 响应只验证 transport、API identity、scenario identity 和对象 identity，不直接覆盖 Store。成功命令仅在二次版本检查通过后原子更新 DO-011，并追加 C10 审计。

## 3. 目标

1. 实现 UI-010 离线同步页面和 `/operations/offline-sync` 路由内容，保留既有 `offline:view` 路由权限。
2. 展示并筛选严格 DO-011 台账，覆盖 `CACHED`、`PENDING_UPLOAD`、`VALIDATING`、`MERGED`、`CONFLICT`、`REJECTED`、`RETRY`。
3. 实现 OS-01 上传、OS-02 校验、OS-03 合并、OS-04 驳回、OS-05 重试。
4. 严格调用 API-018 和 API-019，不新增 endpoint，不修改 OpenAPI、公共请求 Schema、错误码、权限目录或状态机目录。
5. 复用 C03 命令执行、权限、版本、幂等、审计、原子 reset，以及 C04-C09 的共享 runtime 与页面集成模式。
6. 明确区分 SCN-06“冲突识别与恢复引导”和 SCN-01“标准离线包处理闭环”。
7. 产出 focused/full tests、C10 E2E、精确 8 张截图、coverage、verification 和 C10→C11 handoff。

## 4. 非目标与禁止范围

1. 不实现真实离线终端、本地数据库、文件传输或包解析。
2. 不实现增量同步协议、后台任务队列或自动冲突合并。
3. 不写回 WorkOrder、WorkNode、DispatchException、Interlock、Resource、Plan 等上游对象。
4. `workOrderNo` 仅为 DO-011 文本上下文，不作为可写外键；不伪造缺失对象。
5. 不新增依赖、fixture、根 Store slice、Provider、公共 API、公共错误码、权限码或状态机 transition。
6. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、公共契约、权限/状态机目录或 C09 业务实现。
7. API-018/API-019 的 transport 结果不得直接覆盖唯一 Store 中的 DO-011 事实。

## 5. 权威契约与一致性闸门

权威顺序固定为：

1. 用户确认的 C10 范围与本设计。
2. C09 handoff 与最终源码中的既有扩展模式。
3. 冻结 baseline、OpenAPI、fixture、状态机、权限目录和 C03 Store。

DO-011 仅允许以下字段：

```text
id
offlinePackageNo
terminalId
workOrderNo
packageVersion
serverVersion
validation
mergeStatus
version
createdAt
updatedAt
```

冻结 API019Request 仍为旧公共形态。C10 沿用 C09 已存在的 feature-local 严格请求 Schema 扩展点，在 C10 内部把 API-019 body 规范化为：

```ts
{
  action: 'UPLOAD' | 'VALIDATE' | 'MERGE' | 'REJECT' | 'RETRY';
  reason: string;
  validation?: Record<string, unknown>;
}
```

该扩展只作用于 C10 Gateway 与 mock handler 的 API-019 校验分派，不修改 `src/contracts/requests.ts`、OpenAPI 或 baseline。

如果实现过程中发现上述字段、fixture、API identity、transition 或 C09 交接事实不一致，必须停止在契约一致性闸门，不自行猜测或修改冻结层。

## 6. 功能单元与职责

建议文件结构：

```text
src/features/offline-sync/offlinePacketTypes.ts
src/features/offline-sync/offlinePacketProjection.ts
src/features/offline-sync/offlinePacketQueries.ts
src/features/offline-sync/offlinePacketGateway.ts
src/features/offline-sync/offlinePacketCommands.ts
src/features/offline-sync/offlinePacketRuntime.ts
src/features/offline-sync/index.ts
src/features/offline-sync/offline-sync.css
src/features/offline-sync/components/*
src/features/offline-sync/__tests__/*
src/pages/operations/OfflineSyncPage.tsx
src/pages/__tests__/OfflineSyncPage.*.test.tsx
src/pages/__tests__/offlineSyncTestHarness.tsx
e2e/ui-010-offline-sync.spec.ts
```

- `offlinePacketTypes.ts`：UI 投影、query、workflow、动作可用性和反馈类型。
- `offlinePacketProjection.ts`：DO-011 深冻结投影、KPI、状态流、版本差异、校验摘要和动作可用性。
- `offlinePacketQueries.ts`：解析并清洗 `packetId`、`terminalId`、`workOrderNo`、`mergeStatus`、`scenarioId`、`from`。
- `offlinePacketGateway.ts`：严格 API-018/API-019 请求和 envelope/data identity 校验。
- `offlinePacketCommands.ts`：权限、业务前置条件、API 调用、二次版本检查、状态迁移、原子 commit、审计和幂等。
- `offlinePacketRuntime.ts`：选中包、reason、校验草稿、最近反馈和 reset 后页面状态复位。
- `OfflineSyncPage.tsx`：读取唯一 Store、触发 Gateway/command、组合页面组件和技术状态。

## 7. Query 与数据所有权

页面接受：

```text
packetId
terminalId
workOrderNo
mergeStatus
scenarioId
from
```

所有 query 值先 trim；`mergeStatus` 仅接受冻结 DO-011 状态。`packetId` 用于精确选中/筛选，`terminalId` 与 `workOrderNo` 用于文本筛选，`scenarioId` 与 `from` 用于 Demo 来源和场景说明。它们都不写入 DO-011，也不声明生产外键。

C10 允许写入：

- 当前 OfflinePacket 的 `mergeStatus`、`validation`、`serverVersion`、`version`、`updatedAt`。
- 追加一条 C10 command audit。
- C10 workflow 中的选中项、reason、校验草稿、命令处理中状态和最近反馈。

C10 禁止写入其他 root slice。测试必须在命令前后比较 WorkOrder 及其他上游对象快照。

## 8. API-018 与 API-019

### 8.1 API-018 查询

```text
GET /mock/offline-packets
operationId = GET_mock_offline_packets
x-api-id = API-018
```

Gateway 严格校验 success/error envelope、apiId、operationId、scenarioId、now 和 `OfflinePacket[]`。查询结果只证明 transport 与契约身份；页面主事实仍来自唯一 Store。

### 8.2 API-019 命令

```text
POST /mock/offline-packets/:id/command
operationId = POST_mock_offline_packets_id_command
x-api-id = API-019
```

Gateway 使用 feature-local body Schema，严格校验 action、非空 reason 和可选 validation。成功响应必须只包含目标 OfflinePacket，且其 `id` 与 path id 一致。scenarioId 必须与当前共享 Store 场景一致。

内部测试 header 可沿用既有模式增加 C10 专属 network/malformed 注入；它只服务确定性测试，不构成公共 API。

## 9. 命令管线与状态写入

每条命令固定执行：

```text
捕获当前对象与 expectedVersion
→ 权限和数据域检查
→ 请求、对象和状态前置校验
→ API-019
→ scenario/object identity 校验
→ 二次版本与状态检查
→ DO-011 transition
→ 单次原子 Store commit
→ 追加审计并更新反馈
```

动作规则：

| 业务动作 | API action | 权限 | 状态迁移 | 字段语义 |
| --- | --- | --- | --- | --- |
| OS-01 上传 | `UPLOAD` | `offline:retry` | `CACHED/RETRY → PENDING_UPLOAD` | 保留包内容和版本对比，递增对象 version |
| OS-02 校验 | `VALIDATE` | `offline:resolve` | `PENDING_UPLOAD → VALIDATING` | 写入严格 `validation` 结果 |
| OS-03 合并 | `MERGE` | `offline:resolve` | `VALIDATING → MERGED` | 仅允许 `validation.valid === true`；令 `serverVersion = packageVersion` |
| OS-04 驳回 | `REJECT` | `offline:resolve` | `VALIDATING/CONFLICT → REJECTED` | 保留冲突和版本证据 |
| OS-05 重试 | `RETRY` | `offline:retry` | `CONFLICT/REJECTED → RETRY` | 保留原校验证据，等待重新上传/校验 |

每次成功仅递增一次 `version`，并使用共享 Demo 时间更新 `updatedAt`。`createdAt`、`id`、包号、终端、作业单文本和 `packageVersion` 不变。

## 10. 权限、版本、幂等、审计与错误

- 页面访问：`offline:view`。
- 上传与重试：`offline:retry`。
- 校验、合并与驳回：`offline:resolve`。
- data scope 沿用 C03 既有可见范围；越界对象返回安全 not-found，不泄露存在性。

失败映射：

| 场景 | 错误码 | 写入规则 |
| --- | --- | --- |
| 权限拒绝 | `TOS-AUTH-001` | 不修改 DO-011，按 C03 追加一条拒绝审计 |
| expectedVersion 漂移 | `DEMO-VERSION-001` | 不修改 DO-011，保留原对象 |
| 非法请求或状态迁移 | `DEMO-SCENARIO-001` | 不修改 DO-011 |
| SCN-06 冻结版本冲突 | `TOS-OFF-001` | 不修改 DO-011，显示恢复引导 |
| network/malformed | `TOS-EXT-001` | 不修改 DO-011，允许重试或 reset |

同一 `commandId` 的重放返回首次冻结结果，不重复 API、审计、workflow 更新、Store commit 或版本递增。命令失败可以写失败/拒绝审计，但不得产生领域对象部分写入。

## 11. SCN-06 与 SCN-01 语义

### SCN-06：冲突识别与恢复引导

- `OFF-001` 保持冻结 `CONFLICT`、`packageVersion=2`、`serverVersion=1`、`validation.valid=false` 和 `VERSION_CONFLICT`。
- 既有 mock fault 可能在 API-018 或 API-019 命中冻结 `OFFLINE_VERSION_CONFLICT` 并返回 `TOS-OFF-001`；C10 不改动该分派规则。
- 页面继续以唯一 Store 投影展示 OFF-001 的版本差异与冲突原因，同时显示 traceId、auditLogId 和“重置到 SCN-01 继续标准处理”的恢复提示；API 错误不得清空或覆盖 Store 台账。
- 不修改 fault、fixture 或 OFF-001，不把它强行改造成 SCN-06 内完整闭环数据。

### SCN-01：标准离线包处理闭环

- 主链使用 `OFF-004` 演示 `CACHED → PENDING_UPLOAD → VALIDATING → MERGED`。
- reset 到 SCN-01 后，可使用冻结 `OFF-001` 演示 `CONFLICT → RETRY → PENDING_UPLOAD → VALIDATING → MERGED` 或 `REJECTED`。
- 重新校验写入 `{ valid: true, issues: [] }`，之后才允许合并。

对外统一说明：SCN-06 是“冲突识别与恢复引导”，SCN-01 是“标准离线包处理闭环”。

## 12. 页面与响应式

页面沿用现有 Ant Design 工作台视觉，不新增依赖，包含：

- 来源/场景条：六项 query、当前场景和“Demo 文本上下文，非生产外键”说明。
- KPI：缓存、待上传、校验中、已合并、冲突、已驳回、重试。
- 台账：离线包号、终端、作业单号文本、包版本、服务器版本、合并状态。
- 详情：校验结果、冲突原因、版本对比、最近更新时间和冻结状态流。
- 动作：reason、上传、校验、合并、驳回、重试和 reset 到 SCN-01。
- 反馈：命令结果、traceId、auditLogId、幂等命中、版本拒绝、业务错误和恢复建议。

技术状态覆盖 `loading`、`empty`、`forbidden`、`not-found`、`network-error`、`malformed-response` 和 `business-error`。

响应式规则：

- 1440×900：台账、详情、动作三栏同时可见。
- 1280×720：动作区提升为 KPI 下方的紧凑横向操作条，台账和详情保持两栏；当前状态、版本差异、动作按钮和最近反馈必须在首屏可见。
- 不允许横向滚动、裁切、重叠、中文缺字或状态标签遮挡。

## 13. TDD 与 E2E

TDD 闸门：

1. Gate A：DO-011 投影、KPI、query 解析和筛选。
2. Gate B：API-018/API-019 Gateway 的成功、错误、network、malformed 和 identity 校验。
3. Gate C：OS-01 至 OS-05、权限、非法迁移、版本冲突、幂等、原子写入、审计和 reset。
4. Gate D：页面按钮启停、反馈文案、技术状态、响应式、E2E、截图与全量回归。

新增 `e2e/ui-010-offline-sync.spec.ts`，覆盖四组场景：

1. SCN-01 `OFF-004` 标准合并闭环。
2. OFF-001 冲突展示、SCN-06 `TOS-OFF-001` 恢复引导，以及 reset 到 SCN-01 后的标准处理。
3. 权限、版本、非法状态和幂等失败无部分写入，且不修改 WorkOrder 或其他上游对象。
4. API-018/API-019 network、malformed、业务错误可见且可恢复。

## 14. 截图与证据

精确生成 8 张截图：

```text
C10-UI010-SCN01-CACHED-1440x900.png
C10-UI010-SCN01-CACHED-1280x720.png
C10-UI010-SCN01-UPLOADING-1440x900.png
C10-UI010-SCN01-UPLOADING-1280x720.png
C10-UI010-SCN01-CONFLICT-1440x900.png
C10-UI010-SCN01-CONFLICT-1280x720.png
C10-UI010-SCN01-MERGED-1440x900.png
C10-UI010-SCN01-MERGED-1280x720.png
```

`UPLOADING` 是截图业务进度名，对应 DO-011 冻结状态 `PENDING_UPLOAD`。

证据文件：

```text
docs/evidence/C10/preflight.md
docs/evidence/C10/tsc-before.txt
docs/evidence/C10/offline-core-red.txt
docs/evidence/C10/offline-page-red.txt
docs/evidence/C10/offline-e2e-red.txt
docs/evidence/C10/screenshot-index.md
docs/evidence/C10/coverage.json
docs/evidence/C10/verification.md
docs/evidence/C10/tsc-after.txt
docs/handoffs/C10-offline-sync.md
```

## 15. 最终验收

- 冻结 baseline 六份 SHA-256 为 6/6 匹配。
- `git diff --check` 通过，merge commit 数量为 0。
- C10 focused Vitest 全绿；全量 Vitest 如仍出现既有 UI-002 timeout，隔离复跑并如实记录。
- build 通过。
- C10 Playwright 和全量 Playwright 通过并记录实际数量。
- 精确 8 张截图及原始分辨率检查通过。
- 全量 TypeScript 如仍有既有 React/JSX 声明诊断，按事实分类；C10 非 React/JSX 主诊断为 0。
- baseline、依赖、公共契约、权限/状态机目录和 C09 业务实现均无越界修改。
- 分支不合并、不推送，最终工作区干净。
