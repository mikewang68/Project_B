# C07 Dispatch Board Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UI-005 dispatch board so C06 READY work orders can be resource-bound, dispatched, and advanced through a demo execution-feedback skeleton.

**Architecture:** Add `src/features/dispatch-board/**` for ownership, selectors, API-008/API-009 gateway, workflow, command service, components, and focused tests. Domain facts remain in the existing C03 Store; C07-owned writes only mutate C06 WorkOrder/WorkNode fields through C03 command execution and append-only audit. API-008 proves resource binding transport without DO-005 state transition; API-009 performs the frozen `READY -> DISPATCHED` transition.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start from clean branch `demo/c06-task-decomposition`, HEAD `d07512e66194ef7ded1f7238bfd34dc0bf024919`.
- Create or keep work branch `demo/c07-dispatch-board`; do not merge or push.
- Read before editing: `docs/handoffs/C06-task-decomposition.md`, `docs/superpowers/specs/2026-07-21-c07-dispatch-board-design.md`, this plan.
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

- C07 only handles C06-owned `C06-WO-` / `C06-NODE-` records with `ruleVersion === 'C06-DEMO-RULE-1.0'`.
- API-008 resource binding keeps WorkOrder status `READY`; do not call DO-005 `assign` on READY records.
- API-009 dispatch performs DO-005 `READY + dispatch -> DISPATCHED`; DB-03/DB-04 local demo feedback uses frozen DO-005 transitions only.
- `INTERLOCK_FORCE_STOP` blocks DB-01 through DB-04 with `TOS-IL-001`; no override.
- C07 non-React primary TypeScript diagnostics must be 0; global React/JSX declaration failures are reported honestly.

---

### Task 1: C07 preflight and branch evidence

**Files:**
- Create: `docs/evidence/C07/preflight.md`
- Create: `docs/evidence/C07/tsc-before.txt`

**Interfaces:**
- Consumes: C06 handoff, C07 design, clean C06 branch.
- Produces: verified starting point and evidence directory.

- [ ] **Step 1: Verify branch and create C07 work branch**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor d07512e66194ef7ded1f7238bfd34dc0bf024919 HEAD
git switch -c demo/c07-dispatch-board
```

Expected: clean worktree, C06 HEAD ancestor, new branch `demo/c07-dispatch-board`.

- [ ] **Step 2: Verify six frozen hashes**

Run the same `Get-FileHash -Algorithm SHA256` check from C06, using the six Global Constraints values. Expected: `6/6 MATCH`. On mismatch, stop.

- [ ] **Step 3: Record baseline commands**

Run:

```powershell
New-Item -ItemType Directory -Force docs/evidence/C07 | Out-Null
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C07/tsc-before.txt
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

Expected: match C06 handoff facts or document measured differences before editing.

- [ ] **Step 4: Write and commit preflight evidence**

Create `docs/evidence/C07/preflight.md` with branch, HEAD, hashes, test counts, build modules, Playwright count, TypeScript diagnostic classification, and known C06 boundaries.

Run:

```powershell
git add docs/evidence/C07/preflight.md docs/evidence/C07/tsc-before.txt
git commit -m "test(c07): record dispatch board preflight"
```

---

### Task 2: Dispatch-board ownership, selectors, and resource matching

**Files:**
- Create: `src/features/dispatch-board/constants.ts`
- Create: `src/features/dispatch-board/types.ts`
- Create: `src/features/dispatch-board/ownership.ts`
- Create: `src/features/dispatch-board/selectors.ts`
- Create: `src/features/dispatch-board/__tests__/ownership.test.ts`
- Create: `src/features/dispatch-board/__tests__/selectors.test.ts`
- Create: `src/features/dispatch-board/index.ts`
- Create: `docs/evidence/C07/dispatch-core-red.txt`

**Interfaces:**
- Produces: `selectDispatchBoard`, `selectAssignableResources`, `selectDispatchKpis`, C07 ownership predicates.

- [ ] **Step 1: Write failing selector tests**

Cover: only C06-owned READY+ records appear; historical `WO-001..012` are ignored; AREA-A filtering happens before counts; required resource type filters candidates; unavailable resources are visible with reason but not assignable.

- [ ] **Step 2: Save red output**

Run:

```powershell
pnpm vitest run src/features/dispatch-board/__tests__/ownership.test.ts src/features/dispatch-board/__tests__/selectors.test.ts
```

