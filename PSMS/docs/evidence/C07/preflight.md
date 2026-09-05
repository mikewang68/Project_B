# C07 Preflight

## Repository gate

- Start time: `2026-07-21 18:07:00 +08:00`
- Branch created for implementation: `demo/c07-dispatch-board`
- Starting HEAD: `8971de58080627aeea74542d005ee3f349028c04`
- C06 source and handoff ancestor: `d07512e66194ef7ded1f7238bfd34dc0bf024919` (`git merge-base --is-ancestor` exit `0`)
- Starting worktree: clean before creating this evidence.
- The three commits after the C06 source handoff add only the approved C07 design, implementation plan, and execution prompt; `git diff --name-status d07512e..8971de5` contains no source changes.
- No extra Store, Provider, fixture, endpoint, public permission, public error code, state-machine transition, dependency, or later UI module is authorized for C07.

## Environment

- OS/shell: Windows / PowerShell
- Node: `v24.14.0`
- pnpm: `11.9.0`
- Frozen project engine declarations: Node `24.18.0`, pnpm `11.10.0`
- Environment note: the bundled runtime produced the existing unsupported-engine warning. No dependency, lockfile, engine, timeout, or test configuration was changed.

## Frozen baseline hashes

| Path | Expected SHA-256 | Actual SHA-256 | Result |
| --- | --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

Frozen baseline result: `6/6 MATCH`.

## Command evidence

| Command | Exit | Measured result |
| --- | ---: | --- |
| `node_modules\.bin\tsc.CMD --noEmit` | 1 | `413` known React/JSX declaration diagnostics: `TS2604=23`, `TS7016=73`, `TS7026=317`; raw output is preserved in `tsc-before.txt` |
| `pnpm test -- --run --maxWorkers=1` | 1 | `52/53` files and `446/447` tests passed; the existing `PlanLedgerPage.render` URL/remount case reached the unchanged 30-second timeout, with no assertion mismatch |
| isolated rerun of the timed-out `PlanLedgerPage.render` case | 0 | `1/1` selected test passed in `17.41s`; confirms the full-run result is an existing timing/load fluctuation rather than a deterministic source failure |
| `pnpm build` | 0 | PASS; `1,892 modules transformed`; existing large-chunk advisory only |
| first `pnpm test:e2e` invocation | external timeout | The command-level four-minute limit interrupted the run after `24/27` tests had passed; no Playwright assertion had failed |
| unchanged `pnpm test:e2e` rerun with sufficient outer command time | 0 | `27/27` Playwright tests PASS in `4.6m`; existing Ant Design console warnings only |

The existing Playwright suite rewrote 25 historical C01/C04/C05/C06 screenshot files while running. Because the worktree was clean immediately before the command, those runner-generated historical changes were restored before C07 source work; no historical evidence change is retained.

## C06 boundaries carried into C07

- C07 consumes only C06-owned `C06-WO-` / `C06-NODE-` records with `ruleVersion === 'C06-DEMO-RULE-1.0'`.
- C06 confirmed each owned WorkOrder to `READY` while its WorkNode remains `WAITING`; `resourceId` and `teamId` are empty.
- API-008 binds a valid resource instance without applying DO-005 `assign` again and without changing `READY`.
- API-009 applies the frozen `READY + dispatch -> DISPATCHED` transition and advances the paired WorkNode to `READY`.
- DB-03/DB-04 are deterministic local Demo feedback, not production field acknowledgements.
- API-025 reset must clear C07 workflow and idempotency state while restoring fixture domain state.
- `INTERLOCK_FORCE_STOP` blocks DB-01 through DB-04 before API or Store mutation and only exposes the UI-009 entry.

## Baseline conclusion

- Frozen baseline: `6/6 MATCH`.
- Build baseline: `PASS; 1,892 modules`.
- Playwright baseline: `27/27 PASS` on the completed run.
- TypeScript baseline: `exit 1; TS2604=23, TS7016=73, TS7026=317; total=413`, matching the C06 handoff classification.
- Vitest baseline: one known `PlanLedgerPage.render` 30-second timeout occurred even under the requested single-worker full run; the exact case passed unchanged in isolation. This measured difference is retained rather than hidden, and no timeout/config/source workaround was made.
- No C07 source code was modified before these checks completed.
