# C08 Exception Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UI-008 exception handling so dispatch-board exception entries can be reviewed, acknowledged, assigned, handled, reviewed, closed, and reopened through strict DO-009 semantics.

**Architecture:** Add `src/features/exception-handling/**` for strict DO-009 projections, query context parsing, API-014/API-015 gateway, workflow, command service, components, and focused tests. Domain facts remain in the existing C03 Store; C08-owned writes only mutate `exception.exceptions` through C03 command execution and append-only audit. UI-005 query context is displayed as source context only because frozen DO-009 has no work-order or plan foreign key fields.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start from clean branch `demo/c07-dispatch-board`, HEAD `a232b5ea6f53c6b724cbc9db0840d729c48f1526`.
- Create or keep work branch `demo/c08-exception-handling`; do not merge or push.
- Read before editing: `docs/handoffs/C07-dispatch-board.md`, `docs/superpowers/specs/2026-07-21-c08-exception-handling-design.md`, this plan.
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

- C08 only handles strict DO-009 `DispatchException` records. Do not add fields or create a second fixture.
- C07 `workOrderId`/`planId` query context is display-only source context, not a production foreign key.
- API-014 and API-015 responses validate transport and identity only. Never overwrite Store from API returned objects.
- All writes use frozen DO-009 transitions: `ack`, `assign`, `handle`, `review`, `close`, `reopen`.
- Do not implement `ESCALATED` command. Display existing `ESCALATED` if present.
- C08 non-React primary TypeScript diagnostics must be 0; global React/JSX declaration failures are reported honestly.

---

### Task 1: C08 preflight and branch evidence

**Files:**
- Create: `docs/evidence/C08/preflight.md`
- Create: `docs/evidence/C08/tsc-before.txt`

**Interfaces:**
- Consumes: C07 handoff, C08 design, clean C07 branch.
- Produces: verified starting point and evidence directory.

- [ ] **Step 1: Verify branch and create C08 work branch**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor a232b5ea6f53c6b724cbc9db0840d729c48f1526 HEAD
git switch -c demo/c08-exception-handling
```

Expected: clean worktree, C07 HEAD ancestor, new branch `demo/c08-exception-handling`.

- [ ] **Step 2: Verify all six frozen hashes**

Copy the six exact expected SHA-256 values from `docs/evidence/C07/verification.md` or the C07 final handoff and check `docs/baseline/README.md`, `package-baseline.json`, `openapi.yaml`, `demo-fixtures.json`, `page-task-matrix.csv`, and `traceability.csv`. Expected: `6/6 MATCH`. On mismatch, stop.

- [ ] **Step 3: Record baseline commands**

Run:

```powershell
New-Item -ItemType Directory -Force docs/evidence/C08 | Out-Null
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C08/tsc-before.txt
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

Expected: match C07 handoff facts or document measured differences before editing.

- [ ] **Step 4: Write and commit preflight evidence**

Create `docs/evidence/C08/preflight.md` with branch, HEAD, hashes, test counts, build modules, Playwright count, TypeScript diagnostic classification, and known C07 boundaries.

Run:

```powershell
git add docs/evidence/C08/preflight.md docs/evidence/C08/tsc-before.txt
git commit -m "test(c08): record exception handling preflight"
```

---

### Task 2: Exception projections and query context

**Files:**
- Create: `src/features/exception-handling/constants.ts`
- Create: `src/features/exception-handling/types.ts`
- Create: `src/features/exception-handling/selectors.ts`
- Create: `src/features/exception-handling/queryContext.ts`
- Create: `src/features/exception-handling/__tests__/selectors.test.ts`
- Create: `src/features/exception-handling/__tests__/queryContext.test.ts`
- Create: `src/features/exception-handling/index.ts`
- Create: `docs/evidence/C08/exception-core-red.txt`

**Interfaces:**
- Produces: `selectExceptionHandlingBoard`, `selectExceptionKpis`, `parseExceptionQueryContext`.

- [ ] **Step 1: Write failing projection tests**

Cover: strict DO-009 records only, status/type/level/owner filters, due status, evidence counts, INTERLOCK link projection, KPI counts, deep-frozen return values, and C07 query context display without mutating exception facts.

- [ ] **Step 2: Save red output**

