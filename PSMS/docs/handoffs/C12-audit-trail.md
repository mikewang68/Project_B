# C12 审计日志 / UI-013 → C13 交接

## 交付结果

C12 已把 `/governance/audit` 从 scaffold 实现为确定性、只读的审计工作台。页面严格展示冻结 DO-013 与 C03-C11 真实 runtime command audit，独立验证 API-024，提供 8 维组合筛选、结果 KPI、完整详情、trace 聚合、读取故障恢复和 stale trace 解释。

本模块是 Demo 审计投影，不是生产审计平台。它不连接数据库、日志采集、SIEM/ELK/Kafka，不提供不可篡改证明、电子签名、合规归档、导出、打印、下载或清理能力。

## 编号与身份口径

- 冻结页面：`UI-013`。
- 路由：`/governance/audit`。
- API：`API-024 / GET /mock/audit-logs / GET_mock_audit_logs`。
- `UI-012` 是 `/settings/system`，此前“UI-012 审计追踪”仅为编号误写；C12 没有修改 baseline。
- DO-013 权威主键是 `id`。
- envelope `auditLogId` 与 `traceId` 只表示本次 API 读取身份，不是 DO-013 字段、主键别名或台账记录。

严格 DO-013 字段只有：

```text
id, actorId, operatorTerminal, action, objectType, objectId,
before, after, reason, traceId, occurredAt
```

## 稳定入口与公共导出

模块入口为 `src/features/audit-trail/index.ts`，统一导出：

- Gateway：`createAuditGateway`、`AuditGateway` 及 query/result 类型。
- 投影：`projectAuditTrail`、`buildAuditTrace` 与投影/详情/KPI 类型。
- 查询：`parseAuditQuery`、`filterAuditTrail` 及清洗工具。
- workflow：`createAuditTrailWorkflowStore`、`AuditTrailWorkflowState`、`AuditTrailWorkflowStore`。
- 类型：`AuditLedgerItem`、`AuditFilters`、`AuditResultCategory`、`AuditReadObservation` 等。

共享运行时暴露 `runtime.auditTrail.gateway` 与 `runtime.auditTrail.workflow`；React 侧通过 `useAuditTrailWorkflow(selector)` 订阅。C13 应从 `src/features/audit-trail/index.ts` 或共享 Store 的稳定接口消费，不依赖页面组件内部结构。

## 数据源、去重与失败原子性

领域事实来自：

1. `state.configAudit.audit`：冻结 DO-013 台账。
2. `state.configAudit.commandAudit`：现有命令服务真实产生的 DO-013 + metadata。

投影以 `record.id` 去重。同 ID 且 DO-013 字段完全一致时，只附加 command metadata；字段冲突会抛出一致性错误，不静默覆盖。返回记录、metadata、KPI 和 trace 均深冻结。

API-024 success response 在返回前严格校验 envelope、`apiId`、`operationId`、scenario、Demo time、items、`traceId` 和 `auditLogId`。响应 items 不写入 Store，也不覆盖领域台账。network、malformed、business error 和 success identity 只写 feature-local workflow；失败时已经显示的 Store 事实继续可见。

## 结果分类口径

结果类别为：

```text
RECORDED, SUCCESS, DENIED, VERSION_CONFLICT, IDEMPOTENT_HIT, BUSINESS_ERROR
```

- 无 metadata 的冻结 DO-013 只标记 `RECORDED`，不推断成功。
- `DENIED` 只来自 `TOS-AUTH-001` 或明确 `DENIED` metadata。
- `VERSION_CONFLICT` 只来自 `DEMO-VERSION-001`。
- 其他明确失败 metadata 分类为 `BUSINESS_ERROR`。
- `SUCCESS` 只来自明确成功 metadata。
- `IDEMPOTENT_HIT` 只接受 runtime 未来提供、且 `auditLogId` 能匹配真实台账记录的显式 `idempotent: true` feedback。

当前冻结场景未产生独立幂等审计记录；重放复用首次结果且不新增 DO-013。因此初始与 reset 后幂等命中均为 0。生产代码未伪造 `idempotent: true`；单测中的显式 feedback 仅验证未来分支与不匹配 ID 不计数。

