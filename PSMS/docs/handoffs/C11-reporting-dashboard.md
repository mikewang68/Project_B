# C11 统计报表 / UI-011 → C12 交接

## 交付结果

C11 已把 `/reports/operations` 从 scaffold 实现为确定性的运营统计报表工作台。页面提供来源与时点披露、8 项 KPI、5 项效率指标、4 组状态分布、严格 DO-012 台账与详情、13 指标公式抽屉、筛选、报表快照刷新、命令/审计反馈和场景重置。

页面覆盖 loading、Store empty、filter empty、403、not-found、API-020 network、malformed 和业务错误。API-020 故障不会清空 Store 事实；无 `report:view` 时不调用接口也不泄露对象存在性。

## 稳定入口与公共导出

模块入口为 `src/features/reporting/index.ts`，统一导出：

- 查询：`parseReportQuery`。
- 投影：`selectReportLedger`、`selectReportDashboard`。
- 指标：`deriveReportMetrics`、`REPORT_METRIC_DEFINITIONS`。
- 网关：`createReportGateway` 及 `ReportGateway`、`ReportGatewayQuery`、`ReportGatewayResult`。
- 命令：`createReportCommandService` 及 `RefreshReportInput`、`ReportCommandService`。
- UI 临时态：`createReportWorkflowStore` 及 `ReportWorkflowState`、`ReportWorkflowStore`。
- 类型：`ReportQueryContext`、`ReportMetricSnapshot`、`ReportDashboard` 等。

共享运行时暴露 `runtime.reporting.gateway`、`runtime.reporting.workflow`、`runtime.reporting.commands`；React 侧通过 `useReportWorkflow(selector)` 订阅 UI 临时态。

## 路由与查询语义

- 冻结路由：`/reports/operations`。
- 页面：`src/pages/reports/OperationReportPage.tsx`。
- 页面权限：`report:view`；刷新权限：`report:generate`。
- 对象范围：AREA-A，`*` 与 `GLOBAL` 也可见。
- 查询参数：`reportId`、`reportType`、`period`、`generateStatus`、`scenarioId`、`from`；非法枚举和控制字符输入会被安全忽略/清洗。

数据范围判断先于筛选和 API-020 调用。筛选无结果、Store 无报表、无权限和指定对象不存在分别投影为不同安全状态。

## API-020 与 API-021 边界

API-020 固定为：

- 方法与路径：`GET /mock/reports`。
- operationId：`GET_mock_reports`。
- 查询参数：`type`、`period`、重复的 `dimensions`。
- 成功身份：`apiId=API-020`、`operationId=GET_mock_reports`、当前 `scenarioId`、严格 DO-012 items。

Gateway 只验证 transport、envelope、身份和可选报告 ID。API 返回的 items 不会覆盖 Store 报表或任何上游事实。

API-021 `/mock/reports/export` 保留为冻结契约但 C11 没有实现、调用或暴露 Gateway 方法，页面也没有导出/打印控件。C12 不应把 API-021 当作 C11 已完成能力。

## DO-012、指标与公式

DO-012 只允许六个字段：

```text
id, reportType, period, generateStatus, metrics, generatedAt
```

`id` 是唯一权威标识；没有 `reportId`、`version` 或 `updatedAt`。页面和命令都通过 `do012Schema` 验证并使用深冻结副本。

8 项 KPI：计划总数、已确认计划数、推荐已应用数、已生成任务数、已派工任务数、异常数量、安全联锁数量、离线包已合并数量。

5 项效率指标统一四舍五入到整数百分比，分母为 0 时返回 0：

| 指标 | 公式 |
| --- | --- |
| 计划确认率 | `CONFIRMED 或 DECOMPOSED 计划数 / 计划总数` |
| 任务拆解完成率 | `存在作业单的不同 planId 数 / 计划总数` |
| 派工完成率 | `DISPATCHED/ACKNOWLEDGED/IN_PROGRESS/COMPLETED/PAUSED 作业单数 / 作业单总数` |
| 异常关闭率 | `CLOSED 异常数 / 异常总数` |
| 离线同步合并率 | `MERGED 离线包数 / 离线包总数` |

推荐已应用数只统计通过既有 schema 验证且 `status=CONFIRMED` 的 C05 draft。4 组分布按冻结枚举声明顺序输出并保留零值。全部指标只读共享 Store，时点使用 `session.demoTime`，不使用真实时间、随机数或外部服务。

## 刷新命令、并发与隔离

刷新输入为 `commandId?`、`reportId`、`expectedGeneratedAt`、`reason`。处理顺序是权限与范围、输入、目标、第一次 `generatedAt` 比较、提交内第二次比较、单次原子提交。

成功时只允许：

1. 更新目标 DO-012 的 `generateStatus=SUCCESS`、13 个扁平 metrics 和 `generatedAt=session.demoTime`。
2. 追加一条 action `RS-03`、objectType `DO-012` 的 C11 command audit。
3. 更新 feature-local workflow 反馈与 snapshot sequence。

第二次读取发生漂移时返回 `DEMO-VERSION-001`，不产生报表部分写入。相同 `commandId` 返回同一个冻结结果，不重复提交、审计或递增 snapshot sequence。集成和 E2E 已对 Plan、Recommendation、WorkOrder、Resource、Vehicle、Exception、Interlock、Offline 等 C04-C10 slice 做序列化前后不变断言。

`resetScenario('SCN-01')` 恢复 fixtures 中的三条报表，清空筛选、选中项、抽屉、pending、feedback、缓存和 snapshot sequence，并让 `CMD-C11-001`、`TRACE-C11-001`、`AUD-C11-001` 确定性重启。

## 验收证据

- [最终验证](../evidence/C11/verification.md)
- [覆盖率限制记录](../evidence/C11/coverage.json)
- [截图索引](../evidence/C11/screenshot-index.md)
- [TypeScript 前基线](../evidence/C11/tsc-before.txt)
- [TypeScript 后输出](../evidence/C11/tsc-after.txt)
- [端到端测试](../../e2e/ui-011-reports-operations.spec.ts)

实测：C11 聚焦 71/71、C11 Playwright 2/2、全量 Playwright 45/45、production build 2,555 modules、冻结 hash 6/6、merge 0、截图恰好 8 张且尺寸/视觉检查通过。完整 Vitest 为 718/720，只有两项 30 秒慢测 timeout；对应文件隔离复跑分别 3/3 和 11/11 通过，无断言失败或 C11 失败。

## C12 起点与已知限制

- C11 实现与 E2E 证据 HEAD：`7bb9f8c24958be79a0e41beb6dbd8d78f083d47a`。
- 最终证据/handoff 由本文件所在提交补齐；C12 应以 `demo/c11-reporting-dashboard` 的最终分支尖端为起点，精确最终 hash 见 C11 交付消息。
- 不合并、不推送；分支保持为独立线性提交链。
- TypeScript 仍受冻结依赖中的 React/JSX 声明缺口影响，C11 非 React/JSX 诊断为 0。
- 官方 Vitest V8 coverage provider 不在冻结依赖中，不能诚实报告百分比；详情见 coverage.json。
- 报表是 Demo 内确定性快照，不连接真实 BI、数据库、导出服务、打印服务或定时任务。

C12 消费 C11 时应通过 `src/features/reporting/index.ts` 或共享 Store 读取稳定投影，不依赖页面组件内部结构，不写回 C04-C10 上游对象，也不要为展示便利扩展 DO-012 字段或启用 API-021。
