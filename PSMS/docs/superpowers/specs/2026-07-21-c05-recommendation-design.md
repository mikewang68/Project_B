# C05 接车计划推荐设计规格

## 1. 背景与冻结点

C04 已在 `demo/c04-plan-entry` 完成 UI-001 调度总览和 UI-002 计划接收台账，最终提交为 `55944a18e22eecef437ea6394c543e9177380a2b`。C05 从既有路由 `/dispatch/plans/:planId/recommendation` 接手，将 UI-003 从页面骨架实现为可演示的接车推荐闭环。

C05 的起始验证基线为：

- Vitest：32 个文件、257 个测试通过。
- Build：通过，1,860 modules。
- Playwright：19/19 通过。
- 全局 TypeScript：因冻结依赖未包含 React 类型声明，保留 286 条 TS2604/TS7016/TS7026 根因及级联诊断；C04 非 React primary diagnostics 为 0。
- C04 截图：12/12 已检查。
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

### 2.1 采用：Store 投影与经验证的推荐草稿

API-005 先完成严格请求和响应校验。只有 API-005 成功后，纯推荐引擎才读取 C03 Store 中的 Plan、Track 和 WorkOrder，生成确定性候选、评分、排除项及时间窗，并把严格校验后的推荐草稿原子写入现有 `recommendation.drafts`。

API-006 确认通过统一命令管线执行权限、版本、职责分离、Schema、Mock、原子提交和追加写审计。页面和后续 UI-004 只从 Store selector 读取最终结果。

### 2.2 未采用：Mock 返回完整算法结果

冻结 API 信封只约束通用结果，现有 handler 的 API-005/006 path 过滤只返回匹配的 Plan，不包含可直接使用的候选模型。把推荐算法全部放入 Mock 会使 Store 与 Mock 各维护一套领域事实，并扩大 C02 handler 的业务职责。

### 2.3 未采用：页面 workflow 保存完整结果

页面局部状态适合保存当前选中候选和抽屉开关，不适合保存推荐领域结果。完整结果只放在 workflow 会导致刷新丢失、无法被 UI-004 复用，并绕过 C03 原子 Store 与审计边界。

## 3. 目标与非目标

### 3.1 目标

1. 实现 UI-003 单页业务内容并保留现有路由和 C01 烟测标记。
2. 展示计划摘要、权威来源、候选股道、错峰窗口、综合分、推荐依据和排除原因。
3. 支持首选确认、非首选人工调整、影响工单分析和异人复核。
4. 严格调用 API-005 和 API-006，不新增 endpoint，不放宽冻结 Schema。
5. 复用 C04 单一 `DemoRuntimeProvider`、C03 Store、权限、命令、审计、确定性时钟和 reset。
6. 连续演示 SCN-01 与 SCN-02，并提供单元、组件、E2E 和视觉证据。
7. 确认成功后生成已确认推荐草稿并开放 UI-004 入口。

### 3.2 非目标

1. 不实现 UI-004 任务拆解内容、API-007 或工单生成。
2. 不连接真实 95306、铁路调度、优化求解器或生产推荐服务。
3. 不宣称 Demo 评分是生产算法、容量模型或调度承诺。
4. 不修改 Plan 或 Track 的运输事实，不提前占用股道。
5. 不引入新依赖、图表库、页面模板或第二套状态容器。
6. 不修改 `docs/baseline/**`、`package.json` 或 `pnpm-lock.yaml`。
7. 不扩展 UI-001、UI-002 或 UI-004 的业务内容；只允许 C05 需要的窄接口和交接改动。

## 4. 权威资料与冲突规则

实施时按以下优先级判定：

1. 用户在本轮确认的范围、完整推荐闭环、方案 1 和 SCN-02 最小兼容规则。
2. 本设计规格及后续 C05 实施计划。
3. `docs/handoffs/C04-plan-entry.md` 与 C04 最终源码。
4. 页面任务卡 v0.2 中 UI-003 章节和开发任务分解 v0.2 中 TASK-P2-UI-003。
5. 需求分析文档 FR-001-04、FR-001-05、FR-001-06、DR-001～DR-004。
6. 六份冻结机器基线及 C02/C03 公共契约。

冻结基线不得因实现方便而改变。若较早任务卡要求的字段在冻结 Schema 中不存在，页面明确显示数据未提供，不虚构值，也不向严格领域对象加字段。

## 5. 页面范围与视觉结构

UI-003 路由保持：

```text
/dispatch/plans/:planId/recommendation
```

