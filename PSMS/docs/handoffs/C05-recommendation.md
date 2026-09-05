# C05 接车计划推荐交接

## 交付结论

C05 已把 UI-003 `/dispatch/plans/:planId/recommendation` 实现为可连续演示的接车推荐闭环：读取 C04 已确认计划，经严格 API-005 和 Store 真值计算确定性候选，展示评分、排除项、时间轴和规则说明，支持首选确认与人工调整，并通过严格 API-006、C03 命令执行器、单次原子提交和追加写审计生成 CONFIRMED 推荐草稿。成功后只开放既有 UI-004 `/dispatch/plans/:planId/tasks`；任务拆解业务仍未实现。

## Runtime 公共入口

应用继续使用唯一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store。`DemoRuntime` 新增公共成员：

```text
runtime.recommendation.gateway
runtime.recommendation.workflow
runtime.recommendation.commands
```

- `gateway`：`getRecommendation(planId, inputVersion)`、`confirmRecommendation(planId, input)`。
- `workflow`：`getState`、`subscribe`、`selectCandidate`、`setAdjustmentDrawerOpen`、`setRuleDrawerOpen`、`recordCommandError`、`reset`。
- `commands`：`calculateRecommendation(planId)`、`confirmRecommendation(input)`、`resetCommandState()`。
- React 入口：`useDemoRuntime()`、`useDemoSelector()`、`useRecommendationWorkflow()`。
- `src/features/recommendation/index.ts` 统一导出 commands、gateway、rule engine、schema、selector、types 和 workflow。

`createDemoRuntime()` 先创建一个 Store，再把同一 Store 交给 C04 与 C05 命令服务。C04 API-025 成功 reset 的回调同时清空 C05 workflow、命令幂等缓存、暂存计算结果和确定性 ID 序号；Mock runtime reset 同时清空 `resolvedFaultObjects`。C04 计划确认对 C05 立即可见，不存在第二个 Store、Provider 或领域对象副本。

## RecommendationDraft 严格契约

规则版本固定为：

```text
C05-DEMO-RULE-1.0
```

`recommendationDraftSchema` 使用 strict Zod object，并对解析结果深冻结。主要字段为：

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

候选包含 `candidateId/trackId/trackNo/trackVersion/occupyStatus/windowStart/windowEnd/score/rank/recommended/scoreBreakdown/reasons/sourceRefs`；分数必须等于四项分数之和，只有 rank 1 能标记为系统推荐。CONFIRMED 必须同时有有效 `selectedCandidateId` 和 `confirmation`；CALCULATED 不允许 confirmation。人工调整保留原候选、最终候选、原因、受影响工单和可选复核员，最终候选必须等于 selected candidate。

Store 仍把 `recommendation.drafts` 声明为 `Record<string, unknown>`；每次命令写入和 selector 读取均通过严格 Schema，不放宽 C03 slice。

## 100 分规则与稳定排序

推荐引擎只消费当前 Store 中的 Plan、Track 和确定性 `session.demoTime`：

| 分项 | 满分 | 规则 |
| --- | ---: | --- |
| 可用性 | 45 | FREE=45、RELEASING=35、OCCUPIED=25 |
| 时序 | 30 | `max(0, 30 - floor(延迟分钟/10))` |
| 连续性 | 15 | 与原计划股道相同=15，否则=5 |
| 权威性 | 10 | RAIL_PLAN 且计划来源时间、股道更新时间完整=10，否则=0 |

总分降序后，以 `windowStart` 升序、`trackNo` 升序稳定打破平分。SCN-01 固定结果为 T1=100、T3=62、T2=52，T4 因 `TRACK_BLOCKED` 硬排除。硬排除顺序为封锁、货类不兼容、不支持的占用状态、无效释放时间；排除项最终按 trackNo 稳定排序。规则不读取系统时钟或随机源，也不修改输入。

## 三个必需 Selector 与 AREA-A 边界

