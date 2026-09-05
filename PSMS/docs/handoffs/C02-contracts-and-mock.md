# C02 契约与 Mock 层交接

## 交付结论

C02 已实现严格 camelCase 的 Zod/TypeScript 契约和确定性 MSW Mock 层。实现保持 C02A 冻结基线不变，未进入 Store、状态机、命令管线、RBAC、审计持久化或业务页面。

## 契约层

- `src/contracts/enums.ts`：24 个 OpenAPI 枚举组件及 Zod 枚举，包含恰好 9 个公开错误码。
- `src/contracts/schemas.ts`：成功/失败信封、`DO-001`～`DO-014` 严格 Schema、领域类型和冻结数量。
- `src/contracts/requests.ts`：14 个具名 POST 请求 Schema；`reason` 可选，`ruleVersion` 为 string。
- `src/contracts/api.ts`：25 个 API 的方法、标准 OpenAPI 路径、MSW `:id` 路径、operationId、参数、请求与响应 Schema catalog。
- `src/contracts/index.ts`：稳定公共导出。

契约测试逐项对照 `openapi.yaml`，验证 14 个领域字段集合与 required、全部 24 个枚举全集、GET 参数、14 个请求示例以及 200/400/403/409 信封。

## Mock 层

- `src/mocks/fixtures.ts`：直接导入唯一 `demo-fixtures.json`，逐类严格校验并在装载/读取时深拷贝。
- `src/mocks/scenarios.ts`：SCN-01～SCN-07、故障投影、事务式对象变更、原子 reset、固定时钟、可复位 trace/audit 计数器和一次性失败覆盖。
- `src/mocks/delayPolicy.ts`：生产默认使用 MSW delay，测试注入零等待执行器。
- `src/mocks/handlers.ts`：按 catalog 顺序生成恰好 25 个 handler；所有 JSON 响应都通过成功/失败信封。
- `src/mocks/browser.ts` / `server.ts`：浏览器 worker 与 Node 测试 server 适配器。

SCN-02 的 `trackNo` 缺失只存在于投影视图，合法基础 Plan 不被污染；所有 runtime 对象修改都在深拷贝候选状态通过 Zod 后才原子替换。

## 证据

- 契约红灯：`docs/evidence/C02/contract-red.txt`
- Mock 红灯：`docs/evidence/C02/mock-red.txt`
- 契约覆盖：`docs/evidence/C02/contract-coverage.json`
- Mock 覆盖：`docs/evidence/C02/mock-coverage.json`
- TypeScript 前后：`docs/evidence/C02/tsc-before.txt`、`tsc-after.txt`
- 最终验证：`docs/evidence/C02/verification.md`

最终回归为 10/10 测试文件、33/33 测试、生产构建成功、E2E 14/14；六项冻结 SHA-256 全部匹配。

## 已知边界

1. 全局 `tsc` 仍因 C01 已知的 React/ReactDOM 类型声明缺失退出 1；C02 源码与测试新增诊断为 0。依任务约束未修改依赖。
2. 本任务提供 worker/server 适配器但不改 `main.tsx`，因此浏览器启动接线和 `mockServiceWorker.js` 由后续页面集成任务完成。
3. API-013 的 OpenAPI path `id` 示例是通用对象字符串；运行时场景标识以唯一 fixture 中的 `SCN-01`～`SCN-07` 为准。
4. `pnpm build` 保留 C01 既有的大 chunk 警告；C02 未修改页面打包策略。

## 后续使用

- 浏览器：从 `src/mocks/browser.ts` 导入 `worker` 并在应用入口按环境启动。
- Node 测试：从 `src/mocks/server.ts` 导入 `server`。
- 契约消费：统一从 `src/contracts/index.ts` 导入 Schema、类型和 `apiCatalog`。
- 禁止在页面或后续 Store 中复制 fixture；状态初始化应读取 Mock API 或复用 Mock runtime 的深拷贝输出。
