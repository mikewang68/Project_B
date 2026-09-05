# C06 任务拆解实施对话提示词

你现在开始独立实施 **C06：任务拆解**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C06 设计规格与逐步实施计划已经确认并冻结；请使用 `superpowers:executing-plans` 按计划 Task 1～Task 9 顺序执行。除非我明确要求，不要派生子任务或使用多代理。先核验仓库、祖先提交与冻结基线，然后严格采用 TDD 完成源码、页面、测试、截图、验证、提交和交接。

## 一、工程、基点与工作分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C05 最终源码与交接提交：`83363ff18bf606150d6d03a3248046c6bc0f1dfd`
- C06 设计规格提交：`ab952b11b43dbe1f9c563cfc5518f50f080f3a6e`
- C06 实施计划提交：`058a1c725832cb20596ae2eb2d84b32a98e38e6f`
- C06 工作分支：`demo/c06-task-decomposition`

开始前必须：

1. 进入工程，确认当前分支精确为 `demo/c06-task-decomposition` 且工作区干净。
2. 确认上述三个提交都是当前 HEAD 的祖先；不得重建分支、覆盖提交或回退 C05。
3. 通读并以以下文件为实施依据：
   - `docs/handoffs/C05-recommendation.md`
   - `docs/superpowers/specs/2026-07-21-c06-task-decomposition-design.md`
   - `docs/superpowers/plans/2026-07-21-c06-task-decomposition.md`
4. 逐项执行实施计划中的 9 个 Task 和全部 73 个检查框；先产生有效红灯，再写最小实现，再运行聚焦/相关回归并按计划形成小提交。
5. 不要另写竞争计划，不要再次请求确认已经冻结的范围、READY 语义、API-007 body、资源边界或截图数量。

## 二、冻结基线闸门

写 C06 源码前计算并核对：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一哈希不匹配时，给出期望值、实际值、文件状态和最近相关提交后停止；不得自行修订、重建或接受新的基线。

源码修改前执行并记录：

```powershell
pnpm exec tsc --noEmit
pnpm test -- --run
pnpm build
pnpm test:e2e
```

C05 交接基线：Vitest 42 个文件 / 322 个测试，build 通过（1,875 modules），Playwright 23/23；全局 TypeScript 因冻结依赖缺少 React/JSX 声明保留 360 条诊断，其中 TS2604=18、TS7016=63、TS7026=279，C05 非 React primary diagnostics 为 0。完整实测结果写入 `docs/evidence/C06/preflight.md` 和 `tsc-before.txt`。若 Vitest、build 或 E2E 退化，先恢复起始基线，不得把退化带入 C06。

## 三、唯一业务范围与完成边界

C06 只实现现有 UI-004：

```text
/dispatch/plans/:planId/tasks
```

完整演示主线：

```text
C04 已确认 Plan
→ C05 已确认接车推荐
→ API-007 AUTO
→ 生成 C06 DRAFT 任务树
→ 拆分 / 合并 / 重新生成
→ API-007 CONFIRM
→ Plan DECOMPOSED + C06 WorkOrder READY
→ 开放 /dispatch/work-orders
```

UI-005 仍保持既有骨架。C06 不实现资源实例分配、工单下发、接单、执行、API-008、API-009 或 UI-005 业务。

页面必须足以向甲方演示，但确认流程和业务可解释性优先。沿用 C04/C05 的 Ant Design 工作台，不新增依赖、图形库、复杂动画或第二套 UI 框架。

## 四、唯一 Store、数据所有权与冻结事实

必须采用已确认方案：

- 延续唯一 `DemoRuntimeProvider`、唯一 C03 vanilla Store；不得创建第二个 Store、Provider 或 fixture。
- 新建 `src/features/task-decomposition/**`，包含 constants、types、ownership、纯 rule/edit engines、Gateway、三个主 selector、workflow、commands、组件、样式和测试。
- C06 任务使用严格 DO-005/DO-006，追加到现有 `workOrder` slice。
- C06 ownership 必须同时满足：

```text
WorkOrder.id/workOrderNo 以 C06-WO- 开头
WorkNode.id 以 C06-NODE- 开头
WorkNode.nodeNo 以 C06-N- 开头
WorkOrder.ruleVersion = C06-DEMO-RULE-1.0
WorkOrder.planId = 当前 planId
```

- `WO-001`～`WO-012`、`NODE-001`～`NODE-012` 是既有历史事实，任何 C06 动作都不得覆盖、重排或删除。
- 页面 workflow 只能保存选中节点、抽屉、mode、targetNodeId、reason 和最近错误，不得复制 Plan、RecommendationDraft、WorkOrder、WorkNode、Resource 或任务树。
- 页面、组件和 Gateway 不得调用 `replaceDomainState`；所有领域写入只能在 C06 command service 中发生。
- C05 RecommendationDraft 必须严格解析且为 CONFIRMED；C06 不重新计算、重新确认或修改推荐。
- API-025 reset 恢复冻结 fixture，并同时清空 C05/C06 workflow、C06 幂等缓存、暂存候选和确定性序号。