1. `selectConfirmedPlan(state, planId)`：仅返回 AREA-A 范围内、状态为 CONFIRMED 的不可变 Plan 副本。
2. `selectReceptionRecommendations(state, planId)`：先过滤 dataScope 和已确认 Plan，再读取并严格解析推荐草稿。
3. `selectTrackConflicts(state, planId)`：从严格草稿的 excluded 投影稳定、深冻结的冲突视图。

辅助 `selectAffectedWorkOrders` 同样先做 AREA-A 过滤，再按工单 ID 排序。DO-001 没有作业区字段，因此按冻结约定把数据集视为 AREA-A；`*`、`GLOBAL`、`AREA-A` 可读，其他 scope 在对象查找、草稿解析、关联工单和计数前返回空或 undefined，不会先读取再隐藏。

## API-005 / API-006 Gateway

| API | 方法 | HTTP 与请求 |
| --- | --- | --- |
| API-005 | `getRecommendation` | `GET /mock/plans/:planId/recommendation?inputVersion=:positiveInt` |
| API-006 | `confirmRecommendation` | `POST /mock/plans/:planId/recommendation/confirm`，JSON body 为 strict `{ trackNo, window, reason? }` |

Gateway 严格校验成功/失败信封。成功数据必须包含精确 `apiId`、operationId、`now`、`scenarioId`，以及长度恰为 1 且 ID 匹配路径 planId 的 DO-001。API-006 的 reviewer 和 Store version 属于命令/授权上下文，不进入冻结 API body；页面和组件不直接调用 Gateway。

## RC-01 / RC-04 命令、权限、版本与审计

C05 复用 C03 `createCommandExecutor`，命令 entity 为 SM-007，顺序固定为授权、验证、Mock、transition、单次原子 Store commit、追加写审计。

- RC-01 计算要求 `plan:recommend`、AREA-A、CONFIRMED 且完整的 Plan；保存 Plan version 和全部 Track version，调用 API-005 后再次核验，再用 Store 真值计算并写入 CALCULATED 草稿。重复计算只替换同一草稿、`draftVersion + 1`，并清除 workflow 选择。
- RC-04 确认继续要求 `plan:recommend` 与 `plan:confirm`；选择非首位候选还要求 `plan:adjust`。非首位候选必须给出原因；存在 DISPATCHED、ACKNOWLEDGED、IN_PROGRESS 或 PAUSED 影响工单时必须异人复核。
- `reviewerId === actorId` 在 API 前返回 `TOS-AUTH-001`，保留草稿和 workflow 表单/选择，并追加恰好一条 DENIED RC-04 审计。
- Plan 或任一候选 Track version 改变时在 API 前返回 `DEMO-VERSION-001`；Gateway 窗口后的二次核验也能阻止竞态提交。
- 未知候选、已完成影响工单、已失效候选和变化的影响工单集合均在 API/commit 前拒绝。
- API-006 成功后只原子写 CONFIRMED 推荐草稿和确认/调整溯源；Plan、Track、WorkOrder 事实不变。
- commandId 重放返回第一次冻结结果，不重复 Gateway、Store commit、workflow 更新或 audit；成功、拒绝和失败均通过动态 ledger 追加写，不覆盖 C04 审计。
- API-025 reset 重新建立 executor 并清空 replay/failure/trace/暂存计算缓存与 C05 序号，同时保留 C03 reset 审计的可追溯性。

## SCN-02 `resolvedFaultObjects` 兼容边界

SCN-02 的缺失 `trackNo` 仍只存在于 Mock 投影，不污染合法 Store Plan。只有同时满足以下条件才记录解决并放行后续推荐：

1. 场景精确为 SCN-02；
2. API-004 请求通过 strict Schema，且 `supplements` 完整提供该对象声明的全部缺失字段；
3. 解决记录的对象 ID 与后续 API-005/API-006 路径 planId 相同；
4. 该对象所需缺失字段全部存在于 `resolvedFaultObjects`。

