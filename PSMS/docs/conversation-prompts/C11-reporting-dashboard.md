# C11 统计报表实施对话提示词

你现在开始独立实施 **C11：统计报表 / UI-011**。这是编码执行任务，不是重新讨论方案。设计规格与实施计划已经确认；使用 `superpowers:executing-plans` 按 Task 1 到 Task 8 顺序执行。除非用户明确要求，不使用子代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C10 基点：`7fb529c50059b39f088859d42f421d423e7780f7`
- 设计：`docs/superpowers/specs/2026-07-22-c11-reporting-dashboard-design.md`
- 计划：`docs/superpowers/plans/2026-07-22-c11-reporting-dashboard.md`
- 分支：`demo/c11-reporting-dashboard`

保持分支不合并、不推送。实施前确认 C10 是祖先、工作区干净、merge commit 数为 0，并执行六份冻结 hash 与 C10 回归核验。

## 二、冻结基线闸门

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一 hash 不匹配时停止，不修改、不重建、不接受新基线。

## 三、权威范围

冻结入口：

```text
route = /reports/operations
page  = src/pages/reports/OperationReportPage.tsx
e2e   = e2e/ui-011-reports-operations.spec.ts
```

只使用：

- DO-012 `Report`
- API-020 `GET /mock/reports`
- 共享 Demo runtime 的 C04-C10 现有 Store 数据

DO-012 仅包含：

```text
id
reportType
period
generateStatus
metrics
generatedAt
```

页面 query 中的 `reportId` 只映射 DO-012 `id`，不是领域字段。禁止新增 `version`、`updatedAt` 或其他 DO-012 字段。

API-021 `POST /mock/reports/export` 不调用、不实现 Gateway、不渲染按钮。禁止真实 BI、SQL、Excel/PDF 导出、打印、订阅、定时任务或外部服务。

## 四、确定性统计口径

固定标识为“Demo 确定性统计口径”。

```text
计划总数             = DO-001 数量
已确认计划数         = status 为 CONFIRMED/DECOMPOSED
推荐已应用数         = C05 draft 经 schema 验证且 status 为 CONFIRMED
已生成任务数         = DO-005 数量
已派工任务数         = DISPATCHED/ACKNOWLEDGED/IN_PROGRESS/COMPLETED/PAUSED
异常数量             = DO-009 数量
安全联锁数量         = DO-010 数量
离线包已合并数量     = DO-011 mergeStatus 为 MERGED
计划确认率           = 已确认计划数 / 计划总数
任务拆解完成率       = 有 DO-005 的不同 planId 数 / 计划总数
派工完成率           = 已派工任务数 / 已生成任务数
异常关闭率           = CLOSED / 异常数量
离线同步合并率       = MERGED / 离线包数量
```

分母为 0 时为 0%，百分比四舍五入为整数。分布按冻结枚举顺序输出并保留零值项。不得读取真实时间或随机数。

## 五、API-020

```text
GET /mock/reports
operationId = GET_mock_reports
x-api-id    = API-020
query       = type, period, dimensions
```

Gateway 严格验证 success/error envelope、apiId、operationId、scenarioId、Demo now、严格 DO-012 items 和可选 report identity。API 响应只证明 transport 正常，不覆盖 `state.report.reports`。

C11 内部测试 header 仅为 `x-demo-c11-fault: network|malformed`，只作用于 API-020；业务错误使用共享 forced failure。不得修改公共契约。

## 六、生成/刷新命令

权限：页面 `report:view`，动作 `report:generate`。

输入：

```ts
{
  commandId: string;
  reportId: string;
  expectedGeneratedAt: string;
  reason: string;
}
```

固定顺序：

```text
捕获目标与上游快照 -> 页面/动作权限和数据域 -> 请求校验
-> 只读派生指标 -> 二次 generatedAt 比较
-> 单次原子更新目标 DO-012 和 C11 audit -> workflow 反馈
```

`generatedAt` 是冻结并发令牌；不新增 version。成功只将目标 DO-012 写为 `generateStatus=SUCCESS`、当前派生 `metrics`、`generatedAt=session.demoTime`，并追加 C11 audit。相同 commandId 不重复 commit、审计或 workflow 序号。

权限拒绝为 `TOS-AUTH-001`，并发冲突为 `DEMO-VERSION-001`，非法请求为 `DEMO-SCENARIO-001`。失败不得修改目标报表或任何上游对象。

## 七、页面要求

支持 query：`reportId`、`reportType`、`period`、`generateStatus`、`scenarioId`、`from`。

页面必须包含：

- 顶部周期、类型、状态、生成时间、场景来源和 Demo 口径说明。
- 八项 KPI 与五项效率。
- 作业状态、异常、安全联锁、离线包四组分布。
- DO-012 台账、三项筛选、严格字段详情和选中报表快照。
- 生成/刷新、查看指标口径、reset、pending 和 trace/audit 反馈。
- loading、Store empty、filter empty、forbidden、not-found、network、malformed、business-error、version-conflict、success。

1440×900 形成完整演示视图；1280×720 首屏保留 KPI 和主要图表，明细与口径下移。不得横向滚动、裁切、重叠、中文缺字或图表截断。图表必须提供文本摘要和可访问名称，减少动态偏好下禁用动画。

## 八、TDD、E2E 与证据

严格先红后绿：

- `docs/evidence/C11/report-core-red.txt`
- `docs/evidence/C11/report-page-red.txt`
- `docs/evidence/C11/report-e2e-red.txt`

每个新函数先写测试并确认因功能缺失而失败，再写最小实现；失败原因错误时先修测试。每个任务 GREEN 后运行相邻回归并按计划提交。

E2E 覆盖：总览、筛选/详情/口径、刷新成功且仅写 DO-012/C11 audit/workflow、权限/并发/API 错误无部分写入、reset 确定性和上游深快照不变。

只保存以下 8 张截图：

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

逐张检查原始尺寸、溢出、裁切、文字、按钮、KPI 和图表可见性。

## 九、最终验收

必须执行并如实记录：C11 focused Vitest、全量 Vitest、build、C11 Playwright、全量 Playwright、TypeScript、六 hash、`git diff --check`、merge count、禁止范围、API-021 无实现和截图尺寸。

最终创建：

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

最终报告分支、HEAD、干净状态、完整提交链、focused/full 验证、8 张截图、证据路径、C11→C12 handoff 和已知限制。不合并、不推送。
