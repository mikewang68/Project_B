# C13 系统配置 / UI-012 维护与复验提示词

你现在接手已完成的 **C13：系统配置 / UI-012**。除非用户明确要求新改动，否则先做只读复验，不重新实施、不改契约、不合并、不推送。

## 必读资料

```text
docs/handoffs/C13A-config-contract-baseline.md
docs/handoffs/C13-system-settings.md
docs/evidence/C13/verification.md
docs/evidence/C13/screenshot-index.md
docs/evidence/C13/tsc-before.txt
docs/evidence/C13/tsc-after.txt
docs/superpowers/specs/2026-07-22-c13-system-settings-design.md
docs/superpowers/plans/2026-07-22-c13-system-settings.md
docs/baseline/SHA256SUMS.txt
```

## 工程与边界

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- 分支：`demo/c13-system-settings`
- C13A 基点：`a35a93916fa80d1506cdcbd6460b53504e0a88f9`
- C13 实现/截图尖端：`922169bb8ebebbc6d50cbd633bff305f2834bc10`
- route：`/settings/system`
- identity：`UI-012 / DO-015 / CFG-001`
- sole owner：`state.systemConfig.configVersions`
- read/write：`API-022 observation / API-023 edit`

可编辑字段只能是：

```text
displayName, defaultScenarioId, ruleVersion, dispatchStrategy,
recommendationEnabled, offlineSyncEnabled, reportPeriod, auditRetentionDays
```

metadata 与 session/runtime 上下文保持只读。页面不提供 submit/approve/publish/rollback，不提供生产连接参数、凭据、真实持久化、审批或合规归档。

## 复验口径

先确认工作区状态和用户改动，再按需运行：

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx src/runtime/__tests__/runtime.test.tsx src/mocks/__tests__/handlers.test.ts src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts
node_modules\.bin\playwright.CMD test e2e/ui-012-settings-system.spec.ts
node_modules\.bin\tsc.CMD --noEmit --pretty false
```

冻结事实：focused 212/212、C13 Playwright 5/5、全量 Playwright 53/53。全量 Vitest 曾有 3 项纯聚合 30 秒 timeout，原文件隔离 17/17 通过。TypeScript 因冻结安装缺少 `@types/react` 仍 exit 1；允许的只有 TS2604/TS7016/TS7026 声明链，任何其他 C13 诊断都必须先调查。coverage provider 不在冻结依赖中，不安装、不编造百分比。

## 修改规则

若用户要求维护：先写失败测试并确认 RED 原因，再做最小实现；保持 Gateway-first、response 后 Store 重读、DO-015 lifecycle/version 校验、config + DO-013 原子 commit、transport/domain 审计分离、失败保留 draft、页面 reset 禁用和外部授权 reset 清理 C13 local state。

不得修改 `docs/baseline/**`、公共 contracts、Store ownership、状态机、权限目录、依赖或 C04–C12 行为；如确需改变这些边界，暂停页面维护，另立契约基线阶段并取得用户授权。

未经用户另行授权，不合并、不推送。
