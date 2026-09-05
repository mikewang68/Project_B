# C03 Preflight Evidence

## Repository boundary

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`
- Start branch: `demo/c02-contracts-mock`
- Start HEAD: `55906af251cc4e0163104e8e6fa24572f13a77cd`
- Working branch: `demo/c03-state-permissions`
- Initial worktree status: clean
- `94ce3961a8bad0e7b9aceca3980fbb30bfa66877` ancestor check: exit 0
- `2ebaf9d3670839ae210ba3ab8fdb7dff80a73f10` ancestor check: exit 0
- `833a0c466afb36a88daaffcb6a30d395ba4769ea` ancestor check: exit 0

## Frozen SHA-256 gate

Command: `Get-FileHash -Algorithm SHA256` over the six files in `docs/baseline/SHA256SUMS.txt` order.

```text
MATCH  bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
MATCH  ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
MATCH  1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
MATCH  b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
MATCH  c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
MATCH  a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

Mismatches: 0. `docs/baseline/**` is frozen for C03.

## TypeScript baseline

- Requested command: `pnpm exec tsc --noEmit`
- Requested command exit: 1 because the bundled pnpm executable did not resolve the repository-local `tsc` shim from the non-ASCII workspace path.
- Equivalent locked local entry used: `.\node_modules\.bin\tsc.CMD --noEmit`
- Locked TypeScript version: 7.0.2
- Exit: 1
- Primary diagnostics: 38
- C02 contract/mock diagnostics: 0
- C03 target-scope diagnostics: 0
- Exact output: `docs/evidence/C03/tsc-before.txt`

No dependency or lock-file change was made to work around the command-resolution issue.

## C02 regression baseline

Environment note: the bundled Node directory was prepended for child-process resolution. Node was `v24.14.0` and pnpm `11.9.0`; the repository declares Node `24.18.0` and pnpm `11.10.0`, so commands emitted a non-blocking engine warning.

| Command | Exit | Result |
| --- | ---: | --- |
| `pnpm test -- --run` | 0 | 10/10 files, 33/33 tests |
| `pnpm build` | 0 | Vite production build passed; existing >500 kB chunk warning retained |
| `pnpm test:e2e` | 0 | Playwright 14/14 passed |

Regression against the C02 handoff: none.
