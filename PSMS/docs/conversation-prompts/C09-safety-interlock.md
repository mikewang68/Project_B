# C09 安全联锁实施对话提示词

你现在开始独立实施 **C09：安全联锁 / UI-009**。这是编码执行任务，不是重新讨论方案，也不是只补一份计划。C09 设计规格与实施计划已经确认；请使用 `superpowers:executing-plans` 按计划 Task 1 到 Task 8 顺序执行。除非我明确要求，不要派生子任务或使用多代理。

## 一、工程、基点与分支

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- C08 最终源码与交接提交：`632fa3def12e93754f42eb3c8f16c8d80c207c69`
- C09 设计规格：`docs/superpowers/specs/2026-07-21-c09-safety-interlock-design.md`
- C09 实施计划：`docs/superpowers/plans/2026-07-21-c09-safety-interlock.md`
- C09 工作分支：`demo/c09-safety-interlock`

开始前必须：

1. 进入工程，确认当前分支从 `demo/c08-exception-handling` 的 `632fa3def12e93754f42eb3c8f16c8d80c207c69` 派生，工作区干净。
2. 新建或切换到 `demo/c09-safety-interlock`，不要覆盖 C08 分支。
3. 通读以下文件：
   - `docs/handoffs/C08-exception-handling.md`
   - `docs/superpowers/specs/2026-07-21-c09-safety-interlock-design.md`
   - `docs/superpowers/plans/2026-07-21-c09-safety-interlock.md`
4. 严格执行计划中的 8 个 Task 和全部检查步骤；先产生有效红灯，再写最小实现，再运行相关回归并按计划提交。
5. 不要重新规划，不要再次请求确认已经冻结的 UI-009 范围、API-016/API-017 语义、DO-010 无异常外键边界、FORCE_STOP 边界、截图数量或状态迁移边界。

## 二、冻结基线闸门

写 C09 源码前计算并核对：

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

C08 交接事实：C08 focused 8/8 文件、44/44 测试通过；全量 Vitest 546/547，唯一失败为预检已存在的 UI-002 30 秒超时且隔离复跑通过；build 通过 1922 modules；C08 E2E 4/4；全量 Playwright 35/35；截图 8/8；冻结 hash 6/6；全局 TypeScript 512 条既知 React/JSX 声明类诊断，C08 非 React 诊断为 0；Node 24.14.0 低于声明 24.18.0。C09 开始前把实际结果写入 `docs/evidence/C09/preflight.md` 和 `tsc-before.txt`。

## 三、唯一业务范围

C09 只实现 UI-009：

```text
/safety/interlocks
```

完整演示主线：

```text
C08 UI-008 INTERLOCK 异常入口
-> UI-009 联锁台账
-> API-017 申请复位
-> API-017 审批复位
-> API-017 恢复记录
-> 旁路申请/审批作为独立安全演示路径
```

真实设备复位、PLC/ECS 控制、安全旁路授权系统、外部通知均不在 C09 实现范围内。C09 的恢复只是 Demo 恢复记录，不代表真实设备已复位。

## 四、硬性数据边界

- 延续唯一 `DemoRuntimeProvider` 和唯一 C03 vanilla Store；不创建第二个 Store、Provider 或 fixture。
- 新建 `src/features/safety-interlock/**`。
- C09 只处理严格 DO-010：

```text
id
interlockNo
riskType
actionLevel
status
inputSnapshot
receiptStatus
resetRequest
approvalChain
version
createdAt
updatedAt
```

- 冻结 DO-010 没有 `exceptionId`、`workOrderId`、`deviceCommandId` 或外部控制字段。
- 从 UI-008 带入的 `exceptionId/scenarioId/from` 只能作为来源上下文展示，不得写入 DO-010，不得声明为生产外键。
- 不创建新联锁对象，不手写第二套 fixture。
- 不修改 DispatchException、WorkOrder、WorkNode、Resource、Plan 或 RecommendationDraft 事实。
- API-025 reset 必须恢复 fixture 并清空 C09 workflow、幂等缓存和临时选择。

## 五、API-016/API-017 语义

API-016：

```text
GET /mock/interlocks
operationId = GET_mock_interlocks
x-api-id = API-016
```

API-017：

