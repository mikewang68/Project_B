# C06 任务拆解交接

日期：`2026-07-21`

分支：`demo/c06-task-decomposition`

## 1. 完成范围

C06 已完成现有 `UI-004 /dispatch/plans/:planId/tasks` 的可演示任务拆解工作台，覆盖确定性自动拆解、拆分、合并、重新生成和确认任务定义。实现继续使用唯一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store；没有创建第二套 Store、Provider 或 fixture，也没有实现 UI-005 正式派工。

## 2. Runtime 公共入口

`createDemoRuntime()` 返回的公共对象新增并稳定暴露：

- `runtime.taskDecomposition.gateway`：`TaskDecompositionGateway`，只负责 API-007 传输和严格 envelope 校验。
- `runtime.taskDecomposition.workflow`：`TaskDecompositionWorkflowStore`，只保存 `selectedNodeIds`、编辑抽屉、规则面板、`mode`、`targetNodeId`、`reason` 和最近命令错误。
- `runtime.taskDecomposition.commands`：`TaskDecompositionCommandService`，提供 `generateTasks`、`splitTask`、`mergeTasks`、`regenerateTasks`、`confirmTasks` 和内部 reset 能力。
- `useTaskDecompositionWorkflow(selector)`：通过 `useSyncExternalStore` 订阅同一个 workflow；领域真值仍通过 `useDemoSelector` 从唯一 Store 读取。

API-025 成功 reset 后，runtime 同时清空 C05/C06 workflow、C06 幂等缓存、暂存候选和确定性序号；下一次 C06 生成重新从 `G001`、`CMD-C06-001`、`TRACE-C06-001`、`AUD-C06-001` 开始。

## 3. 规则、所有权和图约束

规则版本固定为 `C06-DEMO-RULE-1.0`。

- C06 WorkOrder 所有权要求同一 `planId`、`id` 与 `workOrderNo` 均以 `C06-WO-` 开头，且 `ruleVersion` 精确匹配。
- C06 WorkNode 所有权要求 `id` 以 `C06-NODE-` 开头、`nodeNo` 以 `C06-N-` 开头。
- 既有 `WO-001`～`WO-012`、`NODE-001`～`NODE-012` 永不进入 C06 替换集合。
- 每条 C06 WorkOrder 精确对应一条 WorkNode；校验 ID/编号唯一、连续 sequence、单根、合法直接 parent、无孤儿、无循环和一对一配对。
- `WorkOrder.parentId` 只表达单一直接上游，不是多父 DAG。
- PLAN-001 的 `G001` 使用稳定 ID `C06-WO-PLAN-001-G001-01`～`04` 及对应节点，08:01～10:01 被确定性分为四段 30 分钟；不读取系统时钟或随机数。

## 4. 四类货物路线与 Demo 稳定映射

| cargoType | 四阶段路线 | 所需资源类型 |
| --- | --- | --- |
| `FLY_ASH` | 识别与路由确认 → 卸料准备 → 输送转运 → 筒仓入库 | TEAM → TIPPER → CONVEYOR → SILO |
| `CEMENT` | 识别与路由确认 → 卸料准备 → 输送转运 → 应急筒仓入库 | TEAM → TIPPER → CONVEYOR → SILO |
| `STEEL` | 规格重量校验 → 重载吊装 → AGV 转运 → 货位入库 | TEAM → CRANE → AGV → TEAM |
| `GENERAL_CARGO` | 箱号包装校验 → 掏装/卸载 → 分拣转运 → 入库或发运准备 | TEAM → CRANE → AGV → TEAM |

冻结对象没有 Plan→Waybill/Material 外键。页面只按相同 `cargoType` 和稳定 ID 顺序形成明确标注的 `DEMO_STABLE_MAPPING`，不宣称为生产外键。`GENERAL_CARGO` 明示冻结枚举无法继续区分机电设备和生活物资；AT-TOS-004 的 160 箱只作为验收基准展示，不生成 160 个对象，也不伪装为本计划实际箱量。

## 5. Selectors 与资源边界

三个冻结主 selector 为：

- `selectCargoSummary(state, planId)`
- `selectRuleExplanation(state, planId)`
- `selectTaskTree(state, planId)`

补充 `selectResourcePreview(state, planId)` 展示所需资源类型和 AREA-A 真实候选实例状态。AREA-A/GLOBAL/`*` 才可见；越界对象在查找、严格解析或计数前被隐藏。页面不会借用 AREA-B/C 资源、不会把 `MAINTENANCE`/`LOCKED` 宣称为可分配，也不会创建资源实例。

## 6. API-007 契约

