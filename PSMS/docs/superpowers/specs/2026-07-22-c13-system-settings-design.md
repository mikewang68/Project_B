# C13 系统配置 / UI-012 设计规格

## 1. 目标与交付边界

C13 将 `/settings/system` 从 scaffold 实现为可演示的系统配置工作台。页面支持查看、分组、草稿编辑、字段校验、页面内变更摘要、模拟保存、失败恢复和可追溯反馈，但不实现真实配置中心、生产配置下发或审批发布平台。

权威编号与入口：

```text
pageId       = UI-012
route        = /settings/system
page         = src/pages/settings/SystemSettingsPage.tsx
read API     = API-022 / GET /mock/config / GET_mock_config
command API  = API-023 / POST /mock/config/{id}/command / POST_mock_config_id_command
domain       = DO-015 ConfigVersion
fixture      = CFG-001
store owner  = state.systemConfig.configVersions
```

本阶段不修改：

- `docs/baseline`、OpenAPI、fixture 或 0.3.0 六文件 hash；
- DO-015 公共 Schema、API-022/023 公共请求响应契约；
- `systemConfig` Store 所有权和 DO-015 状态机；
- 权限目录、依赖、C04～C12 feature 或上游领域对象；
- submit、approve、publish、rollback 流程和 UI。

页面必须持续显示“Demo 系统配置视图，非生产配置中心”。

## 2. 权威字段

可编辑白名单固定为：

```text
displayName
defaultScenarioId
ruleVersion
dispatchStrategy
recommendationEnabled
offlineSyncEnabled
reportPeriod
auditRetentionDays
```

只读身份和流转元数据固定为：

```text
id
configVersion
status
version
createdAt
updatedAt
updatedBy
```

枚举和范围：

```text
ConfigStatus       = DRAFT | SUBMITTED | APPROVED | PUBLISHED | ROLLED_BACK
DispatchStrategy   = BALANCED | PRIORITY_FIRST | RESOURCE_FIRST
ConfigReportPeriod = SHIFT | DAILY | MONTHLY
defaultScenarioId  = SCN-01 .. SCN-07
displayName        = trim 后 1..64 字符
ruleVersion        = trim 后 1..32 字符
auditRetentionDays = 1..3650 的整数
version            = 正整数
```

场区、班次、时区、当前角色、数据域、在线状态、Demo 时间和 API 技术状态来自 session/runtime 只读投影。它们不是 ConfigVersion 字段，不得进入 API-023 `changes` 或 Store 配置对象。

## 3. 架构与文件职责

采用独立 feature runtime：

```text
src/features/system-settings/
  systemSettingsTypes.ts       领域投影、分组、草稿、反馈和 Gateway 类型
  systemSettingsProjection.ts  Store + session/runtime 只读投影与配置分组
  systemSettingsQueries.ts     group/configId query 解析与规范化
  systemSettingsGateway.ts     API-022/023 严格 transport 校验
  systemSettingsDraft.ts       草稿创建、白名单更新、字段校验和 before/after 摘要
  systemSettingsRuntime.ts     feature-local workflow store
  systemSettingsCommands.ts    权限、幂等、Gateway-first、原子提交和真实审计
  components/*                 页面展示组件
  system-settings.css          1440/1280 响应式布局
  index.ts                     稳定公共导出
```

集成点只有：

- `src/runtime/DemoRuntimeContext.tsx`：创建并暴露 `runtime.systemSettings`，提供 `useSystemSettingsWorkflow`，并在既有 API-025 成功回调中 reset workflow 和命令缓存；
- `src/mocks/handlers.ts`：为 API-022/023 增加 C13 feature-local 故障头，不增加 Mock 配置状态；
- `src/pages/settings/SystemSettingsPage.tsx`：组合 feature 组件；
- 对应单元、页面、runtime 和 E2E 测试。

每个文件只承担一个职责。页面不得直接实现 Schema、幂等缓存、审计构造或 Store commit。

## 4. 数据所有权

唯一 ConfigVersion 领域事实为：

```text
state.systemConfig.configVersions[0]
```

以下对象都不是第二份领域事实：

- API-022/API-023 response item；
- feature-local draft；
- read observation；
- validation error；
- change preview；
- 页面表单值。

API-022 成功只证明 transport、identity 和 payload Schema 正常，不写 Store。API-023 成功只表示服务端接受本次 Demo edit；命令服务必须重新读取 Store，再验证 version 和 lifecycle，最后用一次根级原子提交更新 DO-015。

MockRuntime 继续只投影冻结 fixture。API-023 response 中的 DO-015 item 只用于目标身份和 Schema 验证，不作为提交后的配置事实，也不覆盖 Store。

## 5. 投影与配置分组

