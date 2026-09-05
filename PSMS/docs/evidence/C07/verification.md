# C07 Final Verification

## Outcome

Status: **PASS WITH DOCUMENTED BASELINE/ENVIRONMENT NOTES**.

C07 functional verification is green: the focused dispatch-board set passed `7/7` files and `49/49` tests, the complete Vitest collection passed `60/60` files and `499/499` tests with one worker, production build passed with `1,907` modules, C07 Playwright passed `4/4`, and the final full Playwright rerun passed `31/31`. Six frozen hashes remain `6/6`, all eight required screenshots passed original-resolution inspection, `git diff --check` passed, and the changed-file scan found no prohibited scope.

Two repository/environment facts are recorded rather than hidden:

1. Global `tsc --noEmit` exits `1` because the frozen dependency baseline omits React/JSX declarations. The final output contains only `TS2604`, `TS7016`, and `TS7026`; C07 non-React primary diagnostics are `0`.
2. The first full Playwright attempt passed `30/31` after the historical UI-002 SCN-02 test did not observe its short-lived success notice within five seconds. The exact isolated historical test then passed `1/1` in 14.0 seconds, and an unchanged full rerun passed `31/31` in 6.2 minutes. No historical implementation, test, timeout, or configuration was changed.

## Identity and environment

| Item | Measured value |
| --- | --- |
| Branch | `demo/c07-dispatch-board` |
| Verification source HEAD | `a4e0a62145c89b6b5f4e28b6a8e854c5febaf5b8` |
| C06 source/handoff ancestor | `d07512e66194ef7ded1f7238bfd34dc0bf024919` |
| C07 execution-prompt HEAD | `8971de5` |
| Node | `v24.14.0` |
| pnpm | `11.9.0` |
| Package engine request | Node `24.18.0` (warning only; build/tests completed) |
| OS/shell | Windows / PowerShell |
| Time zone | Asia/Shanghai (`+08:00`) |
| Final verification window | `2026-07-21 19:17–19:40 +08:00` |

`Verification source HEAD` is the complete Task 1–7 source/test commit immediately before the final evidence commit containing this document. A commit cannot embed its own hash; the post-commit HEAD is reported by the final status command and handoff response.

## Commands and measured results

| Check | Timestamp (+08:00) | Exit | Measured result |
| --- | --- | ---: | --- |
| `vitest run src/features/dispatch-board src/pages/__tests__/DispatchBoardPage.render.test.tsx src/pages/__tests__/DispatchBoardPage.action.test.tsx src/pages/__tests__/DispatchBoardPage.permission.test.tsx` | 19:17 | 0 | `7/7` files, `49/49` tests; 19.43 s |
| complete `vitest run --maxWorkers=1` | 19:18–19:23 | 0 | `60/60` files, `499/499` tests; 297.84 s |
| `pnpm build` | 19:23 | 0 | Vite production build, `1,907` modules; 9.83 s |
| `playwright test e2e/ui-005-dispatch-board.spec.ts` | 19:23–19:24 | 0 | `4/4` passed; four required UI-005 flows |
| first complete `playwright test` | 19:24–19:31 | 1 | `30/31` passed; one transient historical UI-002 SCN-02 success-notice wait |
| isolated historical UI-002 SCN-02 rerun | 19:31 | 0 | `1/1` passed; 14.0 s |
| unchanged complete `playwright test` rerun | 19:31–19:38 | 0 | `31/31` passed; 375.7 s / 6.2 min |
| `tsc --noEmit` with output saved to `tsc-after.txt` | 19:39 | 1 | `467` known React/JSX diagnostics; C07 non-React primary `0` |
| `git diff --check` | 19:40 | 0 | no whitespace errors |
| six SHA-256 checks | 19:40 | 0 | `6/6 MATCH` |

The final full Playwright output retained only the existing Ant Design development warnings for calling `Message` during render and deprecated `List`; these warnings did not fail any test.

## TypeScript classification

Full output: [`tsc-after.txt`](./tsc-after.txt).

| Diagnostic | Count | Classification |
| --- | ---: | --- |
| `TS2604` | 29 | known React/JSX declaration baseline |
| `TS7016` | 81 | known missing React declaration baseline |
| `TS7026` | 357 | known missing JSX intrinsic elements baseline |
| Other TypeScript codes | 0 | none |
| C07 non-React primary diagnostics | 0 | pass criterion satisfied |
| Total | 467 | global exit `1`, honestly retained |

The increase from the preflight total reflects new C07 `.tsx` surface area under the same three known categories. No dependency baseline was changed to mask it.

## Frozen hashes

| File | SHA-256 | Result |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

## Screenshot verification

The screenshot index records exact original sizes and observations: [`screenshot-index.md`](./screenshot-index.md).

- Exactly eight required PNGs exist: `READY_QUEUE`, `ASSIGNED`, `DISPATCHED`, and `EXECUTING`, each at named viewports `1440x900` and `1280x720`.
- They are full-page captures; recorded heights exceed the viewport where content stacks responsively.
- Playwright checked document/body horizontal overflow at no more than 1 px before capture.
- Original-resolution visual inspection result: `8/8 PASS`; no clipping, overlap, unreadable status, or horizontal-only content.

## Changed-file and prohibited-scope audit

The Task 1–7 diff from `8971de5` contains only:

- `src/features/dispatch-board/**` and its unit tests;
- `src/pages/dispatch/DispatchBoardPage.tsx` and UI-005 page tests;
- C07 runtime wiring in `src/runtime/DemoRuntimeContext.tsx` and its test;
- strict API-008/API-009 mock handlers and tests;
- `e2e/ui-005-dispatch-board.spec.ts`;
- `docs/evidence/C07/**`.

Confirmed untouched: `docs/baseline/**`, `package.json`, `pnpm-lock.yaml`, public schemas, public error codes, permission catalog, state-machine catalog, and UI-006 through UI-013 implementation modules. No merge or push was performed.

## Known limitations and boundaries

- API-008/API-009 Gateway code is transport-only; the C03 Store remains authoritative and command commit is atomic after strict response/version checks.
- DB-03 and DB-04 are explicitly local Demo execution feedback. They do not claim receipt or confirmation from a field system.
- UI-005 consumes only C06-owned `C06-WO-` / `C06-NODE-` records using `C06-DEMO-RULE-1.0`; historical fixture work orders are intentionally excluded.
- SCN-05 has no override: `INTERLOCK_FORCE_STOP` blocks DB-01 through DB-04 with `TOS-IL-001` before API/local boundaries.
- The Demo uses the deterministic C06 chain; it does not invent production volume or later-module behavior.