Run:

```powershell
pnpm vitest run src/features/exception-handling/__tests__/selectors.test.ts src/features/exception-handling/__tests__/queryContext.test.ts
```

Save meaningful failure output to `docs/evidence/C08/exception-core-red.txt`.

- [ ] **Step 3: Implement constants and query parser**

Use exact constants:

```ts
export const EXCEPTION_HANDLING_FEATURE = 'C08' as const;
export const C08_AUDIT_PREFIX = 'EX' as const;
export const c08ProgressStates = ['OPEN_QUEUE', 'HANDLING', 'REVIEW', 'CLOSED'] as const;
```

Parser accepts `workOrderId`, `planId`, `scenarioId`, `from`, `status`, `level`, `type`, and `owner`. It returns safe strings and never performs object lookup.

- [ ] **Step 4: Implement selectors**

Selectors apply data scope before object projection, derive state flow, UI-009 link for `INTERLOCK`, source context labels, due flags, and action availability.

- [ ] **Step 5: Run tests and commit**

```powershell
pnpm vitest run src/features/exception-handling/__tests__/selectors.test.ts src/features/exception-handling/__tests__/queryContext.test.ts
git add src/features/exception-handling docs/evidence/C08/exception-core-red.txt
git commit -m "feat(c08): add exception handling projections"
```

---

### Task 3: API-014/API-015 gateway and MSW support

**Files:**
- Create: `src/features/exception-handling/gateway.ts`
- Create: `src/features/exception-handling/__tests__/gateway.test.ts`
- Modify: `src/mocks/handlers.ts`
- Test: `src/mocks/__tests__/handlers.test.ts`

**Interfaces:**
- Produces: `ExceptionHandlingGateway.listExceptions()` and `ExceptionHandlingGateway.commandException()`.

- [ ] **Step 1: Write failing gateway tests**

Cover API-014 list, API-015 command, operation IDs, api IDs, success envelope, strict error envelope, path ID mismatch, malformed response, network failure, and no Store writes.

- [ ] **Step 2: Implement gateway**

API-014:

```text
GET /mock/exceptions
operationId = GET_mock_exceptions
apiId = API-014
```

API-015:

```text
POST /mock/exceptions/:id/command
operationId = POST_mock_exceptions_id_command
apiId = API-015
```

Gateway returns parsed transport results only. It must not call Store mutators.

- [ ] **Step 3: Extend MSW handlers**

Use existing handler patterns. Filter API-015 response by path exception ID and active scenario. Add deterministic fault controls for API-014/API-015 network and malformed cases without modifying baseline.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/exception-handling/__tests__/gateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/exception-handling/gateway.ts src/features/exception-handling/__tests__/gateway.test.ts src/mocks/handlers.ts
git commit -m "feat(c08): add exception handling api gateway"
```

---

### Task 4: EX-01 through EX-05 command pipeline

**Files:**
- Create: `src/features/exception-handling/commands.ts`
- Create: `src/features/exception-handling/workflow.ts`
- Create: `src/features/exception-handling/__tests__/commands.test.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: `ackException`, `assignException`, `submitExceptionHandling`, `reviewException`, `closeException`, `reopenException`, workflow store, and runtime `exceptionHandling` service.

- [ ] **Step 1: Write failing command tests**

Cover permissions, data scope, reason validation, owner validation, evidence validation, API before commit, DO-009 transitions, version drift, idempotency, audit, reset cleanup, and no WorkOrder/WorkNode/Interlock writes.

- [ ] **Step 2: Implement workflow and runtime wiring**

Workflow stores selectedExceptionId, drawer state, mode, reason, ownerDraft, evidenceDraft, and lastCommandError only. Runtime exposes:

```ts
runtime.exceptionHandling.gateway
runtime.exceptionHandling.workflow
runtime.exceptionHandling.commands
```

- [ ] **Step 3: Implement EX commands**

Map actions:

```text
EX-01 ACK: OPEN/REOPENED -> ACKNOWLEDGED
EX-02 ASSIGN: ACKNOWLEDGED -> HANDLING
EX-03 HANDLE: HANDLING -> PENDING_REVIEW
EX-04 REVIEW: PENDING_REVIEW -> HANDLING
EX-04 CLOSE: PENDING_REVIEW -> CLOSED
EX-05 REOPEN: CLOSED -> REOPENED
```

