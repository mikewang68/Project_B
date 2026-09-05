# C08 Exception Handling Preflight

- Recorded at: `2026-07-21 20:43:20 +08:00`
- Work branch: `demo/c08-exception-handling`
- Starting C07 branch: `demo/c07-dispatch-board`
- Starting HEAD: `331cdab5179dd840002dca6fa3db93b8dd961705`
- Required C07 handoff commit: `a232b5ea6f53c6b724cbc9db0840d729c48f1526`
- Ancestor check: `git merge-base --is-ancestor a232b5ea6f53c6b724cbc9db0840d729c48f1526 HEAD` exited `0`
- C08 specification/plan/prompt commits present after the C07 handoff: `70db81c`, `bacadb5`, `331cdab`
- Starting worktree: clean before the C08 evidence directory was created

## Frozen baseline hashes

| File | Expected SHA-256 | Actual SHA-256 | Result |
| --- | --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

Result: `6/6 MATCH`.

## Measured baseline commands

The Codex desktop bundled runtime was added to `PATH` without installing or changing dependencies. The measured runtime was Node `v24.14.0` and pnpm `11.9.0`; the repository engine warning requests Node `24.18.0`.

| Command | Exit | Measured result |
| --- | ---: | --- |
| `node_modules\\.bin\\tsc.CMD --noEmit` | 1 | 467 diagnostics written to `tsc-before.txt`: TS2604 29, TS7016 81, TS7026 357. All are the known React/JSX declaration categories; C08/C07 non-React primary diagnostics: 0. |
| `pnpm test -- --run --maxWorkers=1` | 1 | 60 files: 59 passed, 1 failed; 499 tests: 498 passed, 1 existing `PlanLedgerPage.render` test timed out at 30 seconds. The exact test passed when immediately rerun alone: 1/1 in 22.92 seconds total, 17.37 seconds test time. No timeout/configuration was changed. |
| `pnpm build` | 0 | Production build passed; 1,907 modules transformed. |
| `pnpm test:e2e` | 0 | 31/31 Playwright tests passed using one worker in 375.5 seconds. Earlier invocations were stopped only by 120-second and 300-second outer command limits; no Playwright timeout/configuration was changed. The transient UI-002 SCN-02 marker observed before the second outer stop passed when rerun alone: 1/1. |

The full Vitest difference from the C07 handoff (`499/499`) is recorded rather than hidden. Its only failing item is the already documented `PlanLedgerPage.render` timeout category, and the same item passed in isolation. The complete Playwright result matches the C07 handoff (`31/31`).

## Known C07/C08 boundaries

- C08 owns only strict frozen DO-009 exception fields: `id`, `exceptionNo`, `type`, `level`, `status`, `owner`, `dueAt`, `evidence`, `version`, `createdAt`, and `updatedAt`.
- C07 `workOrderId`, `planId`, `scenarioId`, and `from` query values are display-only source context. They are not DO-009 foreign keys and are never written into exception facts.
- C08 reuses the single C03 Store and single `DemoRuntimeProvider`; no second Store, Provider, or fixture is permitted.
- WorkOrder, WorkNode, Resource, Plan, RecommendationDraft, and Interlock facts remain read-only to C08.
- UI-009 is a linked safety boundary only. C08 does not recover, approve, clear, override, or reset interlocks.
- API-014/API-015 transport responses do not overwrite Store facts.
- No baseline, dependency, public schema, error-code, permission-catalog, or state-machine-catalog file may be changed.
