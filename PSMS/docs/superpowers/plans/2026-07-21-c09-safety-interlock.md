# C09 Safety Interlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UI-009 safety interlock so INTERLOCK context from UI-008 can be reviewed, reset-requested, approved, restored, and optionally override-requested through strict DO-010 semantics.

**Architecture:** Add `src/features/safety-interlock/**` for strict DO-010 projections, query context parsing, API-016/API-017 gateway, workflow, command service, components, and focused tests. Domain facts remain in the existing C03 Store; C09-owned writes only mutate `interlock.interlocks` through C03 command execution and append-only audit. UI-008 query context is displayed as source context only because frozen DO-010 has no exception foreign key fields.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start from clean branch `demo/c08-exception-handling`, HEAD `632fa3def12e93754f42eb3c8f16c8d80c207c69`.
- Create or keep work branch `demo/c09-safety-interlock`; do not merge or push.
- Read before editing: `docs/handoffs/C08-exception-handling.md`, `docs/superpowers/specs/2026-07-21-c09-safety-interlock-design.md`, this plan.
- Never modify `docs/baseline/**`, `package.json`, `pnpm-lock.yaml`, public schemas, public error codes, permission catalog, or state-machine catalog.
- Frozen hashes must remain:

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

- C09 only handles strict DO-010 `Interlock` records. Do not add fields or create a second fixture.
- C08 `exceptionId/scenarioId/from` query context is display-only source context, not a production foreign key.
- API-016 and API-017 responses validate transport and identity only. Never overwrite Store from API returned objects.
- All writes use frozen DO-010 transitions: `trigger`, `receipt`, `requestReset`, `approve`, `restore`, `requestOverride`.
- Do not implement real PLC/ECS/device reset or any silent FORCE_STOP bypass.
- C09 non-React primary TypeScript diagnostics must be 0; global React/JSX declaration failures are reported honestly.

---

### Task 1: C09 preflight and branch evidence

**Files:**
- Create: `docs/evidence/C09/preflight.md`
- Create: `docs/evidence/C09/tsc-before.txt`

**Interfaces:**
- Consumes: C08 handoff, C09 design, clean C08 branch.
- Produces: verified starting point and evidence directory.

- [ ] **Step 1: Verify branch and create C09 work branch**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor 632fa3def12e93754f42eb3c8f16c8d80c207c69 HEAD
git switch -c demo/c09-safety-interlock
```

Expected: clean worktree, C08 HEAD ancestor, new branch `demo/c09-safety-interlock`.

- [ ] **Step 2: Verify all six frozen hashes**

Use the six Global Constraints values. Expected: `6/6 MATCH`. On mismatch, report expected/actual/status/recent log and stop.

- [ ] **Step 3: Record baseline commands**

Run:

```powershell
New-Item -ItemType Directory -Force docs/evidence/C09 | Out-Null
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C09/tsc-before.txt
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

Expected: match C08 handoff facts or document measured differences before editing. Known C08 limitations are not C09 blockers if unchanged.

- [ ] **Step 4: Write and commit preflight evidence**

Create `docs/evidence/C09/preflight.md` with branch, HEAD, hashes, test counts, build modules, Playwright count, TypeScript diagnostic classification, Node version, and known C08 boundaries.

Run:

```powershell
git add docs/evidence/C09/preflight.md docs/evidence/C09/tsc-before.txt
git commit -m "test(c09): record safety interlock preflight"
```

---

### Task 2: Interlock projections and query context

**Files:**
- Create: `src/features/safety-interlock/constants.ts`
- Create: `src/features/safety-interlock/types.ts`
- Create: `src/features/safety-interlock/selectors.ts`
- Create: `src/features/safety-interlock/queryContext.ts`
- Create: `src/features/safety-interlock/__tests__/selectors.test.ts`
- Create: `src/features/safety-interlock/__tests__/queryContext.test.ts`
- Create: `src/features/safety-interlock/index.ts`
- Create: `docs/evidence/C09/interlock-core-red.txt`

**Interfaces:**
- Produces: `selectSafetyInterlockBoard`, `selectInterlockKpis`, `parseInterlockQueryContext`.

- [ ] **Step 1: Write failing projection tests**

Cover: strict DO-010 records only, status/actionLevel/riskType/receiptStatus filters, FORCE_STOP flags, receipt failures, reset request projection, approval chain projection, source context display without mutating facts, and deep-frozen return values.