Save failure output to `docs/evidence/C07/dispatch-core-red.txt`.

- [ ] **Step 3: Implement constants, ownership, and selectors**

Use exact constants:

```ts
export const DISPATCH_BOARD_FEATURE = 'C07' as const;
export const C07_AUDIT_PREFIX = 'DB' as const;
export const c07ProgressStates = ['READY_QUEUE', 'ASSIGNED', 'DISPATCHED', 'EXECUTING'] as const;
```

Selector output must include WorkOrder, WorkNode, plan summary, required resource type, assignable resource IDs, unavailable reasons, current progress state, and KPI counts.

- [ ] **Step 4: Run focused tests and commit**

```powershell
pnpm vitest run src/features/dispatch-board/__tests__/ownership.test.ts src/features/dispatch-board/__tests__/selectors.test.ts
git add src/features/dispatch-board docs/evidence/C07/dispatch-core-red.txt
git commit -m "feat(c07): add dispatch board projections"
```

---

### Task 3: API-008/API-009 gateway and strict transport tests

**Files:**
- Create: `src/features/dispatch-board/gateway.ts`
- Create: `src/features/dispatch-board/__tests__/gateway.test.ts`
- Modify: `src/mocks/handlers.ts`
- Test: existing mock handler tests as needed

**Interfaces:**
- Produces: `DispatchBoardGateway.assignWorkOrder()` and `DispatchBoardGateway.dispatchWorkOrder()`.

- [ ] **Step 1: Write failing gateway tests**

Cover exact paths, operation IDs, api IDs, success envelope, error envelope, path ID mismatch, malformed response, network failure, and no Store writes.

- [ ] **Step 2: Implement gateway**

API-008:

```text
POST /mock/work-orders/:id/assign
operationId = POST_mock_work_orders_id_assign
apiId = API-008
```

API-009:

```text
POST /mock/work-orders/:id/dispatch
operationId = POST_mock_work_orders_id_dispatch
apiId = API-009
```

Gateway returns parsed transport result only. It must not call Store mutators.

- [ ] **Step 3: Extend MSW handlers without changing baseline**

Filter response by path workOrder ID and active scenario. Return public envelope with traceId/auditLogId. Add fault controls for API-008/API-009 network and malformed cases using existing scenario/delay patterns.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/dispatch-board/__tests__/gateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/dispatch-board/gateway.ts src/features/dispatch-board/__tests__/gateway.test.ts src/mocks/handlers.ts
git commit -m "feat(c07): add dispatch board api gateway"
```

---

### Task 4: DB-01 and DB-02 command pipeline

**Files:**
- Create: `src/features/dispatch-board/commands.ts`
- Create: `src/features/dispatch-board/workflow.ts`
- Create: `src/features/dispatch-board/__tests__/commands.test.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: `bindDispatchResource`, `dispatchWorkOrder`, workflow selectors, and runtime `dispatchBoard` service.

- [ ] **Step 1: Write failing command tests**

Cover permissions, data scope, version snapshots, API before commit, API-008 READY no status transition, API-009 READY->DISPATCHED, WorkNode WAITING->READY, idempotency, audit, reset cleanup, and SCN-05 block.

- [ ] **Step 2: Implement workflow and runtime wiring**

Workflow stores only selectedWorkOrderId, selectedResourceId, drawer/panel state, mode, and lastCommandError. Runtime exposes:

```ts
runtime.dispatchBoard.gateway
runtime.dispatchBoard.workflow
runtime.dispatchBoard.commands
```

- [ ] **Step 3: Implement DB-01 bind resource**

Validate C06 ownership, READY status, resource type, AREA-A visibility, AVAILABLE status, versions, and permissions. Call API-008, then write resourceId/teamId/version/updatedAt in one commit. Do not change status.

- [ ] **Step 4: Implement DB-02 dispatch**

Require resourceId/teamId. Call API-009, execute DO-005 `READY + dispatch -> DISPATCHED`, set WorkNode `READY`, increment versions and append audit in one commit.

- [ ] **Step 5: Run tests and commit**

```powershell
pnpm vitest run src/features/dispatch-board/__tests__/commands.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/dispatch-board src/runtime
git commit -m "feat(c07): add assignment and dispatch commands"
```

---

### Task 5: DB-03/DB-04 demo execution feedback and DB-05 exception entry

