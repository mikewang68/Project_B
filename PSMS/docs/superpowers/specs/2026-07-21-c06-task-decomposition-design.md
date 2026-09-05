# C06 任务拆解设计规格

## 1. 背景与冻结点

C05 已在 `demo/c05-recommendation` 完成 UI-003 接车计划推荐闭环，最终提交为 `83363ff18bf606150d6d03a3248046c6bc0f1dfd`。C06 从既有路由 `/dispatch/plans/:planId/tasks` 接手，把 UI-004 从页面骨架实现为可演示的任务拆解、人工编辑和工单草稿确认闭环。

C06 的起始验证基线为：

- C05 focused Vitest：16 个文件、97 个测试通过。
- 全量 Vitest：42 个文件、322 个测试通过。
- Build：通过，1,875 modules。
- C05 Playwright：4/4；全量 Playwright：23/23。
- 全局 TypeScript：因冻结依赖未包含 React 类型声明，保留 360 条 TS2604/TS7016/TS7026 根因及级联诊断；C05 非 React primary diagnostics 为 0。
- C05 截图：8/8 已按原始分辨率检查。
- 工作区：干净。

六份机器基线继续冻结：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

## 2. 已确认方案

### 2.1 采用：同一 Store 中的 C06 专属任务版本

API-007 负责证明动作已按冻结契约到达 Mock 边界。只有严格响应校验和二次版本校验成功后，C06 纯规则引擎才根据同一 Store 中的 Plan、C05 已确认 RecommendationDraft、资源和确定性演示时间生成或修改任务草稿。

C06 生成严格 DO-005 WorkOrder 与 DO-006 WorkNode，并追加到既有 `workOrder` slice。生成记录使用独立 ID 前缀和固定规则版本，既有 `WO-001`～`WO-012`、`NODE-001`～`NODE-012` 保持不变。页面和 UI-005 只通过 selector 读取 C06 所属记录，不复制 fixture，也不建立第二个 Store。

### 2.2 未采用：改写既有 PLAN-001 工单

PLAN-001 已有关联 DRAFT、ACKNOWLEDGED、PAUSED 和 CANCELLED 工单，C05 也把这些对象用于影响分析。改写它们会破坏既有历史事实、C05 交接和回归测试，因此禁止。

### 2.3 未采用：只在页面 workflow 保存草稿

页面临时状态无法支持刷新、跨页面读取、原子 reset 和版本冲突验证。workflow 只保存选中节点、抽屉开关、表单草稿和最近错误；领域任务必须存在于 Store。

## 3. 目标与非目标

### 3.1 目标

1. 实现 UI-004 页面内容，保留既有路由、权限边界和 C01 烟测标记。
2. 实现自动拆解、拆分节点、合并节点、重新生成和确认工单草稿五种动作。
3. 展示计划、C05 已确认股道和窗口、货物摘要、规则解释、任务树、依赖、资源类型预览和审计反馈。
4. 严格调用 API-007，不新增 endpoint，不扩展或放宽冻结请求 Schema。
5. 复用唯一 DemoRuntime、C03 Store、权限、命令执行器、状态机、追加写审计、确定性时钟和 API-025 reset。
6. 确认后把 Plan 变为 `DECOMPOSED`，把 C06 WorkOrder 变为 `READY`，锁定 UI-004 编辑并开放 UI-005 入口。
7. 覆盖 SCN-01 正常整列、AT-TOS-004 高峰任务拆解基准、拒绝边界和视觉证据。

### 3.2 非目标

