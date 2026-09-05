# C09 / UI-009 最终验证

## 验证上下文

- 验证日期：`2026-07-21`（Asia/Shanghai）
- 验证完成时间：`2026-07-21 23:30:31 +08:00`
- 分支：`demo/c09-safety-interlock`
- 最终证据提交前 HEAD：`6f1d265bc6f3134faa9a01c599cb87ec26ffc552`
- C08 最终源码与交接祖先：`632fa3def12e93754f42eb3c8f16c8d80c207c69`
- Node：`v24.14.0`；仓库声明 `24.18.0`
- pnpm：`11.9.0`；仓库声明 `11.10.0`
- 终端使用 Codex bundled Node/pnpm 路径；未修改依赖、lockfile、测试超时或配置。

## 命令与结果

| 命令 | 退出码 | 结果 |
|---|---:|---|
| `pnpm vitest run src/features/safety-interlock ...SafetyInterlockPage*.test.tsx` | 1 | Windows bundled runtime 下 `pnpm vitest` 无法解析本地 `vitest` shim；不是测试断言失败。随后使用同依赖树的直接 shim 等价执行。 |
| `node_modules\.bin\vitest.CMD run src/features/safety-interlock src/pages/__tests__/SafetyInterlockPage.render.test.tsx src/pages/__tests__/SafetyInterlockPage.action.test.tsx src/pages/__tests__/SafetyInterlockPage.permission.test.tsx` | 0 | C09 focused：`8/8` 文件、`55/55` 测试通过。 |
| `pnpm test -- --run --maxWorkers=1` | 1 | 全量：`75/76` 文件、`605/606` 测试；唯一失败为已知 UI-002 30 秒超时，实际该用例耗时 39.8 秒。 |
| `node_modules\.bin\vitest.CMD run src/pages/__tests__/PlanLedgerPage.render.test.tsx` | 0 | UI-002 隔离复跑：`1/1` 文件、`11/11` 测试通过；未修改 timeout/config。 |
| `pnpm build` | 0 | Vite `8.1.4`，`1937 modules transformed`，构建通过；保留大 chunk 警告。 |
| `node_modules\.bin\playwright.CMD test e2e/ui-009-safety-interlock.spec.ts` | 0 | C09 E2E：`4/4` 通过。 |
| `pnpm test:e2e` | 0 | 全量 Playwright：`39/39` 通过，耗时约 7.7 分钟；仅有既有 Ant Design List/Message 警告。 |
| `node_modules\.bin\tsc.CMD --noEmit` | 1 | 输出保存到 [tsc-after.txt](./tsc-after.txt)；按下节分类。 |
| `git diff --check` | 0 | 通过。 |

全量 Playwright 会重写既有 C01/C04–C09 截图。验证结束后已恢复这些纯运行副作用，保留 Task 7 已检查并提交的 C09 原图。

## TypeScript 分类

| 类别 | 预检 | 最终 | 变化 |
|---|---:|---:|---:|
| 总诊断 | 512 | 559 | +47 |
| TS2604 | 36 | 43 | +7 |
| TS7016 | 89 | 97 | +8 |
| TS7026 | 387 | 419 | +32 |
| C09 非 React `.ts` primary diagnostics | 0 | 0 | 0 |

- 559 条全部属于既有 React/JSX 类型声明缺口：缺少 `react`/`react/jsx-runtime` declarations、`JSX.IntrinsicElements` 和由此派生的 Ant Design JSX construct signature。
- C09 新增 47 条恰好对应新 TSX：TS2604 `7`、TS7016 `8`、TS7026 `32`。
- C09 的 constants、query parser、selectors、Gateway、commands、workflow 与 integration `.ts` 没有 primary diagnostic。
- 按冻结范围不新增 `@types/react`、不修改 `package.json` 或 lockfile，因此不伪装全局 `tsc` 通过。

## 冻结 SHA-256

| 文件 | SHA-256 | 结果 |
|---|---|---|
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

结论：冻结基线 `6/6 MATCH`。

## 截图验证

