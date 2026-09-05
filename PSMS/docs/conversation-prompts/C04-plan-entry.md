# C04 调度总览与计划接收入口实施对话提示词

你现在开始独立实施 **C04：调度总览与计划接收入口**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C04 规格与逐步实施计划已经确认并冻结；请先核验仓库和基线，然后严格按照实施计划采用 TDD 完成源码、页面、测试、截图、验证、提交和交接。

## 一、工程、基点与工作分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- 起始分支：`demo/c03-state-permissions`
- C03 最终源码与交接提交：`3c9dcd7c0ba8ab9f7f9adae959277fbeefaa56ac`
- C04 设计规格提交：`151004ae53dcd88c4910380bf4d2b6b27ef81b4a`
- C04 实施计划提交：`aad25795efbf472a91228824c92b8def8daa86fb`
- C04 工作分支：`demo/c04-plan-entry`

开始前必须：

1. 进入工程，确认工作区干净。
2. 确认以上三个提交都是当前 `HEAD` 的祖先。
3. 从当前干净的 `demo/c03-state-permissions` 创建并切换到 `demo/c04-plan-entry`。若目标分支已存在，只能在确认基点正确、工作区干净且没有未知提交后继续，不得覆盖已有改动。
4. 通读并以以下文件为实施依据：
   - `docs/handoffs/C03-state-and-permissions.md`
   - `docs/superpowers/specs/2026-07-19-c04-plan-entry-design.md`
   - `docs/superpowers/plans/2026-07-19-c04-plan-entry.md`
5. 逐项执行计划中的 8 个 Task 和全部检查框，不另写竞争计划，不再次请求确认已经冻结的规格。

## 二、冻结基线闸门

开始写 C04 源码前，计算并核对以下六份 SHA-256：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一哈希不匹配时，给出期望值、实际值、文件状态和最近相关提交后停止，不得自行修改基线。全部匹配后，C04 全程禁止修改 `docs/baseline/**`。

在源码修改前保存并记录以下回归：

```powershell
pnpm exec tsc --noEmit
pnpm test -- --run
pnpm build
pnpm test:e2e
```

C03 交接基线是：Vitest 19/19 文件、165/165 测试通过，build 通过，Playwright 14/14 通过；全局 `tsc --noEmit` 保留 38 条既有 React 诊断，而 C03 范围诊断为 0。完整输出写入 `docs/evidence/C04/preflight.md` 和 `tsc-before.txt`。若 Vitest、build 或 E2E 退化，先排查恢复，不得把退化带入 C04。

## 三、唯一业务范围

C04 只完成两个页面：

- **UI-001 调度总览**：KPI、重点计划、风险与接口状态、简化场区/股道示意、进入计划台账的入口。
- **UI-002 计划接收台账**：查询筛选、计划表格、详情抽屉、校验问题、接口状态以及完整确认流程。

确认流程必须明确展示：

```text
计划同步 → 自动校验 → 字段补录/冲突处理 → 计划确认 → 开放推荐入口
```

确认成功后只显示前往既有 `/dispatch/plans/:planId/recommendation` 的入口。**不得实现 UI-003 的推荐计算、方案内容或确认逻辑。**

视觉以现有 Ant Design 组件为主，借鉴 Ant Design Pro Analysis 的总览卡片结构和 Query Table 的筛选/表格/操作布局即可。页面要像可演示的业务系统，但确认流程、状态反馈和权限边界优先；不新增 UI 框架、不下载页面模板、不追求复杂动画或地图。

## 四、运行架构与数据所有权

必须按设计形成一个运行时闭环：

