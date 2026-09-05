# C11 统计报表 / UI-011 设计规格

## 1. 背景与基点

C10 已完成 UI-010 离线同步，交接基点为分支 `demo/c10-offline-sync`、提交 `7fb529c50059b39f088859d42f421d423e7780f7`。C11 从该干净基点创建分支 `demo/c11-reporting-dashboard`，把 UI-011 占位页 `/reports/operations` 实现为前面 C04-C10 业务流程的确定性演示汇总页。

C11 只使用冻结 DO-012 `Report`、API-020 和共享 Demo runtime 中的既有事实。它不实现真实 BI、查询引擎、Excel/PDF 导出、打印、订阅、定时任务或外部数据服务。

## 2. 已确认方案

采用“DO-012 报表壳 + Runtime 只读派生指标”。

- 冻结路由、页面文件和测试文件分别为 `/reports/operations`、`src/pages/reports/OperationReportPage.tsx` 和 `e2e/ui-011-reports-operations.spec.ts`。
- DO-012 只允许 `id`、`reportType`、`period`、`generateStatus`、`metrics`、`generatedAt`；不发明 `reportId`、`version` 或 `updatedAt`。
- API-020 `GET /mock/reports` 负责 transport 和 API identity 校验。响应只证明读取链路正常，不覆盖唯一 Store 中的报表事实。
- API-021 `POST /mock/reports/export` 属于冻结 UI-011 绑定接口，但与 C11“禁止导出”的硬边界冲突。C11 保留其公共契约，不调用、不暴露按钮、不实现 Gateway 或导出任务。
- 生成/刷新由 C11 feature-local command service 实现。它使用当前 DO-012 `generatedAt` 作为乐观并发令牌，在一次原子 Store commit 中只更新目标 DO-012 的 `generateStatus`、`metrics` 和 `generatedAt`，并追加 C11 command audit。
- KPI、效率、趋势和分布均从当前唯一 Store 只读派生；页面只组合查询、投影、图表和命令反馈，不承载统计规则。

## 3. 目标

1. 实现 UI-011 统计报表页面，保留冻结 `report:view` 路由权限。
2. 展示并筛选三条冻结 DO-012 报表台账，筛选维度为 `reportType`、`period` 和 `generateStatus`。
3. 展示运行结果、作业效率、异常趋势、安全联锁和离线同步的确定性 KPI、比率与分布。
4. 实现 RS-01 查看报表、RS-02 筛选报表、RS-03 生成/刷新快照、RS-04 查看指标口径和 RS-05 错误恢复。
5. 严格校验 API-020 的 success/error envelope、`apiId`、`operationId`、`scenarioId`、Demo 时钟和 DO-012 identity。
6. 覆盖 loading、empty、forbidden、not-found、network、malformed、business-error、version-conflict 和 success feedback。
7. 产出 focused/full tests、C11 E2E、恰好 8 张截图、coverage、verification 和 C11→C12 handoff。

## 4. 非目标与禁止范围

1. 不实现真实 BI、SQL、报表引擎、Excel/PDF 导出、打印模板、订阅、定时任务或权限后台。
2. 不调用 API-021，不修改其 OpenAPI、request schema、handler 契约或公共目录。
3. 不新增依赖、fixture、Store、Provider、公共权限码、公共错误码、公共 API 或公共状态机 transition。
4. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、公共请求/响应契约、冻结 fixture 或 C10 业务实现。
5. 不写回 Plan、WorkOrder、WorkNode、DispatchException、Interlock、OfflinePacket、Recommendation 或其他上游业务对象。
6. 不把 DO-012 `metrics` 中的冻结样例数字伪装为当前 runtime 计算结果；页面当前指标始终来自 C11 派生函数。
7. 不使用真实当前时间、随机数或外部网络。所有时间来自共享 `session.demoTime` 或目标报表现有 `generatedAt`。

## 5. 权威契约与一致性结论

权威顺序固定为：

1. 用户确认的“按权威基线方案实施”及本设计。
2. C10 handoff 与最终源码中的共享 runtime 扩展模式。
3. 冻结 page-task-matrix、traceability、OpenAPI、fixture、权限目录和 Store schema。

冻结 UI-011 契约为：

```text
route       = /reports/operations
page        = src/pages/reports/OperationReportPage.tsx
pageId      = UI-011
view        = report:view
generate    = report:generate
API-020     = GET /mock/reports
operationId = GET_mock_reports
DO-012      = id, reportType, period, generateStatus, metrics, generatedAt
```

