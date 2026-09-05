# C02A 契约基线修复交接

## 结论

C02 最初报告的 14 类对象冲突及 OpenAPI 响应、枚举、字段、错误码、场景种子问题已经修复。唯一权威运行时契约为 `docs/baseline` 中的 0.2.0 基线，规模冻结为 14 个领域对象、25 个 Mock API、7 个演示场景、9 个公开错误码。

## 稳定导出

- `docs/baseline/openapi.yaml`：OpenAPI 3.1 JSON 语法；严格 camelCase；标准 `{id}` + `x-msw-path`；请求/响应引用完整。
- `docs/baseline/demo-fixtures.json`：唯一 fixture 数据源；含精确对象数量、13 个角色、7 个可原子复位场景和 9 个错误码。
- `docs/baseline/README.md`：0.2.0 冻结规则和运行命令。
- `docs/baseline/SHA256SUMS.txt`：六份官方基线的冻结哈希。
- `docs/evidence/C02A/baseline-diff.md`：字段和决策差异。
- `docs/evidence/C02A/verification.md`：生成、文档、契约和 C01 回归证据。

## C02 重启点

1. 确认分支仍为 `demo/c02-contracts-mock`，工作区干净，六份哈希与 `docs/baseline/SHA256SUMS.txt` 一致。
2. 从 `tsc-before` 证据开始，记录已知 React 类型声明限制，不改依赖。
3. 先写契约层失败测试，再实现 Zod Schema/解析器；通过“契约一致性闸门”后才进入 MSW。
4. Mock 层只能装载 `demo-fixtures.json`，不得手写第二套 fixture，不得修改 `docs/baseline`。
5. C02 范围只含契约与 Mock；不得进入 Store、状态机、命令管线、RBAC、审计持久化或页面实现。

## 冻结提交

本交接随提交信息严格为 `fix(c02): reconcile generated demo contracts` 的 C02A 修复提交冻结。其完整提交哈希在后续 C02 继续提示词中写入，以避免提交自引用。