- 根 `DemoRuntimeProvider` 持有唯一 C03 vanilla Store、严格 `PlanEntryGateway`、页面级 workflow Store 和计划命令服务。
- 应用渲染前启动现有 MSW browser worker；提交由当前已安装 MSW 生成的 `public/mockServiceWorker.js`。未处理的 `/mock/**` 请求必须报错。
- Gateway 只覆盖 API-001、API-002、API-003、API-004、API-025：`/mock/overview`、`/mock/plans`、`/mock/plans/sync`、`/mock/plans/:id/confirm`、`/mock/demo/reset`。
- 每个成功或失败信封都先用 C02 Zod Schema 严格解析，再返回 UI；畸形信封必须失败，不得宽松接受。
- 领域对象的唯一真相仍是 C03 Store。页面只读 selector 投影，写动作只能经过命令执行器；不得让 API 返回旧对象覆盖 Store，也不得在 React 组件里直接写领域数组。
- workflow Store 只保存 `selectedPlanId`、重试/熔断、最近成功时间和已解决字段名，不复制计划领域对象。
- URL query 保存日期、作业区、场景、批次、车次、状态、异常类型、分页、排序、`planId` 和来源；解析与序列化必须可确定 round-trip。
- DO-001 没有作业区字段，因此本 Demo 的计划 fixture 明确视为 AREA-A 数据集。先按会话 `dataScope` 与 query `workArea` 过滤，再计算对象列表和 KPI；超出范围时对象和计数都为 0。
- UI-001、UI-002 必须保留 C01 路由烟测依赖的精确可见标记和现有路由路径。

页面数据只能来自以下 7 个纯 selector，不得在页面再写一套业务映射：

```text
selectOverviewKpis
selectVisibleYardObjects
selectOpenRisks
selectPlanLedger
selectInterfaceHealth
selectValidationIssues
selectPlanDetails
```

## 五、两项且仅两项兼容补充

规格已经明确批准以下两个最小语义变化，无需再次询问，但不得扩大范围。

### 1. DO-001 状态机

增加：

```text
ADJUSTED + confirm → CONFIRMED
```

DO-001 完整允许迁移数从 52 变为 **53**。同步更新 transition catalog、类型和覆盖断言；未知状态、未知命令及其他禁止迁移仍按原行为拒绝。

### 2. SCN-02 的 API-004 恢复条件

仅当以下条件同时满足时，允许 C02 handler 跳过 SCN-02 固定的 `TOS-EXT-002`：

- 当前场景是 SCN-02；
- 当前契约是 API-004；
- `supplements` 包含该场景 mutation 声明的全部缺失字段；
- 请求通过既有严格 Schema。

其他 API、其他场景、补录不完整、非法请求和既有错误码行为完全不变。补录与确认两次 API-004 都携带完整 `supplements`。必须先补 C02/C03 回归测试，保存有效红灯，再实现这两项变化。不得修改 OpenAPI、fixture、公开错误码或另造 endpoint。

## 六、Gate A：运行连接

先红后绿，红灯保存到 `docs/evidence/C04/runtime-red.txt`。至少证明：

- browser Mock 正确启动，只有一个 Store/Provider，两个消费者观察同一状态；
- Gateway 的方法、路径、body 和成功/失败信封解析严格；
- query round-trip、默认值、非法输入回退确定；
- workflow 第 3 次失败时打开熔断，reset 清空全部页面状态；
- 7 个 selector 均为纯投影，数据域在计数前过滤；
- SCN-02 仅把 mutation 声明的 `trackNo` 投影为缺失，不删除 Store 中的严格字段；
- 两项兼容补充及既有 C02/C03 回归全绿。

Gate A 全绿后才进入页面实现。

## 七、Gate B：UI-001 调度总览

先保存 `docs/evidence/C04/overview-red.txt`，再实现：

- 正常场景 KPI、重点计划、风险/接口卡片和简化场区示意；
- SCN-03/SCN-04/SCN-05 的风险与接口投影；
- 权限、数据域、重置与进入 UI-002 的跨页 URL；
- 1440×900 和 1280×720 下无横向页面滚动。

只使用现有 Ant Design 与项目 token。无权对象不得先加载再隐藏；操作按钮使用既有 `PermissionGate` 展示、禁用或解释原因。