三个主 selector 名称必须精确为：

```text
selectCargoSummary
selectRuleExplanation
selectTaskTree
```

可增加 `selectResourcePreview`，但不得复制三者的规则。所有读取、计数、映射和版本快照之前先执行 AREA-A 数据域过滤。

## 五、确定性货类路由与追溯

规则版本固定：

```text
C06-DEMO-RULE-1.0
```

四类模板固定为四阶段线性链：

```text
FLY_ASH:
识别与路由确认 INSPECT → 卸料准备 UNLOAD → 输送转运 TRANSFER → 筒仓入库 LOAD

CEMENT:
识别与路由确认 INSPECT → 卸料准备 UNLOAD → 输送转运 TRANSFER → 应急筒仓入库 LOAD

STEEL:
规格重量校验 INSPECT → 重载吊装 UNLOAD → AGV 转运 TRANSFER → 货位入库 LOAD

GENERAL_CARGO:
箱号包装校验 INSPECT → 掏装/卸载 UNLOAD → 分拣转运 TRANSFER → 入库或发运准备 LOAD
```

冻结对象没有 Plan→Waybill/Material 外键。只允许按相同 cargoType 和稳定 ID 顺序形成页面标注为“Demo 稳定映射”的追溯；不得伪装为生产外键。GENERAL_CARGO 必须说明冻结枚举无法进一步区分机电设备和生活物资。

WorkOrder.parentId 只表示一个直接上游，不实现多父 DAG。每个 C06 WorkOrder 精确配一条 WorkNode；严格验证唯一 ID/编号、一对一、连续 sequence、无孤儿、无循环、合法 parent、严格 DO-005/006。

PLAN-001 的 G001 ID 必须稳定为 `C06-WO-PLAN-001-G001-01`～`04` 和对应 `C06-NODE-...`。计划 08:01～10:01 的四个节点确定性拆分为四段 30 分钟；不得使用系统时钟或随机数。

## 六、资源预览与 READY 的冻结语义

AREA-A 冻结资源不足以提供所有粉体阶段的 AVAILABLE 实例，因此：

- 不得伪造 RESOURCE、不得越域借用 AREA-B/C、不得把 MAINTENANCE/LOCKED 宣称为已分配。
- UI-004 只验证所需资源类型存在并展示当前候选实例及真实状态。
- TD-05 后 WorkOrder 进入 READY，表示“任务定义可进入派工”。
- `resourceId=''`、`teamId=''` 保持空字符串，具体实例由 UI-005 后续分配。
- 使用既有 DO-005 `DRAFT + assign → READY` 验证状态迁移；这里的 assign 只代表资源类型预分配，不表示已占用、已下发或 DISPATCHED。
- WorkNode 确认后仍为 WAITING。

不得再次把“资源实例不足”作为规格冲突；这是已确认的规范化边界。

## 七、API-007 与五个动作

Gateway 只调用：

```text
POST /mock/plans/:id/decompose
operationId = POST_mock_plans_id_decompose
```

body 始终严格为：

```ts
{
  ruleVersion: 'C06-DEMO-RULE-1.0',
  mode: 'AUTO' | 'SPLIT' | 'MERGE' | 'REGENERATE' | 'CONFIRM',
}
```

targetNodeId、selectedNodeIds、reason、Plan/Recommendation/Resource/WorkOrder/WorkNode versions、generationVersion、actor 和 businessAction 只在 command payload；不得发送给 API-007。

冻结 handler 对 pathId 过滤后只返回匹配 Plan。成功响应必须严格校验 apiId、operationId、scenarioId、now 和恰好一个同 ID Plan；返回的 Plan/WorkOrder/WorkNode 不能覆盖 Store。

五个动作：

1. `TD-01 / AUTO`：前置满足后生成四条 DRAFT/WAITING C06 任务；已有 DRAFT 时引导重新生成，不重复追加。
2. `TD-02 / SPLIT`：一个可编辑、非 INSPECT 的 C06 DRAFT 节点拆成两个顺序节点；reason 必填，重接上游/下游并重新编号。
3. `TD-03 / MERGE`：只合并相邻直接串联、同 type、同派生 object、同规则版本的两个 C06 DRAFT 节点；不实现任意兄弟或多父合并；reason 必填。
4. `TD-04 / REGENERATE`：二次确认后只替换当前计划 C06 DRAFT，generation G001→G002；保留审计，禁止删除 READY/历史/其他计划对象。
5. `TD-05 / CONFIRM`：无孤儿/循环、版本一致、资源类型存在后，单次原子提交 Plan CONFIRMED→DECOMPOSED、所有 C06 WorkOrder DRAFT→READY、相关版本/updatedAt 递增，开放 UI-005。

