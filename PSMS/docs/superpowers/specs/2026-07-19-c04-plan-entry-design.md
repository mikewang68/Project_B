# C04 调度总览与计划接收入口设计规格

## 1. 背景与冻结点

C01 已建立 13 路由工程骨架；C02 已完成严格 camelCase 契约、14 个领域对象、25 个 MSW handler、7 个确定性场景和 9 个公开错误码；C03 已在提交 `3c9dcd7c0ba8ab9f7f9adae959277fbeefaa56ac` 完成 12 个 Zustand 切片、7 类状态机、统一命令管线、13 角色/45 权限、数据域、职责分离、在线/版本规则、追加写审计和最小路由/动作门禁。当前业务页面仍为 C01 骨架。

C04 进入 P2 页面阶段，采用用户确认的“双页展示型入口”：实现 `UI-001 调度总览` 与 `UI-002 计划接收台账`，重点完成计划同步、校验、补录、确认和接口恢复闭环；`UI-003 接车计划推荐` 只保留后续跳转入口，不在 C04 实现。

六份机器基线继续冻结为：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

## 2. 方案选择

### 2.1 采用：UI-001 入口 + UI-002 深度台账

UI-001 提供甲方可理解的调度入口、KPI、重点计划、风险和接口健康；UI-002 承担完整计划确认流程。这样既遵守任务卡中 `TASK-P2-UI-002` 依赖 `TASK-P2-UI-001` 的顺序，又能在一个阶段形成可连续演示的两页内容。

### 2.2 未采用：只做 UI-002

单页范围较小，但缺少系统首页和业务入口，演示时会直接落到台账，难以说明全局态势与待办来源。

### 2.3 未采用：UI-002 + UI-003

计划到推荐的链路更长，但会提前引入候选方案、股道冲突、规则解释和人工调整，扩大 C04 范围，同时仍缺少总览入口。

## 3. 目标与非目标

### 3.1 目标

- 将 C02 浏览器 Mock、C03 Store、命令、权限和审计连接到真实页面运行时。
- 实现可直接展示的 `UI-001 /dispatch/overview` 与 `UI-002 /dispatch/plans`。
- 完成正常计划确认、SCN-02 字段缺失补录确认、SCN-03 接口超时熔断恢复三条演示流程。
- 用纯 selector 生成 KPI、计划台账、接口健康、校验问题和详情视图；所有计数在数据域过滤后计算。
- 复用 C03 `PermissionGate` 和命令执行器，保证页面不能绕过授权、状态迁移、原子提交与审计。
- 保留筛选、选中对象和跨页来源参数，并支持浏览器刷新恢复。
- 在 1440×900 和 1280×720 下形成常见企业后台质量的页面，无横向页面滚动。

### 3.2 非目标

- 不实现 UI-003 的推荐卡片、评分、排除项、调整或推荐算法。
- 不实现 UI-004～UI-013 的业务页面。
- 不制作复杂 GIS 地图；UI-001 只提供基于现有轨道和资源数据的简化场区态势图。
- 不建设通用低代码页面框架、通用报表引擎或完整组件库。
- 不接生产系统、外网服务或真实 95306。
- 不安装依赖，不修改 `package.json`、`pnpm-lock.yaml` 或冻结基线。

## 4. 权威资料与冲突规则

按以下顺序解释 C04：

1. `docs/baseline/**`：对象字段、枚举、API、错误码、fixture、页面/需求追踪和六份冻结哈希。
2. `docs/handoffs/C03-state-and-permissions.md` 及 C03 公共导出：Store、状态机、权限、命令、审计和会话语义。
3. 《网页 Demo 页面与功能任务卡 v0.2》：UI-001/UI-002 字段、布局、动作、六类 UI 状态和测试。
4. 《网页 Demo 开发任务分解与验收清单 v0.2》：P2 顺序、精确文件、验收场景和证据。
5. 《网页 Demo 技术方案设计文档 v0.4》：页面连接、运行模式和演示闭环。
6. 本规格中经用户确认的 C04 范围和兼容补充。

