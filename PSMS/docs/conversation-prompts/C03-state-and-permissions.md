# C03 状态与权限实施对话提示词

你现在开始独立实施 **C03：状态与权限内核**。这是编码执行任务，不是继续讨论方案，也不是只编写计划。设计规格和逐步实施计划均已确认并冻结；请先核验仓库，再严格按计划采用 TDD 完成实现、证据、验证、提交和交接。

## 一、仓库与提交基线

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- 起始分支：`demo/c02-contracts-mock`
- C02 源码提交：`94ce3961a8bad0e7b9aceca3980fbb30bfa66877`
- C03 设计规格提交：`2ebaf9d3670839ae210ba3ab8fdb7dff80a73f10`
- C03 实施计划提交：`833a0c466afb36a88daaffcb6a30d395ba4769ea`
- C03 工作分支：`demo/c03-state-permissions`

开始前：

1. 进入上述工程，确认起始分支和工作区干净。
2. 确认 C02 源码提交、C03 设计提交和 C03 计划提交都是当前 `HEAD` 的祖先。
3. 从当前干净的起始分支创建并切换到 `demo/c03-state-permissions`；若该分支已存在，只能在确认其基点正确且工作区干净后继续，不得覆盖已有改动。
4. 通读以下三份文件，逐项执行实施计划中的任务和检查框：
   - `docs/handoffs/C02-contracts-and-mock.md`
   - `docs/superpowers/specs/2026-07-19-c03-state-and-permissions-design.md`
   - `docs/superpowers/plans/2026-07-19-c03-state-and-permissions.md`

## 二、冻结基线闸门

开始写 C03 源码前必须核对以下 SHA-256：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

若任一哈希不匹配，列出期望值、实际值和工作区状态后停止，不得自行修改基线。若全部匹配，C03 全程禁止修改 `docs/baseline/**`。

在实现前还要保存并记录：

```powershell
pnpm exec tsc --noEmit
pnpm test -- --run
pnpm build
pnpm test:e2e
```

C02 的已知基线是：Vitest 10/10 文件、33/33 测试通过，build 通过，E2E 14/14 通过；全局 `tsc --noEmit` 有 38 条既有 React 类型诊断，而 C02 范围诊断为 0。把新的完整 `tsc-before` 输出保存到 `docs/evidence/C03/tsc-before.txt`。若测试、build 或 E2E 相对上述基线退化，先查明原因，不得把退化带入 C03。

## 三、唯一实施范围

C03 一次完成以下纵向闭环：

- 12 个 Zustand 根 Store 切片、fixture 初始化、纯 selector、单次原子提交和七场景原子 reset；
- `SM-001`～`SM-007` 共 7 类数据驱动状态机及完整允许迁移覆盖；
- 统一命令管线：权限 → 请求 Schema → C02 Mock → 状态迁移 → 原子 Store commit → 追加写审计；
- 13 个冻结角色、45 个 permission code、13 个路由策略、动作策略、数据域、职责分离、在线限制和版本检查；
- 姓名、手机号、车牌三种纯函数脱敏；
- 严格 DO-013 外包裹形式的追加写审计账本；
- 最小 `PermissionGate`、403 页面、路由保护和导航过滤；
- C03 测试、红灯证据、覆盖 JSON、最终验证和交接文档。

实施计划中的完整状态迁移表、12-slice 所有权、13 角色、45 个权限动作、路由策略、动作策略和判定顺序是唯一实现清单。不得凭印象补充第二套映射，不得删减“看起来暂时用不到”的条目。

## 四、明确禁止事项

- 不实现 UI-001～UI-013 的业务页面内容、表格、表单、图表、地图、详情抽屉或业务按钮。
- 不完成 UI-012 权限配置业务页或 UI-013 审计检索业务页；本阶段只提供共同内核和最小门禁界面。
- 不改变现有 13 条路由路径、页面模块公共接口和 C01 视觉骨架。
- 不修改 `src/contracts/**`、`src/mocks/**` 的公开行为，不手写第二套 fixture，不绕过 C02 MSW handler 直接制造业务成功结果。
- 不修改 `package.json`、`pnpm-lock.yaml`，不安装或升级依赖，不放宽 TypeScript 配置来隐藏诊断。
- 不新增公开错误码；沿用 C02 的 9 个公开错误码。权限拒绝使用 `TOS-AUTH-001`，版本冲突使用 `DEMO-VERSION-001`。
- 不在页面、组件或命令里内联角色判断；所有权限判断必须来自单一 policy catalog。
- 不允许页面直接写领域状态；所有业务写入必须经过 command executor。
- 不使用 `any`、静默状态保持、部分 Store 提交、可变审计历史或重复命令的二次提交/二次审计。

## 五、Gate A：状态与命令

严格按计划先红后绿：

1. 先为状态 catalog 写失败测试，并把有效失败输出保存到 `docs/evidence/C03/state-red.txt`。
2. 实现 `SM-001`～`SM-007` 的纯 transition catalog。未知状态、未知命令、禁止迁移、回执超时和重试耗尽必须结构化拒绝，不能假装成功。
3. 生成 `docs/evidence/C03/state-machine-coverage.json`，逐条证明 7 类状态机的允许迁移和代表性拒绝迁移均有测试。
4. 用 `zustand/vanilla` 组合 12 个切片。完整候选状态必须先在 Store 外构建并校验，最后只调用一次 root replace；失败不得留下部分状态。
5. 所有领域基础数据从 C02 严格 fixture 深拷贝初始化。推荐草稿没有独立 fixture，初始为空，只能由验证后的 API 结果填充。
6. 生成 `docs/evidence/C03/store-coverage.json`，覆盖 12 个切片、DO-001～DO-014 映射、初始化、隔离、原子提交、reset 和跨切片 selector。
7. 先使用注入的 fake 权限、Mock、审计和时钟端口测试命令管线。精确顺序是：permission → schema → Mock → transition → commit → audit。
8. 权限、Schema、Mock 或 transition 失败都不能 commit；相同 `commandId` 重放返回第一次结果，不再次 Mock、推进状态或写审计；`expectedVersion` 不一致返回 `DEMO-VERSION-001`。

