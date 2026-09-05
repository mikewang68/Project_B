# C13A 配置契约基线修复设计

## 1. 背景与结论

C13 在契约一致性闸门停止是正确行为。当前 UI-012、API-022 和 API-023 的编号、路由与页面文件已经冻结，但“系统配置”没有可执行的唯一领域对象：OpenAPI 示例把结果写成 `ConfigVersion`，组件目录和 fixture 却只有 DO-001～DO-014；DO-014 是 `UserRole`，现有 Mock 又错误地把 API-022/023 映射到 DO-014；Store 的 `configAudit` slice 也只有用户角色、冻结审计和命令审计。

本设计增加独立的 C13A“配置契约基线修复”阶段。C13A 正式登记 `DO-015 ConfigVersion`，补齐严格 Schema、唯一 fixture、独立 Store 所有权、API-022/023 请求与响应以及状态机。C13 页面功能继续暂停，修复完成并冻结新 hash 后再从 C13A 尖端重新启动。

预检基点为：

```text
branch=demo/c12-audit-trail
head=f85ede995c6958bbf36e1aa561b1ca7332fb8588
worktree=clean
merge-count=0
```

C13A 工作分支固定为 `demo/c13-config-contract-baseline`。不合并、不推送。

## 2. 已比较方案与最终选择

### 方案 A：DO-015 + 独立 systemConfig slice（采用）

fixture 的 `objects` 增加 DO-015，Store 增加独立 `systemConfig` slice，API-022/023 统一映射 DO-015。MSW 只验证传输和返回冻结对象；后续 C13 command 才有权原子更新 Store。该方案保持“fixture 是唯一种子、Store 是唯一可变领域事实所有者”的现有架构。

### 方案 B：把配置加入 configAudit slice（不采用）

改动较少，但会混淆配置与治理职责，扩大 C12 边界，并使 C13 配置写入与审计台账耦合。

### 方案 C：把配置放在 fixture 顶层（不采用）

可以保留旧的 14 类对象指标，但会让 ConfigVersion 成为领域目录、Schema、Mock 映射和 Store 之外的例外，再次形成双重权威。

## 3. 权威顺序与冻结不变量

修复后的权威顺序为：

1. 本设计中已批准的 DO-015 业务含义和边界；
2. `docs/baseline` 0.3.0 机器基线；
3. `src/contracts/**` 的 Zod 与 TypeScript 实现；
4. `src/mocks/**` 的只读传输投影；
5. `src/stores/**` 的唯一可变领域状态；
6. 后续 C13 feature-local workflow 和页面投影。

基线不变量从“恰好 14 个领域对象”调整为“DO-001～DO-015 恰好 15 个领域对象”。DO-001～DO-014 的字段、数量和含义保持不变；DO-014 继续是 `UserRole`，不得复用或迁移为配置对象。

基线版本从 `0.2.0` 升为 `0.3.0`。全部 25 个 API 编号、13 个页面编号、7 个场景和 9 个公开错误码保持不变。

## 4. DO-015 ConfigVersion

`ConfigVersion` 使用严格 camelCase，`additionalProperties: false`。字段如下：

| 类别 | 字段 | 类型与约束 | 可编辑 |
|---|---|---|---|
| 身份 | `id` | 非空字符串；fixture 固定 `CFG-001` | 否 |
| 身份 | `configVersion` | 非空字符串；fixture 固定 `CFG-1.0` | 否 |
| 配置 | `displayName` | trim 后 1～64 个字符 | 是 |
| 配置 | `defaultScenarioId` | `SCN-01`～`SCN-07` | 是 |
| 配置 | `ruleVersion` | trim 后 1～32 个字符，fixture 固定 `RULE-1.0` | 是 |
| 配置 | `dispatchStrategy` | `BALANCED / PRIORITY_FIRST / RESOURCE_FIRST` | 是 |
| 配置 | `recommendationEnabled` | boolean | 是 |
| 配置 | `offlineSyncEnabled` | boolean | 是 |
| 配置 | `reportPeriod` | `SHIFT / DAILY / MONTHLY` | 是 |
| 配置 | `auditRetentionDays` | 1～3650 的整数 | 是 |
| 流转 | `status` | `DRAFT / SUBMITTED / APPROVED / PUBLISHED / ROLLED_BACK` | 否 |
| 并发 | `version` | 正整数 | 否 |
| 审计 | `createdAt` | 非空 ISO 8601 字符串 | 否 |
| 审计 | `updatedAt` | 非空 ISO 8601 字符串 | 否 |
| 审计 | `updatedBy` | 非空用户 ID；fixture 固定 `USER-011` | 否 |

可编辑白名单恰好为：

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

场区、班次、时区、当前角色、数据域和其他接口状态继续从既有 session/runtime 只读投影，不写入 DO-015。

## 5. 唯一 fixture

`demo-fixtures.json` 的 `objects` 在 DO-014 后增加恰好 1 条 DO-015：

