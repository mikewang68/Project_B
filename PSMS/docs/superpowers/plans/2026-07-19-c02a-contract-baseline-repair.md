# C02A Contract Baseline Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the document-pack generator so the 14 domain schemas, deterministic fixtures, 25 Mock API operations, nine public errors, and seven scenarios form one executable contract, then freeze the repaired baseline and hand C02 an exact continuation prompt.

**Architecture:** The document-engineering workspace owns a single Python contract catalog. Both DOCX deliverables and JSON machine baselines are generated from that catalog, while semantic tests validate relationships that count-only tests missed. The Demo repository receives only versioned documents, generated baselines, evidence, and the C02 continuation prompt; no C02 application source is written during C02A.

**Tech Stack:** Python 3 with `python-docx` and standard library, OpenAPI 3.1 JSON syntax, PowerShell, Git, existing React/Vitest/Vite/Playwright project.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-07-19-c02a-contract-baseline-repair-design.md` at commit `c95203b4a78958afde2932cc47e2c09e7655ed5f`.
- Document-engineering root: `C:\Users\developer\Documents\Codex\2026-07-16\e-b`.
- Demo repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- B项目 document destination: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\相关文档\技术设计`.
- Use bundled Python: `C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`.
- Do not install Python or npm packages and do not change `package.json`, `pnpm-lock.yaml`, or exact dependency versions.
- C02A may change generator source, generated documents, `docs/baseline/openapi.yaml`, `docs/baseline/demo-fixtures.json`, and evidence; it must not create or modify `src/contracts/**`, `src/mocks/**`, Store, state machine, RBAC, audit persistence, or page source.
- The canonical `/mock/**` runtime shape is strict camelCase. Requirement snake_case names survive only as `x-source-field` or `x-source-fields` trace metadata.
- Retain exactly 14 domain objects, 25 operations, seven scenarios, and nine public error codes. Shared/request/response component schemas do not count toward the 14-domain-object invariant.
- Keep the C01 commit `eb8487fa76d6f506fe24650d40fd03137efd514c` untouched.
- Use TDD: preserve the initial semantic red result in `docs/evidence/C02A/tdd-red.md` before changing the generator.
- Baseline repair commit must be exactly `fix(c02): reconcile generated demo contracts`; the C02 continuation prompt is a later documentation commit.

---

## File Structure

### Document-engineering workspace

- Create `work/demo_doc_pack/contract_rules.py`: canonical fields, enum sets, fixture counts/builders, scenario faults, error routing, and source-field mappings.
- Create `work/demo_doc_pack/tests/test_contract_semantics.py`: cross-file semantic tests for objects, OpenAPI, envelopes, examples, errors, and scenarios.
- Modify `work/demo_doc_pack/source_extract.py`: consume `contract_rules` instead of independent fixture and request inference constants.
- Modify `work/demo_doc_pack/machine_outputs.py`: emit standard OpenAPI paths, parameters, request schemas, response schemas, envelopes, and trace metadata.
- Modify `work/demo_doc_pack/validate.py`: reject catalog/schema/fixture/scenario inconsistencies before generation.
- Modify `work/demo_doc_pack/paths.py`: emit the 2026-07-19 v0.4/v0.2 deliverable set under `outputs/demo-doc-pack-v04`.
- Modify `work/demo_doc_pack/build_main_doc.py`: correct failure envelope, validation error routing, field conventions, and revision record.
- Modify `work/demo_doc_pack/build_api_contract_doc.py`: render the canonical object fields, response references, exact errors, and scenario fault table.
- Modify `work/demo_doc_pack/build_page_cards_doc.py`: update input-document versions if referenced.
- Modify `work/demo_doc_pack/build_task_matrix_doc.py`: update baseline version references and C02A prerequisite.
- Modify `work/demo_doc_pack/generate_all.py`: build deterministic v0.4/v0.2 outputs and attachment ZIP.
- Modify existing tests under `work/demo_doc_pack/tests/`: update versioned paths and preserve prior count/layout assertions.

### Demo repository