页面沿用 C04 调度工作台的颜色、间距、PageIdentity、状态面板和权限交互，只使用现有 Ant Design 与简单 CSS。

### 5.1 顶部区域

- 面包屑：调度总览 / 计划接收台账 / 接车计划推荐。
- 返回 UI-002 的入口保留 C04 query 来源信息。
- 场景、角色、接口与固定演示时间提示。
- `PlanSummaryCard` 展示计划编号、批次、车次、货类、到发时间、当前股道、状态、版本、来源系统、来源时间和冲突。
- 冻结对象没有可可靠关联到 Plan 的箱量，也没有计划优先级字段；这两项显示“数据未提供”，不按 cargoType 猜测 Waybill 关联。

### 5.2 主工作区

- 左侧 `RecommendationCardList`：候选股道、接车窗口、综合分、排序、推荐原因和来源标签。第一名标记“系统推荐”。
- 右侧 `ExcludedOptionList`：股道、排除码、明确原因和关联来源。
- `RuleExplanationDrawer`：规则版本、输入快照摘要、各评分分项、排序口径和“仅供 Demo 解释”声明。
- `TrackTimeline`：每条股道的占用状态、预计释放、计划到发区间和候选窗口；点击窗口联动候选卡。
- `AdjustmentDrawer`：最终股道、窗口、调整原因、受影响工单、复核人和权限/版本提示。
- 底部动作区：重新计算、确认接车方案；确认后替换为“进入任务拆解”。

### 5.3 响应式

- 1440×900 使用候选区与排除区左右布局，时间轴横跨内容区。
- 1280×720 将排除区移到候选区下方，抽屉限制宽度和内部滚动。
- 两种视口都不得出现页面横向滚动、卡片裁切、表单遮挡或不可读时间轴。

## 6. 推荐领域模型

C03 根 Store 继续保持 12 个切片，`RecommendationSlice` 仍为 `drafts: Record<string, unknown>`，避免 Store 层反向依赖页面 feature。C05 在 feature 内定义严格 Zod Schema，写入前和 selector 读取时均解析。

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

每个候选至少包含：

```text
candidateId, trackId, trackNo, trackVersion, occupyStatus,
windowStart, windowEnd, score, rank, recommended,
scoreBreakdown, reasons, sourceRefs
```

每个排除项至少包含：

```text
trackId, trackNo, exclusionCode, reason, sourceRefs
```

人工调整保存原首选、最终候选、原因、受影响工单 ID 和复核人。确认记录保存 actorId、roleCode、confirmedAt、commandId 和 traceId；最终审计通过 traceId 与独立追加写账本关联，不在原子提交后为补 auditLogId 再写第二次 Store。

所有对象和嵌套数组冻结；selector 返回不可变视图。API-025 reset 恢复 `drafts: {}`。

## 7. 确定性推荐规则

### 7.1 输入资格

计算前必须满足：

- Plan 存在且通过 AREA-A 数据域过滤。
- Plan 状态为 `CONFIRMED`。
- `trackNo`、`cargoType` 和合法到发时间区间存在。
- API-005 使用当前 `Plan.version` 作为 `inputVersion` 并返回严格成功信封。

不满足时不生成草稿，进入 business-error 或 not-found。

### 7.2 硬排除

按顺序评估每条 Track：

1. `occupyStatus === BLOCKED`：排除为 `TRACK_BLOCKED`。
2. `compatibleCargoTypes` 不包含 Plan.cargoType：排除为 `CARGO_INCOMPATIBLE`。
3. 占用状态不是 `FREE | OCCUPIED | RELEASING`：排除为 `UNSUPPORTED_OCCUPANCY`。
4. 非 FREE 且预计释放时间非法：排除为 `INVALID_RELEASE_TIME`。

被排除候选不可选择，排序和评分均不再执行。

上述 `TRACK_BLOCKED`、`CARGO_INCOMPATIBLE`、`UNSUPPORTED_OCCUPANCY` 和 `INVALID_RELEASE_TIME` 仅是 C05 推荐结果内部的排除原因码，用于页面解释和草稿校验；它们不是公共 API 错误码，不得写入或扩展冻结错误码清单。

### 7.3 窗口

- 原计划持续时长等于 `arrivalDepartureTime` 的结束减开始。
- FREE 股道的窗口从计划到达时间开始。
- OCCUPIED 或 RELEASING 股道的窗口从计划到达时间与 `estimateReleaseTime` 中较晚者开始。
- 窗口结束等于候选开始加原计划持续时长。
- 延迟窗口明确标记“错峰”，不表示当前即可接车。

