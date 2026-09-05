# C13A 预检证据

- 执行日期：2026-07-22（Asia/Shanghai）
- 工程：`production-dispatch-demo`
- 分支：`demo/c13-config-contract-baseline`
- 预检 HEAD：`77af253e9174cac4c6b2ed6069fe22d35b4d2fae`
- C12 基点：`f85ede995c6958bbf36e1aa561b1ca7332fb8588`
- C12 基点是当前 HEAD 祖先：是（`git merge-base --is-ancestor` exit 0）
- C12 基点之后 merge commit：0
- 工作区：预检时干净
- Git 隔离检查：`.git` 与 common dir 都为 `.git`，当前是普通 checkout；用户已指定本工程目录和专用分支，因此按 `using-git-worktrees` 的显式位置偏好原地执行，不另建 worktree。
- 依赖：未安装、未更新；`package.json` 与 lockfile 未修改。

## 基线运行时

```text
direct node: v22.23.1
pnpm exec node: v22.23.1
pnpm: 11.9.0
package engine: 24.18.0
```

运行时版本低于 package 声明值，保留现状，不修改依赖或系统运行时。

## 基线测试观察

第一次执行 `pnpm test` 时，外层命令在 120 秒到期；此前全量负载中的
`src/app/__tests__/routeRender.test.tsx` “13 个路由均显示对应编号、中文名称和当前路径”
已在 40.537 秒处报告超时。该失败不在 C13A 路径，且没有行为断言失败。

随后隔离执行：

```text
node_modules\.bin\vitest.CMD run src/app/__tests__/routeRender.test.tsx --reporter=verbose
exitCode: 0
testFiles: 1 passed
tests: 3 passed
keyCaseDuration: 12.638s
totalDuration: 16.04s
```

裁决：这是全量负载下的性能型基线超时；不修改测试或 timeout。最终验收重新执行全量 Vitest，并如实记录完整结果。

## 冻结范围裁决

- C13 功能实施继续暂停。
- 本阶段不创建 `demo/c13-system-settings`。
- 允许修改 C13A 设计批准的 baseline、contract、Mock、Store、状态机、测试和证据路径。
- 禁止修改页面、RBAC、依赖、C12 audit-trail 业务实现和 C04-C12 feature 实现。
- 不合并、不推送。
