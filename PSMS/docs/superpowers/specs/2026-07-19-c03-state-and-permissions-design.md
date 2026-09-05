# C03 状态与权限设计规格

## 1. 背景与冻结点

C01 已建立 13 路由工程骨架，C02 已在提交 `94ce3961a8bad0e7b9aceca3980fbb30bfa66877` 完成严格 camelCase 契约、14 个领域对象、25 个 MSW handler、7 个确定性场景和 9 个公开错误码。当前分支为 `demo/c02-contracts-mock`，工作区干净。

C03 承接技术方案的 `TASK-P1-003` 与 `TASK-P1-004` 中“状态、命令与权限”部分，形成后续 13 个业务页面共同依赖的状态和授权内核。选择一次完成“状态内核 + 权限内核 + 最小门禁 UI”，不把权限延后到页面阶段，也不在本阶段制作业务页面。

实施时从 C02 提交创建 `demo/c03-state-permissions`。C02A 六份冻结基线及 C02 源码是只读输入，不得修改。

## 2. 方案选择

### 采用：状态与权限纵向闭环

在一个 C03 中实现 12 个 Zustand Store 切片、7 类状态机、统一命令管线、13 角色授权、数据域、职责分离、在线要求、脱敏、追加写审计以及最小 `PermissionGate`/403/导航过滤。

优点是写动作从一开始就不能绕过授权和审计，后续页面只需消费稳定 selector、command 和 permission 接口。代价是 C03 规模大于单纯 Store 任务，因此实施时必须设置“状态层”和“权限层”两个独立 TDD 闸门。

### 未采用：拆成 C03A 与 C03B

先做 Store/状态机，再做权限，单次改动更小，但中间阶段会产生无权限保护的命令接口，并增加交接、重复回归和集成返工。

### 未采用：只做无 UI 的基础内核

可以缩短 C03，但无法验证菜单隐藏、直接访问 403 和动作拒绝原因，容易把授权语义再次分散到业务页面。

## 3. 目标与非目标

### 目标

- 将 12 个 Store 切片组合为一个可原子提交和原子重置的 Zustand 根 Store。
- 将 7 类冻结状态机实现为纯转换表和可穷举测试的 transition 函数。
- 所有跨页写操作只通过统一命令管线执行。
- 权限判定统一覆盖页面、动作、数据域、职责分离、在线要求和对象版本。
- 13 个角色、脱敏格式、离线限制和审计字段与技术设计一致。
- 通过最小权限 UI 验证导航过滤、直接访问 403、动作隐藏/禁用及拒绝原因。
- 为后续 UI-001～UI-013 提供稳定公共导出、覆盖矩阵和交接文档。

### 非目标

- 不实现 UI-001～UI-013 的业务字段、表格、表单、图表、地图、详情抽屉或业务按钮。
- 不完成 UI-012 权限配置页面或 UI-013 审计检索页面。
- 不新增生产后端、数据库、身份认证服务或真实网络依赖。
- 不修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、C02 契约和 Mock 的公开行为。
- 不修复 C01 已知的 React/ReactDOM 类型声明限制，不增加或升级依赖。

## 4. 权威资料与冲突规则

权威顺序如下：

1. `docs/baseline/openapi.yaml` 与 `demo-fixtures.json`：对象字段、枚举、接口、信封、错误码和场景。
2. `src/contracts/**` 与 `src/mocks/**`：C02 已验证的运行时接口。
3. 《网页 Demo 技术方案设计文档 v0.4》：12 Store、命令管线、7 状态机、权限判定顺序、审计与脱敏。
4. 《页面与功能任务卡 v0.2》：页面角色、动作 permission code、数据域和直接访问行为。
5. `docs/baseline/page-task-matrix.csv` 与 `traceability.csv`：页面、接口、测试和追踪关系。

机器基线对字段、枚举和错误码拥有最高权威；技术文档对尚未机器化的 Store、状态迁移和权限策略拥有权威。C03 开始实现前必须把状态机和权限策略提取为测试中的显式期望。若同一状态迁移、permission code 或角色行为在权威资料间存在冲突，必须在写入生产源码前暂停并给出逐项证据，不得自行增加映射规则。

## 5. 总体架构

### 5.1 状态内核

采用一个 Zustand 根 Store，由以下 12 个 slice creator 组成：