- Create `docs/evidence/C02A/tdd-red.md`: exact pre-fix failures.
- Create `docs/evidence/C02A/baseline-diff.md`: old/new hashes, object/API/error/scenario deltas, and authority decision.
- Create `docs/evidence/C02A/verification.md`: generator, document, baseline, C01 regression, and Git results.
- Create `docs/evidence/C02A/SHA256SUMS-before.txt` and `SHA256SUMS-after.txt`.
- Modify `docs/baseline/openapi.yaml` and `docs/baseline/demo-fixtures.json` from generated outputs.
- Modify `docs/baseline/README.md`: identify contract baseline `0.2.0`, the C02A freeze commit, and the 14/25/7/9 invariants.
- Create `docs/baseline/SHA256SUMS.txt`: hashes of the six official baseline files after repair.
- Create `docs/handoffs/C02A-contract-baseline-repair.md`: stable exports and C02 restart point.
- Create `docs/conversation-prompts/C02-contracts-and-mock-resume.md`: final prompt for the paused C02 conversation.

---

### Task 1: Add semantic contract regression tests and capture the red gate

**Files:**
- Create: `work/demo_doc_pack/tests/test_contract_semantics.py`
- Modify: `work/demo_doc_pack/tests/test_machine_outputs.py`
- Create in Demo repo: `docs/evidence/C02A/tdd-red.md`

**Interfaces:**
- Consumes: `write_machine_outputs(load_catalog())`, generated `openapi.yaml`, generated `demo-fixtures.json`.
- Produces: failing assertions that name each semantic defect and remain as permanent regression tests.

- [ ] **Step 1: Add a recursive local JSON-Schema assertion helper to the test file**

Implement `resolve_ref(document, ref)`, `assert_schema_value(testcase, document, schema, value, path)`, and `operation_entries(openapi)`. The helper must support the subset generated by this project: `$ref`, `allOf`, `oneOf`, `type`, `required`, `properties`, `additionalProperties`, `items`, `enum`, and `const`.

```python
def resolve_ref(document: dict, ref: str) -> dict:
    assert ref.startswith("#/")
    value = document
    for token in ref[2:].split("/"):
        value = value[token.replace("~1", "/").replace("~0", "~")]
    return value


def operation_entries(openapi: dict):
    verbs = {"get", "post", "put", "patch", "delete"}
    for path, path_item in openapi["paths"].items():
        for method, operation in path_item.items():
            if method in verbs:
                yield path, method, operation
```

- [ ] **Step 2: Add explicit semantic tests**

Add tests named:

```python
test_all_base_fixtures_validate_against_domain_schemas
test_domain_object_ids_and_fixture_counts_are_exact
test_every_enum_is_declared_and_fixture_values_are_members
test_get_operations_use_parameters_not_request_body
test_path_parameters_and_x_msw_paths_are_complete
test_all_operations_have_request_and_200_400_403_409_schemas
test_response_examples_validate_against_response_schemas
test_reason_is_optional_without_literal_question_mark
test_rule_version_is_string_in_schema_and_example
test_success_and_error_envelopes_have_trace_and_audit_ids
test_public_error_set_is_exact_and_tos_data_is_absent
test_scenario_seed_refs_faults_delays_and_mutations_are_exact
```

Use the exact fixture counts from the design:

```python
EXPECTED_COUNTS = {
    "DO-001": 3, "DO-002": 8, "DO-003": 4, "DO-004": 8,
    "DO-005": 12, "DO-006": 12, "DO-007": 10,
    "DO-008": 6, "DO-009": 5, "DO-010": 4, "DO-011": 4,
    "DO-012": 3, "DO-013": 9, "DO-014": 13,
}
```

- [ ] **Step 3: Run only the new semantic tests and verify the current generator fails**

Run from the document-engineering root:

```powershell
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m unittest work.demo_doc_pack.tests.test_contract_semantics -v
```

Expected: FAIL, including all-fixture/schema mismatch, GET requestBody, missing response schemas, `reason?`, numeric `ruleVersion`, absent scenario seeds, and missing fault configuration.

- [ ] **Step 4: Record the full red result**

Write `docs/evidence/C02A/tdd-red.md` with the command, timestamp, exit code, failing test names, and the statement that no C02 application source existed when the red gate was captured.

- [ ] **Step 5: Do not commit yet**

Keep the red evidence for the single C02A repair commit. Confirm the Demo repository contains only the new evidence file in addition to the already committed design and plan.

---

### Task 2: Build the canonical object, enum, fixture, error, and scenario catalog