若机器基线与页面任务卡仍有冲突，以机器基线字段和 Schema 为准，并在页面 view-model 中解释差异；不得修改 `docs/baseline/**`。本规格第 9 节明确批准的两项源码补充用于弥合已确认的流程缺口，不再视为阻断冲突。

## 5. 借鉴样式与视觉原则

C04 借鉴同一 Ant Design 家族的现成模式，不复制整套项目代码，也不增加 Pro Components：

- UI-001 参考 Ant Design Pro Analysis 的“指标卡 + 分区面板”工作台结构：<https://preview.pro.ant.design/dashboard/analysis/>。
- UI-002 参考 Ant Design Query Table 的“筛选 + 表格 + 详情”结构：<https://ant.design/docs/spec/research-list/>。

使用项目现有 `antd@6.5.1` 的 Card、Statistic、Table、Tag、Alert、Drawer、Descriptions、Steps、Form、Modal、Result、Skeleton、Progress 等组件重组。沿用现有深蓝侧栏、白色内容卡和蓝色主色。视觉目标是“常见企业后台、信息层级清楚、演示像回事”，开发优先级始终低于确认流程、状态一致性、权限和审计。

## 6. 页面职责与布局

### 6.1 UI-001 调度总览

UI-001 负责“看态势、找待办、进入计划”，不处理复杂计划业务。

1. 页面头：页面名称、日期、作业区、当前角色、当前场景、演示时间和重置入口。
2. KPI 区：当日计划、待确认计划、已完成工单、车辆等待、设备可用率、未闭环异常，共 5～6 张卡。
3. 重点计划区：计划/车次、进度、状态、风险；计划编号和待确认 KPI 可进入 UI-002。
4. 风险与接口区：接口健康、未确认异常、联锁和离线摘要。
5. 场区态势简图：以现有 Track/Resource/Appointment 数据绘制轨道、车辆和状态，不使用复杂地图或新增素材。

支持 SCN-01 正常状态及 SCN-03/04/05 的告警投影。进入 UI-002 时只传日期、作业区、场景、状态、planId 和来源意图，不传完整业务对象。

### 6.2 UI-002 计划接收台账

1. 接口健康条：95306/计划接口状态、最近成功时间、重试次数、熔断和恢复动作。
2. 筛选区：计划批次、车次、时间范围、状态、异常类型；写入 URL 并支持刷新恢复。
3. 计划台账：来源、批次、车次、股道、货类、缺失字段、校验、同步、更新时间和动作。
4. 详情抽屉：计划摘要、字段来源、脱敏原始摘要、校验问题、重试记录、变更记录和五段流程进度。
5. 待处理区：缺失字段、冲突原因、建议责任角色和恢复条件。

核心五段流程固定为：

```text
计划同步 → 自动校验 → 字段补录/冲突处理 → 计划确认 → 开放推荐入口
```

确认成功后显示“查看接车推荐”，导航到现有 `/dispatch/plans/:planId/recommendation`；C04 不修改 UI-003 骨架内容。

## 7. 运行架构

### 7.1 DemoRuntimeProvider

在应用根部增加一个运行 Provider，持有：

- 唯一 `DemoStoreApi`；
- 当前合法 C03 会话；
- C03 command executor；
- `PlanEntryGateway`；
- 只包含抽屉、筛选、重试、熔断和已解决字段标记的 `PlanEntryWorkflowState`。

领域对象只存在于 C03 根 Store。页面流程状态不得复制 Plan、Waybill、Track 或其他领域数组。Provider 为页面提供稳定 hooks；页面不得直接 import `mockRuntime` 或直接调用 Store mutation。

### 7.2 浏览器 Mock 启动

复用现有 `src/mocks/browser.ts` 和 handlers。应用渲染前启动 MSW worker，并提交由当前已安装 MSW 生成的 `public/mockServiceWorker.js`。未处理的 `/mock/**` 请求必须报错，静态资源可 bypass。测试通过注入 gateway/Store，不依赖真实 Service Worker。

### 7.3 PlanEntryGateway