1. `sessionStore`：当前角色、数据域、班次、在线状态和演示时钟。
2. `scenarioStore`：当前场景、重置、故障投影和场景状态。
3. `planStore`：计划、校验、确认和版本。
4. `recommendationStore`：推荐结果、评分、排除项和人工调整草稿。
5. `workOrderStore`：任务树、依赖、派工和进度。
6. `resourceStore`：人员、设备、车辆、股道、能力和可用性。
7. `vehicleStore`：预约、排队、叫号、进出场和放行。
8. `exceptionStore`：异常、证据、处置、复核和关闭。
9. `interlockStore`：风险输入、联锁、回执、复位和覆盖。
10. `offlineStore`：终端、同步包、差异、冲突和重试。
11. `reportStore`：指标、钻取、导出任务和校验和。
12. `configAuditStore`：配置版本、审批、发布、回滚和审计记录。

根 Store 只暴露读取状态、纯 selector、初始化和场景重置。领域实体不得由页面直接写入；所有写入必须经过 command executor。跨 slice 更新使用单次根 Store 提交，失败时不产生部分状态。

初始化数据只能来自 C02 Mock API 或 C02 runtime 的深拷贝快照，禁止复制 `demo-fixtures.json`、手写第二套业务对象或让 Store 持有 Mock 内部可变引用。

### 5.2 状态机

状态机以数据驱动的 transition catalog 表示，状态、命令和禁止规则取自 v0.4/v0.2 技术文档。实现六类领域状态机和 `SM-007` 命令回执状态机，合计固定为 7 类。

核心接口接收 `machineId`、当前状态、命令和上下文，返回成功的下一状态或结构化拒绝。状态机函数必须纯净，不读取系统时钟、随机数、浏览器状态或 Zustand Store。

未知状态、未知命令、禁止迁移、回执超时和重试耗尽都必须显式拒绝；不得静默保持原状态后假装成功。

### 5.3 统一命令管线

命令结构沿用技术设计中的 `commandId`、`action`、`entityType`、`entityId`、`expectedVersion`、`payload`、`actor`、`traceId` 和 `clientTime`。执行顺序固定为：

1. UI Intent：生成完整命令和 expectedVersion。
2. Permission Guard：角色、页面/动作、数据域、职责分离和在线状态。
3. Schema Validation：复用 C02 Zod 请求与领域 Schema。
4. MSW Handler：调用 C02 Mock API，不绕过 handler 修改状态。
5. State Transition：检查允许迁移、命令回执和幂等。
6. Store Commit：一次提交实体、索引和派生状态。
7. Audit Append：追加成功或失败审计记录。
8. UI Feedback：返回可供页面展示的成功、失败、冲突或重试结果。

命令执行器不得在失败时提交部分业务状态。相同 `commandId` 重放返回第一次结果且不重复审计或重复推进状态。`expectedVersion` 不一致返回已有 `DEMO-VERSION-001`，保留调用方输入。

## 6. 权限与治理内核

### 6.1 权限模型

权限决策输入至少包括：13 个冻结角色之一、permission code、页面/动作、当前数据域、对象所属域、在线状态、申请人/审批人和对象版本。输出为结构化 `allow` 或 `deny`，拒绝结果包含可展示原因、`TOS-AUTH-001` 和拒绝发生的检查阶段。

判定顺序固定为：页面权限 → 动作权限 → 数据域 → 职责分离 → 在线要求 → 对象版本。数据域必须在列表、计数、汇总、钻取和导出之前过滤，越权对象不能以总数、占位符或错误详情的形式泄露。

具体 permission code、页面角色和动作规则从页面任务卡提取到单一 policy catalog。页面和组件只能查询该 catalog，不得内联角色判断。

### 6.2 职责分离与在线要求

- 申请人与审批人相同时，高风险联锁复位、覆盖和本人权限提升必须拒绝。
- 离线状态允许接单、普通进度、附件和普通节点交账。
- 离线状态禁止复位、覆盖、放行和权限变更。
- 系统管理员不得审批自身权限提升；独立审计员不得修改业务或审计记录。

这些规则必须在命令层再次校验，不能只依赖按钮隐藏。

### 6.3 脱敏与审计

固定脱敏格式为：姓名“张**”、手机号“138****1234”、车牌“川A·***45”。只有 policy catalog 明确允许的授权详情上下文可返回 Demo 原值。脱敏函数必须纯净且不能修改源对象。

审计为追加写，字段至少包括 actor、role、dataScope、action、entityType、entityId、before、after、result、errorCode、traceId、clientTime 和 serverTime。C03 只实现追加与 selector，不实现日志删除、修改或完整 UI-013 页面。

## 7. 最小权限 UI

C03 只提供公共权限表现层：