**Files:**
- Create: `work/demo_doc_pack/contract_rules.py`
- Modify: `work/demo_doc_pack/source_extract.py`
- Modify: `work/demo_doc_pack/validate.py`
- Test: `work/demo_doc_pack/tests/test_catalog.py`
- Test: `work/demo_doc_pack/tests/test_contract_semantics.py`

**Interfaces:**
- Produces: `DOMAIN_OBJECT_SPECS`, `ENUMS`, `FIXTURE_COUNTS`, `PUBLIC_ERROR_CODES`, `SCENARIO_RULES`, `build_fixture_objects()`, and `normalize_object_rows()`.
- Consumed by: `source_extract.build_objects()`, `source_extract.build_scenarios()`, `machine_outputs.write_openapi()`, semantic tests, and DOCX builders.

- [ ] **Step 1: Write catalog tests before the module exists**

Test these exact invariants:

```python
assert list(DOMAIN_OBJECT_SPECS) == [f"DO-{index:03d}" for index in range(1, 15)]
assert FIXTURE_COUNTS == EXPECTED_COUNTS
assert len(PUBLIC_ERROR_CODES) == 9
assert "TOS-DATA-001" not in PUBLIC_ERROR_CODES
assert set(SCENARIO_RULES) == {f"SCN-{index:02d}" for index in range(1, 8)}
assert SCENARIO_RULES["SCN-03"]["fault"]["delayMs"] == 1500
assert SCENARIO_RULES["SCN-07"]["seedRefs"] == ["APT-006"]
```

Run the targeted test and expect an import failure for `contract_rules`.

- [ ] **Step 2: Create the canonical constants**

Define exactly the approved enums, the 13 role codes, the 14 object field lists, source-field metadata, required fields, and scenario table. The scenario constant must be:

```python
SCENARIO_RULES = {
    "SCN-01": {"seedRefs": ["PLAN-001"], "fault": {"type": "NONE", "errorCode": None, "delayMs": 300, "mutations": []}},
    "SCN-02": {"seedRefs": ["PLAN-002"], "fault": {"type": "VALIDATION_MISSING_FIELD", "errorCode": "TOS-EXT-002", "delayMs": 300, "mutations": [{"objectId": "PLAN-002", "omitFields": ["trackNo"]}]}},
    "SCN-03": {"seedRefs": ["PLAN-003"], "fault": {"type": "INTERFACE_TIMEOUT", "errorCode": "TOS-EXT-001", "delayMs": 1500, "mutations": []}},
    "SCN-04": {"seedRefs": ["WO-005"], "fault": {"type": "DEVICE_OFFLINE", "errorCode": "TOS-WO-001", "delayMs": 800, "mutations": []}},
    "SCN-05": {"seedRefs": ["WO-006", "IL-001"], "fault": {"type": "INTERLOCK_FORCE_STOP", "errorCode": "TOS-IL-001", "delayMs": 500, "mutations": []}},
    "SCN-06": {"seedRefs": ["OFF-001"], "fault": {"type": "OFFLINE_VERSION_CONFLICT", "errorCode": "TOS-OFF-001", "delayMs": 700, "mutations": []}},
    "SCN-07": {"seedRefs": ["APT-006"], "fault": {"type": "SOURCE_DATA_CONFLICT", "errorCode": "TOS-EXT-003", "delayMs": 600, "mutations": []}},
}
```

- [ ] **Step 3: Implement deterministic fixture builders**

Create one focused builder per object type. Builders must return valid base objects only; SCN-02 omission lives solely in `fault.mutations`. Use stable IDs and `2026-07-16T...+08:00` times. Required referenced IDs must be created by their owning builder.

```python
FIXTURE_BUILDERS = {
    "DO-001": build_plans,
    "DO-002": build_waybills,
    "DO-003": build_tracks,
    "DO-004": build_materials,
    "DO-005": build_work_orders,
    "DO-006": build_work_nodes,
    "DO-007": build_resources,
    "DO-008": build_appointments,
    "DO-009": build_exceptions,
    "DO-010": build_interlocks,
    "DO-011": build_offline_packets,
    "DO-012": build_reports,
    "DO-013": build_audit_logs,
    "DO-014": build_user_roles,
}
```

Each builder must assert its own count before returning.