## 页面、查询与权限

查询参数为 `auditId/module/action/objectType/result/actorId/objectId/traceId/period/scenarioId/from`。API-024 没有冻结查询参数，因此页面筛选永不下推到 Gateway。

页面覆盖 loading、Store empty、filter empty、route forbidden、audit not-found、network、malformed、business error、stale trace 与 success。详情展示完整 DO-013、before/after JSON 和可用 command metadata；缺失 metadata/request/response 明确显示“未提供”。

UI-013 沿用冻结路由角色 `AUDITOR/REGULATOR/SYS_ADMIN`。reset 另行检查既有 `demo:reset`；AUDITOR 能查看但不能 reset，按钮禁用并保持只读。没有修改权限或角色目录，也没有添加导出、打印、归档、验签或幂等重放按钮。

## Reset 的实际语义

共享 reset 成功后会清空 C12 filters、选中项、详情抽屉、展开链路、pending、读取观察和反馈，让 workflow 回到 `idle`。

既有 C04 reset 实现会恢复 9 条冻结 `configAudit.audit`，同时保留一条本次真实 reset command audit：

```text
id=AUD-C04-003
action=execute
objectId=SCN-01
result=SUCCESS
```

C12 没有修改这条受上游测试锁定的语义，也没有把 reset envelope `AUD-C12-INTEGRATION-RESET` 当作 DO-013。无论是否存在真实 reset audit，幂等命中仍为 0。

## 测试与证据

- [最终验证](../evidence/C12/verification.md)
- [覆盖率限制](../evidence/C12/coverage.json)
- [TypeScript 最终计数](../evidence/C12/tsc-after.txt)
- [截图索引](../evidence/C12/screenshot-index.md)
- [C12 E2E](../../e2e/ui-013-governance-audit.spec.ts)

最终实测：C12 focused Vitest 57/57、C12 Playwright 3/3、全量 Playwright 48/48、production build 2,569 modules、冻结 hash 6/6、merge 0、截图恰好 8 张且尺寸/视觉检查通过。

项目原生全量 Vitest 为 764/765，只有历史 UI-002 SCN-02 在整套负载下 30 秒 timeout；对应隔离用例 19.87 秒通过。TypeScript 为冻结 React 声明缺口：783 条主诊断、仅三类 React/JSX 代码，C12 非 React/JSX 诊断为 0。官方 V8 coverage provider 不在冻结依赖中，未生成或伪造百分比。

## 提交链

以 C11 `507a715046dc7e9858e638b07bb56e920e70da37` 为基点，C12 保持线性提交，merge count 为 0：

```text
0ee6c8f docs(c12): define audit trail design
1da5bc8 docs(c12): add audit trail implementation plan
eff8df6 docs(c12): add audit trail execution prompt
198bcc6 test(c12): record audit trail preflight
d2a992c feat(c12): add audit projections and queries
3eb22fe feat(c12): add audit api gateway
e6355fd feat(c12): add audit trace aggregation
bebf8f3 feat(c12): implement audit trail page
c82f253 test(c12): verify audit integration
8eab4cb test(c12): cover audit trail demo flows
c69bef3 test(c12): stabilize audit action flow
```

最终证据/handoff 由本文件所在提交补齐；精确最终分支尖端见 C12 交付消息。没有合并或推送。

## C13 起点与限制

- C13 应从 `demo/c12-audit-trail` 最终尖端开始，先复核 6 份冻结 hash 和 `git status`。
- 通过 `src/features/audit-trail/index.ts` 使用稳定能力，不写回 `configAudit`，也不要把 API read observation 当成 DO-013。
- 不把当前 Demo 投影描述成真实生产审计、合规归档或不可篡改能力。
- 不依赖 C12 提供导出、打印、验签、归档、幂等重放或真实日志采集；这些能力均未实现。
- 如果未来 runtime 确实产生显式幂等 feedback，必须先匹配真实 DO-013 `id` 再展示；不得为了 KPI 或截图补造记录。