Every command calls API-015, validates versions after API, applies one `replaceDomainState`, and appends one C08 audit.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/exception-handling/__tests__/commands.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/exception-handling src/runtime
git commit -m "feat(c08): add exception handling commands"
```

---

### Task 5: UI-008 page and component tests

**Files:**
- Modify: `src/pages/monitor/ExceptionHandlingPage.tsx`
- Create: `src/features/exception-handling/exception-handling.css`
- Create: `src/features/exception-handling/components/ExceptionContextHeader.tsx`
- Create: `src/features/exception-handling/components/ExceptionKpiStrip.tsx`
- Create: `src/features/exception-handling/components/ExceptionLedger.tsx`
- Create: `src/features/exception-handling/components/ExceptionDetailPanel.tsx`
- Create: `src/features/exception-handling/components/ExceptionActionPanel.tsx`
- Create: `src/features/exception-handling/components/ExceptionEvidencePanel.tsx`
- Create: `src/pages/__tests__/ExceptionHandlingPage.render.test.tsx`
- Create: `src/pages/__tests__/ExceptionHandlingPage.action.test.tsx`
- Create: `src/pages/__tests__/ExceptionHandlingPage.permission.test.tsx`
- Create: `docs/evidence/C08/exception-page-red.txt`

**Interfaces:**
- Produces: usable UI-008 with six technical states and four progress states.

- [ ] **Step 1: Write failing page tests and save red output**

Cover smoke markers, empty, business-error, network-error, forbidden, not-found, OPEN_QUEUE, HANDLING, REVIEW, CLOSED, C07 source context, and UI-009 link.

- [ ] **Step 2: Replace PageScaffold**

Render actual exception handling workspace with current route text, page heading, query support, context header, KPI strip, ledger, detail, evidence, action panel, and audit messages.

- [ ] **Step 3: Wire actions to runtime commands**

Buttons must disable when permission, state, reason, owner, evidence, version, or interlock boundary blocks the action. Errors remain visible and retryable.

- [ ] **Step 4: Run page tests and route smoke**

```powershell
pnpm vitest run src/pages/__tests__/ExceptionHandlingPage.render.test.tsx src/pages/__tests__/ExceptionHandlingPage.action.test.tsx src/pages/__tests__/ExceptionHandlingPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx src/app/__tests__/routeSmoke.test.tsx
git add src/features/exception-handling src/pages/monitor/ExceptionHandlingPage.tsx src/pages/__tests__ docs/evidence/C08/exception-page-red.txt
git commit -m "feat(c08): implement exception handling page"
```

---

### Task 6: Cross-page integration and reset coverage

**Files:**
- Modify: `src/features/dispatch-board/__tests__/selectors.test.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`
- Create: `src/features/exception-handling/__tests__/integration.test.ts`

**Interfaces:**
- Produces: verified C07 -> C08 source context, reset cleanup, and no upstream mutation.

- [ ] **Step 1: Add integration tests**

Cover DB-05 URL query compatibility, UI-008 context parser, no production foreign-key claim, API-025 reset clearing C08 workflow/idempotency, and no WorkOrder/WorkNode mutation after EX commands.

- [ ] **Step 2: Implement small integration fixes**

If needed, export stable helpers from C07/C08 barrels. Do not change C07 behavior or UI-005 visual scope unless a failing C08 compatibility test proves a defect.

- [ ] **Step 3: Run tests and commit**

```powershell
pnpm vitest run src/features/exception-handling/__tests__/integration.test.ts src/features/dispatch-board/__tests__/selectors.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features src/runtime
git commit -m "test(c08): verify exception cross-page integration"
```

---

### Task 7: C08 E2E and screenshots

**Files:**
- Create: `e2e/ui-008-exception-handling.spec.ts`
- Create: `docs/evidence/C08/exception-e2e-red.txt`
- Create: `docs/evidence/C08/screenshot-index.md`
- Create: eight `docs/evidence/C08/C08-UI008-*.png`

**Interfaces:**
- Produces: 4 deterministic Playwright flows and 8 verified screenshots.

- [ ] **Step 1: Write C08 E2E red output**

Run the new spec before complete wiring and save meaningful failure to `docs/evidence/C08/exception-e2e-red.txt`.

- [ ] **Step 2: Implement four E2E flows**

Flows must cover C07 entry -> ack/assign, handle/review/close, permission/scope/version/idempotency, and network/malformed/INTERLOCK UI-009 behavior.

- [ ] **Step 3: Capture exactly 8 screenshots**

Use these names only:

```text
C08-UI008-SCN01-OPEN_QUEUE-1440x900.png
C08-UI008-SCN01-OPEN_QUEUE-1280x720.png
C08-UI008-SCN01-HANDLING-1440x900.png
C08-UI008-SCN01-HANDLING-1280x720.png
C08-UI008-SCN01-REVIEW-1440x900.png
C08-UI008-SCN01-REVIEW-1280x720.png
C08-UI008-SCN01-CLOSED-1440x900.png
C08-UI008-SCN01-CLOSED-1280x720.png
```

- [ ] **Step 4: Inspect screenshots and commit**

Check original resolution, no horizontal scroll, no clipped buttons, no overlapping text, state flow readability, and visible UI-009 entry. Record results in `screenshot-index.md`.

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-008-exception-handling.spec.ts
git add e2e/ui-008-exception-handling.spec.ts docs/evidence/C08
git commit -m "test(c08): cover exception handling demo flows"
```