- [ ] **Step 4: Replace independent object and scenario construction**

Modify `build_objects()` to merge requirement labels/source metadata with `DOMAIN_OBJECT_SPECS`; do not derive exclusive properties from the short requirement field table. Modify `build_scenarios()` to merge text labels/events with `SCENARIO_RULES`. Modify the command-pipeline text source so validation routes to `TOS-EXT-002`, `TOS-EXT-003`, or `DEMO-SCENARIO-001` and never emits `TOS-DATA-001`.

- [ ] **Step 5: Strengthen pre-generation validation**

In `validate_catalog()`, add exact object/fixture counts, unique stable IDs, enum membership, required source-field metadata, exact public errors, scenario seed existence, and fault delay checks. Return human-readable errors prefixed with the object or scenario ID.

- [ ] **Step 6: Run catalog and semantic tests**

Run:

```powershell
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m unittest work.demo_doc_pack.tests.test_catalog work.demo_doc_pack.tests.test_contract_semantics -v
```

Expected: catalog-specific tests PASS; OpenAPI response/query tests may remain FAIL until Task 3. Fixture/schema, count, error, and scenario tests must now PASS.

---

### Task 3: Generate standards-compliant OpenAPI request and response contracts

**Files:**
- Modify: `work/demo_doc_pack/source_extract.py`
- Modify: `work/demo_doc_pack/machine_outputs.py`
- Modify: `work/demo_doc_pack/tests/test_machine_outputs.py`
- Test: `work/demo_doc_pack/tests/test_contract_semantics.py`

**Interfaces:**
- Consumes: canonical catalog from Task 2.
- Produces: OpenAPI 3.1 JSON with `x-api-id`, `x-msw-path`, 14 tagged domain schemas, shared enums, request schemas, response schemas, and reusable envelopes.

- [ ] **Step 1: Add failing tests for standard path/query/response behavior**

Ensure tests assert `/mock/plans/{id}/confirm` exists, `/mock/plans/:id/confirm` does not, `x-msw-path` equals `/mock/plans/:id/confirm`, GET operations have no requestBody, and all four response status entries have a Schema.

- [ ] **Step 2: Replace request-text inference with explicit request contracts**

Build each operation from a 25-entry contract table keyed by API ID. Each entry includes method, OpenAPI path, MSW path, request location, request fields, response payload name, and request example. Normalize:

```python
"API-004": {
    "method": "POST",
    "path": "/mock/plans/{id}/confirm",
    "mswPath": "/mock/plans/:id/confirm",
    "pathParameters": [{"name": "id", "schema": {"type": "string"}}],
    "request": {"reason": {"type": "string", "required": False}, "supplements": {"type": "array", "items": {"type": "string"}, "required": False}},
    "response": "Plan",
}
```

For API-007, declare `ruleVersion` as required string with example `RULE-1.0`.

- [ ] **Step 3: Generate shared envelopes and error responses**

Create `ApiSuccessEnvelope` and `ApiErrorEnvelope`. `ApiErrorEnvelope.required` must be:

```python
["ok", "errorCode", "message", "auditLogId", "traceId"]
```

Create reusable `BadRequestResponse`, `ForbiddenResponse`, and `ConflictResponse` in `components.responses`, each referencing `ApiErrorEnvelope` and carrying a conforming example.

- [ ] **Step 4: Generate operation-specific success payload references**

For 14 direct domain responses, reference the canonical domain schema. For page/snapshot/task results, generate focused additional payload schemas such as `OverviewSnapshot`, `PlanPage`, `TaskTree`, `OperationSnapshot`, `ReportSnapshot`, and `DemoState`. Mark domain schemas with `x-domain-object-id` so the invariant counts only those 14.

- [ ] **Step 5: Emit standard parameters and paths**

GET query fields become `parameters` with `in: query`. Path variables become required `in: path` parameters. POST bodies use `application/json` and named component request schemas. Add `x-api-id` and `x-msw-path` to every operation.

- [ ] **Step 6: Run all generator tests**

Run:

```powershell
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m unittest discover -s work\demo_doc_pack\tests -v
```

Expected: all tests PASS; no `reason?`, no GET requestBody, no numeric `ruleVersion`, every response example validates, and all 25 operations remain present.

---

### Task 4: Version and synchronize the four DOCX deliverables and attachment package

