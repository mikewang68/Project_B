# C13 系统配置预检记录

记录时间：`2026-07-22 23:54:21 +08:00`。

## 分支与基点

- 当前分支：`demo/c13-system-settings`
- 预检 HEAD：`17388eb7439240e245dd25b51f8c7592c1f0aed8`
- C13A 权威基点：`a35a93916fa80d1506cdcbd6460b53504e0a88f9`
- C13A 是当前 HEAD 祖先：是（`git merge-base --is-ancestor` exit code 0）
- C13A 之后 merge count：`0`
- 工作区：干净
- 已批准设计：`docs/superpowers/specs/2026-07-22-c13-system-settings-design.md`
- 详细计划：`docs/superpowers/plans/2026-07-22-c13-system-settings.md`

## 0.3.0 冻结 hash

重算结果与 `docs/baseline/SHA256SUMS.txt` 一致，`6/6`：

```text
5df343d2ea74cc01cb9515205604b14fde0e4962b8a49518527e058192a9e3b3  README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  package-baseline.json
1ea80f23293e9d1e93e464478c1051ca97887e0b6e924a4b816ee264a4651e1e  openapi.yaml
4d206093e0d36a56071dd2eb21feb47a35f690d2f3cdfecb62b782362a50204a  demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  traceability.csv
```

## C13A 聚焦回归

命令覆盖 contracts、OpenAPI consistency、fixtures、handlers、Store、DO-015 state machine 和共享 runtime。

```text
Test Files  7 passed (7)
Tests       149 passed (149)
Start at    23:53:49
Duration    6.86s
exitCode    0
```

首次预检命令把 runtime 文件误写为不存在的 `DemoRuntimeContext.test.tsx`，Vitest 因此只运行 6 文件、135 tests；发现后已把计划修正为实际文件 `src/runtime/__tests__/runtime.test.tsx`，并以上述 7 文件、149 tests 结果作为权威预检。

## TypeScript 冻结基线

`node_modules\.bin\tsc.CMD --noEmit --pretty false` 实测 exit code 1、909 输出行、783 条 primary diagnostics：

```text
TS2604  65
TS7016  126
TS7026  592
```

诊断代码族和数量与 C13A 完全相同。非 TSX primary diagnostics 仍只有：

```text
src/app/routeCatalog.ts                 TS7016 react declaration
src/features/plan-entry/readState.ts   TS7016 react declaration
```

本阶段不安装 `@types/react`，最终验证将与该实测基线逐项比较。

## 实施边界

- DO-015 / `state.systemConfig.configVersions` 继续是唯一配置领域事实。
- 不修改 0.3.0 baseline、公共 API/Schema、Store ownership、DO-015 state machine、RBAC 或依赖。
- UI-012 只实现 API-023 `edit`，页面 reset 保持真实禁用。
- 按详细计划逐块保留 RED，再实现 GREEN；不合并、不推送。