- 方法与路径：`POST /mock/plans/{planId}/decompose`
- body 只能是 `{"ruleVersion":"C06-DEMO-RULE-1.0","mode":"AUTO|SPLIT|MERGE|REGENERATE|CONFIRM"}`。
- `targetNodeId`、`selectedNodeIds`、`reason`、各类版本、`generationVersion`、actor 和 `businessAction` 仅存在于命令 payload，绝不发给 API-007。
- 成功响应严格要求 `apiId=API-007`、`operationId=POST_mock_plans_id_decompose`、合法 `scenarioId`/`now`、且 `items` 恰好包含一个同 ID Plan。
- API 返回的 Plan/WorkOrder/WorkNode 不覆盖 Store；Gateway 无领域写入能力。失败 envelope 原样严格解析为公开错误。

## 7. 五个动作与一致性保证

| 动作 | mode | 权限 | 结果 |
| --- | --- | --- | --- |
| `TD-01` | `AUTO` | `task:decompose` | 生成四条 C06 `DRAFT/WAITING` 草稿；已有草稿时拒绝重复追加并提示 TD-04。 |
| `TD-02` | `SPLIT` | `task:edit` | 要求非空 reason，将目标阶段确定性替换为 A/B 直接链并重连下游。 |
| `TD-03` | `MERGE` | `task:edit` | 只允许两个同类型、同对象、相邻且直接依赖的节点，记录合并 provenance。 |
| `TD-04` | `REGENERATE` | `task:decompose` | 仅替换当前 Plan 的 C06 DRAFT 代次，`G001` 后生成 `G002`；历史审计保留。 |
| `TD-05` | `CONFIRM` | `task:decompose` | 原子执行 Plan `CONFIRMED→DECOMPOSED`、C06 WorkOrder `DRAFT→READY`；WorkNode 保持 `WAITING`。 |

所有动作均使用冻结 permissionCatalog。命令在权限/验证前、API 后以及 commit 内核验 Plan、RecommendationDraft、AREA-A Resource、参与 WorkOrder/WorkNode 版本；漂移统一返回 `DEMO-VERSION-001`，不产生部分写入并保留可重试输入。相同 `commandId` 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或审计。每次成功、拒绝或失败恰好追加一条 C06 审计，时间只使用 `session.demoTime`，不覆盖 C04/C05 账本。

## 8. READY、联锁和后续边界

`READY` 只表示“任务定义及所需资源类型已满足，可进入派工”。C06 不分配资源实例，因此 TD-05 后 `resourceId` 和 `teamId` 仍为空；UI 明示“待 UI-005 分配”。

`INTERLOCK_FORCE_STOP`（SCN-05）在 API 前保守阻断所有 C06 写动作并返回 `TOS-IL-001`。UI-004 提供 `/safety/interlocks?scenarioId=...&planId=...&from=task-decomposition` 的 UI-009 入口，但 C06 不解除或覆盖联锁。

UI-005 保持既有骨架。C06 未实现资源实例分配、工单下发、接单、执行、API-008 或 API-009。

## 9. 页面状态、进度和直达刷新

页面覆盖六种通用状态：`loading`、`empty`、`business-error`、`network-error`、`forbidden`、`not-found`；覆盖四种业务进度：`EMPTY`、`GENERATED`、`EDITED`、`READY`。

直达 `/dispatch/plans/:planId/tasks?scenarioId=...` 仍由唯一 runtime 构建场景真值。缺少已确认 C05 RecommendationDraft 时显示公开业务错误并提供 UI-003 返回入口；越权、越域或未知 Plan 使用安全的 forbidden/not-found 表达，不泄露对象存在性。成功 TD-05 后提供 C07 入口：

`/dispatch/work-orders?planId={planId}&scenarioId={scenarioId}&from=task-decomposition`

## 10. 验证与证据

- C06 focused Vitest：`17/17` 文件、`228/228` 测试通过。
- 全量 Vitest：默认高并发命令在既有 `PlanLedgerPage.render` 30 秒单测超时；未修改 timeout/config。相同全量集合以 `--maxWorkers=1` 运行，`53/53` 文件、`447/447` 测试通过。
- build：`1,892 modules` 通过。
- C06 Playwright：`4/4` 通过；全量 Playwright：`27/27` 通过。
- 全局 TypeScript：因冻结依赖缺少 React/JSX 声明仍失败；最终只有 `TS2604=23`、`TS7016=73`、`TS7026=317`，共 `413` 条，C06 非 React primary diagnostics 为 `0`。
- 六份冻结基线：`6/6` 哈希一致。
- 截图：`8/8` 完成原始分辨率检查。

证据入口：

- `docs/evidence/C06/coverage.json`
- `docs/evidence/C06/verification.md`
- `docs/evidence/C06/tsc-after.txt`
- `docs/evidence/C06/screenshot-index.md`
- `e2e/ui-004-task-decomposition.spec.ts`

## 11. 已知限制

- 冻结数据没有本计划实际箱数，160 箱仅为 AT-TOS-004 验收基准。
- 任务依赖为单 parent 直接链，不支持多父 DAG。
- C06 只预览资源类型和真实候选状态，不创建或分配资源实例。
- UI-005 仍是骨架；正式派工由 C07 从上述 `/dispatch/work-orders` 查询入口继续实现。
