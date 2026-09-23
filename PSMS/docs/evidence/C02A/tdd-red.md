# C02A 契约语义测试红灯证据

- 时间：2026-07-19 14:23:40 +08:00
- 工作目录：`C:\Users\msi-cn\Documents\Codex\2026-07-16\e-b`
- Python：`C:\Users\msi-cn\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`
- 命令：`python -m unittest work.demo_doc_pack.tests.test_contract_semantics -v`
- 退出码：`1`
- 汇总：`Ran 12 tests in 0.057s — FAILED (failures=11)`
- 源码边界：执行红灯时，Demo 仓库不存在 `src/contracts/**` 和 `src/mocks/**`，C02 尚未写入应用源码。

## 逐项结果

| 测试 | 结果 | 预期失败原因 |
|---|---|---|
| `test_all_base_fixtures_validate_against_domain_schemas` | FAIL | DO-001 fixture 为 camelCase，Schema 要求 snake_case 且缺到发时间。 |
| `test_all_operations_have_request_and_200_400_403_409_schemas` | FAIL | operation 缺少 `x-api-id`，响应没有 Schema。 |
| `test_domain_object_ids_and_fixture_counts_are_exact` | FAIL | 当前数量为 DO-001 两条、其余大多一条，不符合冻结数量。 |
| `test_every_enum_is_declared_and_fixture_values_are_members` | FAIL | OpenAPI 中枚举 Schema 数量为 0。 |
| `test_get_operations_use_parameters_not_request_body` | FAIL | GET 使用可选 requestBody，没有 query parameters。 |
| `test_path_parameters_and_x_msw_paths_are_complete` | FAIL | OpenAPI 仍使用 `:id` 且没有 `x-msw-path`。 |
| `test_public_error_set_is_exact_and_tos_data_is_absent` | PASS | 机器基线公开错误码已经是批准的 9 个，未包含 `TOS-DATA-001`。 |
| `test_reason_is_optional_without_literal_question_mark` | FAIL | API-004 仍含字面量 `reason?`。 |
| `test_response_examples_validate_against_response_schemas` | FAIL | 200/400/403/409 只有 example，没有 Schema。 |
| `test_rule_version_is_string_in_schema_and_example` | FAIL | 标准 `{id}` 路径不存在；旧示例中的 `ruleVersion` 为数字。 |
| `test_scenario_seed_refs_faults_delays_and_mutations_are_exact` | FAIL | 场景缺 `seedRefs`、结构化 `fault`、确定延迟和 mutation。 |
| `test_success_and_error_envelopes_have_trace_and_audit_ids` | FAIL | 缺少 `ApiSuccessEnvelope` 与 `ApiErrorEnvelope`。 |

## 红灯有效性结论

所有失败均由已批准的 C02A 功能尚未实现造成，没有导入错误、语法错误或测试自身异常。该结果证明语义测试能够捕获最初阻断 C02 的契约不一致。