Gateway 负责 API-001、API-002、API-003、API-004、API-025 的请求、严格信封解析和 UI 错误归一化。GET 用于证明接口状态并获取权威响应；页面领域视图仍由 Store selector 生成。写动作由命令执行器调用 Gateway 的 Mock 端口，页面不得先 fetch 成功再绕过命令执行器修改 Store。

### 7.4 Selector 与页面投影

新增并导出：

- `selectOverviewKpis`
- `selectVisibleYardObjects`
- `selectOpenRisks`
- `selectPlanLedger`
- `selectInterfaceHealth`
- `selectValidationIssues`
- `selectPlanDetails`

selector 输入为 `DemoRootState`、页面 query 和必要的只读 workflow 状态，输出不可变 view-model。SCN-02 的 `trackNo` 缺失是场景投影：严格 Plan 在 Store 中始终通过 DO-001 Schema；页面根据 `scenario.activeFault.mutations` 显示缺失，补录成功后由 workflow 的已解决标记停止投影。不得把缺字段对象写入严格 Store。

### 7.5 计划命令适配

页面动作与状态机命令分离映射，禁止把 permission code 当作状态机 command：

| 页面动作 | 状态机 command | permission | API |
| --- | --- | --- | --- |
| 模拟同步 | `sync` | `plan:view` | API-003 |
| 字段补录 | `adjust` | `plan:adjust` | API-004 |
| 确认计划 | `confirm` | `plan:confirm` | API-004 |
| 恢复接口 | reset workflow | `interface:retry` 且角色为 INTERFACE_OPS | API-025 |

命令权限 resolver 从动作映射 permission，并从 `supplements.reviewerId` 构造 applicant/approver 职责分离上下文。API-004 的命令 payload 恰好为 `{ reason, supplements }`，可以直接由冻结 Schema 校验。

实际 API 成功信封先由 C02 Schema 解析；Gateway 随后从当前 Store 读取目标 Plan，把其当前 status 作为命令执行器的 transition 输入。原子 commit 在完整 Store candidate 中再次调用同一个纯 `transitionState` 得到 next state，合并经过 Schema 校验的 supplements，清理页面校验标记并递增版本一次。两个 transition 调用对同一当前状态、command 和 catalog 必须得到同一结果，否则拒绝提交。

C04 会话中的领域读取以 Store 为准；Mock runtime 负责请求校验、确定性延迟、故障和信封，不从 API 返回的旧对象覆盖已提交 Store。API-025 reset 同时重置 Mock runtime、Store、workflow、审计序号和命令幂等缓存，恢复权威 fixture 状态。

## 8. 页面交互与数据流

### 8.1 正常确认

1. UI-001 选择 SCN-01，点击待确认计划或 PLAN-001。
2. UI-002 按 URL 定位记录，打开详情抽屉。
3. `plan:confirm` 门禁、API004Request Schema、MSW、DO-001 状态迁移依次通过。
4. Store 单次提交为 `CONFIRMED`，版本递增一次，追加一条成功审计。
5. 页面显示“查看接车推荐”。重复 commandId 返回首次结果，不重复提交或审计。

### 8.2 SCN-02 字段缺失

1. UI-001 投影待确认/阻断红点；进入 UI-002 后定位 PLAN-002。
2. 页面根据场景 mutation 显示 `trackNo` 缺失和 `TOS-EXT-002` 恢复说明。
3. DISPATCHER 填写 `trackNo`、`reason`、`effectiveUntil`、`reviewerId`。`reviewerId` 不得等于当前 actorId。
4. `effectiveUntil` 与 `reviewerId` 放入 API004Request 已允许扩展的 `supplements` 对象；`reason` 使用顶层可选字段，不修改 OpenAPI。
5. 第一次命令使用 `adjust`，成功后 PLAN-002 为 `ADJUSTED`、补充字段写入、版本递增并清除页面缺失投影。
6. 重新校验通过后，第二次命令使用 `confirm`，成功后为 `CONFIRMED`、版本再次递增并开放 UI-003。
7. 补录和确认各有独立 commandId 与审计；任一步失败不产生部分提交。