- [ ] **Step 2: Save red output**

Run:

```powershell
pnpm vitest run src/features/safety-interlock/__tests__/selectors.test.ts src/features/safety-interlock/__tests__/queryContext.test.ts
```

Save meaningful failure output to `docs/evidence/C09/interlock-core-red.txt`.

- [ ] **Step 3: Implement constants and query parser**

Use exact constants:

```ts
export const SAFETY_INTERLOCK_FEATURE = 'C09' as const;
export const C09_AUDIT_PREFIX = 'SI' as const;
export const c09ProgressStates = ['LOCKED', 'RESETTING', 'RESTORED', 'OVERRIDE'] as const;
```

Parser accepts `exceptionId`, `scenarioId`, `from`, `status`, `actionLevel`, `riskType`, and `receiptStatus`. It returns safe strings and never performs object lookup.

- [ ] **Step 4: Implement selectors**

Selectors apply data scope before object projection, derive state flow, FORCE_STOP warning, return UI-008 link, reset/approval summaries, action availability, and KPI counts.

- [ ] **Step 5: Run tests and commit**

```powershell
pnpm vitest run src/features/safety-interlock/__tests__/selectors.test.ts src/features/safety-interlock/__tests__/queryContext.test.ts
git add src/features/safety-interlock docs/evidence/C09/interlock-core-red.txt
git commit -m "feat(c09): add safety interlock projections"
```

---

### Task 3: API-016/API-017 gateway and MSW support

**Files:**
- Create: `src/features/safety-interlock/gateway.ts`
- Create: `src/features/safety-interlock/__tests__/gateway.test.ts`
- Modify: `src/mocks/handlers.ts`
- Test: `src/mocks/__tests__/handlers.test.ts`

**Interfaces:**
- Produces: `SafetyInterlockGateway.listInterlocks()` and `SafetyInterlockGateway.commandInterlock()`.

- [ ] **Step 1: Write failing gateway tests**

Cover API-016 list, API-017 command, operation IDs, api IDs, success envelope, strict error envelope, path ID mismatch, malformed response, network failure, and no Store writes.

- [ ] **Step 2: Implement gateway**

API-016:

```text
GET /mock/interlocks
operationId = GET_mock_interlocks
apiId = API-016
```

API-017:

```text
POST /mock/interlocks/:id/command
operationId = POST_mock_interlocks_id_command
apiId = API-017
```

Gateway returns parsed transport results only. It must not call Store mutators.

- [ ] **Step 3: Extend MSW handlers**

Use existing handler patterns. Filter API-017 response by path interlock ID and active scenario. Add deterministic fault controls for API-016/API-017 network and malformed cases without modifying baseline.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/safety-interlock/__tests__/gateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/safety-interlock/gateway.ts src/features/safety-interlock/__tests__/gateway.test.ts src/mocks/handlers.ts
git commit -m "feat(c09): add safety interlock api gateway"
```

---

### Task 4: SI-01 through SI-05 command pipeline

**Files:**
- Create: `src/features/safety-interlock/commands.ts`
- Create: `src/features/safety-interlock/workflow.ts`
- Create: `src/features/safety-interlock/__tests__/commands.test.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: `triggerInterlock`, `receiptInterlock`, `requestReset`, `approveInterlock`, `restoreInterlock`, `requestOverride`, workflow store, and runtime `safetyInterlock` service.

- [ ] **Step 1: Write failing command tests**

Cover permissions, data scope, reason validation, approval user validation, reset request validation, API before commit, DO-010 transitions, version drift, idempotency, audit, reset cleanup, FORCE_STOP disclosure, and no C08/DO-009 writes.

- [ ] **Step 2: Implement workflow and runtime wiring**

Workflow stores selectedInterlockId, drawer state, mode, reason, approvalDraft, resetRequestDraft, and lastCommandError only. Runtime exposes:

```ts
runtime.safetyInterlock.gateway
runtime.safetyInterlock.workflow
runtime.safetyInterlock.commands
```

- [ ] **Step 3: Implement SI commands**

Map actions:

```text
SI-01 TRIGGER: TRIGGERED -> ACTION_ISSUED
SI-01 RECEIPT: ACTION_ISSUED -> WAITING_RECEIPT, WAITING_RECEIPT -> LOCKED
SI-02 REQUEST_RESET: LOCKED -> RESET_REQUESTED
SI-03 APPROVE: RESET_REQUESTED -> APPROVED or OVERRIDE_PENDING -> OVERRIDDEN
SI-04 RESTORE: APPROVED -> RESTORED
SI-05 REQUEST_OVERRIDE: LOCKED -> OVERRIDE_PENDING
```

Every command calls API-017, validates versions after API, applies one `replaceDomainState`, and appends one C09 audit.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/safety-interlock/__tests__/commands.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/safety-interlock src/runtime
git commit -m "feat(c09): add safety interlock commands"
```

---

### Task 5: UI-009 page and component tests

**Files:**
- Modify: `src/pages/safety/SafetyInterlockPage.tsx`
- Create: `src/features/safety-interlock/safety-interlock.css`
- Create: `src/features/safety-interlock/components/InterlockContextHeader.tsx`
- Create: `src/features/safety-interlock/components/InterlockKpiStrip.tsx`
- Create: `src/features/safety-interlock/components/InterlockLedger.tsx`
- Create: `src/features/safety-interlock/components/InterlockDetailPanel.tsx`
- Create: `src/features/safety-interlock/components/InterlockActionPanel.tsx`
- Create: `src/features/safety-interlock/components/InterlockSnapshotPanel.tsx`
- Create: `src/pages/__tests__/SafetyInterlockPage.render.test.tsx`
- Create: `src/pages/__tests__/SafetyInterlockPage.action.test.tsx`
- Create: `src/pages/__tests__/SafetyInterlockPage.permission.test.tsx`
- Create: `docs/evidence/C09/interlock-page-red.txt`

**Interfaces:**
- Produces: usable UI-009 with six technical states and four progress states.

- [ ] **Step 1: Write failing page tests and save red output**

Cover smoke markers, empty, business-error, network-error, forbidden, not-found, LOCKED, RESETTING, RESTORED, OVERRIDE, C08 source context, FORCE_STOP warning, and return UI-008 link.

- [ ] **Step 2: Replace PageScaffold**

Render actual safety interlock workspace with current route text, page heading, query support, context header, KPI strip, ledger, detail, input snapshot, action panel, and audit messages.

- [ ] **Step 3: Wire actions to runtime commands**

Buttons must disable when permission, state, reason, approval user, reset request, version, or safety boundary blocks the action. Errors remain visible and retryable.

- [ ] **Step 4: Run page tests and route smoke**

```powershell
pnpm vitest run src/pages/__tests__/SafetyInterlockPage.render.test.tsx src/pages/__tests__/SafetyInterlockPage.action.test.tsx src/pages/__tests__/SafetyInterlockPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx src/app/__tests__/routeSmoke.test.tsx
git add src/features/safety-interlock src/pages/safety/SafetyInterlockPage.tsx src/pages/__tests__ docs/evidence/C09/interlock-page-red.txt
git commit -m "feat(c09): implement safety interlock page"
```

---

### Task 6: Cross-page integration and reset coverage

**Files:**
- Modify: `src/features/exception-handling/__tests__/selectors.test.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`
- Create: `src/features/safety-interlock/__tests__/integration.test.ts`

**Interfaces:**
- Produces: verified C08 -> C09 source context, reset cleanup, and no upstream mutation.

- [ ] **Step 1: Add integration tests**

Cover UI-008 INTERLOCK URL query compatibility, UI-009 context parser, no production foreign-key claim, API-025 reset clearing C09 workflow/idempotency, and no DispatchException mutation after SI commands.

- [ ] **Step 2: Implement small integration fixes**

If needed, export stable helpers from C08/C09 barrels. Do not change C08 behavior or UI-008 visual scope unless a failing C09 compatibility test proves a defect.

- [ ] **Step 3: Run tests and commit**

```powershell
pnpm vitest run src/features/safety-interlock/__tests__/integration.test.ts src/features/exception-handling/__tests__/selectors.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features src/runtime
git commit -m "test(c09): verify safety interlock cross-page integration"
```

---

### Task 7: C09 E2E and screenshots

**Files:**
- Create: `e2e/ui-009-safety-interlock.spec.ts`
- Create: `docs/evidence/C09/interlock-e2e-red.txt`
- Create: `docs/evidence/C09/screenshot-index.md`
- Create: eight `docs/evidence/C09/C09-UI009-*.png`

**Interfaces:**
- Produces: 4 deterministic Playwright flows and 8 verified screenshots.

- [ ] **Step 1: Write C09 E2E red output**

Run the new spec before complete wiring and save meaningful failure to `docs/evidence/C09/interlock-e2e-red.txt`.

- [ ] **Step 2: Implement four E2E flows**

Flows must cover C08 entry -> request reset, approve/restore, permission/scope/version/idempotency, and network/malformed/FORCE_STOP/override behavior.

- [ ] **Step 3: Capture exactly 8 screenshots**

Use these names only:

```text
C09-UI009-SCN01-LOCKED-1440x900.png
C09-UI009-SCN01-LOCKED-1280x720.png
C09-UI009-SCN01-RESETTING-1440x900.png
C09-UI009-SCN01-RESETTING-1280x720.png
C09-UI009-SCN01-RESTORED-1440x900.png
C09-UI009-SCN01-RESTORED-1280x720.png
C09-UI009-SCN01-OVERRIDE-1440x900.png
C09-UI009-SCN01-OVERRIDE-1280x720.png
```

- [ ] **Step 4: Inspect screenshots and commit**

Check original resolution, no horizontal scroll, no clipped buttons, no overlapping text, state flow readability, FORCE_STOP warning, and visible return UI-008 entry. Record results in `screenshot-index.md`.

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-009-safety-interlock.spec.ts
git add e2e/ui-009-safety-interlock.spec.ts docs/evidence/C09
git commit -m "test(c09): cover safety interlock demo flows"
```

