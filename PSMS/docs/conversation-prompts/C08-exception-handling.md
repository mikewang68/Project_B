# C08 异常处置实施对话提示词

你现在开始独立实施 **C08：异常处置 / UI-008**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C08 设计规格与实施计划已经确认；请使用 `superpowers:executing-plans` 按计划 Task 1 到 Task 8 顺序执行。除非我明确要求，不要派生子任务或使用多代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C07 最终源码与交接提交：`a232b5ea6f53c6b724cbc9db0840d729c48f1526`
- C08 设计规格：`docs/superpowers/specs/2026-07-21-c08-exception-handling-design.md`
- C08 实施计划：`docs/superpowers/plans/2026-07-21-c08-exception-handling.md`
- C08 工作分支：`demo/c08-exception-handling`

开始前必须：

1. 进入工程，确认当前分支从 `demo/c07-dispatch-board` 的 `a232b5ea6f53c6b724cbc9db0840d729c48f1526` 派生，工作区干净。
2. 新建或切换到 `demo/c08-exception-handling`，不要覆盖 C07 分支。
3. 通读以下文件：
   - `docs/handoffs/C07-dispatch-board.md`
   - `docs/superpowers/specs/2026-07-21-c08-exception-handling-design.md`
   - `docs/superpowers/plans/2026-07-21-c08-exception-handling.md`
4. 严格执行计划中的 8 个 Task 和全部检查步骤；先产生有效红灯，再写最小实现，再运行相关回归并按计划提交。
5. 不要重新规划，不要再次请求确认已经冻结的 UI-008 范围、API-014/API-015 语义、DO-009 无外键边界、UI-009 边界、截图数量或状态迁移边界。

## 二、冻结基线闸门

写 C08 源码前计算并核对：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

任一 hash 不匹配时，给出期望值、实际值、文件状态和最近相关提交后停止；不得自行修订、重建或接受新基线。

源码修改前执行并记录：