- `PermissionGate`：支持隐藏、禁用和拒绝说明三种动作表现。
- `ForbiddenState`：直接访问无页面权限路由时显示 403、所需权限和当前数据域，不加载业务对象。
- 导航过滤：现有 13 个路由按页面权限过滤菜单；URL 直接访问仍由路由门禁独立检查。

现有骨架页面继续显示 C01 占位内容，不增加业务字段和操作。不得借 C03 重做 AppShell 视觉、详情抽屉、命令反馈面板或场景工具条。

## 8. 数据流与失败处理

读取流为：C02 Mock/API → 严格 Schema 解析 → Store 原子初始化 → 数据域 selector → 页面/组件。

写入流为：UI intent → command → permission → schema → Mock → transition → atomic commit → audit → feedback。

失败遵循以下规则：

- 权限、数据域、职责分离和在线要求失败：`TOS-AUTH-001`，业务状态不变，追加拒绝审计。
- 版本冲突：`DEMO-VERSION-001`，业务状态不变，返回刷新/重试信息。
- 请求或状态非法：使用 C02 已有公开错误码和失败信封，不创建 C03 专用错误码。
- Mock 失败或场景故障：保留现有 Store 快照，不提交候选状态。
- 场景重置：以单个事务恢复 12 slices、权限会话、时钟、幂等缓存和审计序号；重复 reset 结果一致。

## 9. 文件边界

预计新增或修改范围：

- `src/stores/**`
- `src/commands/**`
- `src/auth/**`
- `src/governance/audit.ts`
- `src/components/PermissionGate.tsx`
- `src/components/ForbiddenState.tsx`
- 必要的 `src/app` 导航/路由门禁接线
- 对应 `__tests__`、`docs/evidence/C03/**` 与 `docs/handoffs/C03-state-and-permissions.md`

明确禁止修改：

- `docs/baseline/**`
- `src/contracts/**` 和 `src/mocks/**` 的冻结公开契约；若仅为测试注入所需的非破坏性适配，必须先证明公开行为不变并单独记录
- `package.json`、`pnpm-lock.yaml`
- UI-001～UI-013 的业务实现
- C01/C02 历史证据

## 10. TDD 闸门与验收

### Gate A：状态与命令

先保存有效红灯，再实现：

- 12 slice 组合、初始化、纯 selector 和原子 reset。
- 7 状态机的每条允许迁移和代表性拒绝迁移。
- schema 失败、Mock 失败时零部分提交。
- expectedVersion 冲突。
- commandId 幂等重放。
- 跨 slice selector 和场景 reset 确定性。

生成状态机覆盖矩阵、Store 快照和冲突/幂等证据。Gate A 全绿前不得实现权限 UI。

### Gate B：权限与最小 UI

先保存有效红灯，再实现：

- 13 角色 catalog 完整性。
- 页面权限、动作权限、数据域、职责冲突和在线要求。
- 列表、计数、汇总和导出的数据域不泄漏。
- 姓名、手机号和车牌脱敏。
- 成功/失败/拒绝审计追加写和不可修改性。
- PermissionGate 隐藏/禁用/说明。
- 无菜单权限与直接访问 403。

生成权限矩阵报告和权限覆盖 JSON。Gate B 全绿后才能执行全量回归。

### 最终验收

- C02 现有 10/10 测试文件、33/33 测试继续通过，新 C03 测试全部通过。
- `pnpm build` 成功。
- C01 现有 Playwright E2E 14/14 通过。
- C03 新增源码的 TypeScript 诊断为 0；全局已知 38 条 React/ReactDOM/JSX 诊断如仍存在须如实记录。
- 六份冻结基线 SHA-256 与 C02 交接一致。
- `git diff --check` 通过，依赖、锁文件、业务页面和冻结基线无改动。
- 创建独立 C03 源码提交，交接中列明公共导出、状态机覆盖、权限矩阵、已知限制和下一阶段入口。

## 11. C03 对话提示词要求

最终提示词写入 `docs/conversation-prompts/C03-state-and-permissions.md`，必须包含：

- C02 完整基线提交 `94ce3961a8bad0e7b9aceca3980fbb30bfa66877` 和六份冻结哈希。
- 创建/切换 `demo/c03-state-permissions` 的启动步骤。
- 本规格中的目标、权威顺序、冲突闸门、两个 TDD Gate、文件边界和验收命令。
- 明确禁止页面业务实现、基线修改和依赖变更。
- 要求输出红灯、覆盖矩阵、验证记录、交接文档和独立 C03 提交。

提示词只负责启动独立 C03 对话，不直接在当前规格任务中执行 C03 源码实现。