---

### Task 8: Final verification, coverage, and handoff

**Files:**
- Create: `docs/evidence/C09/coverage.json`
- Create: `docs/evidence/C09/verification.md`
- Create: `docs/evidence/C09/tsc-after.txt`
- Create: `docs/handoffs/C09-safety-interlock.md`

**Interfaces:**
- Produces: final evidence and handoff for C10.

- [ ] **Step 1: Run final verification**

Run at least:

```powershell
pnpm vitest run src/features/safety-interlock src/pages/__tests__/SafetyInterlockPage.render.test.tsx src/pages/__tests__/SafetyInterlockPage.action.test.tsx src/pages/__tests__/SafetyInterlockPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-009-safety-interlock.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C09/tsc-after.txt
git diff --check
```

- [ ] **Step 2: Recheck hashes and forbidden scope**

Run six SHA-256 checks and scan changed files. Confirm no baseline, dependency, permission catalog, state-machine catalog, public schema, C08 domain logic, or real equipment-control implementation was modified.

- [ ] **Step 3: Write coverage and verification**

`coverage.json` must enumerate selectors, API-016, API-017, SI-01..SI-05, permissions, data scope, version drift, idempotency, reset, six page states, four progress states, C08 source context, FORCE_STOP warning, 4 E2E, and 8 screenshots with exact test names/status.

`verification.md` must record commands, timestamps, exit codes, counts, modules, tsc classification, hashes, screenshots, changed-file scope, known limitations, branch and HEAD.

- [ ] **Step 4: Write handoff**

`docs/handoffs/C09-safety-interlock.md` must include public runtime exports, selectors, Gateway behavior, command semantics, SI-01..SI-05, source-context boundary, FORCE_STOP boundary, permissions, reset behavior, evidence links, known limitations, and C10 recommended entry.

- [ ] **Step 5: Commit final evidence**

```powershell
git add docs/evidence/C09/coverage.json docs/evidence/C09/verification.md docs/evidence/C09/tsc-after.txt docs/handoffs/C09-safety-interlock.md
git commit -m "docs(c09): add safety interlock evidence and handoff"
git status --short --branch
```

Expected: clean worktree, branch `demo/c09-safety-interlock`, no merge, no push.

---

## Plan Self-Review

- Spec coverage: C09 route, C08 source context, DO-010, API-016/API-017, SI-01..SI-05, permissions, data scope, version, idempotency, audit, reset, UI states, E2E, screenshots, verification, and handoff are each mapped to tasks.
- Placeholder scan: no TBD/TODO/implement later placeholders.
- Type consistency: names are stable across tasks: `safetyInterlock`, `selectSafetyInterlockBoard`, `parseInterlockQueryContext`, `triggerInterlock`, `receiptInterlock`, `requestReset`, `approveInterlock`, `restoreInterlock`, `requestOverride`.
- Scope check: UI-009 only; real equipment control and C08 mutation remain out of scope.