### 7.4 Demo 评分

评分总分 100，只用于排序和解释：

- 可用效率，最高 45：FREE 45、RELEASING 35、OCCUPIED 25。
- 时间匹配，最高 30：无延迟为 30；有延迟时按每 10 分钟扣 1 分，最低 0。
- 计划连续性，最高 15：与 Plan.trackNo 相同为 15，否则为 5。
- 权威输入完整性，最高 10：计划来源为 RAIL_PLAN 且计划/股道时间戳完整为 10，否则为 0。

硬排除在评分前完成，因此安全与货类兼容不是可被效率分抵消的软分项。冻结 Plan 没有 priority 字段，优先级显示为“数据未提供”且不参与评分。

最终排序固定为：score 降序、windowStart 升序、trackNo 升序。规则版本固定为 `C05-DEMO-RULE-1.0`。

## 8. 运行架构与组件边界

### 8.1 RecommendationGateway

在 C04 Gateway 边界上新增两个严格方法：

```text
getRecommendation(planId, inputVersion) -> API-005
confirmRecommendation(planId, { trackNo, window, reason? }) -> API-006
```

Gateway 验证 method、path、query/body、HTTP transport 和成功/失败信封，并用 C05 本地 Zod Schema 校验 `data.apiId`、operationId、scenarioId、now 和匹配 Plan item。API 返回对象只证明调用及对象身份，不覆盖 Store 中的 Plan/Track。

### 8.2 RecommendationRuleEngine

纯函数输入为 Plan、Track 数组、演示时间和规则版本，输出候选及排除项。它不访问 Store、Gateway、React、Date、random 或 localStorage。

### 8.3 Recommendation selectors

精确实现任务卡的三个主 selector：

1. `selectConfirmedPlan`
2. `selectReceptionRecommendations`
3. `selectTrackConflicts`

可增加页面组合 selector，但不得复制三者的领域规则。所有 selector 在读取、计数和关联 WorkOrder 前先应用 C04 的 AREA-A 数据域规则。

### 8.4 Recommendation workflow

页面 workflow 只保存：

```text
selectedCandidateId
adjustmentDrawerOpen
ruleDrawerOpen
lastCommandError
```

草稿、候选、排除项、确认结果和影响工单不进入 workflow。

### 8.5 RecommendationCommandService

服务复用 C03 `createCommandExecutor`。计算和确认使用 SM-007 的 `execute` 技术命令，并在 payload 中携带 `businessAction = RC-01 | RC-04`。C05 审计适配器把业务动作写入 DO-013 `action`，状态机仍只接收既有 `execute`，因此不修改 C03 状态机 catalog。

执行顺序保持：

```text
权限 → 请求/业务 Schema → Gateway Mock → SM-007 transition → 单次原子 Store commit → 追加审计
```

重新计算成功写入新的 CALCULATED 草稿并清除旧选择。确认成功只把推荐草稿变为 CONFIRMED，不改变 Plan.status、Plan.version 或 Track 状态。

## 9. 动作、权限与职责分离

UI-003 页面只允许精确角色 `DISPATCHER`。无权角色在懒加载业务模块和 API 请求前进入 403。

动作要求：

| 动作 | 权限 | API | 说明 |
| --- | --- | --- | --- |
| 查看/重新计算 | `plan:recommend` | API-005 | 当前 Plan 必须已确认 |
| 选择首选 | `plan:recommend` | 无 | 仅 workflow 选择 |
| 选择非首选 | `plan:recommend` + `plan:adjust` | 无 | 原因在最终确认前必填 |
| 最终确认 | `plan:recommend` + `plan:confirm` | API-006 | 重新验证版本与候选 |

若选择非首选，且关联 WorkOrder 状态属于 `DISPATCHED | ACKNOWLEDGED | IN_PROGRESS | PAUSED`，则命令标记 `highRisk`，复核人必填且必须与 actorId 不同。相同人员返回 `TOS-AUTH-001`，不调用 API、不改草稿并追加恰好一条 DENIED 审计。

若存在 `COMPLETED` 关联工单，人工调整返回 `DEMO-SCENARIO-001` 并提示转后续变更流程，不允许在 C05 直接覆盖已完成事实。

确认命令的 API-006 body 严格保持 `{ trackNo, window, reason? }`。reviewerId、inputPlanVersion、trackVersion、affectedWorkOrderIds 和 businessAction 仅存在于 C05 命令 payload，Gateway 不把它们发送给 API-006。