所有动作技术命令保持：

```text
entityType = SM-007
action = execute
payload.current = ACCEPTED
```

不得修改 C03 state-machine catalog。业务 action 只在审计适配器中记为 TD-01～TD-05。

## 八、权限、版本、幂等、原子性和安全

- 页面：`task:view`。
- TD-01、TD-04、TD-05：`task:decompose`。
- TD-02、TD-03：`task:edit`。
- 使用冻结 permissionCatalog；DISPATCHER 和 SHIFT_LEADER 均按现有策略执行，不新增角色表。
- AREA-A/GLOBAL/`*` 可见；越界对象在查找/计数前隐藏，不泄露存在性。
- 命令保存 Plan.version、RecommendationDraft.draftVersion、所有参与 Resource/C06 WorkOrder/C06 WorkNode.version。
- 权限/验证前、API 后和 commit 内都重新核验。版本变化统一返回 `DEMO-VERSION-001`，保留草稿和表单，不产生部分写入。
- `INTERLOCK_FORCE_STOP` 在 API 前以 `TOS-IL-001` 阻断所有 C06 写动作，提供 UI-009 入口，不允许 C06 覆盖。
- 相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或审计。
- 每次成功、拒绝、失败恰好追加一条 C06 审计；不得覆盖 C04/C05 账本。
- ID 使用 `CMD-C06-###`、`TRACE-C06-###`、`AUD-C06-###`，时间只用 `session.demoTime`。

固定顺序：

```text
权限 → 请求/业务 Schema → API-007 → 二次版本校验
→ SM-007 transition → 单次原子 Store commit → 追加一条审计
```

## 九、UI-004 必须展示的内容

- 面包屑、返回 UI-003、`UI-004`、`任务拆解`、精确当前路由。
- Plan 事实、C05 已确认股道/时间窗/规则/确认信息。
- CargoSummary：本计划货类、Demo 稳定映射、来源和缺失数据。
- AT-TOS-004 基准：2 列、80 节、160 箱；必须标注“验收基准，不是本计划实际箱量”，实际箱量显示“数据未提供”。
- TaskTree：nodeNo、stage/type、派生 objectId、上游、ruleVersion、status、versions。
- RuleExplainPanel：货类路线、输入、来源、限制和未提供项。
- ResourcePreview：所需类型、候选 ID、AREA、真实 status、READY/派工说明。
- TaskEditDrawer：mode、目标、reason、受影响节点、预期版本和错误保留。
- 五个动作、命令反馈、trace/audit、READY 后 UI-005 入口。

六类页面状态固定：loading、empty、business-error、network-error、forbidden、not-found。业务进度状态固定：EMPTY、GENERATED、EDITED、READY。

直接刷新如果 bootstrap 恢复到未确认 fixture，显示 business-error 并引导返回 UI-002/UI-003；不得从 URL、缓存或 API 旧对象伪造前置状态。

1440×900 使用任务树与说明/资源左右布局；1280×720 可改为单列/抽屉。两种视口都不得出现页面横向滚动、树/表单/按钮裁切或不可读依赖。

## 十、四道 TDD 闸门

### Gate A：任务内核与契约

先保存 `docs/evidence/C06/task-core-red.txt`。覆盖 ownership、四货类、严格 DO-005/006、图校验、稳定映射、三个 selector、资源预览和 API-007。相关 C02/C03/C05 回归全绿后进入 Gate B。

### Gate B：命令与状态

先保存 `docs/evidence/C06/task-command-red.txt`。覆盖 TD-01～TD-05、split/merge、regenerate、Plan/WorkOrder transitions、严格 body、权限、版本、原子性、幂等、审计、联锁和 reset。

### Gate C：页面与跨页

先保存 `docs/evidence/C06/task-page-red.txt`。覆盖页面内容、编辑抽屉、确认、6 页面状态、4 进度状态、角色/数据域、UI-003 返回和 UI-005 入口。

### Gate D：确定性演示证据

先保存 `docs/evidence/C06/task-e2e-red.txt`。完成恰好 4 条 C06 E2E、8 张截图、coverage、verification、handoff 和全量回归。全部通过前不得宣称 C06 完成。

## 十一、E2E 与 8 张截图

E2E 文件使用 Windows 合法名称：

```text
e2e/ui-004-task-decomposition.spec.ts
```

恰好 4 条：