**Files:**
- Modify: `work/demo_doc_pack/paths.py`
- Modify: `work/demo_doc_pack/build_main_doc.py`
- Modify: `work/demo_doc_pack/build_api_contract_doc.py`
- Modify: `work/demo_doc_pack/build_page_cards_doc.py`
- Modify: `work/demo_doc_pack/build_task_matrix_doc.py`
- Modify: `work/demo_doc_pack/generate_all.py`
- Modify: `work/demo_doc_pack/tests/test_docx_outputs.py`
- Modify: `work/demo_doc_pack/tests/test_machine_outputs.py`

**Interfaces:**
- Produces: `outputs/demo-doc-pack-v04` containing the v0.4 main design, three v0.2 support documents, v0.2 attachment ZIP, machine files, and SHA manifest.

- [ ] **Step 1: Write failing version and content tests**

Assert these exact filenames:

```text
B项目-生产调度管理模块网页Demo技术方案设计文档-v0.4-20260719.docx
B项目-生产调度管理模块网页Demo页面与功能任务卡-v0.2-20260719.docx
B项目-生产调度管理模块网页Demo Mock接口与数据契约-v0.2-20260719.docx
B项目-生产调度管理模块网页Demo开发任务分解与验收清单-v0.2-20260719.docx
B项目-生产调度管理模块网页Demo开发附件包-v0.2-20260719.zip
```

Also assert extracted DOCX text contains `C02A`, `camelCase`, `x-source-field`, `API-001`, `API-025`, `SCN-01`, `SCN-07`, all nine public errors, and no `TOS-DATA-001` or `reason?`.

- [ ] **Step 2: Change output paths and deterministic ZIP timestamp**

Set output root to `outputs/demo-doc-pack-v04`, document versions/dates to the list above, and `ZIP_TIMESTAMP = (2026, 7, 19, 0, 0, 0)`.

- [ ] **Step 3: Update main design and API contract prose**

Update revision records, canonical object naming, success/error envelopes, validation error routing, GET/query rule, `{id}` versus `x-msw-path`, fixture counts, and the seven scenario fault/delay table. Preserve B项目 document formatting and existing chapter hierarchy.

- [ ] **Step 4: Update page cards and task matrix references**

Replace old v0.1/v0.3 input names with v0.2/v0.4 names. Add C02A as an explicit prerequisite to `TASK-P1-001` and `TASK-P1-002`, while keeping page task IDs and acceptance IDs unchanged.

- [ ] **Step 5: Generate the complete document package**

Run:

```powershell
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' -m work.demo_doc_pack.generate_all
```

Expected: five deliverables listed above plus seven machine artifacts under `outputs/demo-doc-pack-v04/machine` (`README.md`, five other official baseline files, and `SHA256SUMS.txt`).

- [ ] **Step 6: Re-run the full Python suite**

Run the unittest discovery command from Task 3. Expected: all tests PASS and deterministic ZIP hash unchanged across two consecutive generations.

---

### Task 5: Render and visually verify the revised DOCX package

**Files:**
- Read: all four generated DOCX files under `outputs/demo-doc-pack-v04`
- Create: `work/demo_doc_pack/audits/c02a-render-verification.json`
- Create: rendered PNG directories under `work/demo_doc_pack/rendered/c02a/`

**Interfaces:**
- Consumes: generated DOCX files from Task 4.
- Produces: page images and an audit record proving no clipping, broken tables, blank pages, or unreadable code blocks.

- [ ] **Step 1: Load and follow the B项目 format and documents skills**

Read completely:

```text
C:\Users\developer\.codex\skills\b-project-process-doc-format\SKILL.md
C:\Users\developer\.codex\plugins\cache\openai-primary-runtime\documents\26.715.12143\skills\documents\SKILL.md
```

- [ ] **Step 2: Render every DOCX to page PNGs**

Use:

```powershell
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'C:\Users\developer\.codex\plugins\cache\openai-primary-runtime\documents\26.715.12143\skills\documents\render_docx.py' 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\outputs\demo-doc-pack-v04\B项目-生产调度管理模块网页Demo技术方案设计文档-v0.4-20260719.docx' --output_dir 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\work\demo_doc_pack\rendered\c02a\main'
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'C:\Users\developer\.codex\plugins\cache\openai-primary-runtime\documents\26.715.12143\skills\documents\render_docx.py' 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\outputs\demo-doc-pack-v04\B项目-生产调度管理模块网页Demo页面与功能任务卡-v0.2-20260719.docx' --output_dir 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\work\demo_doc_pack\rendered\c02a\page-cards'
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'C:\Users\developer\.codex\plugins\cache\openai-primary-runtime\documents\26.715.12143\skills\documents\render_docx.py' 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\outputs\demo-doc-pack-v04\B项目-生产调度管理模块网页Demo Mock接口与数据契约-v0.2-20260719.docx' --output_dir 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\work\demo_doc_pack\rendered\c02a\api-contract'
& 'C:\Users\developer\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe' 'C:\Users\developer\.codex\plugins\cache\openai-primary-runtime\documents\26.715.12143\skills\documents\render_docx.py' 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\outputs\demo-doc-pack-v04\B项目-生产调度管理模块网页Demo开发任务分解与验收清单-v0.2-20260719.docx' --output_dir 'C:\Users\developer\Documents\Codex\2026-07-16\e-b\work\demo_doc_pack\rendered\c02a\task-matrix'
```

Expected: all four commands exit 0 and each output directory contains at least one PNG.

- [ ] **Step 3: Inspect all rendered pages**

Create contact sheets if needed, then inspect headings, revision tables, wide field tables, JSON/OpenAPI code blocks, headers/footers, and final pages. Record page counts and every issue in the audit JSON.

- [ ] **Step 4: Correct and re-render any layout issue**

Change only the relevant builder/layout helper, regenerate the affected document, and re-render it. Repeat until the audit reports zero clipping, overlap, unexpected blank pages, and truncated table rows.

- [ ] **Step 5: Run structural DOCX audits**

Run the document skill's heading, section, table geometry, and accessibility audit scripts against all four files. Expected: no blocking errors. Record non-blocking warnings with rationale.

---

### Task 6: Synchronize repaired baselines and documents, run regressions, and freeze C02A

**Files:**
- Modify in Demo repo: `docs/baseline/README.md`
- Replace in Demo repo: `docs/baseline/openapi.yaml`, `docs/baseline/demo-fixtures.json`
- Create in Demo repo: `docs/baseline/SHA256SUMS.txt`
- Create in Demo repo: `docs/evidence/C02A/SHA256SUMS-before.txt`
- Create in Demo repo: `docs/evidence/C02A/SHA256SUMS-after.txt`
- Create in Demo repo: `docs/evidence/C02A/baseline-diff.md`
- Create in Demo repo: `docs/evidence/C02A/verification.md`
- Create in Demo repo: `docs/handoffs/C02A-contract-baseline-repair.md`
- Copy to B项目 technical-design directory: four revised DOCX files and revised attachment ZIP.

**Interfaces:**
- Produces: the frozen baseline and handoff that C02 may trust without changing.

- [ ] **Step 1: Capture old hashes before replacement**

Hash the six official Demo baseline files in filename order and write `SHA256SUMS-before.txt`. Include the current C01 handoff hashes and verify they match before replacement.

- [ ] **Step 2: Copy generated files to their destinations**

Copy `openapi.yaml`, `demo-fixtures.json`, and the revised README text into the Demo repository. Copy all five document-package deliverables to `相关文档\技术设计` without deleting prior versions.

- [ ] **Step 3: Write after hashes and a human-readable diff**

`baseline-diff.md` must list old/new SHA-256 for all six files, the 14 canonical object field changes, component/schema count interpretation, 25 operation path/query/response changes, nine-error decision, and seven scenario seed/fault/delay changes.

- [ ] **Step 4: Run the Demo baseline semantic check**

Parse the files directly from the Demo repository with the same semantic test helper. Expected: 14 object groups, exact fixture counts, 25 operations, seven scenarios, nine errors, zero semantic issues.

- [ ] **Step 5: Run C01 regressions**