## 10. SCN-01 与 SCN-02 流程

### 10.1 SCN-01 首选确认

1. 在 UI-002 把 PLAN-001 确认为 CONFIRMED。
2. SPA 导航进入 UI-003，保留单一 runtime。
3. API-005 使用当前 Plan.version 计算 CALCULATED 草稿。
4. 默认选中排序第一候选，展示依据、排除项和时间轴。
5. API-006 确认，草稿变为 CONFIRMED，显示 UI-004 入口。

### 10.2 SCN-01 人工调整

1. 从同一 CALCULATED 草稿选择非首选候选。
2. 填写调整原因；若影响执行状态工单，选择异人复核。
3. 重新校验计划版本、股道版本、候选资格和职责分离。
4. API-006 成功后保留原首选、最终候选、原因、影响工单、复核人和审计。

### 10.3 SCN-02 连续闭环

1. UI-002 完成 PLAN-002 trackNo 补录、异人复核和确认。
2. 成功的完整 API-004 在 Mock runtime 标记 PLAN-002 的场景缺字段已解决。
3. UI-003 的 API-005/006 仅对同一已解决对象跳过同一 `TOS-EXT-002`。
4. 推荐计算和确认按普通版本、权限及审计规则执行。

## 11. 经批准的 SCN-02 兼容补充

现有 C04 handler 只让完整 API-004 跳过 `TOS-EXT-002`，没有改变 Mock runtime 的故障状态；因此同一 SPA runtime 中紧接着调用 API-005/006 仍会被已解决的缺字段再次阻断。

C05 允许一项最小补充：

- MockRuntime 维护当前场景内的 `resolvedFaultObjects`，内容只包含对象 ID 和已解决字段名。
- SCN-02 的完整 API-004 成功后，记录该 pathId 及 mutation 声明的全部缺失字段。
- 仅当 API 为 API-005 或 API-006、pathId 相同、活动场景仍为 SCN-02 且全部 mutation 字段已解决时，跳过该对象的 `TOS-EXT-002`。
- 不完整 API-004、不同对象、不同场景、不同错误、其他 API 和强制故障保持原行为。
- API-025 reset 清空 `resolvedFaultObjects`。
- 六份冻结基线、公开 Schema、endpoint 和错误码均不改变。

新增 C02/C04 回归测试证明允许与拒绝边界，不把此规则泛化为任意场景故障清除。

## 12. 版本、幂等、原子性与审计

- API-005 query 的 `inputVersion` 必须等于当前 Plan.version。
- 草稿保存 inputPlanVersion 和每个候选的 trackVersion。
- API-006 前任一版本变化都返回 `DEMO-VERSION-001`，保留选择和输入，要求重新计算。
- 相同 commandId 重放返回首次冻结结果，不重复 Gateway、Store commit 或审计。
- 失败不得留下部分草稿、部分 confirmation 或已清空表单。
- 成功、失败、拒绝各追加恰好一条审计；业务动作记录 RC-01 或 RC-04。
- ID、trace、audit、generatedAt 和 confirmedAt 使用 C04 可重置计数及 `session.demoTime`，禁止 Date/random。
- reset 同时恢复 fixture、推荐草稿、workflow、兼容标记、命令幂等缓存和确定性序号。

## 13. 页面状态与直接访问

六类状态固定为：

| 状态 | 页面行为 | 恢复 |
| --- | --- | --- |
| loading | 推荐骨架屏，写操作禁用 | 等待 API-005 |
| empty | 无合格候选，保留全部排除原因 | 返回 UI-002 或重算 |
| business-error | 未确认、输入不完整、已完成工单阻断或候选失效 | 执行明确修复动作 |
| network-error | 显示错误码、接口状态、最近成功时间；保留选择与表单 | 重试/重新计算 |
| forbidden | 403、所需权限和当前数据域；不加载对象/API | 切换合法会话 |
| not-found | 对象不存在或越出 AREA-A | 返回计划台账 |

API-005 malformed envelope 和 fetch rejection 归入 network-error。API-006 业务失败或网络失败均保留候选、调整原因和复核人。

SPA 从 C04 成功确认后进入 C05 是完整演示主线。浏览器直接访问或刷新路由时仍能安全渲染；若 Demo bootstrap 已把 Store 恢复到未确认 fixture，页面显示 business-error 并引导返回 UI-002，不从 URL、缓存或 API 旧对象伪造 CONFIRMED 状态。