API-020 允许查询参数 `type`、`period` 和 `dimensions`。C11 页面 URL 使用更明确的 `reportType`、`period`、`generateStatus`、`reportId`、`scenarioId` 和 `from`；Gateway 把 `reportType` 映射为 API-020 的 `type`。这里的 `reportId` 只是页面选中参数，必须解析到 DO-012 `id`，不构成新的领域字段。

已解除的闸门结论：使用冻结路由和 `id`；API-021 不实施；生成/刷新留在 C11 feature-local 命令中。如果后续发现冻结字段、API identity、fixture、权限或 C10 交接事实发生新的冲突，必须再次停止，不自行猜测。

## 6. 功能单元与职责

采用与 C08-C10 一致的独立 feature slice：

```text
src/features/reporting/reportTypes.ts
src/features/reporting/reportProjection.ts
src/features/reporting/reportMetrics.ts
src/features/reporting/reportQueries.ts
src/features/reporting/reportGateway.ts
src/features/reporting/reportCommands.ts
src/features/reporting/reportRuntime.ts
src/features/reporting/index.ts
src/features/reporting/reporting.css
src/features/reporting/components/*
src/features/reporting/__tests__/*
src/pages/reports/OperationReportPage.tsx
src/pages/__tests__/OperationReportPage.*.test.tsx
src/pages/__tests__/reportingTestHarness.tsx
e2e/ui-011-reports-operations.spec.ts
```

- `reportTypes.ts`：报表台账、指标、分布、公式、workflow、Gateway 和命令结果的 feature-local 类型。
- `reportProjection.ts`：DO-012 深冻结投影、筛选、选中详情、展示标签和空态判断。
- `reportMetrics.ts`：唯一统计规则入口；从 `DemoRootState` 只读派生 KPI、比率、状态分布和公式说明。
- `reportQueries.ts`：解析并清洗页面查询参数，非法枚举值忽略，文本 trim、去控制字符并限制长度。
- `reportGateway.ts`：API-020 请求构造及 success/error、API/scenario/report identity 校验。
- `reportCommands.ts`：权限、并发令牌、派生快照、二次校验、原子 DO-012 commit、审计、幂等和错误映射。
- `reportRuntime.ts`：选中报表、筛选、口径抽屉、pending、最近反馈和 reset 后 UI 状态复位。
- `OperationReportPage.tsx`：订阅共享 Store 和 reporting workflow，调用 Gateway/command，组合各展示组件与技术状态。

## 7. 数据所有权与统计口径

### 7.1 允许写入

成功生成/刷新只允许：

- 目标 `state.report.reports[index]` 的 `generateStatus = 'SUCCESS'`。
- 目标 DO-012 的 `metrics` 更新为当前 C11 派生快照的确定性扁平数值。
- 目标 DO-012 的 `generatedAt = state.session.demoTime`。
- 追加一条 C11 command audit。
- 更新 C11 workflow 的 pending、最近反馈和本地快照序号。

命令开始时页面可显示 feature-local `RUNNING` 反馈；公共 DO-012 只在成功 commit 时写入 `SUCCESS`，失败时保持原对象完整不变。

### 7.2 严禁写入

命令前后必须对 `plan`、`recommendation`、`workOrder`、`resource`、`vehicle`、`exception`、`interlock` 和 `offline` slices 做深快照比较。API-020 响应也不得覆盖 `report` slice。

### 7.3 KPI 口径

所有页面指标带固定标识“Demo 确定性统计口径”。

| 指标 | 来源 | 公式 |
| --- | --- | --- |
| 计划总数 | DO-001 | `plan.plans.length` |
| 已确认计划数 | DO-001 | 状态属于 `CONFIRMED` 或 `DECOMPOSED` 的计划数 |
| 推荐已应用数 | C05 runtime | 用 `recommendationDraftSchema` 验证后 `status === 'CONFIRMED'` 的 draft 数；空 runtime 为 0 |
| 已生成任务数 | DO-005 | `workOrder.workOrders.length` |
| 已派工任务数 | DO-005 | 状态属于 `DISPATCHED`、`ACKNOWLEDGED`、`IN_PROGRESS`、`COMPLETED` 或 `PAUSED` 的作业单数 |
| 异常数量 | DO-009 | `exception.exceptions.length` |
| 安全联锁数量 | DO-010 | `interlock.interlocks.length` |
| 离线包已合并数量 | DO-011 | `mergeStatus === 'MERGED'` 的离线包数 |

效率指标使用安全除法，分母为 0 时结果为 0：

