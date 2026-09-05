# C05 接车计划推荐实施对话提示词

你现在开始独立实施 **C05：接车计划推荐**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C05 规格和逐步实施计划已经确认并冻结；请使用 `superpowers:executing-plans` 按计划 Task 1～Task 8 顺序执行。除非我明确要求，不要派生子任务或使用多代理。先核验仓库与基线，然后严格采用 TDD 完成源码、页面、测试、截图、验证、提交和交接。

## 一、工程、基点与工作分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- 起始分支：`demo/c04-plan-entry`
- C04 最终源码与交接提交：`55944a18e22eecef437ea6394c543e9177380a2b`
- C05 设计规格提交：`5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61`
- C05 实施计划提交：`bb00885aab8ad7d4ea15c07d19cb9197eef338bb`
- C05 工作分支：`demo/c05-recommendation`

开始前必须：

1. 进入工程并确认工作区干净。
2. 确认上述三个提交都是当前 `HEAD` 的祖先。
3. 从当前干净的 `demo/c04-plan-entry` 创建并切换到 `demo/c05-recommendation`。若目标分支已经存在，只能在确认基点正确、工作区干净且没有未知提交后继续；不得覆盖已有改动。
4. 通读并以以下文件为实施依据：
   - `docs/handoffs/C04-plan-entry.md`
   - `docs/superpowers/specs/2026-07-21-c05-recommendation-design.md`
   - `docs/superpowers/plans/2026-07-21-c05-recommendation.md`
5. 逐项执行计划中的 8 个 Task 和全部 60 个检查框；按计划保存红灯、运行聚焦回归并形成小提交。不要另写竞争计划，不要再次请求确认已经冻结的规格。

## 二、冻结基线闸门

开始写 C05 源码前计算并核对以下六份 SHA-256：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一哈希不匹配时，给出期望值、实际值、文件状态和最近相关提交后停止；不得自行修改或重建基线。全部匹配后，C05 全程禁止修改 `docs/baseline/**`。

源码修改前执行并记录：

```powershell
pnpm exec tsc --noEmit
pnpm test -- --run
pnpm build
pnpm test:e2e
```

C04 交接基线是：Vitest 32 个文件 / 257 个测试通过，build 通过（1,860 modules），Playwright 19/19；全局 TypeScript 因冻结依赖缺少 React 声明保留 286 条诊断，其中 TS2604=13、TS7016=51、TS7026=222，C04 非 React primary diagnostics 为 0。完整结果写入 `docs/evidence/C05/preflight.md` 和 `tsc-before.txt`。若 Vitest、build 或 E2E 退化，先恢复 C04 基线，不得把退化带入 C05。

## 三、唯一业务范围

C05 只把既有 UI-003 路由实现为可演示的接车推荐闭环：

```text
/dispatch/plans/:planId/recommendation
```

完整流程是：

```text
读取已确认计划
→ API-005 严格校验
→ Store 计划/股道/工单生成确定性推荐
→ 展示候选、排除项、时间轴和推荐依据
→ 选择首选或人工调整
→ 必要时填写原因与异人复核
→ API-006 严格确认
→ 生成 CONFIRMED 推荐草稿
→ 开放既有 UI-004 入口
```

确认成功后只开放：

```text
/dispatch/plans/:planId/tasks
```

UI-004 继续保持 C01 页面骨架。不得实现 API-007、任务拆解、工单生成或 UI-004 业务内容。

页面必须像可向甲方演示的业务系统：沿用 C04 Ant Design 工作台风格，包含计划摘要、候选卡片、排除选项、股道时间轴、规则说明抽屉、人工调整抽屉、确认反馈和进入任务拆解入口。确认流程、规则可解释性、权限与状态反馈优先，不新增框架、模板、图表库或复杂动画。

## 四、运行架构与数据所有权

必须采用已确认的方案 1：

