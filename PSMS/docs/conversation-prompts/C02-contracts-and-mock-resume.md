# C02 契约与 Mock 层继续实现提示词（C02A 修复基线后）

你正在继续此前暂停的 **C02：契约与 Mock 层** 对话。此前因 `openapi.yaml` 与 `demo-fixtures.json` 不一致而触发的“契约一致性闸门”已经由 C02A 修复并冻结；从现在起，以本提示词为最高优先级继续执行。凡是旧提示、旧交接或当前上下文中与本提示词冲突的字段映射、六基线哈希、fixture 形态、错误码、场景或“发现冲突后暂停”结论，均视为已被本提示词明确取代，不再沿用。

## 一、仓库与冻结点

- 工程：`E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- 分支：`demo/c02-contracts-mock`
- C01 基线提交：`eb8487fa76d6f506fe24650d40fd03137efd514c`
- C02A 设计提交：`c95203b4a78958afde2932cc47e2c09e7655ed5f`
- C02A 修复提交：`9f23acc8d9b0c1e9a23cfa9eb6d5355a06ed7cda`
- C02A 修复提交信息：`fix(c02): reconcile generated demo contracts`

开始前必须确认 `git branch --show-current` 为上述分支、工作区干净，并核对下列六份文件 SHA-256：

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

也可直接校验 `docs/baseline/SHA256SUMS.txt`。若哈希不匹配，只报告实际差异并停止；若匹配，就继续实现，不得再次修改 `docs/baseline/**`。

## 二、唯一权威契约

1. 运行时对象形态统一为严格 camelCase，`additionalProperties: false`。需求中的 snake_case 仅通过 OpenAPI 的 `x-source-field` / `x-source-fields` 追踪，不得进入运行时对象，也不得再写字段转换层。
2. 领域对象固定为 `DO-001` 至 `DO-014` 共 14 个。共享枚举、请求 Schema、成功/失败信封和页面结果 Schema 不计入 14 个领域对象。
3. Fixture 数量按 DO-001→DO-014 固定为：`3, 8, 4, 8, 12, 12, 10, 6, 5, 4, 4, 3, 9, 13`。`docs/baseline/demo-fixtures.json` 是唯一数据源；不得手写、复制或维护第二套 fixture。
4. API 固定为 `API-001` 至 `API-025` 共 25 个。OpenAPI 使用标准 `{id}` 路径；MSW 运行时路径读取 operation 的 `x-msw-path`。GET 使用 query/path parameters，不得使用 requestBody。
5. `POST /mock/plans/{id}/confirm` 的 `reason` 是真正的可选字段，不存在名为 `reason?` 的属性。`ruleVersion` 为 string，示例 `RULE-1.0`。
6. 200 成功响应和 400/403/409 失败响应必须按 OpenAPI 引用解析。成功与失败信封均含 `traceId`、`auditLogId`；失败信封还含 `errorCode`、`message`。
7. 公开错误码只能是以下 9 个：`TOS-EXT-001`、`TOS-EXT-002`、`TOS-EXT-003`、`TOS-WO-001`、`TOS-IL-001`、`TOS-OFF-001`、`TOS-AUTH-001`、`DEMO-VERSION-001`、`DEMO-SCENARIO-001`。不得出现 `TOS-DATA-001`。
8. 场景固定为 `SCN-01` 至 `SCN-07`：
   - SCN-01：PLAN-001，NONE，300ms；
   - SCN-02：PLAN-002，运行时省略 `trackNo`，`TOS-EXT-002`，300ms；
   - SCN-03：PLAN-003，INTERFACE_TIMEOUT，`TOS-EXT-001`，1500ms；
   - SCN-04：WO-005，DEVICE_OFFLINE，`TOS-WO-001`，800ms；
   - SCN-05：WO-006 + IL-001，INTERLOCK_FORCE_STOP，`TOS-IL-001`，500ms；
   - SCN-06：OFF-001，OFFLINE_VERSION_CONFLICT，`TOS-OFF-001`，700ms；
   - SCN-07：APT-006，SOURCE_DATA_CONFLICT，`TOS-EXT-003`，600ms。
9. 七个场景必须可以原子复位。场景 mutation 只能在运行时作用于从权威 fixture 深拷贝得到的状态，不得污染基准对象。

## 三、严格范围

C02 只实现：

- `src/contracts/**`：Zod 枚举、14 个领域 Schema、共享信封、25 个请求/响应契约、fixture 解析和类型导出；
- `src/mocks/**`：MSW handlers、唯一 fixture 装载器、场景/故障控制、原子 reset、确定性延迟和失败信封；
- 对应的契约与 Mock 测试、覆盖矩阵和 C02 证据/交接文档。

C02 禁止实现或修改：Store、业务状态机、命令管线、RBAC、审计持久化、13 个业务页面、路由结构和视觉组件。禁止修改 `package.json`、`pnpm-lock.yaml` 或安装/升级依赖；必须使用现有 Zod、MSW、Vitest 与 Playwright。

## 四、执行顺序与两个内部闸门

采用 TDD，任何实现代码之前先保存有效红灯。一次只推进一个闸门。

### 0. 前置证据：tsc-before

先运行并完整保存 `tsc-before`：

```powershell
pnpm exec tsc --noEmit
```

把命令、时间、退出码和完整输出保存到 `docs/evidence/C02/tsc-before.txt`。C01 已知的 `@types/react` / `@types/react-dom` 声明限制可以保留；不得通过增加包、改版本或放宽 tsconfig 解决。该已知限制不阻断后续 Vitest、build 和 E2E 闸门，但必须在最终交接中如实记录。

### 闸门 A：契约层

1. 先写失败测试并保存到 `docs/evidence/C02/contract-red.txt`，至少覆盖：
   - DO-001～DO-014 的所有基础 fixture 均能被对应严格 Zod Schema 解析；
   - 缺少 required 字段、非法枚举、额外字段都失败；
   - 精确对象数量和稳定 ID；
   - 25 个 operation 的请求、200/400/403/409 信封；
   - 可选 `reason`、字符串 `ruleVersion`；
   - 成功/失败 `traceId` 与 `auditLogId`；
   - 精确 9 错误码与 SCN-01～SCN-07 结构。
2. 再实现 `src/contracts/**`，不得绕过 Zod 校验或使用 `any` 吞掉差异。
3. 生成 `docs/evidence/C02/contract-coverage.json`，逐项列出 DO-001～DO-014、API-001～API-025、9 个错误码和 SCN-01～SCN-07 的测试映射与通过状态。
4. 只有契约测试全绿、coverage 无缺项、基线哈希仍未变化，才允许进入闸门 B。

### 闸门 B：Mock 层

1. 先写失败测试并保存到 `docs/evidence/C02/mock-red.txt`，覆盖 25 个 handlers、标准路径与 `x-msw-path` 映射、query/path/body 校验、权限/冲突/校验失败信封、七场景延迟/故障/mutation、reset 幂等与跨测试隔离。
2. 再实现 `src/mocks/**`。所有运行时数据从 `demo-fixtures.json` 深拷贝产生；禁止内联第二套业务对象。
3. 确定性测试不得真实等待 1500ms；使用 fake timers 或注入时钟验证配置值及调度行为。
4. 生成 `docs/evidence/C02/mock-coverage.json`，逐项映射 API-001～API-025、SCN-01～SCN-07、400/403/409 和 reset/隔离测试。
5. 只有 Mock 测试全绿、coverage 无缺项、基线哈希仍未变化，才算完成 C02。

## 五、完成验证

至少执行并记录：

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
```

要求：

- 新增契约/Mock 测试全绿；
- 构建成功；
- C01 现有 14 条 E2E 全部通过；
- `git diff --check` 通过；
- 六份冻结基线哈希与本提示词完全一致；
- `git diff --name-only` 不含 Store、状态机、RBAC、页面或路由实现；
- 保存测试/构建/E2E/哈希结果到 `docs/evidence/C02/verification.md`；
- 创建 `docs/handoffs/C02-contracts-and-mock.md`，列明文件、导出、25 API 覆盖、7 场景、已知类型限制和下一任务入口。

## 六、提交与交付

完成后创建一个独立 C02 源码提交，建议提交信息：

```text
feat(c02): implement contracts and mock layer
```

提交前检查：不得包含 `docs/baseline/**` 改动，不得包含 C02 范围外源码，不得覆盖 C01 或 C02A 提交。最后报告：提交完整哈希、变更文件、契约/Mock 测试数量、build 结果、E2E 14/14、两个 coverage JSON 路径、交接路径及仍保留的已知限制。

现在开始执行。不要重新讨论已冻结的字段映射，也不要因旧的 14 对象冲突再次暂停；只有实时哈希不匹配或修复后基线自身被新测试证明存在新的不可调和矛盾时，才触发阻断并提供可复现证据。
