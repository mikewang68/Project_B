# C13A 契约基线修复验证记录

## 结论

C13A 已完成 DO-015 ConfigVersion 契约、fixture、API-022/023 映射、独立 Store 所有权和状态机注册。`UI-012` 页面未实施，未创建 `demo/c13-system-settings`，未修改页面、feature、RBAC、依赖或 E2E 用例。

记录时间：`2026-07-22 23:26:02 +08:00`。

## 基点与分支

- C12 基点：`f85ede995c6958bbf36e1aa561b1ca7332fb8588`
- 分支：`demo/c13-config-contract-baseline`
- 实施代码尖端：`f76c2eb7ca68e7fa8af5ea5d85bc8753dd29adc4`
- merge count：`0`
- 未合并、未推送。

## 冻结 hash

`docs/baseline/SHA256SUMS.txt` 共 6 项，逐项重算结果为 `6/6`：

```text
5df343d2ea74cc01cb9515205604b14fde0e4962b8a49518527e058192a9e3b3  README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  package-baseline.json
1ea80f23293e9d1e93e464478c1051ca97887e0b6e924a4b816ee264a4651e1e  openapi.yaml
4d206093e0d36a56071dd2eb21feb47a35f690d2f3cdfecb62b782362a50204a  demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  traceability.csv
```

相同副本见 `SHA256SUMS-after.txt`。

## TDD 证据

- 契约 RED：3 个文件中 6 条失败、11 条通过，失败均为缺失 DO-015、精确 envelope 和 API023Request 联合；见 `config-contract-red.txt`。
- Store RED：3 条失败、12 条通过，失败均为缺失 `systemConfig` slice；见 `config-store-red.txt`。
- Mock RED：2 条预期失败，分别捕获 API-022 仍映射 13 个 DO-014 用户，以及未知 `CFG-404` 返回 200。
- 生命周期 RED：10 条预期失败，均由缺失 DO-015 状态机引起。

生产实现均在对应 RED 后加入。

## 自动化验证

### C13A 聚焦 Vitest

Vitest 报告开始时间：`2026-07-22 23:10:55 +08:00`。

```text
Test Files  7 passed (7)
Tests       149 passed (149)
Duration    7.29s
exitCode    0
```

覆盖 contracts、OpenAPI consistency、fixtures、handlers、Store、state machines 和共享 runtime。

### 全量 Vitest

Vitest 报告开始时间：`2026-07-22 23:11:10 +08:00`。

```text
Test Files  2 failed | 101 passed (103)
Tests       2 failed | 778 passed (780)
Duration    180.09s
exitCode    1
```

两个失败都只是 30 秒聚合负载 timeout，没有断言失败：

- `PlanLedgerPage.render.test.tsx` 的 SCN-02 URL round-trip 用例；隔离文件复跑 `11/11`，该用例 `22.521s`，文件总时长 `43.08s`。
- `AuditLogPage.action.test.tsx` 的筛选/详情/trace 用例；隔离文件复跑 `3/3`，该用例 `15.280s`，文件总时长 `28.06s`。

两份隔离复跑均于 Vitest 报告时间 `2026-07-22 23:14:21 +08:00` 开始，exit code 均为 0。全量套件据此仍如实记录为未全绿，不把隔离通过表述为全量通过。

### TypeScript

命令：`node_modules\\.bin\\tsc.CMD --noEmit --pretty false`。

```text
exitCode            1
outputLines         909
primaryDiagnostics  783
TS2604              65
TS7016              126
TS7026              592
unexpected families 0
```

与 `tsc-before.txt` 的数量和代码族完全相同；两条非 `.tsx` 诊断仍只是 `.ts` 文件导入 React 时的 TS7016。C13A 新增诊断族和计数均为 0。详见 `tsc-after.txt`。

### Production build

```text
command     pnpm build
exitCode    0
modules     2,569 transformed
build time  17.45s
```

保留既有大 chunk 提示，没有构建失败。

### 全量 Playwright

```text
command     pnpm test:e2e
exitCode    0
tests       48 passed (48)
duration    8.6m
workers     1
```

测试运行曾重写既有 C01～C12 截图；这些文件在运行前为干净状态，随后仅恢复测试生成的二进制改动。C13A 没有新增截图。浏览器控制台仍有既有 Ant Design Message/List warning，没有失败。

## 上游与范围审计

- DO-001～DO-014 的 JSON 深比较一致。
- fixture 的 `version/timezone/roles/scenarios/acceptanceScenarios/errorCodes` 深比较一致。
- `package-baseline.json`、`page-task-matrix.csv`、`traceability.csv` 内容未变；后三者中只有 README/OpenAPI/fixture 产生新的 hash。
- `package.json`、`pnpm-lock.yaml`、`src/pages`、`src/features`、`src/app/routeCatalog.ts`、`src/auth`、RoutePermissionBoundary、PermissionGate 和 `e2e` 未变。
- 没有创建 `demo/c13-system-settings`，没有修改 UI-012 页面或既有 RBAC 目录。
- `git diff --check` 通过；提交历史线性，merge count 为 0。

## 环境与已知限制

- `package.json` 要求 Node `24.18.0`；pnpm 命令报告其运行时为 Node `24.14.0`、pnpm `11.9.0`，直接 `node`/`pnpm exec node` 为 `22.23.1`。本阶段未修改依赖或工具链。
- 全量 Vitest 有两条聚合负载 timeout，隔离均通过；这是当前唯一测试未全绿项。
- TypeScript 仍受冻结 React/JSX 声明缺口影响，计数与预检一致。
- C13A 只建立契约基线；系统配置页面、Gateway、业务命令服务和页面交互仍留给 C13。

## 线性提交链

```text
fea914c docs(c13a): define config contract baseline repair
77af253 docs(c13a): add config contract repair plan
7e0d63f test(c13a): record config baseline preflight
08e8cf3 fix(c13a): add DO-015 config contract baseline
9c6273e fix(c13a): map config apis to DO-015
e931213 fix(c13a): add config store ownership
f76c2eb fix(c13a): register config lifecycle
```

最终 evidence/handoff 提交由本文件所在提交补齐；精确最终分支尖端见交付消息。