- 延续 C04 的单一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store；不得创建第二个 Store 或 Provider。
- 新建 `src/features/recommendation/**`，内部包含严格 Schema、领域类型、纯规则引擎、Gateway、三个主 selector、页面 workflow、命令服务、组件、样式和测试。
- API-005 只负责严格验证请求、失败/成功信封、operationId、scenarioId、演示时间和匹配 Plan 身份。
- API-005 成功后，纯规则引擎从当前 Store 的 Plan、Track、WorkOrder 读取事实，生成候选、排除项、评分和窗口。
- 严格推荐草稿写入既有 `recommendation.drafts`。`RecommendationSlice` 继续保持 `Record<string, unknown>`；每次写入和 selector 读取都用 C05 Zod Schema 解析。
- API-006 通过 C03 命令执行器完成权限、版本、职责分离、Schema、Mock、SM-007 transition、单次原子 Store commit 和追加写审计。
- 页面只从 Store selector 读取领域结果。React 组件不得直接修改领域数组，不得用 API 返回旧对象覆盖 Store。
- workflow 只能保存：`selectedCandidateId`、两个抽屉开关和最近命令错误；不得复制 Plan、Track、WorkOrder、候选或确认结果。
- C04 API-025 reset 必须同时清除推荐草稿、C05 workflow、命令幂等缓存、确定性 ID 序号和 Mock `resolvedFaultObjects`。

三个主 selector 名称必须精确为：

```text
selectConfirmedPlan
selectReceptionRecommendations
selectTrackConflicts
```

DO-001 没有作业区字段，因此继续把冻结 Plan fixture 视为 AREA-A 数据集。会话 dataScope 必须在对象查找、候选、排除、影响工单和计数之前过滤。越出范围不得先读取再隐藏。

## 五、推荐草稿和确定性规则

规则版本固定：

```text
C05-DEMO-RULE-1.0
```

推荐草稿至少包含：

```text
planId
draftVersion
status = CALCULATED | CONFIRMED
inputPlanVersion
ruleVersion
generatedAt
candidates[]
excluded[]
selectedCandidateId?
adjustment?
confirmation?
```

每个候选必须包含：

```text
candidateId, trackId, trackNo, trackVersion, occupyStatus,
windowStart, windowEnd, score, rank, recommended,
scoreBreakdown, reasons, sourceRefs
```

计算资格：Plan 必须存在、AREA-A 可见、状态为 CONFIRMED，且 trackNo、cargoType、合法到发时间完整；API-005 的 inputVersion 必须等于当前 Plan.version。

硬排除按固定顺序执行：

1. BLOCKED → `TRACK_BLOCKED`
2. compatibleCargoTypes 不包含计划货类 → `CARGO_INCOMPATIBLE`
3. 非 FREE/OCCUPIED/RELEASING → `UNSUPPORTED_OCCUPANCY`
4. 非 FREE 且释放时间非法 → `INVALID_RELEASE_TIME`

这些是 C05 内部排除原因码，不是公共 API 错误码；不得扩展冻结错误码清单。

窗口规则：

- 原计划持续时长等于 departure - arrival。
- FREE 从计划到达时间开始。
- OCCUPIED/RELEASING 从计划到达与 estimateReleaseTime 的较晚者开始。
- 结束时间为候选开始加原计划持续时长。
- 延迟窗口明确显示“错峰”。

Demo 评分总分 100：

- 可用效率 45：FREE 45、RELEASING 35、OCCUPIED 25。
- 时间匹配 30：无延迟 30；每延迟 10 分钟扣 1 分，最低 0。
- 计划连续性 15：与当前 trackNo 相同为 15，否则 5。
- 权威输入完整性 10：RAIL_PLAN 且计划/股道时间戳完整为 10，否则 0。

最终排序固定为 score 降序、windowStart 升序、trackNo 升序。PLAN-001 冻结数据的期望排序与分数必须是：

```text
T1 = 100，rank 1
T3 = 62，rank 2
T2 = 52，rank 3
T4 = TRACK_BLOCKED
```

冻结 Plan 没有 priority 字段，也没有可可靠关联的箱量。页面必须明确显示“数据未提供”，不得猜测 Waybill 关联、优先级或箱量；这些值不得参与评分。

## 六、SCN-02 最小兼容补充

