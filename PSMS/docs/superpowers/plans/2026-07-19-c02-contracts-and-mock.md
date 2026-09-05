# C02 契约与 Mock 层实施计划

> 执行分支：`demo/c02-contracts-mock`
>
> 冻结点：`6bff5795729dd0beadd3c2c82d9a7c9e03c30f49`
>
> 边界：只实现 `src/contracts/**`、`src/mocks/**`、对应测试、证据与交接文档；不修改 `docs/baseline/**`、依赖、锁文件、页面、路由、Store、状态机、RBAC 或审计持久化。

## Task 1：保存编译前置证据

**文件：**

- 新建：`docs/evidence/C02/tsc-before.txt`

**步骤：**

1. 使用仓库指定的 Node 24.18.0 / pnpm 11.10.0 运行 `pnpm exec tsc --noEmit`。
2. 原样记录命令、北京时间、退出码与完整标准输出/错误输出。
3. 仅记录既有 React 类型声明限制，不修改依赖或基线来掩盖它。

## Task 2：Gate A 契约层红测

**文件：**

- 新建：`src/contracts/__tests__/schemas.test.ts`
- 新建：`src/contracts/__tests__/openapi-consistency.test.ts`
- 新建：`docs/evidence/C02/contract-red.txt`

**步骤：**

1. 先写测试，要求恰好 `DO-001`～`DO-014`、严格 camelCase、fixture 数量 `3/8/4/8/12/12/10/6/5/4/4/3/9/13`、严格枚举与未知字段拒绝。
2. 测试 25 个 API 的标准路径、`x-msw-path`、operationId、GET 参数、具名请求、200/400/403/409 信封及 9 个公开错误码。
3. 在任何契约实现文件出现前运行目标测试，保存可复现失败输出到 `contract-red.txt`。

## Task 3：Gate A 契约层实现与绿灯

**文件：**

- 新建：`src/contracts/enums.ts`
- 新建：`src/contracts/schemas.ts`
- 新建：`src/contracts/requests.ts`
- 新建：`src/contracts/api.ts`
- 新建：`src/contracts/index.ts`
- 新建：`docs/evidence/C02/contract-coverage.json`

**步骤：**

1. 用 Zod 4.4.3 定义公共枚举、成功/失败信封、14 个领域对象和具名请求；对象采用 `.strict()`。
2. `reason` 仅作为可选字段，`ruleVersion` 为字符串并接受 `RULE-1.0`；不增加 snake_case 运行时转换层。
3. 用稳定的 API catalog 记录恰好 25 个方法/路径/operationId/`x-msw-path`/参数/请求 Schema 映射；测试逐项对照 OpenAPI。
4. 运行 Gate A 测试至绿灯，输出机器可读覆盖清单。
5. 重算六项基线 SHA-256；全部匹配后才能开始 Gate B。

## Task 4：Gate B Mock 层红测

**文件：**

- 新建：`src/mocks/__tests__/fixtures.test.ts`
- 新建：`src/mocks/__tests__/scenarios.test.ts`
- 新建：`src/mocks/__tests__/handlers.test.ts`
- 新建：`docs/evidence/C02/mock-red.txt`

**步骤：**

1. 先写测试，要求 runtime 只从 `demo-fixtures.json` 深拷贝、reset 原子恢复对象/场景/时钟/计数器/故障状态。
2. 覆盖 SCN-01～SCN-07 的固定 seedRefs、故障、错误码和延迟，并确认 SCN-02 只在故障覆盖中移除 `trackNo`。
3. 覆盖 25 个 MSW handler 的唯一方法/路径以及成功、400 校验、403 权限、409 冲突响应。
4. 测试注入零等待策略，禁止真实等待；在 Mock 实现文件出现前保存红灯输出。

## Task 5：Gate B Mock 层实现与绿灯

**文件：**

- 新建：`src/mocks/fixtures.ts`
- 新建：`src/mocks/clock.ts`
- 新建：`src/mocks/delayPolicy.ts`
- 新建：`src/mocks/scenarios.ts`
- 新建：`src/mocks/handlers.ts`
- 新建：`src/mocks/server.ts`
- 新建：`src/mocks/browser.ts`
- 新建：`src/mocks/index.ts`
- 新建：`docs/evidence/C02/mock-coverage.json`

**步骤：**

1. 直接导入冻结 fixture JSON；每次初始化/读取/reset 均使用深拷贝，禁止在源码中复制第二套业务对象。
2. 实现固定时钟、递增 trace/audit ID、可注入延迟策略、场景控制器和测试用一次性 403/409 故障覆盖。
3. 生成恰好 25 个 MSW handlers；路径使用 `x-msw-path` 的 `:id` 形式，请求由契约层解析。
4. 所有 JSON 成功/失败响应分别通过公共信封；失败仅使用冻结的 9 个错误码。
5. 运行 Gate B 测试至绿灯并输出机器可读覆盖清单。

## Task 6：全量验证、交接与提交

**文件：**

- 新建：`docs/evidence/C02/verification.md`
- 新建：`docs/handoffs/C02-contracts-and-mock.md`

**步骤：**

1. 运行 `pnpm test -- --run`、`pnpm build`、`pnpm test:e2e`，记录测试文件数、断言数和 E2E 14/14。
2. 复核六项基线 SHA-256、`package.json`/锁文件零变更、变更范围、公开错误码恰好九项、无运行时 snake_case 字段。
3. 检查 Git diff 与未跟踪文件，将真实结果写入验证和交接文档。
4. 仅创建一个 C02 源码提交：`feat(c02): implement contracts and mock layer`。
5. 提交后再次确认工作区干净并报告最终提交哈希。