1. SCN-01：UI-002 确认 → UI-003 首选确认 → UI-004 AUTO → TD-05 → DECOMPOSED/READY → UI-005。
2. SCN-01：拆分 → 合并 → 再拆分 → 重新生成，核对 reason、版本、任务树和审计。
3. BUSINESS/AREA-B 拒绝、SHIFT_LEADER 冻结权限、版本冲突保留状态和幂等证据。
4. API-007 network/malformed 恢复，以及 SCN-05 `TOS-IL-001` 保守阻断和 UI-009 入口。

全量 Playwright 目标为 27 条（既有 23 + C06 4；若仓库期间有合法新增，报告实测数）。保存且只保存：

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

逐张按原始分辨率检查横向滚动、裁切、重叠、任务树缩进、依赖可读性、抽屉/弹窗、中文缺字、异常空白和 READY 的 UI-005 入口。结果写入 `docs/evidence/C06/screenshot-index.md`。不得覆盖 C04/C05 旧截图。

## 十二、硬性禁止事项

- 不修改六份 baseline、依赖文件、lockfile、API catalog、公共错误码、权限目录或状态机 catalog。
- 不新增第二套 fixture、Store、Provider、endpoint、公共角色/权限或业务枚举。
- 不改写既有工单/节点，不让 API 返回旧对象覆盖 Store。
- 不把 reason、targetNodeId、selectedNodeIds、versions 或 actor 发给 API-007。
- 不造 160 个业务对象，不把验收基准写成 Plan 实际箱量。
- 不伪造资源、不越数据域、不把资源预览写成已分配。
- 不实现 UI-005 或后续 C07～C13。
- 不使用 `any`、不放宽 Zod、TS 配置或 strict Schema 掩盖错误。
- 不在页面/组件/Gateway 中调用领域 mutator、Mock runtime 或 handler。
- 不使用 Date.now、无输入当前时间、Math.random、随机 UUID 或 localStorage 作为领域事实。
- 不以假成功、静默保持、跳过测试、删回归测试或修改失败断言解决问题。

## 十三、冲突与停止规则

- 按计划 Task 1～Task 9 执行；一般实现困难、类型错误、测试失败、视觉调整或环境问题不是需求冲突，应定位并修复。
- 遇到 bug 或意外行为时先使用 `superpowers:systematic-debugging`，不得猜测修复。
- 只有实时六哈希不匹配，或确认规格/计划与冻结契约在同一具体字段、状态、接口、权限或不可改变的数据事实上确实无法兼容时才能停止。
- 停止时必须给出文件、行号、双方原文、最小复现、已尝试验证和建议决策，不能只给概括性描述。
- READY 空 resourceId/teamId、API-007 只返回 Plan、单父依赖、Demo 稳定映射、AT-TOS-004 不生成 160 对象、Windows E2E 文件名，均已在规格与计划中解决，不得重新作为疑问提出。

## 十四、最终验证与交接

至少执行：

```powershell
pnpm test -- --run
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-004-task-decomposition.spec.ts
pnpm test:e2e
pnpm exec tsc --noEmit
git diff --check
```

并执行计划 Task 9 的 focused Vitest、六哈希复核和禁止范围扫描。最终要求：

- C06 focused tests 与全量 Vitest 全绿，报告实际文件数/测试数。
- build 通过并报告实际 modules。
- C06 E2E 4/4，全量 Playwright 全绿并报告实际数量。
- C06 非 React primary diagnostics 为 0；全局 tsc 按实测记录，不能伪装通过。
- 六哈希 6/6 不变，`git diff --check` 通过，工作区干净。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C06/coverage.json` 完整枚举 UI-004、3 selectors、API-007、5 actions、SCN-01/SCN-05/AT-TOS-004、6 页面状态、4 进度状态、4 E2E 和 8 截图，并填写精确测试名/status。
- `docs/evidence/C06/verification.md` 记录环境、命令、时间、退出码、测试/build/E2E/tsc、哈希、截图、范围和 diff check。
- 创建 `docs/handoffs/C06-task-decomposition.md`，列明 runtime 公共入口、规则/ownership、货类模板、selectors、Gateway、五动作、READY 语义、权限/版本/审计/reset、联锁、页面状态、证据、限制和 C07 `/dispatch/work-orders` 入口。

最终报告必须包含：当前分支、完整 HEAD、完整提交链、变更文件分类、focused/full Vitest、build、C06 4/4 与全量 Playwright、tsc 实际状态、六哈希、8 张截图、coverage、verification、handoff 和已知限制。保留在 `demo/c06-task-decomposition`，不合并、不推送。

现在开始执行：先完成工作区、祖先提交、六哈希和 C05 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重新规划，不要再次请求确认已经通过的规格。
