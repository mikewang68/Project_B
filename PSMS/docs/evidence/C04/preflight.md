# C04 plan-entry preflight

Captured on 2026-07-19 (Asia/Shanghai) before C04 source changes.

## Repository entry state

- Branch: `demo/c04-plan-entry` (already existed and was not recreated or overwritten).
- HEAD: `2fa77b5b08dd0e3e97e8ef034a1744e58f2172eb`.
- `git status --short` before evidence creation had no tracked-file changes. It did show
  `?? .superpowers/`, containing the task orchestration files
  `.superpowers/sdd/task-1-brief.md` and `.superpowers/sdd/progress.md`; these are not
  part of the C04 commit.
- Ancestor checks, all exit `0`:
  - `3c9dcd7c0ba8ab9f7f9adae959277fbeefaa56ac`
  - `151004ae53dcd88c4910380bf4d2b6b27ef81b4a`
  - `aad25795efbf472a91228824c92b8def8daa86fb`

## Frozen baseline hashes

| File | SHA-256 | Result |
| --- | --- | --- |
| `docs/baseline/README.md` | `BC41651FC1876B0E9EB674DE700BF43C5D93E24DFD2C93234B0508595BE4EC34` | match |
| `docs/baseline/package-baseline.json` | `CA501290C82F2742CB555C099B04C85CD505BF156B38D7B6A9062BCD1F4C1810` | match |
| `docs/baseline/openapi.yaml` | `1AD194BAB44074ABCADC4AFED252C104AF623BF81C9B08C0A11A1AA721887B83` | match |
| `docs/baseline/demo-fixtures.json` | `B0F1506DB2D89E291BAF4772ED604561978AB66C43B607EC1F859F2C0AB907BA` | match |
| `docs/baseline/page-task-matrix.csv` | `C2FC700D07D962A03441824A1FA5B6B30564FDA9B73F85F31F8C6C7745F90C3B` | match |
| `docs/baseline/traceability.csv` | `A83E7DF6C0963A184527EE55BDECDBD6F7691874502DC55C400B4EA860491DD2` | match |

The scripted hash verification completed with exit `0` before and after the regression run.

## Environment

- Node.js: `v24.14.0` from the Codex runtime (the inherited command path did not expose
  `node`; the runtime directory was added only to the process environment for verification).
- pnpm: `11.9.0`.
- Vitest: `4.1.10`.
- Vite: `8.1.4`.
- Playwright: `1.61.1`.
- Each pnpm command warned that `package.json` requests Node `24.18.0`; the verification
  environment supplied `24.14.0`.

## TypeScript before-state

At 2026-07-19T20:45:31+08:00, the user-required command `pnpm exec tsc --noEmit`
exited `1` with `tsc is not recognized as an internal or external command`. It continued
to produce that exact result after the temporary Node runtime path was supplied. This is
the documented non-ASCII project-path executable-shim issue and is recorded without
changing dependencies or TypeScript configuration.

The required direct command, run with the same temporary Node runtime path, was:

```powershell
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C04/tsc-before.txt
```

It exited `1` and produced the frozen `38` React/ReactDOM TypeScript diagnostics. The full
diagnostic output is preserved in `tsc-before.txt`.

## C03 regression baseline

| Started (Asia/Shanghai) | Command | Exit | Result |
| --- | --- | ---: | --- |
| 2026-07-19T20:46:55+08:00 | `pnpm test -- --run` | 0 | 19/19 test files and 165/165 tests passed (17.12 s) |
| 2026-07-19T20:47:21+08:00 | `pnpm build` | 0 | Vite production build completed in 571 ms; it emitted the existing >500 kB chunk warning |
| 2026-07-19T20:47:29+08:00 | `pnpm test:e2e` | 0 | 14/14 Playwright tests passed (1.3 min) |

The E2E run rewrote four C01 visual-baseline screenshots. They were restored with
`git restore` before staging:

- `docs/evidence/C01/C01-404-1280x720.png`
- `docs/evidence/C01/C01-404-1440x900.png`
- `docs/evidence/C01/C01-overview-1280x720.png`
- `docs/evidence/C01/C01-overview-1440x900.png`

Post-restore, `git status --short -- docs/evidence/C01` and `git diff --name-only --
docs/evidence/C01` were both empty.

## Complete raw regression output (review fix)

The following commands were rerun on the unchanged C04 entry code. The complete combined
stdout/stderr captured by the PowerShell host is retained below; its `pnpm.cmd` entries are
host stream metadata, followed by the unabridged command output.

### `pnpm test -- --run`

- Started: `2026-07-19T20:55:42.4604039+08:00`
- Finished: `2026-07-19T20:56:00.8974971+08:00`
- Exit: `0`

```text
[WARN] Unsupported engine: wanted: {"node":"24.18.0"} (current: {"node":"v24.14.0","pnpm":"11.9.0"})
pnpm.cmd : $ vitest run "--" "--run"
所在位置 行:6 字符: 6
+ $raw=& pnpm test -- --run 2>&1
+      ~~~~~~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: ($ vitest run "--" "--run":String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError


 RUN  v4.1.10 E:/魔法入门与精通/自己写的神奇玩应/研究/工作/B项目/production-dispatch-demo


 Test Files  19 passed (19)
      Tests  165 passed (165)
   Start at  20:55:43
   Duration  17.12s (transform 2.07s, setup 5.28s, import 13.68s, tests 23.68s, environment 53.54s)
```