- 计划确认率 = 已确认计划数 / 计划总数。
- 任务拆解完成率 = 至少关联一条 DO-005 作业单的不同 `planId` 数 / 计划总数。
- 派工完成率 = 已派工任务数 / 已生成任务数。
- 异常关闭率 = `status === 'CLOSED'` / 异常数量。
- 离线同步合并率 = `mergeStatus === 'MERGED'` / 离线包总数。

分布区只根据冻结枚举聚合：计划状态、作业单状态、异常等级与类型、安全联锁动作等级、离线包状态。分布按枚举声明顺序输出，零值项保留，使截图和测试稳定。

写入 DO-012 `metrics` 的快照键固定为 `planTotal`、`confirmedPlanCount`、`appliedRecommendationCount`、`generatedWorkOrderCount`、`dispatchedWorkOrderCount`、`exceptionCount`、`interlockCount`、`mergedOfflinePacketCount`、`planConfirmationRate`、`taskDecompositionRate`、`dispatchRate`、`exceptionClosureRate` 和 `offlineMergeRate`。比率存为 0 至 100 的整数百分比；原 fixture 中的其他 metrics 键在成功刷新后由这组确定性快照整体替换，不向公共 schema 增加字段要求。

## 8. Query、筛选与选中语义

页面接受：

```text
reportId
reportType
period
generateStatus
scenarioId
from
```

- `reportId` 只用于选择 DO-012 `id`。
- `reportType`、`generateStatus` 只接受冻结枚举。
- `period` 做精确文本筛选，不解析真实日历周期。
- `scenarioId` 和 `from` 只用于 Demo 来源说明，不写入领域对象。
- 数据范围/页面权限检查先于 API 调用和对象筛选；拒绝时不泄露对象是否存在。
- 筛选结果为空显示报表筛选空态；Store 中 DO-012 为空显示数据空态，两者文案分离。

## 9. API-020 Gateway

```text
GET /mock/reports
operationId = GET_mock_reports
apiId       = API-020
```

Gateway 输入为可选 `reportType`、`period`、`dimensions` 和测试 fault。请求只发送冻结查询参数。响应必须验证：

1. HTTP、JSON 与 success/error envelope。
2. `data.apiId === 'API-020'`。
3. `data.operationId === 'GET_mock_reports'`。
4. `data.scenarioId` 与共享 Store 当前 scenario 一致。
5. `data.now` 为非空确定性 Demo 时间。
6. `data.items` 严格符合 DO-012，且请求指定 `reportId` 时必须存在同一 `id`。

network 与 malformed 使用 C11 专属测试 header，沿用 C08-C10 的内部测试注入模式；business-error 使用共享 mock runtime 的 forced failure。它们只服务确定性测试，不扩展公共 API。

## 10. 生成/刷新命令

RS-03 使用 `report:generate`。命令输入为：

```text
commandId
reportId
expectedGeneratedAt
reason
```

固定管线：

```text
捕获目标 DO-012 与上游快照
→ 页面/动作权限和数据域检查
→ reportId、reason、expectedGeneratedAt 校验
→ 只读派生当前指标
→ 二次读取目标并比较 generatedAt
→ 单次原子更新 DO-012 与 command audit
→ workflow 记录成功反馈和本地快照序号
```

- `generatedAt` 是冻结字段，因此用作乐观并发令牌；不新增 `version`。
- `expectedGeneratedAt !== current.generatedAt` 返回既有 `DEMO-VERSION-001`，不产生报表或上游写入。
- 目标不存在返回安全 not-found；非法输入/状态返回 `DEMO-SCENARIO-001`。
- 权限拒绝返回 `TOS-AUTH-001`。
- network/malformed/business-error 来自 RS-01 读取链路；本地生成命令不伪造不存在的服务端 API 错误。
- 同一 `commandId` 重放返回第一次冻结结果，不重复 commit、审计或 workflow 序号。
- reset 到 SCN-01 后报表、workflow、幂等缓存和反馈全部恢复确定性初始状态。

## 11. 页面信息架构与响应式

页面沿用现有 Ant Design 工作台视觉和已安装 ECharts，不新增依赖。结构为：

1. 顶部上下文条：报表标题、当前周期/类型/生成状态、生成时间、场景来源和“Demo 确定性统计口径”说明。
2. KPI 区：八项核心 KPI，数字、单位和来源对象一并显示。
3. 效率区：五项进度指标，显示百分比、分子/分母和公式入口。
4. 趋势/分布区：作业状态、异常、安全联锁、离线包四组简化柱状/条形图；所有图表同时提供文字值和可访问名称。
5. 台账与详情区：DO-012 表格、三项筛选、选中态、严格字段详情和派生快照摘要。
6. 操作与反馈区：生成/刷新、查看口径、reset、pending、成功/失败反馈、trace/audit 标识。
7. 口径抽屉：逐项展示来源对象、状态集合、分子、分母和公式；不展示 SQL 或生产计算承诺。