## 八、Gate C：UI-002 计划确认

先保存 `docs/evidence/C04/plan-ledger-red.txt`，再实现筛选、表格、详情抽屉、五段流程和三条主路径。

### 正常路径 SCN-01

- PLAN-001 初始 `PENDING_CONFIRM`。
- 同步与自动校验成功后执行确认。
- 最终成为 `CONFIRMED`，推荐入口出现。

### 字段缺失 SCN-02

- PLAN-002 的严格 Store 对象保持不变，页面投影缺少 `trackNo` 并显示 `TOS-EXT-002`。
- 补录字段固定为 `trackNo`、`reason`、`effectiveUntil`、`reviewerId`；`effectiveUntil` 与 `reviewerId` 都位于 API-004 的 `supplements` 内。
- `reviewerId` 必须与当前 actor 不同。相同人员提交返回 `TOS-AUTH-001`，状态不变并写拒绝审计。
- `adjust` 成功后重新校验；完整 supplements 再执行 `confirm`，PLAN-002 由 `ADJUSTED` 变为 `CONFIRMED`。

### 接口超时 SCN-03

- API-003 连续失败 3 次，每次返回 `TOS-EXT-001`，第 3 次打开熔断。
- 熔断后再次同步直接返回冻结的最近失败，不新增 Gateway 请求、Store 变化或审计。
- 恢复操作只能由精确角色 `INTERFACE_OPS` 执行，权限映射仅为 `interface:retry`；不要额外要求 `demo:reset`。
- 恢复调用 API-025，原子重置 Mock runtime、Store、workflow、审计序号、重试计数和命令幂等缓存到 SCN-01，然后同步成功。

Gate C 还必须覆盖权限拒绝、版本冲突、失败时保留表单、命令 loading、错误提示和审计。Gate C 全绿前不得宣称 C04 完成。

## 九、权限、命令、审计与 reset

- `sync → plan:view → API-003`；`adjust → plan:adjust → API-004`；`confirm → plan:confirm → API-004`。
- 普通场景 reset 使用 `demo:reset`；SCN-03 → SCN-01 的恢复使用精确 `INTERFACE_OPS + interface:retry`。
- 权限 resolver 从 `supplements.reviewerId` 构造 applicant/approver 职责分离上下文。
- 继续使用 C03 固定判定顺序、命令幂等、expectedVersion 和一次 root replace；失败不得部分提交。
- 成功、失败、拒绝各追加恰好一条有效 C03 审计；相同 `commandId` 重放不再次请求、提交或审计。
- ID、trace、audit 与时间必须来自可重置的确定性计数和 `session.demoTime`，不得用 Date/random。
- API-025 reset 使用 `createFixtureSnapshot()` 重建权威状态，并同步更新有效会话存储；不得维护第二套 reset fixture。

## 十、E2E、截图与演示证据

在既有 14 条 E2E 之外恰好新增 5 条 C04 E2E：

1. UI-001 → UI-002 → PLAN-001 确认 → 推荐入口出现。
2. SCN-02 → PLAN-002 缺 `trackNo` → 补录/异人复核 → 重新校验 → 确认。
3. SCN-03 → 连续失败三次 → 熔断 → INTERFACE_OPS 恢复到 SCN-01 → 同步成功。
4. 无权角色不加载对象；DISPATCHER 不可恢复接口；拒绝审计存在。
5. UI-001/UI-002 往返保留日期、作业区、筛选与 `planId`。

全量 Playwright 最终必须为 **19/19（14 既有 + 5 C04）**。保存并逐张 100% 检查以下 12 张截图：