页面投影包含一个 ConfigVersion、session/runtime 上下文和五个稳定分组：

```text
overview     基础与版本
dispatch     调度规则
integration  接口与离线
governance   报表与审计
access       权限与运行上下文
```

分组内容：

1. `overview`：可编辑 `displayName/defaultScenarioId`；只读 id、configVersion、status、version、createdAt、updatedAt、updatedBy。
2. `dispatch`：可编辑 `ruleVersion/dispatchStrategy/recommendationEnabled`。
3. `integration`：可编辑 `offlineSyncEnabled`；只读显示 API-022/023 状态。TOS/ECS/PLC/车辆预约只显示“未纳入 DO-015，不提供生产连接参数”，不构造字段和值。
4. `governance`：可编辑 `reportPeriod/auditRetentionDays`；说明 Demo 审计不是合规归档。
5. `access`：只读显示 roleCode、dataScope、shiftId、online、timezone、demoTime 和 activeScenarioId。

query 只接受：

```text
group=overview|dispatch|integration|governance|access
configId=<string>
scenarioId=SCN-01..SCN-07
from=<string>
```

未知 group 回退 `overview`；文本参数 trim；重复参数取第一项。`configId` 存在但不是可见 `CFG-001` 时显示 not-found，不自行创建配置。

## 6. 草稿与变更摘要

默认页面为只读。点击“编辑配置”时，workflow 从当前 Store ConfigVersion 复制八个可编辑字段，并记录 `draftBaseVersion`。草稿是完整可编辑字段集合，便于字段级校验；提交时只生成实际变化的 strict `changes`。

workflow 状态至少包含：

```text
selectedGroup
draft
draftBaseVersion
dirtyFields
validationErrors
pending
readState
readObservation
lastFeedback
```

变更摘要由当前 Store 与 draft 即时派生，不持有第二份 after 事实。字段顺序固定为白名单顺序。每一项包含 field、label、before、after；布尔值和枚举使用中文标签，底层提交仍使用冻结值。

交互规则：

- 无草稿时所有字段只读；
- 草稿修改只接受八个白名单 key；
- 保存要求至少一个实际差异；
- 保存要求 trim 后非空的变更说明；
- “放弃修改”清除 draft、dirty fields、validation errors 和预览，恢复当前 Store 显示；
- 保存失败保留草稿；保存成功清除草稿；
- pending 时禁止编辑、放弃和重复提交。

字段校验复用 DO-015 派生 Schema 和 `configVersionChangesSchema`，不在页面复制一套规则。错误按字段保存；无差异和空原因作为表单级错误。

## 7. Gateway

稳定接口：

```ts
type SystemSettingsGateway = Readonly<{
  listConfig(query: ListConfigQuery): Promise<SystemSettingsGatewayResult>;
  editConfig(input: EditConfigInput): Promise<SystemSettingsGatewayResult>;
}>;
```

`listConfig`：

- GET `/mock/config`；
- 严格解析 `api022SuccessEnvelopeSchema` 或 `apiErrorEnvelopeSchema`；
- 校验 apiId、operationId、expectedScenarioId、expectedNow、expectedConfigId；
- items 必须能定位唯一 `CFG-001`；
- 返回深冻结 observation。

`editConfig`：

- POST `/mock/config/{id}/command`；
- body 必须通过 `api023RequestSchema`，且 C13 只构造 `command: edit`；
- 严格解析 `api023SuccessEnvelopeSchema` 或 `apiErrorEnvelopeSchema`；
- 校验 apiId、operationId、expectedScenarioId、expectedNow、items length 1、target id；
- 返回深冻结 observation。

Gateway 不访问 Store、不执行权限判断、不修改 workflow。

内部故障头：

```text
x-demo-c13-fault: network | malformed | business
```

仅作用于 API-022/API-023。`network` 返回 transport error，`malformed` 返回不能通过 envelope 的 200 body，`business` 返回 409 `DEMO-SCENARIO-001`。该头不写入 OpenAPI，不成为公共契约。版本冲突通过 expectedVersion/Store 漂移和测试 fetcher 返回 `DEMO-VERSION-001` 覆盖。

## 8. 命令服务与原子提交

稳定入口：

```ts
type SaveSystemSettingsInput = Readonly<{
  commandId?: string;
  configId: string;
  expectedVersion: number;
  changes: ConfigVersionChanges;
  reason: string;
}>;

type SystemSettingsCommandService = Readonly<{
  save(input: SaveSystemSettingsInput): Promise<CommandResult>;
  resetCommandState(): void;
}>;
```

执行顺序固定为：