## 14. 文件边界

允许新建 `src/features/recommendation/**`，实现类型、Schema、规则引擎、selector、Gateway、workflow、命令服务、组件、样式和测试。

允许窄修改：

- `src/runtime/DemoRuntimeContext.tsx` 与 `src/runtime/index.ts`：注入 C05 workflow/command service。
- `src/pages/dispatch/ReceptionRecommendationPage.tsx`：替换骨架。
- `src/mocks/scenarios.ts`、`src/mocks/handlers.ts` 及对应测试：实现第 11 节兼容补充。
- C04 plan-entry 公共入口：只在需要共享返回 query、状态面板或 reviewer 选择器时增加稳定导出。
- C01 route smoke 测试：只允许保持或扩展 UI-003 精确可见标记，不改路由。

禁止改动六份 baseline、依赖文件、其他业务页面主体和 UI-004 业务实现。

## 15. TDD 闸门

### Gate A：推荐内核

- 先保存 `docs/evidence/C05/recommendation-red.txt` 和 `compatibility-red.txt`。
- 覆盖严格草稿 Schema、规则引擎、三个主 selector、API-005/006 Gateway、AREA-A 前置过滤、版本快照、原子写入、reset 和 SCN-02 兼容边界。
- C04/C03/C02 相关回归全绿后才能进入 Gate B。

### Gate B：页面展示

- 先保存 `docs/evidence/C05/page-red.txt`。
- 覆盖计划摘要、数据未提供标记、候选、排除项、规则解释、时间轴和六类状态。
- 覆盖 UI-003 精确页面权限、业务模块/API 不提前加载、返回 UI-002 和响应式布局。

### Gate C：调整与确认

- 先保存 `docs/evidence/C05/action-red.txt`。
- 覆盖首选确认、非首选原因、影响工单、异人复核、同人拒绝、API-006 严格 body、版本冲突、幂等、审计、reset 和 UI-004 入口。
- Gate C 全绿前不得宣称 C05 完成。

## 16. E2E 与视觉验收

恰好新增 4 条 C05 E2E：

1. SCN-01：UI-002 确认 PLAN-001 → UI-003 生成推荐 → 首选确认 → UI-004 入口。
2. SCN-01：选择非首选 → 填写原因和异人复核 → 确认并核对审计。
3. SCN-02：补录确认 PLAN-002 → 兼容放行 API-005/006 → 推荐确认。
4. 无权访问、同人复核拒绝和版本变化后要求重新计算。

全量 Playwright 目标为 23/23（19 既有 + 4 C05）。保存并以原始分辨率检查 8 张截图：

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

每张截图检查横向滚动、裁切、卡片重叠、时间轴可读性、抽屉溢出、表单错误、中文缺字和异常空白。结果写入 `docs/evidence/C05/screenshot-index.md`。

## 17. 最终验收与交接

- C05 focused tests 和全量 Vitest 通过并报告实际数量。
- Build 通过。
- C05 4 条 E2E 通过，全量 Playwright 23/23。
- C05 非 React primary diagnostics 为 0；全局 TypeScript 只按实际诊断记录，不伪装通过。
- 六份冻结基线哈希 6/6 不变，`git diff --check` 通过。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C05/coverage.json` 覆盖页面、3 selectors、2 APIs、2 场景、6 UI 状态、4 E2E、8 截图、权限/版本/审计/reset 和兼容规则。
- `docs/evidence/C05/verification.md` 记录命令、版本、时间、退出码、测试数量、build、E2E、tsc、哈希、截图和范围检查。
- 创建 `docs/handoffs/C05-recommendation.md`，列明公共导出、草稿 Schema、规则版本、评分、selector、Gateway、命令、权限、兼容补充、场景、证据、限制和 C06 UI-004 入口。

## 18. C05 最终对话提示词要求

用户审阅本规格并确认后，实施计划和新对话提示词必须包含：

- C04 最终提交、C05 设计/计划提交、目标分支和六份基线哈希。
- UI-003 单页范围、方案 1、完整推荐闭环、确定性规则、三道 Gate 和四条 E2E。
- 经批准的 SCN-02 `resolvedFaultObjects` 最小兼容规则。
- API-006 body 与命令上下文分离、版本、幂等、职责分离和审计要求。
- 不新增依赖、不修改基线、不实现 UI-004 业务内容的硬边界。
- 明确要求执行而非重新规划，并输出提交、测试、E2E、8 张截图、coverage、verification 和 handoff。