From the Demo repository run:

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
```

Expected: 5/5 test files, 9/9 assertions, build exit 0, and 14/14 Playwright tests. Do not claim `tsc --noEmit` passes; the known React declaration limitation remains.

- [ ] **Step 6: Write verification and handoff documents**

Record exact commands, exit codes, test counts, document render results, hashes, modified files, preserved limitations, C01 commit, design commit, and the C02 restart instruction. State explicitly that no `src/contracts/**` or `src/mocks/**` file exists yet.

- [ ] **Step 7: Commit only C02A baseline repair artifacts**

Verify `git diff --check`, inspect `git status --short`, then commit:

```powershell
git add docs/baseline docs/evidence/C02A docs/handoffs/C02A-contract-baseline-repair.md
git commit -m "fix(c02): reconcile generated demo contracts"
```

Expected: one baseline-repair commit after `c95203b`; no C02 source files in the commit; clean worktree.

---

### Task 7: Generate and verify the paused-C02 continuation prompt

**Files:**
- Create in document workspace: `docs/conversation-prompts/C02-contracts-and-mock-resume.md`
- Create in Demo repo: `docs/conversation-prompts/C02-contracts-and-mock-resume.md`
- Modify if retained: `docs/conversation-prompts/C02-contracts-and-mock.md` only to mark it superseded.

**Interfaces:**
- Consumes: actual C02A commit hash, actual six-file hashes, C02A handoff, C01 commit, and repaired baseline counts.
- Produces: one copy/paste prompt that resumes the already paused C02 task from `tsc-before` and contract TDD without reopening settled decisions.

- [ ] **Step 1: Write the prompt with actual hashes, not placeholders**

The prompt must state:

```text
- C01 baseline commit: eb8487fa76d6f506fe24650d40fd03137efd514c
- C02A design commit: c95203b4a78958afde2932cc47e2c09e7655ed5f
- C02A repair commit: write the full 40-character value returned by `git rev-parse HEAD` after Task 6
- Branch: demo/c02-contracts-mock
- Work from the repaired 14/25/7/9 baseline.
- Do not modify docs/baseline again.
- Continue from tsc-before, failing contract tests, Zod contracts, then MSW.
- Preserve the known @types/react/@types/react-dom limitation without adding packages.
```

Before saving, replace the explanatory repair-commit line with the real 40-character hash and insert the exact six SHA-256 values. The finished file must contain no unresolved placeholder token.

- [ ] **Step 2: Include the authoritative implementation rules**

Restate canonical camelCase, `x-source-field`, 14 domain objects versus additional shared schemas, standard `{id}` plus `x-msw-path`, GET parameters, optional `reason`, string `ruleVersion`, response envelopes, exact nine errors, fixture-only data source, and deterministic scenario faults.

- [ ] **Step 3: Preserve C02 scope and gates**

Require two internal gates: contract layer first, Mock layer second. Prohibit Store, state machines, command pipeline, RBAC, audit persistence, and pages. Require TDD evidence, contract/mock coverage JSON, tests, build, existing 14 E2E, handoff, and a separate C02 source commit.

- [ ] **Step 4: Validate the prompt against live repository state**

Run a validator that checks every named file exists, all hashes match, branch is correct, worktree is clean, no unresolved placeholder token exists, and required markers `DO-001`, `DO-014`, `API-001`, `API-025`, `SCN-01`, `SCN-07`, all nine errors, `tsc-before`, `contract-coverage.json`, and `mock-coverage.json` are present.

- [ ] **Step 5: Commit and hand the prompt to the user**

Copy the validated prompt into the Demo repository, then commit:

```powershell
git add docs/conversation-prompts/C02-contracts-and-mock-resume.md
git commit -m "docs(c02): add repaired-contract continuation prompt"
```

Expected: clean worktree. Return a clickable link to the prompt and summarize the C02A and prompt commit hashes.

---

## Final Verification Checklist

- [ ] `python -m unittest discover -s work\demo_doc_pack\tests -v` exits 0.
- [ ] Two consecutive `generate_all` runs produce identical attachment ZIP SHA-256.
- [ ] All four revised DOCX files render and pass structural/visual QA.
- [ ] Demo `openapi.yaml` and `demo-fixtures.json` pass semantic validation with 14/25/7/9 invariants.
- [ ] C01 regression suite reports 9/9 assertions, build exit 0, and 14/14 E2E.
- [ ] C02A repair commit contains no `src/**` changes.
- [ ] Continuation prompt has actual commit/hash values and no placeholders.
- [ ] Demo branch is `demo/c02-contracts-mock` and worktree is clean.