1. 以 `commandId` 查询已解析结果；命中时直接返回首次结果，不执行后续步骤。
2. 读取当前 Store，执行 `authorize({ pageId: 'UI-012', permission: 'settings:edit' })`；既有权限同时约束角色和在线状态。
3. 校验 configId、target existence、DRAFT status、expectedVersion、reason 和 strict changes。
4. 设置 workflow pending，调用 API-023 `edit`。
5. 校验 success/error envelope、scenario、Demo time、target identity 和 DO-015 item。
6. API 成功后重新读取 Store；config identity 或 version 发生变化时返回 `DEMO-VERSION-001`。
7. 调用 `transitionState({ machineId: 'DO-015', current: 'DRAFT', command: 'edit' })`。
8. 构造 after：只覆盖 changes，并设置 `version + 1`、`updatedAt = session.demoTime`、`updatedBy = session.actorId`；id/configVersion/status/createdAt 不变。
9. 一次 `replaceDomainState` 同时提交 after 和成功 `CommandAuditEntry`。
10. 发布成功 feedback 并清除草稿。

所有提交前后都用 `do015Schema` 校验。命令服务不相信页面已经校验。

## 9. 审计、幂等与身份分层

真实领域审计写入现有：

```text
state.configAudit.commandAudit
```

成功记录：

```text
record.id          = AUD-C13-###
record.action      = SS-05
record.objectType  = DO-015
record.objectId    = CFG-001
record.before      = 提交前完整 ConfigVersion
record.after       = 提交后完整 ConfigVersion
record.reason      = 用户变更说明
record.traceId     = 本次实际链路号
record.occurredAt  = session.demoTime
metadata.result    = SUCCESS
```

权限、校验、API、版本、transition 或 commit 失败不修改 DO-015。命令尝试已发生时可追加一条真实失败 audit，before/after 都是提交前事实；metadata 使用 `DENIED` 或 `FAILED` 及真实 errorCode。失败 audit 通过独立根级原子操作追加。若审计构造或追加失败，不产生伪造成功反馈。

API envelope `auditLogId` 始终标为“接口回执审计号”；`AUD-C13-*` 始终标为“领域变更审计号”。二者不得互相替代。

同一 commandId 的真实重放：

- 返回首次冻结结果；
- 不再调用 API；
- 不再提交 Store；
- 不新增 audit；
- C13 feature-local feedback 可标记 `idempotent: true`，因为真实缓存命中已发生；
- 不修改首次 CommandAuditEntry metadata，也不为 KPI 伪造新记录。

## 10. Reset 权限口径

UI-012 的允许角色为 `SYS_ADMIN/INTERFACE_OPS/SAFETY`，但三者均不在既有 `demo:reset` action policy 中。本阶段禁止修改权限目录，因此：

- 页面“重置到冻结场景”控件保持禁用；
- 明确显示当前角色缺少 `demo:reset`；
- 不直接调用 Gateway 绕过命令权限；
- “放弃修改”只恢复当前 Store 值，不冒充场景 reset；
- `runtime.systemSettings` 接入既有外部授权 API-025 成功回调；回调清空 C13 workflow 和幂等缓存，Store 的 C13A reset 路径恢复 DO-015；
- integration test 使用授权 reset 上下文验证该行为；页面/E2E 不伪造 reset success。

## 11. 页面布局

### 1440×900

页面使用三栏：

```text
左：分组导航
中：配置详情 / 编辑表单
右：页面内 sticky 变更摘要与操作
```

顶部上下文包含：UI-012、路由、API-022/023、active scenario、configVersion、ruleVersion、status、version、updatedAt、Demo 声明和 API read observation。

右侧摘要固定在页面内容中，不使用 Drawer/Modal。摘要显示实际变化、变更说明、字段错误、保存/放弃、pending 和最后反馈。

### 1280×720

在宽度低于 `1320px` 时：

- 分组导航保持紧凑；
- 配置表单占主宽度；
- 变更摘要下移到配置表单之后；
- 顶部上下文、分组导航、关键字段和保存反馈在首屏流程中可达；
- 禁止横向滚动、中文截断、按钮遮挡和状态标签重叠。

## 12. 页面状态

- `loading`：API-022 读取中，显示 Skeleton；Store 事实仍不被 response 替换。
- `empty`：Store 没有可见 DO-015。
- `forbidden`：既有 RoutePermissionBoundary 拒绝页面；页面不自行放宽。
- `not-found`：query configId 或 API target 不存在。
- `draft-dirty`：摘要显示实际差异。
- `validation-error`：字段和表单错误可见，Store 不变。
- `version-conflict`：显示 expected/actual，保留 draft。
- `network-error`：读取或保存 transport 失败，允许重试。
- `malformed-response`：Schema/identity 失败，明确契约错误。
- `business-error`：展示公共 errorCode、message、traceId、接口回执 auditLogId。
- `success`：展示新 version、traceId、接口回执审计号和领域变更审计号。
- `reset-disabled`：说明缺少 `demo:reset`，不伪造成功。