### 8.3 SCN-03 接口超时

1. 计划同步连续失败，展示 `TOS-EXT-001`、保留筛选和用户输入。
2. 第三次失败后 workflow 标记“已熔断”，停止自动请求。
3. 只有 `INTERFACE_OPS` 显示可执行“恢复接口”；DISPATCHER 只能查看原因和所需角色。页面限制可比 C03 通用 catalog 更窄，不得放宽权限。
4. 恢复调用 API-025 重置到 SCN-01，原子重置 Store、页面 workflow、命令幂等缓存和计数，再执行一次同步。
5. 成功后健康条显示正常，并记录恢复前后状态与审计。

## 9. 经批准的兼容补充

现有 C02/C03 基线无法单独完成任务卡要求的“SCN-02 补录后确认”：SCN-02 对 API-004 固定失败，且 DO-001 没有 `ADJUSTED + confirm` 迁移。C04 明确批准以下两项最小源码补充，六份机器基线保持不变。

### 9.1 状态机补充

在 DO-001 catalog 增加且只增加：

```text
ADJUSTED + confirm → CONFIRMED
```

原 52 条允许迁移变为 53 条。更新 C03 状态机测试和覆盖证据，证明所有既有迁移不变且新迁移具名覆盖。

### 9.2 SCN-02 恢复条件

在 C02 handler 的场景失败判定中增加窄条件：仅当活动场景为 SCN-02、API 为 API-004，且 `supplements` 提供了该场景 mutation 声明的全部缺失字段并通过请求 Schema 时，跳过 `TOS-EXT-002` 场景失败。其他 API、场景、字段不全、非法请求和错误码行为保持不变。

补充和确认两次 API-004 均携带完整 supplements。新增 C02 回归测试证明缺字段仍失败、补齐字段成功、其他场景不受影响。不得增加第二套 endpoint、fixture 或错误码。

## 10. 权限、职责分离与审计

- UI-001 使用 C03 `overview:view`；UI-002 使用 `plan:view`。
- 总览导出、场景重置、计划调整、计划确认和接口恢复分别查询现有 permission catalog。
- 补录只对 DISPATCHER 开放；接口恢复进一步收窄为 INTERFACE_OPS；确认仍遵循 C03 `plan:confirm` catalog。
- reviewerId 与 actorId 相同，在命令权限阶段拒绝并返回 `TOS-AUTH-001`；业务状态不变，追加拒绝审计。
- 页面禁用或隐藏按钮不替代命令授权；直接调用仍必须被拒绝。
- 审计复用严格 DO-013 wrapper，记录 actor、role、dataScope、action、entity、before/after、result、errorCode、traceId、client/server time；不实现 UI-013 查询页。

## 11. UI 状态与错误处理

两个页面均实现任务卡固定六类状态：loading、empty、business-error、network-error、forbidden、not-found，并有正常 data 状态。

- loading：Skeleton，写操作禁用。
- empty：保留当前筛选摘要和重置筛选入口。
- business-error：展示公开 errorCode、原因和允许的恢复动作。
- network-error：展示重试、接口健康和最近成功时间，不清空输入。
- forbidden：由 C03 路由门禁处理，不加载或泄露业务对象。
- not-found：提示对象已变化，允许返回列表或刷新，不提交旧版本。
- version conflict：`DEMO-VERSION-001`，保留用户输入并提示刷新后重试。

页面只能展示 C02 的 9 个公开错误码，不创建 C04 专用错误码。确定性测试使用 fake timers 或注入时钟，不真实等待 1500ms。

## 12. 文件边界

允许创建或修改：