```powershell
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

C07 交接事实：focused Vitest 49/49；全量 Vitest 499/499；build 通过 1,907 modules；C07 E2E 4/4；全量 E2E 31/31；截图 8/8；冻结 hash 6/6；全局 TypeScript 467 条均为既知 React/JSX 声明类诊断，C07 非 React 主诊断为 0。C08 开始前把实际结果写入 `docs/evidence/C08/preflight.md` 和 `tsc-before.txt`。

## 三、唯一业务范围

C08 只实现 UI-008：

```text
/monitor/exceptions
```

完整演示主线：

```text
C07 UI-005 执行反馈或异常入口
-> UI-008 异常台账
-> API-015 确认异常
-> API-015 分派处理
-> API-015 提交处理
-> API-015 复核退回或关闭
-> 必要时重开
```

UI-009 安全联锁只作为链接边界，不在 C08 实现恢复、审批、解除或覆盖。UI-005 的 WorkOrder/WorkNode 也不因 C08 异常处置被回写。

## 四、硬性数据边界

- 延续唯一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store；不创建第二个 Store、Provider 或 fixture。
- 新建 `src/features/exception-handling/**`。
- C08 只处理严格 DO-009：

```text
id
exceptionNo
type
level
status
owner
dueAt
evidence
version
createdAt
updatedAt
```

- 冻结 DO-009 没有 `workOrderId`、`planId`、`resourceId` 或联锁外键字段。
- 从 UI-005 带入的 `workOrderId/planId/scenarioId/from` 只能作为来源上下文展示，不得写入 DO-009，不得声明为生产外键。
- 不创建新异常对象，不手写第二套 fixture。
- 不修改 WorkOrder、WorkNode、Resource、Plan、RecommendationDraft 或 Interlock 事实。
- API-025 reset 必须恢复 fixture 并清空 C08 workflow、幂等缓存和临时选择。

## 五、API-014/API-015 语义

API-014：

```text
GET /mock/exceptions
operationId = GET_mock_exceptions
x-api-id = API-014
```

API-015：

```text
POST /mock/exceptions/:id/command
operationId = POST_mock_exceptions_id_command
x-api-id = API-015
```

API-015 body 限定为：

```ts
{
  action: 'ACK' | 'ASSIGN' | 'HANDLE' | 'REVIEW' | 'CLOSE' | 'REOPEN';
  reason: string;
  owner?: string;
  evidence?: string[];
}
```

Gateway 只验证 transport、envelope、apiId、operationId、scenarioId、now 和对象身份；返回对象不得覆盖 Store。

## 六、五个动作

1. `EX-01 / 确认异常`：权限 `exception:ack`，OPEN/REOPENED -> ACKNOWLEDGED。
2. `EX-02 / 分派处理`：权限 `exception:handle`，ACKNOWLEDGED -> HANDLING，可更新 owner。
3. `EX-03 / 提交处理`：权限 `exception:handle`，HANDLING -> PENDING_REVIEW，追加 evidence。
4. `EX-04 / 复核/关闭`：权限 `exception:review` 或 `exception:close`，PENDING_REVIEW -> HANDLING 或 CLOSED。
5. `EX-05 / 重开`：权限 `exception:reopen`，CLOSED -> REOPENED。

所有动作使用冻结 DO-009 transition。C08 不新增 `ESCALATE` 动作；`ESCALATED` 只作为冻结状态展示。

## 七、权限、版本、幂等、审计

固定顺序：

```text
权限 -> 数据域 -> 请求/业务校验 -> API-015 -> 二次版本校验
-> DO-009 transition -> 单次原子 Store commit -> 追加一条审计
```

- 页面：`monitor:view`
- 确认：`exception:ack`
- 分派与提交处理：`exception:handle`
- 复核退回：`exception:review`
- 关闭：`exception:close`
- 重开：`exception:reopen`
- 版本漂移返回 `DEMO-VERSION-001`，不产生部分写入
- 相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或 audit
- 成功、拒绝、失败都追加一条 C08 audit，不覆盖 C04-C07

## 八、UI-008 必须展示

- 精确页面标识：`UI-008`
- 页面标题：`异常处置`
- 当前路由：`/monitor/exceptions`
- 来源上下文：workOrderId、planId、scenarioId、from，以及“Demo 来源上下文，非生产外键”说明
- KPI：未确认、处理中、待复核、已关闭、超期、联锁类
- 异常台账：exceptionNo、type、level、status、owner、dueAt、version、evidence count
- 异常详情：状态流、证据、影响说明、来源上下文、处理记录摘要、trace/audit
- 操作：确认、分派、提交处理、复核退回、关闭、重开、UI-009 联锁入口
- 六类页面状态：loading、empty、business-error、network-error、forbidden、not-found
- 四类业务进度：OPEN_QUEUE、HANDLING、REVIEW、CLOSED

1440x900 使用台账、详情、处置三栏；1280x720 使用台账+详情主列，处置面板可堆叠或抽屉化。两种视口都不得横向滚动、按钮裁切、文字重叠或中文缺字。

## 九、四道 TDD 闸门

Gate A：异常投影、上下文解析、API-014/API-015 Gateway。红灯输出：`docs/evidence/C08/exception-core-red.txt`。

Gate B：EX-01 到 EX-05 命令、状态迁移、版本、幂等、审计、reset。

Gate C：UI-008 页面、权限、六类状态、四类业务进度、跨页入口和响应式。红灯输出：`docs/evidence/C08/exception-page-red.txt`。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。红灯输出：`docs/evidence/C08/exception-e2e-red.txt`。

## 十、E2E 与截图

E2E 文件使用 Windows 合法名称：

```text
e2e/ui-008-exception-handling.spec.ts
```

恰好 4 条 C08 E2E：

1. SCN-01：从 UI-005 异常入口进入 UI-008，确认异常并分派处理。
2. SCN-01：提交处理、复核退回、再次提交并关闭，验证版本和审计。
3. 权限、数据域、非法状态、版本漂移、幂等重放，验证无部分写入。
4. API-014/API-015 network/malformed 和 INTERLOCK 类型异常，验证恢复、UI-009 入口和不解除联锁。

保存且只保存 8 张截图：

```text
C08-UI008-SCN01-OPEN_QUEUE-1440x900.png
C08-UI008-SCN01-OPEN_QUEUE-1280x720.png
C08-UI008-SCN01-HANDLING-1440x900.png
C08-UI008-SCN01-HANDLING-1280x720.png
C08-UI008-SCN01-REVIEW-1440x900.png
C08-UI008-SCN01-REVIEW-1280x720.png
C08-UI008-SCN01-CLOSED-1440x900.png
C08-UI008-SCN01-CLOSED-1280x720.png
```

逐张按原始分辨率检查横向滚动、裁切、重叠、中文缺字、状态流可读性、处置按钮可见性和 UI-009 入口。结果写入 `docs/evidence/C08/screenshot-index.md`。

## 十一、硬性禁止事项

- 不修改六份 baseline、依赖文件、lockfile、公共 API Schema、错误码、权限目录或状态机 catalog。
- 不新增 Store、Provider、fixture、endpoint、公共角色、权限码或业务枚举。
- 不创建新异常对象，不给 DO-009 添加字段，不手写第二套 fixture。
- 不把 UI-005 query context 伪装为 DO-009 生产外键。
- 不改写 WorkOrder、WorkNode、Resource、Plan、RecommendationDraft 或 Interlock 事实。
- 不实现 UI-009 联锁恢复或 UI-006/UI-007/UI-010 之后模块。
- 不使用 `Date.now`、未注入当前时间、`Math.random`、随机 UUID 或 localStorage 作为领域事实。
- 不通过跳过测试、删除断言、放宽 Schema、修改超时或修改配置来制造通过。

## 十二、最终验证与交接

至少执行：

```powershell
pnpm vitest run src/features/exception-handling src/pages/__tests__/ExceptionHandlingPage.render.test.tsx src/pages/__tests__/ExceptionHandlingPage.action.test.tsx src/pages/__tests__/ExceptionHandlingPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-008-exception-handling.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit
git diff --check
```

并执行 Task 8 的 focused tests、六 hash 复核和禁止范围扫描。最终要求：

- C08 focused tests 全绿，报告实际文件数/测试数。
- 全量 Vitest 单 worker 全绿；若默认并发仍触发既有 `PlanLedgerPage.render` 超时，按事实说明，不修改 timeout/config。
- build 通过并报告 modules。
- C08 E2E 4/4，全量 Playwright 全绿并报告实际数量。
- C08 非 React primary diagnostics 为 0；全局 tsc 按实测记录，不伪装通过。
- 六 hash 6/6 不变，`git diff --check` 通过，禁止范围无修改。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C08/coverage.json` 完整列举 UI-008、selectors、API-014/API-015、EX-01..EX-05、权限、数据域、版本、幂等、reset、页面状态、业务进度、C07 来源上下文、UI-009 链接、E2E 和截图。
- `docs/evidence/C08/verification.md` 记录环境、命令、时间、退出码、测试/build/E2E/tsc、hash、截图、范围和 diff check。
- 创建 `docs/handoffs/C08-exception-handling.md`，列明 runtime 公共入口、selectors、Gateway、命令语义、来源上下文边界、UI-009 边界、权限、reset、证据、限制和 C09 建议入口。

最终报告必须包含：当前分支、完整 HEAD、完整提交链、变更文件分类、focused/full Vitest、build、C08 4/4 与全量 Playwright、tsc 实际状态、六 hash、8 张截图、coverage、verification、handoff 和已知限制。保留在 `demo/c08-exception-handling`，不合并、不推送。

现在开始执行：先完成工作区、祖先提交、六 hash 和 C07 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重写方案，不要请求确认已冻结规格。