```json
{
  "id": "CFG-001",
  "configVersion": "CFG-1.0",
  "displayName": "B项目生产调度 Demo",
  "defaultScenarioId": "SCN-01",
  "ruleVersion": "RULE-1.0",
  "dispatchStrategy": "BALANCED",
  "recommendationEnabled": true,
  "offlineSyncEnabled": true,
  "reportPeriod": "SHIFT",
  "auditRetentionDays": 180,
  "status": "DRAFT",
  "version": 1,
  "createdAt": "2026-07-16T08:15:00+08:00",
  "updatedAt": "2026-07-16T08:15:00+08:00",
  "updatedBy": "USER-011"
}
```

单记录足以支撑后续 C13 的查看、草稿编辑、校验、模拟保存、版本冲突和 reset。C13A 不引入配置版本历史、多版本激活、真实发布、灰度、审批、热更新或生产下发语义。

## 6. Store 所有权与 reset

`DemoRootState` 增加独立 slice：

```ts
type SystemConfigSlice = {
  configVersions: ConfigVersion[];
};

type DemoRootState = {
  // existing slices remain unchanged
  systemConfig: SystemConfigSlice;
};
```

`createInitialState` 从 fixture 的 DO-015 解析该 slice；`validateDemoRootState` 每次替换状态时重新以 `do015Schema` 严格校验；`createDemoStore` 继续统一深冻结。`resetFromSnapshot` 从同一 fixture 恢复 `CFG-001` 的全部字段、状态和版本。

DO-015 不进入 `configAudit`，不增加第二个 store/provider，不写入 C04～C12 slice。Mock Runtime 不是可变配置所有者。

## 7. API-022 读取契约

API-022 保持：

```text
GET /mock/config
x-msw-path=/mock/config
operationId=GET_mock_config
apiId=API-022
```

可选 `domain` query 参数保持兼容；它是 Demo 传输参数，不改变 DO-015 所有权或数据域。

成功数据必须严格包含：

```ts
{
  apiId: 'API-022';
  operationId: 'GET_mock_config';
  now: string;
  scenarioId: string;
  items: ConfigVersion[];
}
```

SCN-01 正常响应的 `items` 恰好包含 `CFG-001`。API 成功只证明 transport 和响应契约正常，不覆盖 Store。

## 8. API-023 命令契约

API-023 保持：

```text
POST /mock/config/{id}/command
x-msw-path=/mock/config/:id/command
operationId=POST_mock_config_id_command
apiId=API-023
```

原来同时要求 `edit/submit/approve/publish/rollback` 五个字符串的请求 Schema 被替换为严格判别联合：

```ts
type ConfigVersionEditCommand = {
  command: 'edit';
  expectedVersion: number;
  changes: Partial<Pick<ConfigVersion, EditableField>>;
  reason?: string;
};

type ConfigVersionStateCommand = {
  command: 'submit' | 'approve' | 'publish' | 'rollback';
  expectedVersion: number;
  reason?: string;
};
```

`edit.changes` 必须至少包含一个字段，且只能包含八个白名单字段。`submit/approve/publish/rollback` 出现 `changes` 或其他未知字段时必须失败。`expectedVersion` 必须是正整数。

成功数据沿用现有 Gateway 形态：

```ts
{
  apiId: 'API-023';
  operationId: 'POST_mock_config_id_command';
  now: string;
  scenarioId: string;
  items: [ConfigVersion];
}
```

唯一 item 的 `id` 必须等于 path id。MSW 返回冻结目标对象作为 transport observation，不执行领域提交、不增加版本、不改变状态。后续 C13 command 在 Gateway 成功后重新检查 Store 快照，再原子计算并提交下一状态。

## 9. 状态机与权限映射

DO-015 状态流严格冻结为：

```text
DRAFT --edit--> DRAFT
DRAFT --submit--> SUBMITTED
SUBMITTED --approve--> APPROVED
APPROVED --publish--> PUBLISHED
PUBLISHED --rollback--> ROLLED_BACK
```

其他状态和命令组合全部拒绝。成功领域提交时才执行：

- `version + 1`；
- `updatedAt = session.demoTime`；
- `updatedBy = session.actorId`；
- `edit` 合并并重新通过 DO-015 严格 Schema 校验；
- 其他命令只改变 `status`，不接受或修改配置字段。

现有权限目录保持不变：

| 命令 | 已有权限 |
|---|---|
| `edit` | `settings:edit` |
| `submit` | `settings:edit` |
| `approve` | `settings:approve` |
| `publish` | `settings:publish` |
| `rollback` | `settings:rollback` |

C13 页面只暴露 `edit` 模拟保存，不实现审批流、生产发布或回滚操作界面。

## 10. 错误与原子性边界