```text
C04-UI001-SCN01-1440x900.png
C04-UI001-SCN01-1280x720.png
C04-UI002-SCN01-CONFIRMED-1440x900.png
C04-UI002-SCN01-CONFIRMED-1280x720.png
C04-UI002-SCN02-MISSING-1440x900.png
C04-UI002-SCN02-MISSING-1280x720.png
C04-UI002-SCN02-CONFIRMED-1440x900.png
C04-UI002-SCN02-CONFIRMED-1280x720.png
C04-UI002-SCN03-CIRCUIT-1440x900.png
C04-UI002-SCN03-CIRCUIT-1280x720.png
C04-UI002-SCN03-RECOVERED-1440x900.png
C04-UI002-SCN03-RECOVERED-1280x720.png
```

截图必须没有裁切、重叠、横向滚动、抽屉溢出、不可读表格、缺字或异常空白。生成 `docs/evidence/C04/screenshot-index.md`，逐项记录页面、场景、视口、测试和检查结果。若回归覆盖 C01 旧截图，恢复旧文件，不把非 C04 截图改动提交进来。

## 十一、硬性禁止事项

- 不修改 `docs/baseline/**`、`package.json` 或 `pnpm-lock.yaml`，不安装、升级或新增依赖。
- 不实现 UI-003 业务内容，不扩展到 UI-004～UI-013，不改现有 13 条路由。
- 不新增第二套 fixture、领域对象、角色映射、权限 catalog、endpoint 或公开错误码。
- 不使用 `any`，不放宽 Zod Schema、TypeScript 配置或 `additionalProperties` 来掩盖错误。
- 不让页面直接修改领域 Store，不在组件里内联角色判断，不用 API 旧对象覆盖已提交状态。
- 不以静默状态保持、假成功、跳过测试、改断言或删除回归测试解决失败。
- 除第五码文批准的两项补充外，不修改 C02/C03 公开语义。

## 十二、实施节奏、冲突规则与提交

- 严格执行计划 Task 1～Task 8：先写由缺少预期能力导致的有效红灯，再做最小实现，再跑聚焦与相关回归，再按计划提交。
- 每个 Task 使用计划给出的文件路径、测试命令、输出证据和建议提交边界，保持提交小而清晰。
- 一般实现困难、类型错误、测试失败、视觉调整或环境问题都不是“需求冲突”，应继续定位和修复。
- 只有实时六哈希不匹配，或已确认规格/实施计划与冻结机器契约在同一具体字段、状态迁移或权限条目上确实无法兼容时才能停止。停止时给出文件、行号、双方原文、最小复现和建议决策，不得只给概括性描述。

## 十三、最终验证与交接

至少执行：

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
pnpm exec tsc --noEmit
git diff --check
```

并执行计划列出的 C04 focused Vitest 集。最终要求：

- C04 focused tests 与全量 Vitest 全绿，并报告实际文件数/测试数；
- build 通过；Playwright 19/19；
- C04 新增文件的 TypeScript primary diagnostics 为 0；全局 38 条既有诊断若仍存在，按实际结果记录，不得宣称全局 tsc 通过；
- 六份冻结基线哈希全部不变；工作区干净；
- `docs/evidence/C04/coverage.json` 枚举 2 页面、7 selectors、5 APIs、3 主流程、6 UI 状态、4 相关角色、5 E2E、12 截图、53 条 DO-001 允许迁移及测试名/状态；
- `docs/evidence/C04/verification.md` 记录命令、环境、时间、退出码、测试数量、build、E2E、tsc、哈希、截图检查、范围检查和 `git diff --check`；
- 创建 `docs/handoffs/C04-plan-entry.md`，列明 runtime/gateway/hooks、selector、query、workflow、页面组件、三条流程、两项兼容补充、命令/审计/reset 语义、测试证据、截图、已知限制和 C05 推荐入口。

最终报告必须包含：当前分支、完整提交哈希、变更文件分类、focused/full Vitest 数量、build、Playwright 19/19、tsc 实际状态、六哈希、12 张截图、coverage、verification、handoff 和已知限制。

现在开始执行：先做工作区、祖先提交、六哈希和 C03 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重新规划，不要再次请求确认已通过的规格。
