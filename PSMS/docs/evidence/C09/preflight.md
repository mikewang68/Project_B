# C09 安全联锁预检证据

## 起点与分支

- 记录时间：`2026-07-21 22:33:50 +08:00`
- 工作分支：`demo/c09-safety-interlock`
- 分支创建前分支：`demo/c08-exception-handling`
- 分支创建点 / 当前源码 HEAD：`463c6de926b2fd5e6f5180c7a50c0534c7bcf956`
- C08 最终源码与交接提交：`632fa3def12e93754f42eb3c8f16c8d80c207c69`
- 祖先校验：`git merge-base --is-ancestor 632fa3d... HEAD`，退出码 `0`
- `632fa3d...` 之后仅有 C09 设计、实施计划和执行提示三笔文档提交；没有后续源码变化。
- 分支创建前工作区：干净。

## 冻结基线哈希

| 文件 | SHA-256 | 状态 |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

结论：`6/6 MATCH`。

## 环境

- Node：`v24.14.0`，低于仓库声明的 `24.18.0`。
- pnpm：`11.9.0`，低于 `packageManager` 声明的 `11.10.0`。
- 终端初始未把 Node 加入 `PATH`；使用 Codex 已配置的 bundled Node 与 pnpm 路径完成全部实测，没有修改依赖或配置。

## TypeScript 基线

命令：`node_modules\.bin\tsc.CMD --noEmit`

- 退出码：`1`
- 诊断总数：`512`
- `TS2604`：`36`
- `TS7016`：`89`
- `TS7026`：`387`
- 非 React/JSX primary diagnostics：`0`
- 原始输出：[tsc-before.txt](./tsc-before.txt)

结果与 C08 交接一致：失败仅来自既有 React/JSX 类型声明缺口。

## Vitest 基线

命令：`pnpm test -- --run --maxWorkers=1`

- 文件：`67/68` 通过。
- 测试：`546/547` 通过。
- 唯一失败：`src/pages/__tests__/PlanLedgerPage.render.test.tsx` 中 UI-002 的 `round-trips the SCN-02 missing-field exception filter through URL replacement and remount`，触发既有 `30000ms` 超时。
- 隔离复跑同一用例：`1/1` 通过，另有 `10` 条同文件用例按筛选跳过。
- 未修改 timeout、测试或配置。

结果与 C08 交接中记录的既知波动一致。

## Build 基线

命令：`pnpm build`

- 退出码：`0`
- Vite：`8.1.4`
- modules transformed：`1922`
- 既有大 chunk warning 保留；未修改构建配置。

## Playwright 基线

命令：`pnpm test:e2e`

- 全量：`34/35` 通过。
- 唯一失败：C01 route smoke 的 UI-003 路由捕获到一次 `Failed to load resource: the server responded with a status of 404 (Not Found)` 控制台错误。
- UI-003 隔离复跑：`1/1` 通过。
- 第一次全量尝试因外部命令上限在第 `24/35` 条后被终止；终止前 UI-011 出现了同类不固定 route-smoke 失败，说明失败路由并不稳定。
- 与 C08 交接的 `35/35` 相比，本次存在一次不可稳定复现的 route-smoke 资源 404 波动；未修改 E2E、重试、超时或 Playwright 配置。
- 全量 E2E 运行产生的 C01/C04-C08 旧截图重写副作用已恢复，未纳入 C09 变更。

## 已知 C08 边界

- UI-009 在预检时仍是 `PageScaffold`，本次预检尚未写入 C09 源码。
- C08 只提供 `/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling` 来源入口，不处理联锁复位或恢复。
- query 中的 `exceptionId/scenarioId/from` 只能作为来源上下文，不是 DO-010 生产外键。
- C09 不改写 DispatchException、C08 workflow、回放缓存或 DO-009 状态。
- 不实现真实设备复位、PLC/ECS 控制或 FORCE_STOP 静默绕过。