响应式规则：

- 1440×900：上下文、KPI、效率、四组分布、台账摘要和口径入口形成完整演示视图。
- 1280×720：KPI 与主要图表在首屏；台账详情和口径说明下移；图表列数和字号收紧。
- 所有宽度下禁止横向滚动、中文截断、按钮遮挡、状态标签重叠和图表裁切。

## 12. 技术状态与失败原子性

| 状态 | 展示 | 写入规则 |
| --- | --- | --- |
| loading | 报表骨架和加载提示 | 无写入 |
| empty | DO-012 空态或筛选空态 | 无写入 |
| forbidden | 403 页面态或动作拒绝 | 不调用读取 API；动作拒绝可追加拒绝审计 |
| not-found | URL 选中对象不可见/不存在 | 无写入，不泄露越权对象 |
| network-error | 读取失败与重试入口 | 无写入，Store 台账仍可恢复展示 |
| malformed-response | 契约错误与重试入口 | 无写入，不接受部分 items |
| business-error | 公共错误码、traceId、auditLogId | 无领域写入 |
| version-conflict | 刷新提示和当前 generatedAt | 无报表或上游写入 |
| success | 快照时间、审计号和确定性反馈 | 仅目标 DO-012、C11 workflow/audit |

Gateway 必须先把整个响应验证完成后再返回。命令必须在 commit 前完成权限、并发和二次对象检查；任何异常都不能产生部分写入。

## 13. TDD 与 E2E

TDD 闸门：

1. Gate A：DO-012 投影、query、筛选、KPI、比率、分布和公式。
2. Gate B：API-020 成功、error、network、malformed、API/scenario/report identity 校验。
3. Gate C：生成/刷新、权限、并发冲突、幂等、原子写入、审计、reset 和上游快照不变。
4. Gate D：页面 loading/empty/forbidden/not-found/错误/成功、按钮启停、反馈、口径说明和响应式。
5. Gate E：C11 E2E、截图、focused/full 回归、build、TypeScript 和冻结边界。

新增 `e2e/ui-011-reports-operations.spec.ts`，覆盖：

- SCN-01 总览、筛选、选中详情和口径抽屉。
- 生成/刷新成功，仅修改目标 DO-012、C11 workflow/audit。
- 权限、generatedAt 冲突、network、malformed、business-error 失败无部分写入。
- C04-C10 上游对象深快照保持不变。

## 14. 截图与证据

恰好生成 8 张截图：

```text
C11-UI011-SCN01-OVERVIEW-1440x900.png
C11-UI011-SCN01-OVERVIEW-1280x720.png
C11-UI011-SCN01-FILTERED-1440x900.png
C11-UI011-SCN01-FILTERED-1280x720.png
C11-UI011-SCN01-GENERATED-1440x900.png
C11-UI011-SCN01-GENERATED-1280x720.png
C11-UI011-SCN01-METRICS-1440x900.png
C11-UI011-SCN01-METRICS-1280x720.png
```

证据文件：

```text
docs/evidence/C11/preflight.md
docs/evidence/C11/tsc-before.txt
docs/evidence/C11/report-core-red.txt
docs/evidence/C11/report-page-red.txt
docs/evidence/C11/report-e2e-red.txt
docs/evidence/C11/screenshot-index.md
docs/evidence/C11/coverage.json
docs/evidence/C11/verification.md
docs/evidence/C11/tsc-after.txt
docs/handoffs/C11-reporting-dashboard.md
```

## 15. 最终验收

- 冻结 baseline 六份 SHA-256 为 6/6 匹配。
- `git diff --check` 通过；merge commit 数量为 0。
- C11 focused Vitest 与全量 Vitest 通过并记录实际数量。
- build、C11 Playwright 和全量 Playwright 通过并记录实际数量。
- 恰好 8 张截图，原始尺寸和视觉检查通过。
- TypeScript 既有 React/JSX 声明基线按事实分类；C11 非 React/JSX 主诊断为 0。
- baseline、依赖、公共契约、权限/状态机目录、C10 业务实现和 C04-C10 上游对象均无越界修改。
- 分支不合并、不推送，最终工作区干净。