## 13. 权限

页面访问继续使用：

```text
pageId     = UI-012
permission = settings:view
roles      = SYS_ADMIN | INTERFACE_OPS | SAFETY
```

编辑保存继续使用 `settings:edit`。不增加权限码。由于 `settings:edit` 是 online-required，离线 session 能查看页面但不能保存。页面展示 authorize 返回的真实禁用原因。

C13 不提供 settings:approve、settings:publish、settings:rollback 操作，即使权限目录和 DO-015 lifecycle 已存在这些扩展点。

## 14. TDD 计划边界

所有生产行为必须先看到对应测试正确失败。

### Core

- projection 只读取 DO-015 和 session/runtime，不构造配置字段；
- query 清洗、重复参数、未知 group、not-found；
- draft 创建、更新、discard、字段错误、无变化、空 reason；
- before/after 摘要只含 dirty 白名单；
- API-022/023 success/error/identity/scenario/now/target/network/malformed/business；
- command permission、offline、unknown target、validation、version、transition、Gateway failure；
- success 原子提交、failure no config write、真实 audit、幂等重放；
- runtime 挂载和外部授权 API-025 reset。

### Page

冻结测试文件：

```text
src/pages/__tests__/SystemSettingsPage.render.test.tsx
src/pages/__tests__/SystemSettingsPage.permission.test.tsx
src/pages/__tests__/SystemSettingsPage.action.test.tsx
```

覆盖五分组、只读/编辑切换、fixed summary、validation、discard、success feedback、disabled reset、read/save fault 和 Store 不变性。

### E2E

权威文件：

```text
e2e/ui-012-settings-system.spec.ts
```

覆盖 SCN-01 总览、编辑和摘要、校验失败、保存成功、权限/版本/API failure、discard、上游快照不变，以及外部 reset integration 的诚实边界。

截图恰好 8 张：

```text
C13-UI012-SCN01-OVERVIEW-1440x900.png
C13-UI012-SCN01-OVERVIEW-1280x720.png
C13-UI012-SCN01-EDITING-1440x900.png
C13-UI012-SCN01-EDITING-1280x720.png
C13-UI012-SCN01-VALIDATION-1440x900.png
C13-UI012-SCN01-VALIDATION-1280x720.png
C13-UI012-SCN01-SAVED-1440x900.png
C13-UI012-SCN01-SAVED-1280x720.png
```

逐张检查原始尺寸、溢出、裁切、中文、分组、表单、摘要和反馈。

## 15. 证据与最终验证

证据文件：

```text
docs/evidence/C13/preflight.md
docs/evidence/C13/tsc-before.txt
docs/evidence/C13/settings-core-red.txt
docs/evidence/C13/settings-page-red.txt
docs/evidence/C13/settings-e2e-red.txt
docs/evidence/C13/screenshot-index.md
docs/evidence/C13/coverage.json
docs/evidence/C13/verification.md
docs/evidence/C13/tsc-after.txt
docs/handoffs/C13-system-settings.md
```

最终执行并如实记录：

- 0.3.0 hash `6/6`；
- C13 focused Vitest；
- 全量 Vitest及聚合负载 timeout 的隔离复跑；
- production build；
- C13 Playwright；
- 全量 Playwright；
- 8 张截图原始尺寸和视觉检查；
- TypeScript 与 C13A 783 条 React/JSX 声明诊断对比，C13 新增非 React/JSX 主诊断必须为 0；
- baseline、依赖、公共 contracts、权限、状态机、C12 feature 和 C04～C12 上游对象禁止范围；
- `git diff --check`、线性提交、merge count 0 和最终干净工作区。

官方 Vitest V8 coverage provider 不在冻结依赖时，记录真实失败和未生成可信百分比，不安装依赖、不伪造 coverage。

## 16. 成功标准

C13 完成时必须同时满足：

1. UI-012 不再是 scaffold；
2. DO-015 仍是唯一配置事实；
3. 八个白名单字段可经草稿、校验、摘要和 API-023 edit 原子提交；
4. 只读元数据和 session/runtime 投影不可编辑；
5. API-022/023 observation 与 Store ownership 分离；
6. 保存成功产生一个真实 DO-013 command audit，失败无配置部分写入；
7. 幂等重放没有第二次副作用；
8. 页面不暴露审批、发布、回滚或生产敏感能力；
9. reset 权限口径真实，不修改 RBAC；
10. 1440×900 和 1280×720 均无布局缺陷；
11. baseline 和上游冻结边界保持不变；
12. 验证、截图、证据、handoff 和提交链完整可复核。
