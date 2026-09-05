# C12 审计日志实施对话提示词

你现在继续实施 **C12：审计日志 / UI-013**。设计规格与实施计划已经确认；使用 `superpowers:executing-plans` 按 Task 1 到 Task 9 顺序执行，严格遵守 TDD。除非用户明确要求，不使用子代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C11 基点：`507a715046dc7e9858e638b07bb56e920e70da37`
- 设计：`docs/superpowers/specs/2026-07-22-c12-audit-trail-design.md`
- 计划：`docs/superpowers/plans/2026-07-22-c12-audit-trail.md`
- 分支：`demo/c12-audit-trail`

不合并、不推送。实施前核对 C11 祖先、工作区、merge count、六份冻结 hash 和 C11 回归基线。

## 二、已解除的编号与字段闸门

冻结事实：

```text
UI-012 = 系统配置 /settings/system / API-022, API-023
UI-013 = 审计日志 /governance/audit / API-024
```

本轮 C12 必须实现 UI-013；此前“UI-012 审计追踪”为误写。不得修改 baseline。

冻结 DO-013 字段仅为：

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

`id` 是 DO-013 主键。`auditLogId` 仅属于 API success/error envelope 与 C12 read observation，不得写入或替代 DO-013 `id`。

## 三、冻结入口与 API

```text
route       = /governance/audit
page        = src/pages/governance/AuditLogPage.tsx
e2e         = e2e/ui-013-governance-audit.spec.ts
permission  = audit:view
apiId       = API-024
method/path = GET /mock/audit-logs
operationId = GET_mock_audit_logs
```

Gateway 严格验证 JSON、success/error envelope、apiId、operationId、scenarioId、Demo now、DO-013 items、traceId 与 envelope auditLogId。API 成功只证明 transport 正常，不得覆盖 Store。

C12 内部 fault 仅为 `x-demo-c12-fault: network|malformed|business`，只作用于 API-024；不得扩展 OpenAPI 或公共 schema。

## 四、数据来源与结果口径

只读合并：

- `state.configAudit.audit`：冻结 DO-013。
- `state.configAudit.commandAudit`：C03-C11 实际 command audit。
- C12 feature-local API observation：读取 trace/envelope 技术状态，不是 DO-013，不计入台账总数。

同一 `record.id` 去重；字段不同则停止，不静默选值。冻结 DO-013 无 metadata，分类为 `RECORDED`，不推断成功。

分类优先级：

```text
IDEMPOTENT_HIT
DENIED
VERSION_CONFLICT
BUSINESS_ERROR
SUCCESS
RECORDED
```

重置后幂等命中数固定为 0。不得伪造 `idempotent: true` 或新 DO-013；只有 runtime 未来产生明确幂等反馈，且能关联现有 record.id 时才增强分类。页面必须说明当前冻结场景没有独立幂等审计记录。

## 五、页面与状态

页面包含：

- UI-013/API-024/当前场景和“Demo 审计投影，非真实生产日志”。
- 台账总数、显式成功、权限拒绝、版本冲突、幂等命中、业务错误、最近 traceId。
- 来源模块、动作、对象类型、结果、actorId、objectId、traceId、period 文本筛选。
- 严格 DO-013 台账、详情抽屉、before/after、metadata 来源说明。
- 按 traceId 聚合的真实链路；只有一条就显示一条，不补造节点。
- API read banner、重试和 SCN-01 reset。

覆盖 loading、empty、filter-empty、forbidden、not-found、network-error、malformed-response、business-error、stale-trace/trace-not-found、success feedback。API 观察不改变 empty 判定。

1440×900 使用台账 + 右侧 trace；1280×720 trace 下移。不得横向滚动、中文截断、按钮/标签重叠或抽屉裁切。

## 六、写入边界

C12 只写自己的 workflow：filters、selectedAuditId、expandedTraceId、detailDrawerOpen、pending、readState、readObservation、lastFeedback。

禁止写入 `configAudit.audit`、`configAudit.commandAudit`、Plan、Recommendation、WorkOrder、WorkNode、Resource、Appointment、Exception、Interlock、OfflinePacket、Report、UserRole、权限和状态机。C12 不为访问页面追加自审计。

reset 只能调用既有 `runtime.commands.resetScenario('SCN-01')`，然后清空 C12 workflow 并重新读取 API-024。

## 七、TDD 与提交

每个生产函数先写失败测试并确认失败原因正确，再写最小实现。必须保存：

```text
docs/evidence/C12/audit-core-red.txt
docs/evidence/C12/audit-page-red.txt
docs/evidence/C12/audit-e2e-red.txt
```

提交顺序：

```text
docs(c12): define audit trail design
docs(c12): add audit trail implementation plan
docs(c12): add audit trail execution prompt
test(c12): record audit trail preflight
feat(c12): add audit projections and queries
feat(c12): add audit api gateway
feat(c12): add audit trace aggregation
feat(c12): implement audit trail page
test(c12): verify audit integration
test(c12): cover audit trail demo flows
docs(c12): add audit trail evidence and handoff
```

## 八、截图与证据

只保存恰好 8 张：

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

逐张检查原始尺寸、溢出、裁切、文字、KPI、筛选、台账、抽屉和链路可见性。

## 九、最终验证

必须执行并如实记录：六 hash、`git diff --check`、merge count、C12 focused Vitest、全量 Vitest、build、C12 Playwright、全量 Playwright、TypeScript、截图尺寸、禁止范围和上游深快照。

覆盖率 provider 若仍缺失，记录真实失败与未产生可信百分比，不安装依赖、不伪造数据。

最终创建：

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

最终报告分支、HEAD、干净状态、验证数量、8 张截图、证据路径、C12→C13 handoff、提交链和已知限制。不合并、不推送。