- `src/runtime/**`：Provider、hooks、browser Mock bootstrap。
- `src/features/plan-entry/**`：Gateway、query codec、workflow、命令适配、view-model selectors 和共享页面组件。
- `src/pages/dispatch/OverviewPage.tsx`
- `src/pages/dispatch/PlanLedgerPage.tsx`
- `src/pages/__tests__/OverviewPage.*.test.tsx`
- `src/pages/__tests__/PlanLedgerPage.*.test.tsx`
- `src/commands/stateMachines.ts` 及对应测试/覆盖证据，仅第 9.1 节补充。
- `src/mocks/handlers.ts` 及对应测试，仅第 9.2 节补充。
- `src/main.tsx`、`src/app/App.tsx`、必要的共享布局/样式。
- `public/mockServiceWorker.js`
- `e2e/ui-001-dispatch-overview.spec.ts`
- `e2e/ui-002-dispatch-plans.spec.ts`
- `docs/evidence/C04/**`
- `docs/handoffs/C04-plan-entry.md`

禁止修改：

- `docs/baseline/**`
- `package.json`、`pnpm-lock.yaml`
- 14 个 C02 领域 Schema、API catalog、公开信封和错误码集合
- C03 permission catalog 的角色或权限集合
- UI-003～UI-013 的业务页面内容
- C01/C02/C03 历史证据，除第 9.1 节要求的新增状态机覆盖记录必须在 C04 证据中说明，不回写伪造旧结果

## 13. TDD 闸门

### Gate A：运行连接

- 先保存有效红灯到 `docs/evidence/C04/runtime-red.txt`。
- 覆盖 MSW browser bootstrap、唯一 Store/Provider、Gateway 信封解析、query round-trip、7 个 selector、数据域先过滤后计数、SCN-02 严格对象投影和 reset。
- 覆盖第 9 节两项兼容补充及既有 C02/C03 回归。

### Gate B：UI-001 总览

- 先保存 `overview-red.txt`。
- 覆盖正常内容、KPI、重点计划、风险/接口、场区简图、SCN-03/04/05 投影、权限、重置和跨页 URL。
- 视觉以 Ant Design Pro Analysis 结构为参考，不要求复杂动画或地图。

### Gate C：UI-002 计划确认

- 先保存 `plan-ledger-red.txt`。
- 覆盖筛选、表格、抽屉、五段流程、正常确认、SCN-02 补录/异人复核/确认、SCN-03 三次失败/熔断/运维恢复、权限拒绝、版本冲突和审计。
- Gate C 全绿前不得宣称 C04 完成。

## 14. E2E 与验收

新增 E2E 至少覆盖：

1. UI-001 → UI-002 → PLAN-001 确认 → 推荐入口出现。
2. SCN-02 → PLAN-002 缺 trackNo → 补录和异人复核 → 重新校验 → 确认。
3. SCN-03 → 连续失败三次 → 熔断 → INTERFACE_OPS 恢复到 SCN-01 → 同步成功。
4. 无权角色不加载对象；DISPATCHER 不可执行接口恢复；拒绝审计存在。
5. UI-001/UI-002 往返保留日期、作业区、筛选和 planId。

最终验收：

- UI-001/UI-002 的 render、permission、action 测试全绿。
- C03 全量 Vitest 基线 165/165 和 C01 Playwright 基线 14/14 不退化。
- C04 新 E2E 全绿，构建成功，C04 TypeScript primary diagnostics 为 0。
- 1440×900 和 1280×720 无横向页面滚动。
- 保存 UI-001/UI-002 正常、字段缺失、接口熔断截图。
- 六份冻结基线 SHA-256 全部不变，`git diff --check` 通过。
- 生成 `docs/evidence/C04/coverage.json`、`verification.md` 和截图索引。
- 创建 `docs/handoffs/C04-plan-entry.md`，列明公共导出、页面流程、兼容补充、测试、截图、限制和 C05 推荐入口。

## 15. C04 对话提示词要求

用户规格确认后，实施计划与最终新对话提示词必须包含：

- C03 最终提交、C04 设计/计划提交、目标分支和六基线哈希。
- 本规格的双页范围、借鉴来源、五段确认流程、两项兼容补充和三个 TDD Gate。
- 不新增依赖、不修改基线、不实现 UI-003 业务内容的硬边界。
- 明确要求执行而非重新规划，并输出提交、测试、E2E、截图、coverage、verification 和 handoff。