---

### Task 8: Final verification, coverage, and handoff

**Files:**
- Create: `docs/evidence/C08/coverage.json`
- Create: `docs/evidence/C08/verification.md`
- Create: `docs/evidence/C08/tsc-after.txt`
- Create: `docs/handoffs/C08-exception-handling.md`

**Interfaces:**
- Produces: final evidence and handoff for C09.

- [ ] **Step 1: Run final verification**

Run at least:

```powershell
pnpm vitest run src/features/exception-handling src/pages/__tests__/ExceptionHandlingPage.render.test.tsx src/pages/__tests__/ExceptionHandlingPage.action.test.tsx src/pages/__tests__/ExceptionHandlingPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-008-exception-handling.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C08/tsc-after.txt
git diff --check
```

- [ ] **Step 2: Recheck hashes and forbidden scope**

Run six SHA-256 checks and scan changed files. Confirm no baseline, dependency, permission catalog, state-machine catalog, public schema, C07 domain logic, or UI-009 implementation was modified.

- [ ] **Step 3: Write coverage and verification**

`coverage.json` must enumerate selectors, API-014, API-015, EX-01..EX-05, permissions, data scope, version drift, idempotency, reset, six page states, four progress states, C07 source context, UI-009 link, 4 E2E, and 8 screenshots with exact test names/status.

`verification.md` must record commands, timestamps, exit codes, counts, modules, tsc classification, hashes, screenshots, changed-file scope, known limitations, branch and HEAD.

- [ ] **Step 4: Write handoff**

`docs/handoffs/C08-exception-handling.md` must include public runtime exports, selectors, Gateway behavior, command semantics, EX-01..EX-05, source-context boundary, UI-009 boundary, permissions, reset behavior, evidence links, known limitations, and C09 recommended entry.

- [ ] **Step 5: Commit final evidence**

```powershell
git add docs/evidence/C08/coverage.json docs/evidence/C08/verification.md docs/evidence/C08/tsc-after.txt docs/handoffs/C08-exception-handling.md
git commit -m "docs(c08): add exception handling evidence and handoff"
git status --short --branch
```

Expected: clean worktree, branch `demo/c08-exception-handling`, no merge, no push.

---

## Plan Self-Review

- Spec coverage: C08 route, C07 source context, DO-009, API-014/API-015, EX-01..EX-05, permissions, data scope, version, idempotency, audit, reset, UI states, E2E, screenshots, verification, and handoff are each mapped to tasks.
- Placeholder scan: no TBD/TODO/implement later placeholders.
- Type consistency: names are stable across tasks: `exceptionHandling`, `selectExceptionHandlingBoard`, `parseExceptionQueryContext`, `ackException`, `assignException`, `submitExceptionHandling`, `reviewException`, `closeException`, `reopenException`.
- Scope check: UI-008 only; UI-009 remains a linked boundary, not implemented.