这是已经批准的 C05 唯一 Mock 语义补充，无需再次询问，但不得扩大：

- MockRuntime 新增可重置的 `resolvedFaultObjects`，按对象 ID 保存已解决字段名。
- 只有 SCN-02 中请求结构合法、包含该对象全部 mutation 缺失字段且最终成功的 API-004，才能把对应字段记为已解决。
- 之后仅对同一 SCN-02、同一 pathId 的 API-005/API-006 跳过同一 `TOS-EXT-002`。
- API-005/API-006 的响应投影只为该对象恢复已经解决的字段，以保证返回 Plan 仍通过严格 DO-001 Schema。
- API-002 和其他 API 继续使用原始缺字段投影；不同对象、不同场景、不完整 API-004、非法请求、强制失败、其他错误均保持原行为。
- API-025 reset 清空 `resolvedFaultObjects`。
- 六份 baseline、公开 Schema、endpoint、API catalog 和错误码不得改变。

必须先补 runtime/handler 允许与拒绝边界测试并保存 `compatibility-red.txt`。若只跳过错误却让 API-005 返回缺 trackNo 的对象，仍属于实现失败；必须同时满足严格响应 Schema。

## 七、API-005、API-006 与命令边界

Gateway 精确调用：

```text
GET /mock/plans/:id/recommendation?inputVersion=<current Plan.version>
POST /mock/plans/:id/recommendation/confirm
```

API-006 body 严格保持：

```ts
{
  trackNo: string;
  window: string;
  reason?: string;
}
```

不得把 reviewerId、inputPlanVersion、trackVersion、affectedWorkOrderIds 或 businessAction 发送给 API-006；这些只存在于 C05 命令 payload。

计算与确认都使用：

```text
entityType = SM-007
action = execute
payload.current = ACCEPTED
```

状态机继续接收 `execute`，不得修改 C03 state-machine catalog。审计适配器只为写 DO-013 克隆命令，将 action 记为：

```text
RC-01  推荐计算
RC-04  推荐确认
```

固定执行顺序：

```text
权限 → 请求/业务 Schema → Gateway Mock → SM-007 transition
→ 单次原子 Store commit → 追加一条审计
```

成功、失败、拒绝各追加恰好一条审计；相同 commandId 重放不得再次请求、提交或审计。ID 使用 `CMD-C05-001`、`TRACE-C05-001`、`AUD-C05-001` 形式的可重置计数；时间只用 `session.demoTime`，不得用系统时间或随机数。

## 八、权限、人工调整与版本

UI-003 页面只允许精确角色 `DISPATCHER`。无权角色必须在懒加载业务模块和 API-005 之前进入既有 403。

动作权限：

```text
查看/计算：plan:recommend
选择首选：plan:recommend
选择非首选：plan:recommend + plan:adjust
最终确认：plan:recommend + plan:confirm
```

选择 rank > 1 的候选必须填写非空原因。若该 Plan 的关联 WorkOrder 存在以下状态，则属于高风险人工调整：

```text
DISPATCHED | ACKNOWLEDGED | IN_PROGRESS | PAUSED
```

高风险调整要求 reviewerId，并且 reviewerId 必须与 actorId 不同。相同人员返回 `TOS-AUTH-001`，不调用 API、不修改草稿，并追加恰好一条 DENIED RC-04 审计。

若存在关联 COMPLETED 工单，人工调整返回 `DEMO-SCENARIO-001`，不得覆盖已完成事实。

推荐草稿保存 inputPlanVersion 和候选 trackVersion。API-006 前任一版本变化都返回 `DEMO-VERSION-001`，保留候选、原因和复核人，并要求重新计算。确认成功只把推荐草稿变为 CONFIRMED；不得修改 Plan.status、Plan.version 或 Track 状态。

## 九、UI-003 页面内容和状态

页面至少包含：