- 请求结构错误、空 `changes`、未知字段、非法枚举统一返回符合公共信封的 `DEMO-SCENARIO-001`。
- 未知 config id 不得伪造对象；API-023 返回业务错误信封。
- `TOS-AUTH-001` 继续表示权限或数据域拒绝。
- `DEMO-VERSION-001` 继续表示 `expectedVersion` 与 Store 实际版本不一致。
- 非法状态迁移使用现有命令管线的业务失败口径，不增加第 10 个公开错误码。
- API-022/023 success/error envelope 必须保留 `traceId` 和 `auditLogId`。
- C13A 不执行配置 command，因此不写配置、不追加 DO-013，也不修改 feature workflow。
- 后续 C13 的 Gateway 失败、响应畸形、版本冲突、业务失败或权限拒绝都必须发生在 commit 前；不得出现部分写入。

## 11. 修复文件范围

### 基线与文档

- 修改 `docs/baseline/openapi.yaml`：新增 DO-015、枚举、API-022/023 精确数据 Schema 和 API023Request 判别联合。
- 修改 `docs/baseline/demo-fixtures.json`：增加唯一 DO-015。
- 修改 `docs/baseline/README.md`：版本改为 0.3.0、对象数改为 15、记录 C13A 冻结规则。
- 复核 `page-task-matrix.csv` 与 `traceability.csv`；没有权威新需求编号时不伪造 traceability 行，UI-012 仍保持“未开始”。
- `package-baseline.json` 内容保持不变。
- 重新生成 `docs/baseline/SHA256SUMS.txt`，覆盖上述六份官方基线文件；未变化文件也必须重新核对。

### 应用契约、Mock、Store 与状态机

- 修改 `src/contracts/enums.ts`、`schemas.ts`、`requests.ts`、`api.ts` 和稳定导出。
- 修改 `src/mocks/fixtures.ts`、`handlers.ts` 及相关验证。
- 修改 `src/stores/types.ts`、`slices.ts`、`initialState.ts` 及稳定导出。
- 修改 `src/commands/stateMachines.ts` 和相关类型/测试，登记 DO-015 状态流。
- 不修改任何页面、布局、样式、RBAC 目录、C12 audit-trail 实现或依赖清单。

### C13A 交付物

- `docs/evidence/C13A/preflight.md`
- `docs/evidence/C13A/tsc-before.txt`
- `docs/evidence/C13A/config-contract-red.txt`
- `docs/evidence/C13A/config-store-red.txt`
- `docs/evidence/C13A/SHA256SUMS-before.txt`
- `docs/evidence/C13A/SHA256SUMS-after.txt`
- `docs/evidence/C13A/verification.md`
- `docs/handoffs/C13A-config-contract-baseline.md`
- `docs/conversation-prompts/C13-system-settings.md`

没有 UI 改动，因此 C13A 不生成截图和 screenshot index。

## 12. TDD 与验证

实施遵循测试先行：

1. 先写失败测试，证明缺少 DO-015、API-023 Schema 错误、API 映射指向 DO-014、Store 没有 systemConfig、状态机没有 DO-015。
2. 保存红灯输出后才写最小实现。
3. 每个层级通过 focused 测试后再进入下一层。

最终必须执行并如实记录：

1. DO-001～DO-015 ID、Schema 和 fixture 数量精确；DO-014 内容和数量不变；DO-015 恰好 1 条。
2. API-022/023 的 method、path、operationId、apiId、request、response、目标 ID 和错误信封一致。
3. API-023 `edit` 白名单与非 edit 禁止 `changes` 的正反测试。
4. DO-015 五条合法状态迁移与全部关键非法迁移测试。
5. Store 初始化、严格校验、深冻结、原子替换和 reset。
6. C04～C12 上游领域对象快照保持不变。
7. C13A focused Vitest、全量 Vitest、production build、全量 Playwright。
8. 若历史 UI-002 慢测再次超时，隔离复跑并记录原始与隔离结果。
9. TypeScript 诊断；允许既有 React/JSX 声明缺口继续存在，但 C13A 非 React/JSX 新诊断必须为 0。
10. `git diff --check`、merge commit 数量 0、依赖无改动、页面无改动、RBAC 无改动、C12 业务实现无改动。
11. 六份官方基线 SHA-256 与新 `SHA256SUMS.txt` 6/6 匹配。
12. 最终工作区干净，不合并、不推送。

## 13. 提交与 C13 重启点

C13A 使用线性提交，建议提交链为：

```text
docs(c13a): define config contract baseline repair
docs(c13a): add config contract repair plan
test(c13a): record config contract red gates
fix(c13a): add DO-015 config contract baseline
fix(c13a): add config store ownership and api mapping
test(c13a): verify config contract baseline
docs(c13a): add config contract evidence and handoff
```

完成后，C13 必须从 `demo/c13-config-contract-baseline` 最终尖端创建 `demo/c13-system-settings`，先复核新 6/6 hash 和干净工作区，再实施页面。C13 不得再次修改 0.3.0 基线、DO-015 Schema、公共请求/响应契约、Store 所有权或状态机目录。