1. 不实现 UI-005 的资源实例分配、工单下发、接单或执行业务。
2. 不实现生产优化求解器、实时容量计算、设备控制指令或真实 WMS/ECS/95306 对接。
3. 不新增业务枚举、公共错误码、API 字段、根 Store slice、依赖或第二套 fixture。
4. 不生成 160 条虚假集装箱或工单对象，不把 AT-TOS-004 验收基准冒充当前 Plan 实际箱量。
5. 不覆盖既有历史 WorkOrder、WorkNode、Resource、Plan 或 C05 RecommendationDraft。
6. 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml` 或 UI-005 主体业务。

## 4. 权威资料与冲突规则

实施按以下优先级判定：

1. 用户本轮确认的完整但收敛范围、方案一和三段设计。
2. 本设计规格及后续 C06 实施计划。
3. `docs/handoffs/C05-recommendation.md` 与 C05 最终源码。
4. 页面任务卡 v0.2 中 UI-004、开发任务分解 v0.2 中 TASK-P2-UI004。
5. 需求分析文档 FR-002-01、FR-002-02、FR-002-03、US-02、US-06、任务拆解规则表和货类路由表。
6. 六份冻结机器基线与 C02/C03 公共契约。

发生冲突时不得修改冻结基线。冻结对象没有 Plan 到 Waybill/Material 的外键，也没有实际箱量、安全等级或多父依赖字段，因此采用以下明确规范化规则：

- 货票和物料只按相同 `cargoType`、稳定 ID 顺序形成“Demo 稳定映射”，页面必须标注，不宣称生产关联。
- WorkOrder.parentId 表示单一直接上游；C06 不实现多父 DAG。
- Task 的作业对象、阶段和资源类型从 Plan.cargoType、WorkOrder.type、ID 和规则版本稳定投影，不向 DO-005/006 添加字段。
- AT-TOS-004 的 2 列、80 节、160 箱只作为高峰验收基准和聚合波次解释，不作为当前 Plan 的事实字段。
- `GENERAL_CARGO` 同时承接冻结枚举无法再细分的机电设备与生活物资；页面说明此 Demo 边界。

## 5. 页面范围与视觉结构

UI-004 路由保持：

```text
/dispatch/plans/:planId/tasks
```

页面沿用 C04/C05 的 Ant Design 调度工作台视觉语言，只使用现有组件和简单 CSS。

### 5.1 顶部上下文

- 面包屑：调度总览 / 计划接收台账 / 接车计划推荐 / 任务拆解。
- 返回 UI-003，保留允许的 `scenarioId`、筛选和来源意图 query，不在 URL 传业务对象。
- `PlanSummaryCard`：计划编号、批次、车次、货类、到发时间、状态和版本。
- `CargoSummary`：权威 Plan 货类、同货类 Waybill/Material 稳定映射、来源标签、缺失字段提示。
- C05 确认摘要：最终股道、接车窗口、推荐规则版本、确认人和 traceId。
- 高峰基准条：2 列、80 节、160 箱，明确标注“验收基准，不是本计划实际箱量”。

### 5.2 主工作区

- 左侧 `TaskTree`：节点编号、阶段、作业类型、派生作业对象、直接上游、规则版本、状态和版本。
- 可选 `DependencyGraph` 仅使用 CSS/Ant Design 线性或树形表达，不引入图形库；复杂图不可成为动作执行前提。
- 右侧 `RuleExplainPanel`：货类路由、输入来源、匹配规则、排除条件、Demo 稳定映射和未提供数据。
- `ResourcePreview`：展示每一阶段所需资源类型、AREA-A 当前候选及状态，只作类型可满足性预览，不下发实例。
- `TaskEditDrawer`：mode、目标节点、原因、受影响节点、预期版本和风险提示。
- 底部命令区：自动拆解、拆分、合并、重新生成、确认草稿、命令结果、trace 和 audit 信息。

### 5.3 查询与响应式

- `planId` 文本筛选，可选。
- `cargoType` 单选，支持 ALL。
- `status` 多选。
- 1440×900 使用任务树与说明/资源左右布局。
- 1280×720 将右侧内容下移或使用抽屉，页面不得横向滚动；树、表单和确认按钮不得裁切。

## 6. C06 领域所有权与严格投影

规则版本固定为：

```text
C06-DEMO-RULE-1.0
```

C06 所属记录同时满足：

- WorkOrder.id 和 workOrderNo 以 `C06-WO-` 开头；
- WorkNode.id 以 `C06-NODE-` 开头，nodeNo 以 `C06-N-` 开头；
- WorkOrder.ruleVersion 等于 `C06-DEMO-RULE-1.0`；
- WorkOrder.planId 等于当前 planId。

每个页面任务节点由一条 C06 WorkOrder 和一条同 workOrderNo 的 C06 WorkNode 投影。WorkOrder.parentId 表示直接上游 WorkOrder.id；根节点 parentId 为空。WorkNode.sequence 必须是从 1 开始的连续正整数。自动生成、拆分、合并和重新生成后都必须通过 DO-005/006 严格 Schema、唯一 ID、无孤儿、无循环、连续 sequence 和一对一关联校验。

规则解释、派生 objectId、所需资源类型、货票/物料 sourceRefs 和货类阶段名称由纯 selector/rule projection 计算，不塞入严格领域对象。人工原因存在于命令 payload 和追加写审计，不复制到标题或 blockReason。

既有 PLAN-001 历史工单仍由普通 selector 可见，但 UI-004 当前任务树只显示 C06 所属记录。C06 的删除仅允许删除尚未确认的 C06 DRAFT 记录；任何既有记录或 READY 及后续状态记录都不得删除。

## 7. 确定性任务拆解规则

### 7.1 输入资格

自动拆解前必须满足：

- Plan 经 AREA-A 数据域过滤后存在，状态为 `CONFIRMED`，`missingFields` 为空。
- 同 planId 的 C05 RecommendationDraft 通过严格 Schema，状态为 `CONFIRMED`，且 selectedCandidate 存在。
- Plan.version、RecommendationDraft.draftVersion 和参与规则计算的 Resource.version 已形成命令快照。
- 活动场景不是 `INTERLOCK_FORCE_STOP`。
- API-007 使用当前规则版本和 `mode=AUTO` 返回严格成功信封，且唯一 Plan item 与 path planId 匹配。

### 7.2 货类模板

规则引擎对冻结四种 cargoType 生成稳定线性任务链：

| cargoType | 阶段与 WorkOrder.type | 资源类型预览 |
| --- | --- | --- |
| FLY_ASH | 识别与路由确认 INSPECT → 卸料准备 UNLOAD → 输送转运 TRANSFER → 筒仓入库 LOAD | TEAM、TIPPER、CONVEYOR、SILO |
| CEMENT | 识别与路由确认 INSPECT → 卸料准备 UNLOAD → 输送转运 TRANSFER → 应急筒仓入库 LOAD | TEAM、TIPPER、CONVEYOR、SILO |
| STEEL | 规格重量校验 INSPECT → 重载吊装 UNLOAD → AGV 转运 TRANSFER → 货位入库 LOAD | TEAM、CRANE、AGV、TEAM |
| GENERAL_CARGO | 箱号包装校验 INSPECT → 掏装/卸载 UNLOAD → 分拣转运 TRANSFER → 入库或发运准备 LOAD | TEAM、CRANE、AGV、TEAM |

引擎只使用输入，不访问 Store、Gateway、React、系统时钟或随机数。ID、时间和顺序由 planId、规则版本、generationVersion 和确定性计数器生成。

### 7.3 资源预览与 READY 语义

冻结 AREA-A 资源不足以为所有粉体阶段提供 AVAILABLE 实例，因此 C06 不伪造资源，也不越域借用 AREA-B/C 实例。资源校验分为两层：

1. C06 确认只校验每个阶段的所需资源类型在冻结资源目录中存在，形成“类型可满足”结论；当前实例不可用时显示候选状态和 UI-005 后续分配提示。
2. C06 WorkOrder 确认后进入 `READY`，表示任务定义已准备好进入派工；`resourceId` 和 `teamId` 保持严格 Schema 允许的空字符串，实例分配由 UI-005 完成。

该规范化选择遵循 UI-004 “ResourcePreview 只预览、不下发”和 TD05 “资源类型可满足后进入 READY”。它不宣称资源已占用或已下发。UI-005 后续从 READY 执行正式派工；C06 不提前进入 DISPATCHED。

## 8. 五种业务动作

五种动作都复用 C03 `createCommandExecutor` 和 SM-007 技术命令；业务动作写入审计为 TD-01～TD-05。

API-007 body 始终严格为：

```text
{ ruleVersion: "C06-DEMO-RULE-1.0", mode: "AUTO|SPLIT|MERGE|REGENERATE|CONFIRM" }
```

targetNodeId、selectedNodeIds、reason、版本、actor 和 generationVersion 只存在于 C06 command payload，不发送给冻结 API。

### 8.1 TD-01 自动拆解

- 要求尚无 C06 READY 记录；若已有 DRAFT，返回可恢复业务提示并引导重新生成。
- API-007 成功后生成四阶段 DRAFT WorkOrder/WorkNode，单次原子追加到 workOrder slice。
- Plan 和 C05 草稿保持不变，页面进入 GENERATED 状态。

### 8.2 TD-02 拆分节点

- 只能选择一个 C06 DRAFT、未执行、非 INSPECT 的节点。
- reason 必填。
- 用两个相同 WorkOrder.type 的顺序节点替换目标节点；第一个继承原上游，第二个以上一个为 parent，原下游改指向第二个。
- 严格 Schema、无循环、无孤儿和版本校验失败时不提交。

### 8.3 TD-03 合并节点

- 只能合并两个相邻、直接串联、相同 type、相同派生作业对象、相同规则版本的 C06 DRAFT 节点。
- reason 必填。
- 冻结 DO-005 只支持单一 parent，因此不实现任意兄弟或多父节点合并；这项窄化必须在页面禁用原因中说明。
- 合并后保留第一节点的上游和第二节点的下游，重新编号并原子替换。

### 8.4 TD-04 重新生成

- 只能操作未确认的 C06 DRAFT 记录。
- 二次确认明确说明当前人工编辑版本将被丢弃；审计历史仍保留。
- API-007 成功后删除当前 planId 的 C06 DRAFT 记录，使用当前 Store 真值重建系统建议，generationVersion 增加 1。

### 8.5 TD-05 确认工单草稿

- 校验 C05 推荐仍为 CONFIRMED、计划仍为 CONFIRMED、所有 C06 记录仍为 DRAFT、规则版本有效、版本快照一致、图无孤儿/循环、类型资源目录可满足。
- 通过 DO-001 的 `CONFIRMED + decompose → DECOMPOSED` 验证计划迁移；每条 DRAFT WorkOrder 通过既有 `DRAFT + assign → READY` 迁移验证。这里的 assign 是第 7.3 节定义的“资源类型预分配”，不写资源实例。
- API-007 成功后二次核验全部版本，一次 Store commit 同时更新 Plan、全部 C06 WorkOrder/WorkNode 版本和时间；WorkNode 保持 WAITING，等待 UI-005 正式派工。
- 成功后页面锁定编辑并显示精确 UI-005 路径 `/dispatch/work-orders`；query 只携带 planId、scenarioId 和来源意图。

## 9. 运行架构与组件边界

### 9.1 TaskDecompositionGateway

新增一个严格方法：

```text
decomposePlan(planId, { ruleVersion, mode }) -> API-007
```

Gateway 验证 HTTP、请求 Schema、成功/失败信封、apiId、operationId、scenarioId、now 和匹配 Plan item。冻结 handler 对 pathId 的过滤使 API-007 响应只包含匹配 Plan；DO-005/006 不能作为响应事实使用。Gateway 返回对象只证明调用和身份，不覆盖 Store。

### 9.2 TaskDecompositionRuleEngine

纯函数负责货类模板、ID、阶段、上游、时间、资源类型需求、稳定映射和规则解释。它输出可被 DO-005/006 Schema 接受的候选对象以及页面 projection，不执行写入。

### 9.3 三个任务卡主 selector

精确实现：

1. `selectCargoSummary`
2. `selectRuleExplanation`
3. `selectTaskTree`

可增加 `selectResourcePreview`、`selectTaskCommandContext` 等组合 selector，但不得复制主规则。所有 selector 在对象读取、计数和关联前先应用 AREA-A 边界；返回值深冻结。

### 9.4 TaskDecompositionWorkflow

workflow 只保存：

```text
selectedNodeIds
editDrawerOpen
rulePanelOpen
mode
targetNodeId
reason
lastCommandError
```

WorkOrder、WorkNode、Plan、RecommendationDraft、资源列表和规则结果不进入 workflow。

### 9.5 DemoRuntime

`DemoRuntime` 新增：

```text
runtime.taskDecomposition.gateway
runtime.taskDecomposition.workflow
runtime.taskDecomposition.commands
```

创建顺序仍是一个 Store，再把同一 Store 交给 C04、C05 和 C06 服务。API-025 成功 reset 同时清空 C05/C06 workflow、C06 幂等与暂存计算缓存、确定性 ID 序号；Store reset 从冻结 fixture 恢复，因此 C06 追加记录自然消失。

## 10. 权限、版本、幂等与审计

### 10.1 权限

- 页面：`task:view`。
- TD-01、TD-04、TD-05：`task:decompose`。
- TD-02、TD-03：`task:edit`。
- 冻结 permissionCatalog 允许 DISPATCHER 和 SHIFT_LEADER；C06 不擅自收窄或扩大。
- AREA-A、GLOBAL 或 `*` 可读；其他数据域在对象查找和计数前返回 not-found/空投影，不泄露存在性。

### 10.2 版本与原子性

命令保存 Plan.version、RecommendationDraft.draftVersion、所有 C06 WorkOrder/WorkNode.version 和参与 Resource.version。授权前、Mock 后和 commit 内均重新核验。任何变化返回 `DEMO-VERSION-001`，保留 workflow 输入且不留下部分写入。

成功动作必须只有一次 `replaceDomainState`。失败、拒绝、Gateway 异常、malformed envelope、二次版本冲突和 commit 校验失败不得改变 Plan 或 workOrder slice。

### 10.3 幂等与审计

- 相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或 audit。
- 成功、失败和拒绝各追加恰好一条 C06 审计；不覆盖 C04/C05 账本。
- 审计 action 为 TD-01～TD-05，保留 planId、目标节点、reason、前后版本、actor、traceId、结果和错误码。
- ID、trace、audit 和时间只使用可重置序号与 `session.demoTime`，禁止 Date/random。

## 11. 失败处理与安全边界

| 条件 | 结果 | Store/UI 行为 |
| --- | --- | --- |
| C05 推荐不存在或未确认 | `DEMO-SCENARIO-001` | 不调用 API，返回 UI-003 修复 |
| Plan 缺失、未确认或存在 missingFields | `DEMO-SCENARIO-001` | 不生成可执行任务 |
| AREA-A 越界或权限不足 | `TOS-AUTH-001` | 不读取业务详情，不调用 API |
| 活动 fault 为 INTERLOCK_FORCE_STOP | `TOS-IL-001` | 全部写动作阻断，提供 UI-009 入口，不允许覆盖 |
| 版本变化 | `DEMO-VERSION-001` | 保留表单，要求刷新或重新生成 |
| 规则版本无效、孤儿或循环 | `DEMO-SCENARIO-001` | 保留当前合法草稿，显示具体节点 |
| API-007 业务失败 | 原公共错误码 | 不提交，追加 FAILED 审计 |
| fetch 或 malformed envelope | network-error | 不提交，允许原动作重试 |

SCN-04 设备离线没有冻结 Plan/Resource 的直接关联，C06 只展示资源目录实际状态，不把无关联的 WO-005 fault 错绑到 PLAN-001。SCN-05 `INTERLOCK_FORCE_STOP` 是全局安全演示状态，C06 在命令层保守阻断全部拆解写动作。

## 12. 页面状态与跨页行为

六类技术/业务状态固定为：

| 状态 | 页面行为 | 恢复 |
| --- | --- | --- |
| loading | 页面骨架，写动作禁用 | 等待 Store/bootstrap/API |
| empty | 尚未自动拆解或过滤后无节点 | 执行 TD-01 或清除筛选 |
| business-error | 推荐未确认、计划不完整、图非法或类型资源缺失 | 显示明确修复入口 |
| network-error | API-007 transport/信封失败，保留草稿和表单 | 重试原动作 |
| forbidden | 403，不加载对象或 API | 切换合法会话 |
| not-found | planId 不存在或数据域越界 | 返回 UI-002 |

业务进度状态另分为 EMPTY、GENERATED、EDITED、READY。READY 页面只读，展示计划 DECOMPOSED、工单 READY、规则版本、确认审计和 UI-005 入口。

从 UI-003 进入时必须复用同一 SPA runtime 和已确认 RecommendationDraft。直接刷新若 bootstrap 恢复到未确认 fixture，则显示 business-error 并引导重新完成 UI-002/UI-003，不从 URL、localStorage 或 API 旧对象伪造前置状态。

异常提示允许链接 UI-008/UI-009；query 只携带对象 ID、筛选和意图，不携带完整对象。

## 13. 文件边界

允许新建：

- `src/features/task-decomposition/**`：类型、规则、selector、Gateway、workflow、commands、组件、样式和测试。
- `e2e/ui-004-task-decomposition.spec.ts`：使用 Windows 合法文件名；冻结矩阵中的冒号仅视为路由占位表示。
- `docs/evidence/C06/**`、`docs/handoffs/C06-task-decomposition.md`。

允许窄修改：

- `src/runtime/DemoRuntimeContext.tsx` 与 runtime 导出：注入 C06 服务并接入 reset。
- `src/pages/dispatch/TaskDecompositionPage.tsx`：替换骨架。
- C05 公共导出：只为严格读取 RecommendationDraft 增加稳定入口。
- C01 route smoke 与相关页面测试：保持 UI-004 精确标记。

禁止修改六份 baseline、依赖/锁文件、公共 API Schema、错误码、permissionCatalog、状态机 catalog、其他页面主体和 UI-005 业务。

## 14. TDD 闸门

### Gate A：任务内核与契约

- 先保存 `docs/evidence/C06/task-core-red.txt`。
- 覆盖 C06 所有权、货类模板、严格 DO-005/006、三主 selector、资源类型预览、API-007 Gateway、模式请求、AREA-A 前置过滤和 AT-TOS-004 基准声明。
- C02/C03/C05 回归全绿后才能进入 Gate B。

### Gate B：命令与状态

- 先保存 `docs/evidence/C06/task-command-red.txt`。
- 覆盖 TD-01～TD-05、Plan/WorkOrder 状态迁移、拆分重接、合并限制、重新生成、版本、原子性、幂等、追加写审计、联锁阻断和 reset。
- 验证 API-007 body 不包含 targetNodeId、reason 或版本字段。

### Gate C：页面与跨页

- 先保存 `docs/evidence/C06/task-page-red.txt`。
- 覆盖页面摘要、任务树、规则说明、资源预览、筛选、抽屉、六类状态、EMPTY/GENERATED/EDITED/READY、UI-003 返回和 UI-005 入口。
- 覆盖 DISPATCHER/SHIFT_LEADER、403、AREA-A 和直接刷新。

### Gate D：确定性演示证据

- 先保存 `docs/evidence/C06/task-e2e-red.txt`。
- 完成恰好 4 条 C06 E2E、8 张截图、coverage、verification、handoff 和全量回归。
- Gate D 全绿前不得宣称 C06 完成。

## 15. E2E 与视觉验收

恰好新增 4 条 C06 E2E：

1. SCN-01：从 UI-002 确认 PLAN-001、UI-003 确认首选，再进入 UI-004 自动拆解、确认 READY 并打开 UI-005 入口。
2. SCN-01：生成后拆分目标节点、合并恢复、再次拆分并重新生成，验证原因、版本和审计。
3. 权限、AREA-A、版本变化和 commandId 重放，验证不调用/不重复 API 与无部分提交。
4. API-007 network/malformed 失败和 SCN-05 联锁阻断，验证草稿保留、UI-009 入口与恢复后重试。

保存并按原始分辨率检查恰好 8 张截图：

```text
C06-UI004-SCN01-GENERATED-1440x900.png
C06-UI004-SCN01-GENERATED-1280x720.png
C06-UI004-SCN01-EDITED-1440x900.png
C06-UI004-SCN01-EDITED-1280x720.png
C06-UI004-SCN01-REGENERATED-1440x900.png
C06-UI004-SCN01-REGENERATED-1280x720.png
C06-UI004-SCN01-READY-1440x900.png
C06-UI004-SCN01-READY-1280x720.png
```

逐张检查横向滚动、裁切、任务树缩进、状态标签、抽屉溢出、表单错误、中文缺字、异常空白和 UI-005 入口可见性。结果写入 `docs/evidence/C06/screenshot-index.md`。

## 16. 最终验收与交接

- C06 focused Vitest 与全量 Vitest 通过并报告实际数量。
- Build 通过并报告实际 modules。
- C06 4 条 E2E 通过；全量 Playwright 通过并报告实际数量。
- C06 非 React primary diagnostics 为 0；全局 TypeScript 只记录实际结果，不伪装通过。
- 六份冻结基线哈希 6/6 匹配，禁止范围无改动，`git diff --check` 通过。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C06/coverage.json` 覆盖页面、3 主 selectors、API-007、5 动作、SCN-01、AT-TOS-004、6 UI 状态、4 E2E、8 截图、权限/版本/联锁/审计/reset。
- `docs/evidence/C06/verification.md` 记录命令、时间、版本、退出码、测试数量、build、E2E、tsc、哈希、截图和范围检查。
- `docs/handoffs/C06-task-decomposition.md` 记录公共导出、规则版本、所有权、货类模板、Gateway、selectors、命令、READY 语义、权限、场景、证据、限制和 C07/UI-005 入口。

完成时保留在 `demo/c06-task-decomposition`，不合并、不推送，工作区必须干净。

## 17. C06 最终对话提示词要求

用户审阅本规格并确认后，实施计划和新对话提示词必须包含：

- C05 最终提交、C06 设计/计划提交、目标分支和六份基线哈希。
- UI-004 单页范围、方案一、五动作闭环、C06 所有权、货类模板和 READY 规范化语义。
- API-007 严格 body 与命令上下文分离，API 返回旧对象不得覆盖 Store。
- 四道 TDD Gate、四条 E2E、8 张截图、AT-TOS-004 基准展示和 Windows 合法 E2E 文件名。
- 权限、AREA-A、版本、幂等、原子性、追加写审计、SCN-05 联锁阻断和 reset 要求。
- 不新增依赖、不修改基线、不改既有工单、不实现 UI-005 正式派工的硬边界。
- 明确要求执行而非重新规划，并输出完整提交链、测试、Build、TSC 分类、E2E、截图、coverage、verification 和 handoff。