**Files:**
- Modify: `src/features/dispatch-board/commands.ts`
- Modify: `src/features/dispatch-board/selectors.ts`
- Modify: `src/features/dispatch-board/__tests__/commands.test.ts`
- Modify: `src/features/dispatch-board/__tests__/selectors.test.ts`

**Interfaces:**
- Produces: `acknowledgeWorkOrder`, `startWorkOrder`, `pauseWorkOrder`, `completeWorkOrder`, exception-entry projection.

- [ ] **Step 1: Add failing tests for local feedback**

Cover `DISPATCHED -> ACKNOWLEDGED`, `ACKNOWLEDGED -> IN_PROGRESS`, `IN_PROGRESS -> PAUSED`, `PAUSED -> IN_PROGRESS`, `IN_PROGRESS -> COMPLETED`, WorkNode status sync, audit, idempotency, and blocked invalid states.

- [ ] **Step 2: Implement local feedback commands**

Use C03 command executor and frozen DO-005 transitionState. No new API calls. Set `ackStatus`, `actualStartTime`, `actualFinishTime`, WorkNode status, version, and audit.

- [ ] **Step 3: Implement DB-05 exception entry projection**

Produce URL:

```text
/monitor/exceptions?workOrderId={id}&planId={planId}&scenarioId={scenarioId}&from=dispatch-board
```

No Store write.

- [ ] **Step 4: Run tests and commit**

```powershell
pnpm vitest run src/features/dispatch-board/__tests__/commands.test.ts src/features/dispatch-board/__tests__/selectors.test.ts
git add src/features/dispatch-board
git commit -m "feat(c07): add execution feedback commands"
```

---

### Task 6: UI-005 page and component tests

**Files:**
- Modify: `src/pages/dispatch/DispatchBoardPage.tsx`
- Create: `src/features/dispatch-board/dispatch-board.css`
- Create: `src/features/dispatch-board/components/DispatchContextHeader.tsx`
- Create: `src/features/dispatch-board/components/DispatchKpiStrip.tsx`
- Create: `src/features/dispatch-board/components/WorkOrderQueue.tsx`
- Create: `src/features/dispatch-board/components/WorkOrderDetailPanel.tsx`
- Create: `src/features/dispatch-board/components/ResourceAssignmentPanel.tsx`
- Create: `src/features/dispatch-board/components/ExecutionFeedbackPanel.tsx`
- Create: `src/pages/__tests__/DispatchBoardPage.render.test.tsx`
- Create: `src/pages/__tests__/DispatchBoardPage.action.test.tsx`
- Create: `src/pages/__tests__/DispatchBoardPage.permission.test.tsx`
- Create: `docs/evidence/C07/dispatch-page-red.txt`

**Interfaces:**
- Produces: usable UI-005 with six technical states and four progress states.

- [ ] **Step 1: Write failing page tests and save red output**

Cover smoke markers, empty, business-error, network-error, forbidden, not-found, READY_QUEUE, ASSIGNED, DISPATCHED, EXECUTING, and responsive-safe key content.

- [ ] **Step 2: Replace PageScaffold**

Render actual dispatch board with current route text, page heading, query support, context header, KPI strip, queue, detail, resource panel, feedback panel, and audit messages.

- [ ] **Step 3: Wire actions to runtime commands**

Buttons must disable when permission, state, resource status, version, or interlock blocks the action. Errors remain visible and retryable.

- [ ] **Step 4: Run page tests and route smoke**

```powershell
pnpm vitest run src/pages/__tests__/DispatchBoardPage.render.test.tsx src/pages/__tests__/DispatchBoardPage.action.test.tsx src/pages/__tests__/DispatchBoardPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx src/app/__tests__/routeSmoke.test.tsx
git add src/features/dispatch-board src/pages/dispatch/DispatchBoardPage.tsx src/pages/__tests__ docs/evidence/C07/dispatch-page-red.txt
git commit -m "feat(c07): implement dispatch board page"
```

---

### Task 7: C07 E2E and screenshots

**Files:**
- Create: `e2e/ui-005-dispatch-board.spec.ts`
- Create: `docs/evidence/C07/screenshot-index.md`
- Create: eight `docs/evidence/C07/C07-UI005-*.png`

**Interfaces:**
- Produces: 4 deterministic Playwright flows and 8 verified screenshots.

