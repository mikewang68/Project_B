# C13A 配置契约基线修复 → C13 系统配置交接

## 交付结论

C13A 已把系统配置的权威身份统一为 `DO-015 ConfigVersion`。DO-014 继续只表示用户角色；API-022/023、fixture、Mock 映射、Store 所有权和生命周期均指向 DO-015。

本阶段没有实施 `UI-012`，没有创建 `demo/c13-system-settings`，没有修改页面、feature 或 RBAC。C13 必须从本分支最终尖端另建页面实施分支。

## 唯一 ConfigVersion Schema

Fixture 唯一对象：`CFG-001`，数量固定为 1。

可编辑白名单只有：

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

只读身份与流转元数据：

```text
id
configVersion
status
version
createdAt
updatedAt
updatedBy
```

场区、班次、时区、当前角色、数据域和其他接口状态继续来自 session/runtime 只读投影，不得写入 DO-015。

冻结枚举：

```text
ConfigStatus       = DRAFT | SUBMITTED | APPROVED | PUBLISHED | ROLLED_BACK
DispatchStrategy   = BALANCED | PRIORITY_FIRST | RESOURCE_FIRST
ConfigReportPeriod = SHIFT | DAILY | MONTHLY
defaultScenarioId  = SCN-01 .. SCN-07
```

其他限制：`displayName` 1～64 字符，`ruleVersion` 1～32 字符，`auditRetentionDays` 为 1～3650 的整数，`version` 为正整数；Schema 严格拒绝未知字段。

公共导出来自 `src/contracts`：

- `do015Schema` / `ConfigVersion`
- `configVersionChangesSchema`
- `api023RequestSchema`
- `api022DataSchema` / `api023DataSchema`
- `api022SuccessEnvelopeSchema` / `api023SuccessEnvelopeSchema`

## API-022 / API-023

API-022：

```text
GET /mock/config
operationId = GET_mock_config
response    = API022SuccessEnvelope
items       = DO-015[]
```

API-023：

```text
POST /mock/config/{id}/command
operationId = POST_mock_config_id_command
request     = API023Request
response    = API023SuccessEnvelope
items       = exactly one DO-015 target
```

`API023Request` 是两个严格分支：

```json
{
  "command": "edit",
  "expectedVersion": 1,
  "changes": { "displayName": "B项目生产调度 Demo（草稿）" },
  "reason": "可选的非空原因"
}
```

以及：

```json
{
  "command": "submit | approve | publish | rollback",
  "expectedVersion": 1,
  "reason": "可选的非空原因"
}
```

只有 `edit` 可带至少一个白名单 `changes`。`submit/approve/publish/rollback` 不允许 `changes`，只做状态流转。API-023 不是任意字段更新接口；旧的五字段 body、未知 changes 和未知 `CFG-*` id 均返回 400 `DEMO-SCENARIO-001`。

## Store 所有权边界

DO-015 的唯一可变领域所有者是：

```text
state.systemConfig.configVersions
```

类型入口为 `SystemConfigSlice`，构造入口为 `createSystemConfigSlice`。初始化、根状态再校验、深冻结和 `resetFromSnapshot` 已覆盖 DO-015。

`configAudit` 仍只拥有 `userRoles`、`audit` 和 `commandAudit`，不得放入 `configVersions`。MockRuntime 只提供冻结 fixture 的 API transport observation，不拥有、更新或替代 Store 配置事实。C13 不得从 API response 直接覆盖 Store；应在命令成功后重新校验目标 DO-015，再执行一次原子 Store commit。

## 生命周期

DO-015 状态机只允许：

```text
DRAFT       + edit     -> DRAFT
DRAFT       + submit   -> SUBMITTED
SUBMITTED   + approve  -> APPROVED
APPROVED    + publish  -> PUBLISHED
PUBLISHED   + rollback -> ROLLED_BACK
```

其他组合返回 `FORBIDDEN_TRANSITION`。C13 页面本轮只暴露 `edit`，不得提供 submit/approve/publish/rollback UI；这些命令存在于权威契约和状态机中，供后续受控流程消费。

## C13 写入顺序

C13 的 edit 流程必须保持：

1. 使用既有路由权限和 session/dataScope，只读取得上下文。
2. 通过 API-023 Gateway 发送严格 `edit + expectedVersion + changes`。
3. 严格验证 success/error envelope、API identity、scenario、目标 `CFG-001` 和 DO-015 Schema。
4. 再次检查当前 Store version 与状态机结果，防止响应期间状态漂移。
5. 一次 `replaceDomainState` 原子提交 DO-015，并通过既有受支持路径追加真实 DO-013 command audit。
6. 失败时不部分写 Store，不伪造 audit，不让 API observation 成为第二份领域事实。

## 冻结 hash 与验证

新基线版本为 `0.3.0`，六 hash 见：

- `docs/baseline/SHA256SUMS.txt`
- `docs/evidence/C13A/SHA256SUMS-after.txt`
- `docs/evidence/C13A/verification.md`

实测摘要：C13A focused Vitest `149/149`，production build `2,569` modules，全量 Playwright `48/48`，hash `6/6`。全量 Vitest 为 `778/780`，仅 UI-002/UI-013 两条聚合负载 30 秒 timeout，隔离文件分别 `11/11`、`3/3` 通过。TypeScript 仍为预检相同的 783 条 React/JSX 声明链诊断，C13A 新增诊断为 0。

## 分支与提交

- 分支：`demo/c13-config-contract-baseline`
- C12 基点：`f85ede995c6958bbf36e1aa561b1ca7332fb8588`
- 实施代码尖端：`f76c2eb7ca68e7fa8af5ea5d85bc8753dd29adc4`
- merge count：`0`
- 未合并、未推送。

最终 evidence/handoff 提交由本文件所在提交补齐；C13 应以交付消息中的最终 HEAD 为起点。

## C13 禁止事项

- 不再修改 0.3.0 baseline、公共 ConfigVersion 契约、Store ownership 或 DO-015 lifecycle。
- 不把 DO-014、session/runtime 投影、API read observation 或页面 form state当作 ConfigVersion。
- 不增加自由字段更新、未知命令、隐式状态迁移或客户端伪造元数据。
- 不暴露 approve/publish/rollback UI，不宣称真实生产配置中心、审批平台或持久化能力。
- 不合并、不推送，除非用户另行授权。