```text
POST /mock/interlocks/:id/command
operationId = POST_mock_interlocks_id_command
x-api-id = API-017
```

API-017 body 限定为：

```ts
{
  action: 'TRIGGER' | 'RECEIPT' | 'REQUEST_RESET' | 'APPROVE' | 'RESTORE' | 'REQUEST_OVERRIDE';
  reason: string;
  approvalUserId?: string;
  resetRequest?: Record<string, unknown>;
}
```

Gateway 只验证 transport、envelope、apiId、operationId、scenarioId、now 和对象身份；返回对象不得覆盖 Store。

## 六、五个动作

1. `SI-01 / 触发和回执`：权限 `interlock:view`，TRIGGERED -> ACTION_ISSUED -> WAITING_RECEIPT -> LOCKED。冻结 fixture 没有该起始状态，E2E 不造数。
2. `SI-02 / 申请复位`：权限 `interlock:reset`，LOCKED -> RESET_REQUESTED。
3. `SI-03 / 审批复位`：权限 `interlock:approve`，RESET_REQUESTED -> APPROVED。
4. `SI-04 / 恢复`：权限 `interlock:reset`，APPROVED -> RESTORED。页面必须显示“Demo 恢复记录，不代表真实设备已复位”。
5. `SI-05 / 申请和审批旁路`：权限 `interlock:request-override` / `interlock:approve`，LOCKED -> OVERRIDE_PENDING -> OVERRIDDEN。

所有动作使用冻结 DO-010 transition。C09 不新增 `forceRestore`、`clearForceStop` 或真实设备控制动作。

## 七、权限、版本、幂等、审计

固定顺序：

```text
权限 -> 数据域 -> 请求/业务校验 -> API-017 -> 二次版本校验
-> DO-010 transition -> 单次原子 Store commit -> 追加一条审计
```

- 页面：`interlock:view`
- 申请复位与恢复：`interlock:reset`
- 审批复位和审批旁路：`interlock:approve`
- 申请旁路：`interlock:request-override`
- 版本漂移返回 `DEMO-VERSION-001`，不产生部分写入
- 相同 commandId 重放返回首次冻结结果，不重复 API、Store commit、workflow 更新或 audit
- 成功、拒绝、失败都追加一条 C09 audit，不覆盖 C04-C08

## 八、UI-009 必须展示

- 精确页面标识：`UI-009`
- 页面标题：`安全联锁`
- 当前路由：`/safety/interlocks`
- 来源上下文：exceptionId、scenarioId、from，以及“Demo 来源上下文，非生产外键”说明
- KPI：锁定中、待审批、已批准、已恢复、FORCE_STOP、回执失败
- 联锁台账：interlockNo、riskType、actionLevel、status、receiptStatus、version、updatedAt
- 联锁详情：状态流、inputSnapshot、resetRequest、approvalChain、FORCE_STOP 安全说明、trace/audit
- 操作：触发、回执、申请复位、审批、恢复、申请旁路、审批旁路、返回 UI-008
- 六类页面状态：loading、empty、business-error、network-error、forbidden、not-found
- 四类业务进度：LOCKED、RESETTING、RESTORED、OVERRIDE

1440x900 使用台账、详情、处置三栏；1280x720 使用台账+详情主列，处置面板可堆叠或抽屉化。两种视口都不得横向滚动、按钮裁切、文字重叠或中文缺字。

## 九、四道 TDD 闸门

Gate A：联锁投影、上下文解析、API-016/API-017 Gateway。红灯输出：`docs/evidence/C09/interlock-core-red.txt`。

Gate B：SI-01 到 SI-05 命令、状态迁移、版本、幂等、审计、reset。

Gate C：UI-009 页面、权限、六类状态、四类业务进度、跨页入口和响应式。红灯输出：`docs/evidence/C09/interlock-page-red.txt`。

Gate D：4 条 E2E、8 张截图、coverage、verification、handoff、全量回归。红灯输出：`docs/evidence/C09/interlock-e2e-red.txt`。

## 十、E2E 与截图

E2E 文件使用 Windows 合法名称：

```text
e2e/ui-009-safety-interlock.spec.ts
```

恰好 4 条 C09 E2E：