字段不全、非法请求、API-004 失败、不同对象、其他场景和 reset 后请求继续返回原始故障；非 API-005/API-006 不继承豁免。投影恢复也只有在调用方显式请求同一已解决对象时才恢复字段。SCN-02 的正式 E2E 从 UI-002 补录、v3 确认后不刷新页面，连续进入 UI-003 并完成推荐，证明 TOS-EXT-002 不会错误阻断后续链路。

## UI-003 页面状态与交互

页面沿用 C04 Ant Design 工作台，包含计划摘要、候选卡片、排除项、接车时间轴、规则说明抽屉、人工调整/确认抽屉、确认反馈、命令追踪和 UI-004 入口。workflow 只保存候选选择、两个抽屉开关和最近命令错误，不复制领域数组。

页面覆盖六种状态：

- `loading`：direct refresh 或自动 API-005 计算期间；
- `empty`：严格草稿存在但所有股道硬排除，展示全部原因和重新计算；
- `business-error`：Plan 未确认、草稿非法或结构化业务失败；
- `network-error`：API-005 外部错误或 malformed 信封，保留重新计算；
- `forbidden`：非 DISPATCHER 在懒加载模块和 API-005 前由既有路由边界进入 403；
- `not-found`：未知 planId 或 AREA-A 越界，不泄露计划、候选或关联数据。

生产 direct refresh 仍先按 URL `scenarioId` 严格执行 API-025 bootstrap，再创建唯一 runtime；页面随后从 Store 读取 planId 真值并自动计算。确认成功后候选不可再编辑，显示 RC-04 追踪、人工调整溯源（如有）和精确 `/dispatch/plans/:planId/tasks` 链接。

## 测试与证据

- Task 8 focused Vitest：`16/16` 文件、`97/97` 测试。
- 全量 Vitest：`42/42` 文件、`322/322` 测试。
- 构建：`1875 modules transformed`，通过。
- C05 Playwright：`4/4`；全量 Playwright：`23/23`。
- C05 E2E 文件：`e2e/ui-003-reception-recommendation.spec.ts`，覆盖 SCN-01 首选确认、SCN-01 T3 调整、SCN-02 连续完成、权限/职责分离/版本恢复。
- 8 张截图：`docs/evidence/C05/C05-UI003-*.png`，两种视口，逐张索引见 `docs/evidence/C05/screenshot-index.md`。
- 机器覆盖：`docs/evidence/C05/coverage.json`。
- 完整命令、哈希、TSC、尺寸和范围审计：`docs/evidence/C05/verification.md`、`docs/evidence/C05/tsc-after.txt`。
- TDD RED：`compatibility-red.txt`、`recommendation-red.txt`、`page-red.txt`、`action-red.txt`。

## 已知边界

1. 100 分模型是固定 Demo 规则，不是生产调度优化器；未引入实时路况、联锁或容量求解。
2. 冻结 DO-001 没有计划优先级和关联箱量，页面明确显示“数据未提供”，不推断默认值。
3. UI-004 仍为 C01 骨架；C05 不实现 API-007、任务拆解或工单生成。
4. 浏览器使用本地 MSW 和确定性 fixture，不连接真实生产后端。
5. 全局 TSC 因冻结依赖缺少 React declaration 保留 360 条 TS2604/TS7016/TS7026；C05 非 React primary 为 0。
6. 构建继续出现既有 `>500 kB` chunk advisory，本阶段未调整打包策略。

## C06 UI-004 入口

C06 只应在 C05 Store 中存在对应 planId 的 strict `RecommendationDraft` 且 `status === 'CONFIRMED'` 后，从以下路由接手：

```text
/dispatch/plans/:planId/tasks
```

C06 应继续复用同一 `DemoRuntimeProvider`、唯一 Store、C03 权限/命令/审计和 C05 已确认草稿；不得重新计算推荐、复制 fixture、把 API 返回旧对象覆盖 Store，或绕过 API-007 的冻结契约。
