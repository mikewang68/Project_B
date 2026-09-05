# C03 状态与权限内核验证证据

## 结论

C03 的状态内核、权限内核、审计和最小 UI 门禁全部通过冻结验收。分支为 `demo/c03-state-permissions`，起点 `55906af251cc4e0163104e8e6fa24572f13a77cd` 仍是当前提交祖先。未修改依赖、锁文件、冻结基线、C02 契约/Mock、业务页面或 C01/C02 证据。

## 执行环境

- 实际 Node：`v24.14.0`；实际 pnpm：`11.9.0`。
- 仓库声明 Node `24.18.0`、pnpm `11.10.0`，因此 pnpm 命令输出非阻断 engine warning。
- 非 ASCII 工作区下 `pnpm exec tsc` / `pnpm exec vitest` 未解析本地 shim；聚焦测试和类型检查改用同一锁定依赖的 `node_modules/.bin/*.CMD`，未改依赖或锁文件。

## TDD 闸门与回归

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `node_modules/.bin/vitest.CMD run src/commands/__tests__/stateMachines.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/pipeline.test.ts` | 0 | Gate A：3/3 文件，91/91 测试 |
| `node_modules/.bin/vitest.CMD run src/auth/__tests__/authorize.test.ts src/auth/__tests__/masking.test.ts src/commands/__tests__/authorization-integration.test.ts src/governance/__tests__/audit.test.ts src/components/__tests__/permissionGate.test.tsx src/app/__tests__/routePermission.test.tsx` | 0 | Gate B：6/6 文件，41/41 测试 |
| `pnpm test -- --run` | 0 | 19/19 文件，165/165 测试 |
| `pnpm build` | 0 | Vite 8.1.4；1585 modules transformed；主包 783.04 kB（gzip 246.31 kB） |
| `pnpm test:e2e` | 0 | Playwright 恰好 14/14；13 路由与双分辨率/404 场景均通过 |
| `pnpm exec tsc --noEmit` | 1 | 环境未解析本地 `tsc` shim |
| `node_modules/.bin/tsc.CMD --noEmit` | 1 | 全局 38 条冻结前 React/ReactDOM/JSX 诊断；C03 诊断 0 |

构建仅保留既有的 `Some chunks are larger than 500 kB after minification` 警告。Playwright 会重写 4 张 C01 截图；验证后已逐文件恢复，最终 C01 证据 diff 为 0。

## TypeScript 增量

- 前置全局 primary diagnostics：38。
- 后置全局 primary diagnostics：38。
- 忽略行列位置后，前后诊断集合差异：0。
- C03 新增范围 primary diagnostics：0。
- 完整输出：`docs/evidence/C03/tsc-before.txt`、`docs/evidence/C03/tsc-after.txt`。

没有为绕过诊断新增声明、依赖、`tsconfig` 变更或 suppression directive。

## 覆盖证据

- 红灯：`docs/evidence/C03/state-red.txt`、`docs/evidence/C03/permission-red.txt`。
- 7 类状态机：`docs/evidence/C03/state-machine-coverage.json`，69/69；52 条允许迁移和每机代表性拒绝均具名。
- 12 切片 Store：`docs/evidence/C03/store-coverage.json`，13/13；DO-001～DO-014 恰好覆盖，SCN-01～SCN-07 原子 reset 均通过。
- 权限：`docs/evidence/C03/permission-coverage.json`，34/34；13 角色、45 权限、13 路由、6 阶段、SoD、离线、脱敏、会话、动作/路由门禁均覆盖。
- 命令与审计补充测试位于 `pipeline.test.ts`、`authorization-integration.test.ts`、`audit.test.ts`；完整回归总数已包含它们。

## 冻结边界

六份 SHA-256 复核结果均为 MATCH：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

- `git diff --check 55906af..HEAD`：退出 0。
- `package.json`、`pnpm-lock.yaml`、`docs/baseline/**`、`src/contracts/**`、`src/mocks/**`、`src/pages/**`、`docs/evidence/C01/**`、`docs/evidence/C02/**`：差异文件数 0。
- C03 生产源码中系统时钟、随机数、原始 timer：0 命中。
- C03 生产源码中 snake_case 属性定义：0 命中。
- C03 生产源码公开错误码字面量仅为冻结九码中的 `DEMO-SCENARIO-001`、`DEMO-VERSION-001`、`TOS-AUTH-001`、`TOS-EXT-001`。

## 变更范围

最终文档提交前共有 44 个实现/证据文件：`docs/evidence/C03` 7、`e2e` 1、`src/app` 6、`src/auth` 10、`src/commands` 7、`src/components` 3、`src/governance` 2、`src/layouts` 1、`src/stores` 7。本提交再增加 `tsc-after.txt`、本验证文件和 C03 交接文件。

实现严格停在公共内核与最小门禁 UI，没有制作业务页面。