- 面包屑和返回 UI-002 入口。
- `UI-003`、`接车计划推荐`、精确当前路由。
- 场景、角色、接口和固定演示时间提示。
- 计划摘要：批次、车次、货类、到发时间、当前股道、CONFIRMED 状态、版本、来源系统、来源时间、冲突。
- 优先级和关联箱量“数据未提供”。
- 候选卡片：股道、窗口、分数、排名、推荐原因、来源，rank 1 标记“系统推荐”。
- 排除列表：股道、内部排除码、原因和来源。
- 规则说明抽屉：输入版本、四个评分分项、硬排除、排序、规则版本和“仅供 Demo 解释”。
- 股道时间轴：占用状态、预计释放、计划区间、候选窗口和错峰标记。
- 人工调整抽屉：最终候选、原因、影响工单、复核人、权限/版本提示。
- 确认后的原首选、最终候选、原因、复核人、命令/trace 摘要和 UI-004 入口。

六类状态固定为：loading、empty、business-error、network-error、forbidden、not-found。

SPA 从 C04 成功确认后进入 C05 是完整演示主线。浏览器直接访问或刷新 UI-003 时，如果 bootstrap 已恢复到未确认 fixture，必须显示 business-error 并引导返回 UI-002；不得从 URL、缓存或 API 旧对象伪造 CONFIRMED。

1440×900 使用候选/排除左右结构；1280×720 将排除区移到下方。两种视口都不得出现页面横向滚动、卡片裁切、抽屉溢出、表单遮挡或不可读时间轴。

## 十、三道 TDD 闸门

### Gate A：推荐内核

先保存：

```text
docs/evidence/C05/recommendation-red.txt
docs/evidence/C05/compatibility-red.txt
```

覆盖严格草稿 Schema、PLAN-001 精确分数、窗口、排序、硬排除、纯函数/冻结结果、三个 selector、AREA-A 前置过滤、API-005/006 Gateway、版本快照、命令、原子写入、幂等、审计、reset 和 SCN-02 允许/拒绝边界。相关 C02/C03/C04 回归全部绿色后才能进入 Gate B。

### Gate B：页面展示

先保存：

```text
docs/evidence/C05/page-red.txt
```

覆盖计划摘要、数据未提供、候选、排除项、规则解释、时间轴、六类状态、精确页面权限、模块/API 不提前加载、返回 UI-002 和响应式布局。

### Gate C：调整与确认

先保存：

```text
docs/evidence/C05/action-red.txt
```

覆盖首选确认、非首选原因、影响工单、异人复核、同人拒绝、COMPLETED 阻断、API-006 严格 body、版本冲突、失败保留输入、幂等、审计、reset 和 UI-004 入口。Gate C 全绿前不得宣称 C05 完成。

## 十一、E2E 和八张截图

在既有 19 条 E2E 之外恰好新增 4 条 C05 E2E：

1. SCN-01：UI-002 确认 PLAN-001 → UI-003 生成推荐 → 首选确认 → UI-004 入口。
2. SCN-01：选择 T3 非首选 → 原因和异人复核 → 确认并核对 RC-04 审计与不修改 Plan/Track。
3. SCN-02：PLAN-002 补录/确认 → 同 SPA 放行 API-005/API-006 → 推荐确认。
4. 无权访问、同人复核拒绝、严格 `DEMO-VERSION-001` 响应后保留输入并重新计算。

第 4 条浏览器测试可以一次性拦截 API-006 返回严格 409 版本冲突信封，以验证页面恢复表现；真实 Store Plan/Track 版本变化必须由命令单元测试证明在 API 调用前被拒绝。

全量 Playwright 最终必须为 **23/23（19 既有 + 4 C05）**。保存并逐张按原始分辨率检查以下 8 张截图：

```text
C05-UI003-SCN01-CALCULATED-1440x900.png
C05-UI003-SCN01-CALCULATED-1280x720.png
C05-UI003-SCN01-ADJUSTMENT-1440x900.png
C05-UI003-SCN01-ADJUSTMENT-1280x720.png
C05-UI003-SCN01-CONFIRMED-1440x900.png
C05-UI003-SCN01-CONFIRMED-1280x720.png
C05-UI003-SCN02-CONFIRMED-1440x900.png
C05-UI003-SCN02-CONFIRMED-1280x720.png
```