### `pnpm build`

- Started: `2026-07-19T20:56:07.5274383+08:00`
- Finished: `2026-07-19T20:56:09.1011118+08:00`
- Exit: `0`

```text
[WARN] Unsupported engine: wanted: {"node":"24.18.0"} (current: {"node":"v24.14.0","pnpm":"11.9.0"})
pnpm.cmd : $ vite build
所在位置 行:6 字符: 6
+ $raw=& pnpm build 2>&1
+      ~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: ($ vite build:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError

vite v8.1.4 building client environment for production...
transforming...✓ 1585 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                                        0.50 kB │ gzip:   0.34 kB
dist/assets/index-DreM5GG0.css                         2.34 kB │ gzip:   0.94 kB
dist/assets/AuditLogPage-CHQxSchV.js                   0.14 kB │ gzip:   0.14 kB
dist/assets/DispatchBoardPage-CHQxSchV.js              0.14 kB │ gzip:   0.14 kB
dist/assets/ExceptionHandlingPage-CHQxSchV.js          0.14 kB │ gzip:   0.14 kB
dist/assets/OfflineSyncPage-CHQxSchV.js                0.14 kB │ gzip:   0.14 kB
dist/assets/OperationMonitorPage-CHQxSchV.js           0.14 kB │ gzip:   0.14 kB
dist/assets/OperationReportPage-CHQxSchV.js            0.14 kB │ gzip:   0.14 kB
dist/assets/OverviewPage-CHQxSchV.js                   0.14 kB │ gzip:   0.14 kB
dist/assets/PlanLedgerPage-CHQxSchV.js                 0.14 kB │ gzip:   0.14 kB
dist/assets/ReceptionRecommendationPage-CHQxSchV.js    0.14 kB │ gzip:   0.14 kB
dist/assets/RoadAppointmentPage-CHQxSchV.js            0.14 kB │ gzip:   0.14 kB
dist/assets/SafetyInterlockPage-CHQxSchV.js            0.14 kB │ gzip:   0.14 kB
dist/assets/SystemSettingsPage-CHQxSchV.js             0.14 kB │ gzip:   0.14 kB
dist/assets/TaskDecompositionPage-CHQxSchV.js          0.14 kB │ gzip:   0.14 kB
dist/assets/PageScaffold-hAV998JJ.js                  56.89 kB │ gzip:  17.76 kB
dist/assets/index-B_qpZmRW.js                        783.04 kB │ gzip: 246.31 kB

✓ built in 589ms
[plugin builtin:vite-reporter]
(!) Some chunks are larger than 500 kB after minification. Consider:
- Using dynamic import() to code-split the application
- Use build.rolldownOptions.output.codeSplitting to improve chunking: https://rolldown.rs/reference/OutputOptions.codeSplitting
- Adjust chunk size limit for this warning via build.chunkSizeWarningLimit.
```

### `pnpm test:e2e`

- Started: `2026-07-19T20:56:15.8897347+08:00`
- Finished: `2026-07-19T20:57:33.8942465+08:00`
- Exit: `0`

```text
[WARN] Unsupported engine: wanted: {"node":"24.18.0"} (current: {"node":"v24.14.0","pnpm":"11.9.0"})
pnpm.cmd : $ playwright test
所在位置 行:6 字符: 6
+ $raw=& pnpm test:e2e 2>&1
+      ~~~~~~~~~~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: ($ playwright test:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError


Running 14 tests using 1 worker

  ok  1 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-001 /dispatch/overview (3.6s)
  ok  2 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-002 /dispatch/plans (2.6s)
  ok  3 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-003 /dispatch/plans/PLAN-DEMO-001/recommendation (2.6s)
  ok  4 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-004 /dispatch/plans/PLAN-DEMO-001/tasks (2.7s)
  ok  5 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-005 /dispatch/work-orders (3.0s)
  ok  6 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-006 /yard/appointments (2.8s)
  ok  7 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-007 /monitor/operations (2.6s)
  ok  8 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-008 /monitor/exceptions (2.9s)
  ok  9 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-009 /safety/interlocks (2.6s)
  ok 10 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-010 /operations/offline-sync (2.5s)
  ok 11 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-011 /reports/operations (2.7s)
  ok 12 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-012 /settings/system (2.7s)
  ok 13 e2e\route-smoke.spec.ts:58:5 › C01 十三路由冒烟测试 › UI-013 /governance/audit (2.6s)
  ok 14 e2e\route-smoke.spec.ts:71:1 › 1440×900 与 1280×720 覆盖十三路由和 404 (38.6s)

  14 passed (1.3m)
```

The later raw-terminal rerun completed at 20:59–21:01 with the same exit codes and counts
(19/165, build success, and 14/14); its Windows console encoding garbled CJK glyphs, so the
UTF-8 PowerShell-host capture above is the retained reviewer-readable raw evidence.