- [ ] **Step 1: Write C07 E2E red output**

Before completing implementation-specific selectors, run the new spec and save meaningful failure to `docs/evidence/C07/dispatch-e2e-red.txt`.

- [ ] **Step 2: Implement four E2E flows**

Flows must cover SCN-01 ready-to-dispatch, execution feedback, permission/version/idempotency/resource rejection, and API/network/SCN-05 recovery.

- [ ] **Step 3: Capture exactly 8 screenshots**

Use these names only:

```text
C07-UI005-SCN01-READY_QUEUE-1440x900.png
C07-UI005-SCN01-READY_QUEUE-1280x720.png
C07-UI005-SCN01-ASSIGNED-1440x900.png
C07-UI005-SCN01-ASSIGNED-1280x720.png
C07-UI005-SCN01-DISPATCHED-1440x900.png
C07-UI005-SCN01-DISPATCHED-1280x720.png
C07-UI005-SCN01-EXECUTING-1440x900.png
C07-UI005-SCN01-EXECUTING-1280x720.png
```

- [ ] **Step 4: Inspect screenshots and commit**

Check original resolution, no horizontal scroll, no clipped buttons, no overlapping text, resource pool readability, and visible status labels. Record results in `screenshot-index.md`.

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-005-dispatch-board.spec.ts
git add e2e/ui-005-dispatch-board.spec.ts docs/evidence/C07
git commit -m "test(c07): cover dispatch board demo flows"
```

---

### Task 8: Final verification, coverage, and handoff

**Files:**
- Create: `docs/evidence/C07/coverage.json`
- Create: `docs/evidence/C07/verification.md`
- Create: `docs/evidence/C07/tsc-after.txt`
- Create: `docs/handoffs/C07-dispatch-board.md`

**Interfaces:**
- Produces: final evidence and handoff for C08.

- [ ] **Step 1: Run final verification**

Run at least:

```powershell
pnpm vitest run src/features/dispatch-board src/pages/__tests__/DispatchBoardPage.render.test.tsx src/pages/__tests__/DispatchBoardPage.action.test.tsx src/pages/__tests__/DispatchBoardPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-005-dispatch-board.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C07/tsc-after.txt
git diff --check
```

- [ ] **Step 2: Recheck hashes and forbidden scope**

Run six SHA-256 checks and scan changed files. Confirm no baseline, dependency, permission catalog, state-machine catalog, public schema, or later UI module was modified.

- [ ] **Step 3: Write coverage and verification**

`coverage.json` must enumerate selectors, API-008, API-009, DB-01..DB-05, permissions, data scope, version drift, idempotency, SCN-05, reset, six page states, four progress states, 4 E2E, and 8 screenshots with exact test names/status.

`verification.md` must record commands, timestamps, exit codes, counts, modules, tsc classification, hashes, screenshots, changed-file scope, known limitations, branch and HEAD.

- [ ] **Step 4: Write handoff**

`docs/handoffs/C07-dispatch-board.md` must include public runtime exports, selectors, Gateway behavior, command semantics, DB-01..DB-05, resource-binding semantics, execution-feedback boundary, permissions, reset behavior, evidence links, known limitations, and C08 recommended entry.

- [ ] **Step 5: Commit final evidence**

```powershell
git add docs/evidence/C07/coverage.json docs/evidence/C07/verification.md docs/evidence/C07/tsc-after.txt docs/handoffs/C07-dispatch-board.md
git commit -m "docs(c07): add dispatch board evidence and handoff"
git status --short --branch
```

Expected: clean worktree, branch `demo/c07-dispatch-board`, no merge, no push.

---

## Plan Self-Review

- Spec coverage: C07 route, C06 READY input, API-008/API-009, DB-01..DB-05, permissions, data scope, version, idempotency, audit, SCN-05, reset, UI states, E2E, screenshots, verification, and handoff are each mapped to tasks.
- Placeholder scan: no TBD/TODO/implement later placeholders.
- Type consistency: names are stable across tasks: `dispatchBoard`, `selectDispatchBoard`, `selectAssignableResources`, `bindDispatchResource`, `dispatchWorkOrder`, `acknowledgeWorkOrder`, `startWorkOrder`, `pauseWorkOrder`, `completeWorkOrder`.
- Scope check: UI-005 only; UI-006..UI-013 remain out of scope except read-only links.