- 文件数：`8/8`，且 `C09-UI009-*.png` 恰好 8 个。
- LOCKED：1440×1447、1280×2099。
- RESETTING：1440×1531、1280×2155。
- RESTORED：1440×1475、1280×2155。
- OVERRIDE：1440×1503、1280×2155。
- 逐张原图检查已完成：无横向滚动、按钮裁切、文字重叠或中文缺字；状态流、处置按钮、FORCE_STOP 指示/安全边界与返回 UI-008 入口可见。
- 详细索引：[screenshot-index.md](./screenshot-index.md)。

## 变更范围与禁止项

- 相对实施起点 `463c6de926b2fd5e6f5180c7a50c0534c7bcf956`，Task 1–7 共 45 个变更文件；Task 8 仅新增 `coverage.json`、`verification.md`、`tsc-after.txt`、C09 handoff，最终共 49 个 C09 文件/受控测试接线变更。
- 新增 C09 feature、UI-009 页面、测试、MSW 的 API-016/API-017 内部接线、共享 runtime 接线与证据。
- C08 仅修改 selector 测试以验证 UI-008 → UI-009 URL 兼容；`src/features/exception-handling` 业务实现无变化，DO-009 无写入。
- 未修改 `docs/baseline/**`、`package.json`、`pnpm-lock.yaml`、公共 OpenAPI/schema/error code、权限目录、状态机目录、fixture、角色或根 Store slice。
- API-016/API-017 仅使用 `/mock/interlocks` 与 `/mock/interlocks/:id/command`；没有新增 endpoint。
- `exceptionId/scenarioId/from` 只保存在显示用 query context，不进入严格 DO-010 投影或 API-017 body。
- `deviceCommand` 仅出现在负向测试的伪造字段中，投影明确剔除；生产实现没有设备命令字段。
- 页面只有“Demo 记录、不代表真实设备已复位、不执行 PLC/ECS 控制”的声明；没有真实设备复位、PLC/ECS 控制或 FORCE_STOP 静默旁路实现。

## C09 提交链（最终证据提交前）

1. `5f92b57e1df92c58f0ee4450627d74a798988561` — `test(c09): record safety interlock preflight`
2. `70d09dde8510bc736f7c0ec794885bd21ff1654e` — `feat(c09): add safety interlock projections`
3. `73eccbbe0c7ae5c5ad81e80ab64b7d9b77da7804` — `feat(c09): add safety interlock api gateway`
4. `4854e9ebe7b393f3427ef68fc39eb9e761431605` — `feat(c09): add safety interlock commands`
5. `495a6bbf1d2a02ac8f0baf37c819b9d232fcbe5c` — `feat(c09): implement safety interlock page`
6. `83ca4bc4c0eb7eaa37f360cb724b39d807bbd9ec` — `test(c09): verify safety interlock cross-page integration`
7. `6f1d265bc6f3134faa9a01c599cb87ec26ffc552` — `test(c09): cover safety interlock demo flows`

## 已知限制

- Node/pnpm 版本低于仓库声明，且 `pnpm vitest` 直接入口在 bundled Windows runtime 中不可解析；本地直接 shim 可稳定执行同一 Vitest。
- 全量 Vitest 仍存在已知 UI-002 30 秒性能波动；隔离复跑 11/11 通过。
- 全局 TypeScript 因冻结依赖缺少 React 类型声明而退出 1；C09 非 React 诊断为 0。
- 全量 Playwright 保留既有 Ant Design List 弃用及 Message render-time 警告；39/39 仍通过。
- Task 5 计划命令列出的 `src/app/__tests__/routeSmoke.test.tsx` 在仓库中不存在；Vitest 实际执行三个 UI-009 页面文件和现有 `routeRender.test.tsx`，17/17 通过。最终 Playwright route smoke 13/13 路由与全量 39/39 通过。
- C09 只演示安全联锁记录与审批路径；不证明现场设备已复位，不连接真实 PLC/ECS，不提供生产旁路授权。
- 分支保持 `demo/c09-safety-interlock`；未合并、未推送。
