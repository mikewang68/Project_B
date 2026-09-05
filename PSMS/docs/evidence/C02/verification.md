# C02 契约与 Mock 层验证记录

## 结论

C02 允许范围内的契约与 Mock 实现已通过红绿测试、全量回归、生产构建、Playwright E2E、类型增量、冻结哈希和 Git 范围检查。

## 冻结点与范围

- 分支：`demo/c02-contracts-mock`
- 开始提交：`6bff5795729dd0beadd3c2c82d9a7c9e03c30f49`
- 源码范围：`src/contracts/**`、`src/mocks/**`
- 文档范围：`docs/evidence/C02/**`、`docs/handoffs/C02-contracts-and-mock.md`、本次实施计划
- `package.json`、`pnpm-lock.yaml`、页面、路由、Store、状态机、RBAC、审计持久化均未修改。

## TDD 证据

| 闸门 | 红灯 | 绿灯/覆盖 |
|---|---|---|
| Gate A 契约 | `contract-red.txt`：实现前 2 个套件因缺少 `src/contracts/index.ts` 失败 | `contract-coverage.json`：14 个领域对象、24 个枚举组件、14 个请求、25 个 API、公共信封与 9 个错误码 |
| Gate B Mock | `mock-red.txt`：实现前 3 个套件因缺少 Mock 模块失败 | `mock-coverage.json`：唯一 fixture、深拷贝、原子 reset、7 场景、25 handlers、400/403/409、无真实测试等待 |

## 最终命令

| 命令 | 结果 |
|---|---|
| `pnpm test -- --run` | 退出码 0；10/10 测试文件、33/33 测试通过 |
| `pnpm build` | 退出码 0；Vite 8.1.4 构建 1489 个模块成功；仅有既有大 chunk 提示 |
| `pnpm test:e2e` | 退出码 0；14/14 Playwright 测试通过，覆盖 13 路由、404、1440×900 与 1280×720 |

最终命令使用 Node 24.18.0、pnpm 11.10.0。第一次 E2E 尝试在启动测试页面前因本机 Playwright 浏览器缓存为空而失败；安装 Playwright 1.61.1 对应 Chromium revision 1228 后，连续两次完整 E2E 均为 14/14。该环境恢复未修改工程依赖或锁文件。

## TypeScript 增量检查

- `tsc-before.txt`：`pnpm exec tsc --noEmit` 退出码 1，记录 38 条既有 React、ReactDOM 与 JSX 声明诊断。
- `tsc-after.txt`：全局退出码仍为 1、主诊断仍为 38 条，但 `src/contracts/**` 与 `src/mocks/**` 诊断为 0。
- 按任务约束未增加 `@types/react` 或 `@types/react-dom`，也未修改依赖。

## 六项冻结 SHA-256

| 文件 | SHA-256 | 结果 |
|---|---|---|
| `README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

## 静态与范围检查

- `git diff --check`：通过。
- `package.json` / `pnpm-lock.yaml` diff：空。
- C02 生产源码未使用系统时钟、随机数、随机 UUID、`setTimeout` 或 sleep。
- 运行时 DTO 使用 camelCase；生产源码中的下划线只属于冻结 `operationId` 值。
- 公开错误码集合逐项与 OpenAPI/fixture 一致，恰好 9 项。
- 生产源码未手写 `PLAN-*`、`WO-*`、`APT-*`、`IL-*`、`OFF-*` 业务 fixture；运行时业务种子只导入 `docs/baseline/demo-fixtures.json`。