1. SCN-01：从 UI-008 INTERLOCK 入口进入 UI-009，对 LOCKED 联锁申请复位。
2. SCN-01：审批复位并恢复，验证版本、审批链、审计和 Demo 恢复声明。
3. 权限、数据域、非法状态、版本漂移、幂等重放，验证无部分写入。
4. API-016/API-017 network/malformed、FORCE_STOP 和旁路路径，验证恢复、阻断提示和不回写 UI-008。

保存且只保存 8 张截图：

```text
C09-UI009-SCN01-LOCKED-1440x900.png
C09-UI009-SCN01-LOCKED-1280x720.png
C09-UI009-SCN01-RESETTING-1440x900.png
C09-UI009-SCN01-RESETTING-1280x720.png
C09-UI009-SCN01-RESTORED-1440x900.png
C09-UI009-SCN01-RESTORED-1280x720.png
C09-UI009-SCN01-OVERRIDE-1440x900.png
C09-UI009-SCN01-OVERRIDE-1280x720.png
```

逐张按原始分辨率检查横向滚动、裁切、重叠、中文缺字、状态流可读性、处置按钮可见性、FORCE_STOP 提示和返回 UI-008 入口。结果写入 `docs/evidence/C09/screenshot-index.md`。

## 十一、硬性禁止事项

- 不修改六份 baseline、依赖文件、lockfile、公共 API Schema、错误码、权限目录或状态机 catalog。
- 不新增 Store、Provider、fixture、endpoint、公共角色、权限码或业务枚举。
- 不创建新联锁对象，不给 DO-010 添加字段，不手写第二套 fixture。
- 不把 UI-008 query context 伪装为 DO-010 生产外键。
- 不改写 DispatchException、WorkOrder、WorkNode、Resource、Plan 或 RecommendationDraft 事实。
- 不实现真实设备复位、PLC/ECS 控制、外部安全系统或通知。
- 不使用 `Date.now`、未注入当前时间、`Math.random`、随机 UUID 或 localStorage 作为领域事实。
- 不通过跳过测试、删除断言、放宽 Schema、修改超时或修改配置来制造通过。

## 十二、最终验证与交接

至少执行：

```powershell
pnpm vitest run src/features/safety-interlock src/pages/__tests__/SafetyInterlockPage.render.test.tsx src/pages/__tests__/SafetyInterlockPage.action.test.tsx src/pages/__tests__/SafetyInterlockPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-009-safety-interlock.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit
git diff --check
```

并执行 Task 8 的 focused tests、六 hash 复核和禁止范围扫描。最终要求：

- C09 focused tests 全绿，报告实际文件数/测试数。
- 全量 Vitest 单 worker 全绿或按 C08 已知 UI-002 超时波动如实说明；不得修改 timeout/config。
- build 通过并报告 modules。
- C09 E2E 4/4，全量 Playwright 全绿并报告实际数量。
- C09 非 React primary diagnostics 为 0；全局 tsc 按实测记录，不伪装通过。
- 六 hash 6/6 不变，`git diff --check` 通过，禁止范围无修改。
- 8 张截图 8/8 完成原始分辨率检查。
- `docs/evidence/C09/coverage.json` 完整列举 UI-009、selectors、API-016/API-017、SI-01..SI-05、权限、数据域、版本、幂等、reset、页面状态、业务进度、C08 来源上下文、FORCE_STOP 提示、E2E 和截图。
- `docs/evidence/C09/verification.md` 记录环境、命令、时间、退出码、测试/build/E2E/tsc、hash、截图、范围和 diff check。
- 创建 `docs/handoffs/C09-safety-interlock.md`，列明 runtime 公共入口、selectors、Gateway、命令语义、来源上下文边界、FORCE_STOP 边界、权限、reset、证据、限制和 C10 建议入口。

最终报告必须包含：当前分支、完整 HEAD、完整提交链、变更文件分类、focused/full Vitest、build、C09 4/4 与全量 Playwright、tsc 实际状态、六 hash、8 张截图、coverage、verification、handoff 和已知限制。保留在 `demo/c09-safety-interlock`，不合并、不推送。

现在开始执行：先完成工作区、祖先提交、六 hash 和 C08 回归核验；核验通过后从实施计划 Task 1 依次推进。不要重写方案，不要请求确认已冻结规格。