Gate A 的状态机、Store 和命令管线测试全部通过并完成覆盖证据后，才可进入 Gate B。

## 六、Gate B：权限、治理与最小 UI

1. 在写权限实现前先写失败测试，并保存 `docs/evidence/C03/permission-red.txt`。
2. 角色 catalog 必须精确为设计/计划冻结的 13 个角色；permission catalog 必须精确为计划列出的 45 个 code；route catalog 必须覆盖 `UI-001`～`UI-013`。
3. 权限判定顺序固定为：页面 → 动作 → 数据域 → 职责分离 → 在线状态 → 对象版本。构造多项同时失败的用例，证明较早阶段优先返回。
4. 数据域必须在计数和聚合前过滤；未知角色、权限或数据域返回结构化拒绝，不得默认放行。
5. 必测拒绝：同一人审批高风险操作、离线复位/覆盖/放行/权限变更、SYS_ADMIN 审批自身权限提升、AUDITOR 修改业务或审计记录。
6. 默认演示会话为 `DISPATCHER` / `USER-001` / `['AREA-A']` / online；E2E 只可通过计划中一个固定 localStorage key 覆盖会话。
7. 脱敏格式固定：`张三 → 张**`、`13800121234 → 138****1234`、`川A·12345 → 川A·***45`；脱敏视图返回新对象，不修改源数据。
8. `CommandAuditEntry` 必须包装严格 C02 `AuditLog`（DO-013）并把 C03 扩展元数据放在 wrapper 中；不得给 `AuditLog` 增加字段或放宽严格 Schema。成功和失败都追加一条，普通调用者只能读取副本，不能 update/delete。
9. 将真实授权和审计适配器接入 Gate A 命令执行器，用集成测试证明允许动作成功提交一次、拒绝动作状态不变且追加拒绝审计、重复 commandId 不重复审计。
10. 最小 UI 只包含 `PermissionGate`、通用 403、路由保护和导航过滤。URL 直接访问必须独立校验；无权页面不得先 import/render 再隐藏；按钮级门禁支持隐藏或禁用并提供原因。
11. 生成 `docs/evidence/C03/permission-coverage.json`，列出 13 角色、45 code、13 路由、6 个决策阶段、离线限制、职责分离、3 种脱敏和对应测试名。

Gate B 全绿前不得宣称 C03 完成。

## 七、TDD、提交与冲突规则

- 遵循 `docs/superpowers/plans/2026-07-19-c03-state-and-permissions.md` 的任务顺序、文件路径、测试命令、预期失败和建议提交点；不要另写一份竞争计划。
- 每个红灯必须是由尚未存在的预期能力导致，而不是语法错误、错误 import 或破坏既有测试。
- 每完成一个计划任务就运行对应聚焦测试并按计划提交，保持提交边界清晰。
- 如果旧任务卡对业务页面交互的范围大于本提示词，本提示词和已确认的 C03 设计规格优先；把页面业务实现留给后续阶段。
- 只有实时基线哈希不匹配，或已确认规格、实施计划与冻结机器契约在同一个具体状态迁移/权限条目上出现无法兼容的冲突时才停止。停止时必须给出文件、行号、双方原文、最小复现和建议决策；一般实现困难、类型错误或测试失败不构成“需求冲突”，应继续排查修复。

## 八、最终验证与交付

完成源码后至少运行：

```powershell
pnpm exec vitest run src/state-machines/__tests__/transition.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/pipeline.test.ts
pnpm exec vitest run src/auth/__tests__/permission.test.ts src/governance/__tests__/audit.test.ts src/commands/__tests__/authorization-integration.test.ts src/components/auth/__tests__/PermissionGate.test.tsx src/app/__tests__/route-permissions.test.tsx
pnpm test -- --run
pnpm build
pnpm test:e2e
pnpm exec tsc --noEmit
git diff --check
```

验收要求：

- C03 聚焦测试、全量 Vitest、build 全部通过；
- C01 的 14 条 E2E 仍为 14/14；
- 六个基线哈希完全不变；
- C03 新增文件的 TypeScript 诊断为 0；全局既有 React 诊断若仍存在，只能按实际结果记录，不得伪装为全局 tsc 通过；
- `state-machine-coverage.json`、`store-coverage.json`、`permission-coverage.json` 无缺项；
- `docs/evidence/C03/verification.md` 写明命令、退出码、测试数量、build、E2E、tsc、哈希和范围检查；
- 创建 `docs/handoffs/C03-state-and-permissions.md`，列出公共导出、12 slices、7 状态机、13 角色、45 权限、13 路由、命令/审计语义、证据路径、已知限制和下一阶段入口；
- 最终源码提交不得包含 `docs/baseline/**`、依赖文件或业务页面实现。

最终报告必须包含：当前分支、完整提交哈希、变更文件分类、聚焦/全量测试数量、build、E2E 14/14、tsc 实际状态、三个 coverage JSON、verification 与 handoff 路径，以及任何仍保留的已知限制。

现在开始执行。先做仓库、祖先提交、工作区、六哈希和 C02 回归核验；核验通过后从计划 Task 1 依次推进，不要再次请求确认已冻结的规格。
