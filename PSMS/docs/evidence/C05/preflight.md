# C05 Preflight

## Repository

- Branch: `demo/c05-recommendation`
- Start HEAD: `b3a978faf1e16e51c0d44c74c86d2a424c69e9a6`
- C04 source ancestor: `55944a18e22eecef437ea6394c543e9177380a2b` (`git merge-base --is-ancestor` exit `0`)
- C05 design ancestor: `5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61` (`git merge-base --is-ancestor` exit `0`)
- C05 implementation-plan ancestor: `bb00885aab8ad7d4ea15c07d19cb9197eef338bb` (`git merge-base --is-ancestor` exit `0`)
- Worktree before C05 evidence: clean on `demo/c04-plan-entry`; `demo/c05-recommendation` was created from that exact HEAD.

## Frozen baseline hashes

| File | Actual SHA-256 | Result |
| --- | --- | --- |
| `docs/baseline/README.md` | `bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34` | MATCH |
| `docs/baseline/package-baseline.json` | `ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810` | MATCH |
| `docs/baseline/openapi.yaml` | `1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83` | MATCH |
| `docs/baseline/demo-fixtures.json` | `b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba` | MATCH |
| `docs/baseline/page-task-matrix.csv` | `c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b` | MATCH |
| `docs/baseline/traceability.csv` | `a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2` | MATCH |

Frozen baseline: `6/6 MATCH`.

## Environment

- Time zone: Asia/Shanghai (`+08:00`).
- Node: `v24.18.0`.
- pnpm: `11.10.0`.
- Exact existing runtime: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\tmp\c01-node-v24.18.0\node-v24.18.0-win-x64`.
- The inherited shell exposed no `node`. The Codex bundled fallback was `v24.14.0` / pnpm `11.9.0`; one diagnostic run on that mismatched runtime timed out two render tests. No source change was made. Re-running those two files on the repository-contracted runtime passed `2/2` files and `4/4` tests, after which every authoritative baseline command below used Node `24.18.0` / pnpm `11.10.0`.

## Baseline commands

| Time (Asia/Shanghai) | Command | Exit | Measured result |
| --- | --- | ---: | --- |
| `2026-07-21T13:17:46+08:00` | `node_modules\.bin\tsc.CMD --noEmit` | 1 | TS2604=13, TS7016=51, TS7026=222, total=286 |
| `2026-07-21T13:17:58+08:00`–`13:18:48+08:00` | `pnpm test -- --run` | 0 | 32/32 files, 257/257 tests PASS |
| `2026-07-21T13:19:00+08:00`–`13:19:11+08:00` | `pnpm build` | 0 | PASS, 1,860 modules transformed |
| `2026-07-21T13:19:19+08:00`–`13:22:25+08:00` | `pnpm test:e2e` | 0 | 19/19 PASS, one worker |

The complete TypeScript output is stored in `docs/evidence/C05/tsc-before.txt`. The global TypeScript result is intentionally recorded as exit `1`; all 286 diagnostics are the frozen React declaration categories above.

The Playwright run regenerated tracked C01/C04 screenshot files as a test side effect. Those exact old evidence files were restored from HEAD immediately afterward. `docs/evidence/C01`, `docs/evidence/C04`, `docs/baseline/**`, `package.json`, and `pnpm-lock.yaml` have no C05 preflight diff.

## Gate result

- Frozen hashes: `6/6 MATCH`
- Vitest baseline: `32 files / 257 tests PASS`
- Build baseline: `PASS (1,860 modules)`
- Playwright baseline: `19/19 PASS`
- TypeScript baseline: `exit 1; TS2604=13, TS7016=51, TS7026=222; total=286`

Task 1 preflight is fit for C05 implementation; no C05 source code was changed during this gate.