每张检查横向滚动、裁切、重叠、时间轴可读性、抽屉溢出、表单错误、中文缺字和异常空白。结果写入 `docs/evidence/C05/screenshot-index.md`。不得修改或覆盖 C01/C04 旧截图。

## 十二、硬性禁止事项

- 不修改 `docs/baseline/**`、`package.json` 或 `pnpm-lock.yaml`，不安装/升级/删除依赖。
- 不新增第二套 fixture、Store、Provider、角色映射、权限码、endpoint、API catalog 项或公共错误码。
- 不修改 C03 状态机 catalog；SM-007 继续使用 execute。
- 不把 reviewer/version/affectedWorkOrders/businessAction 发给 API-006。
- 不实现 UI-004、API-007 或后续 C06～C13 业务。
- 不使用 `any`，不放宽 Zod Schema、TypeScript 配置或 `additionalProperties` 掩盖错误。
- 不在页面/组件里调用 `replaceDomainState`、`resetFromSnapshot`、Mock runtime 或 handler。
- 不让 API 返回旧 Plan/Track 覆盖当前 Store。
- 不用 Date.now、无输入的 new Date、Math.random、随机 UUID 或本地缓存作为领域事实。
- 不以假成功、静默状态保持、跳过测试、修改失败断言或删除回归测试解决问题。
- 除第六节精确兼容补充和 C04 reset 回调窄扩展外，不改变 C02/C03/C04 公共语义。

## 十三、实施节奏与冲突处理

- 严格执行计划 Task 1～Task 8；每个 Task 先有效红灯、再最小实现、再聚焦和相关回归、再按计划提交。
- 每个 Task 使用计划给出的文件、类型、函数名、命令、预期输出和提交边界。
- 一般实现困难、类型错误、测试失败、视觉调整或环境问题不是“需求冲突”，应继续定位和修复。
- 遇到测试失败或意外行为时，先使用系统化调试定位根因，不得猜测修复。
- 只有实时六哈希不匹配，或确认规格/实施计划与冻结机器契约在同一具体字段、状态、接口或权限条目上确实无法兼容时才能停止。
- 停止时必须给出文件、行号、双方原文、最小复现和建议决策，不能只给概括性描述。
- 计划中已经解决的 SCN-02 投影问题、API-006 上下文分离和 TypeScript 既有诊断不得再次作为规格疑问提出。

## 十四、最终验证与交接

至少执行：

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
pnpm exec tsc --noEmit
git diff --check
```

并执行计划 Task 8 列出的 C05 focused Vitest 集与六哈希复核。最终要求：

- C05 focused tests 与全量 Vitest 全绿，报告实际文件数和测试数。
- build 通过并报告实际模块数。
- C05 E2E 4/4，全量 Playwright 23/23。
- C05 非 React primary diagnostics 为 0；全局 tsc 按实际诊断记录，不能伪装通过。
- 六份冻结基线哈希 6/6 不变；`git diff --check` 通过；工作区干净。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C05/coverage.json` 枚举 UI-003、3 selectors、2 APIs、2 场景、6 UI 状态、权限/版本/幂等/审计/reset/兼容规则、4 E2E 和 8 截图，并写入精确测试名与状态。
- `docs/evidence/C05/verification.md` 记录命令、环境、时间、退出码、测试数量、build、E2E、tsc、哈希、截图、范围扫描和 diff check。
- 创建 `docs/handoffs/C05-recommendation.md`，列明 runtime 公共成员、严格草稿 Schema、规则版本/评分、三个 selector、Gateway、命令/权限/版本/审计/reset、SCN-02 兼容边界、页面状态、证据、限制和 C06 UI-004 入口。

最终报告必须包含：当前分支、完整 HEAD、完整提交链、变更文件分类、focused/full Vitest 数量、build、C05 4/4 与全量 Playwright 23/23、tsc 实际状态、六哈希、8 张截图、coverage、verification、handoff 和已知限制。

现在开始执行：先完成工作区、祖先提交、六哈希和 C04 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重新规划，不要再次请求确认已经通过的规格。
