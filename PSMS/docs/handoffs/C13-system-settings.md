# C13 系统配置 / UI-012 交接

## 交付结论

C13 已完成 `/settings/system`。页面不再是 scaffold，并明确声明“Demo 系统配置视图，非生产配置中心”。实现遵循 C13A 冻结契约：DO-015 是唯一配置身份，`state.systemConfig.configVersions` 是唯一可变领域 owner，DO-014 仍是用户角色。

## 快速身份

```text
route                  /settings/system
identity               UI-012 / DO-015 / CFG-001
read/write             API-022 / API-023 edit
owner                  state.systemConfig.configVersions
editable keys          exact eight-field whitelist
audit identities       transport receipt vs AUD-C13 domain audit
reset boundary         disabled on page; external authorized callback integrated
known limitations      Demo-only, no production persistence/approval/publish/rollback
```

## 页面与 feature

- 页面：`src/pages/settings/SystemSettingsPage.tsx`
- feature：`src/features/system-settings/**`
- E2E：`e2e/ui-012-settings-system.spec.ts`
- 设计：`docs/superpowers/specs/2026-07-22-c13-system-settings-design.md`
- 计划：`docs/superpowers/plans/2026-07-22-c13-system-settings.md`
- 最终验证：`docs/evidence/C13/verification.md`
- 截图清单：`docs/evidence/C13/screenshot-index.md`

5 个页面分组为：基础与版本、调度规则、接口与离线、报表与审计、权限与上下文。1440 下摘要固定在右侧页面栏，1280 下进入中心表单后的正常流；不是 Drawer 或 Modal。

## 字段与上下文边界

唯一可编辑白名单：

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

只读元数据：

```text
id, configVersion, status, version, createdAt, updatedAt, updatedBy
```

roleCode、dataScope、shiftId、online、timezone、demoTime、activeScenarioId 只从 session/runtime 投影，不进入 DO-015 或 API-023 changes。外部系统连接参数不属于 DO-015，页面不提供配置入口。

## 读取、保存与审计

API-022 只验证 transport observation，不覆盖 Store。API-023 页面只发送严格 `edit`：

```json
{
  "command": "edit",
  "expectedVersion": 1,
  "changes": { "displayName": "新的 Demo 名称" },
  "reason": "变更说明"
}
```

保存为 Gateway-first。成功响应后重新读取 Store，检查 expectedVersion 与 `DRAFT + edit -> DRAFT`，再通过一次 `replaceDomainState` 同时提交 DO-015 和真实 DO-013 command audit。API envelope `auditLogId` 与领域 `AUD-C13-*` 明确分开。

所有失败保留 draft 并避免部分写；幂等 replay 复用首次结果且不新增审计。页面不暴露 submit、approve、publish 或 rollback。

## 权限与 reset

- UI-012 角色沿用冻结目录：`SYS_ADMIN / INTERFACE_OPS / SAFETY`。
- DISPATCHER 在 API-022 前被路由拒绝。
- 离线 session 可读但不能 edit。
- UI-012 所有角色都没有 `demo:reset`，所以页面 reset 按钮禁用并显示原因，页面不调用 API-025。
- 其他已授权 flow 完成 API-025 reset 后，共享 runtime 会清理 C13 draft、feedback、read observation 和 command idempotency cache。

## 验证摘要

- C13/contracts focused Vitest：212/212 通过。
- 全量 Vitest：843/846；3 项仅聚合 30 秒 timeout，原文件隔离 17/17 通过。
- production build：2,584 modules，通过。
- C13 Playwright：5/5 通过。
- 全量 Playwright：53/53 通过。
- TypeScript：仍 exit 1；仅冻结 React/JSX 声明链三类，C13 非冻结诊断 0。
- coverage：冻结安装缺少 `@vitest/coverage-v8`，无可信百分比。
- baseline hash：6/6；merge count：0。
- 截图：恰好 8 张，尺寸和原图视觉检查通过。

## 分支与提交

- 分支：`demo/c13-system-settings`
- C13A 基点：`a35a93916fa80d1506cdcbd6460b53504e0a88f9`
- C13 实现/截图尖端：`922169bb8ebebbc6d50cbd633bff305f2834bc10`
- 未合并、未推送。

最终 evidence/handoff 提交由本文件所在提交补齐；最终 HEAD 以交付消息为准。

## 后续维护规则

- 不修改 0.3.0 baseline、公共 ConfigVersion/API 契约、Store ownership、DO-015 lifecycle 或 RBAC，除非另立契约阶段并重新冻结。
- 不让 Gateway、MockRuntime、workflow 或页面成为第二配置 owner。
- 不把 API receipt 当作 DO-013，也不伪造生产持久化、审批、发布、回滚或合规能力。
- 修改 UI-012 时保留 RED→GREEN 证据，并至少复跑 C13 focused、C13 Playwright、TypeScript 差异和冻结 hash。
- 未经用户另行授权，不合并、不推送。
