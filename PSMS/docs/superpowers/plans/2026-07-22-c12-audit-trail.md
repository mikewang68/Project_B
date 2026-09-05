# C12 Audit Trail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build frozen UI-013 as a deterministic, read-only audit workbench that validates API-024, projects strict DO-013 plus real C03-C11 command audit, explains trace chains, and never fabricates history or mutates upstream domain objects.

**Architecture:** Add an isolated `src/features/audit-trail/**` slice for strict projection, query/filter logic, result classification, API-024 validation, trace aggregation, and feature-local workflow. The shared Store remains the sole domain truth; API observations are kept separate from DO-013 and all UI interactions are local except the existing SCN-01 reset.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start point: `demo/c11-reporting-dashboard` at `507a715046dc7e9858e638b07bb56e920e70da37`.
- Work branch: `demo/c12-audit-trail`; do not merge or push.
- Read before editing: `docs/handoffs/C11-reporting-dashboard.md`, `docs/superpowers/specs/2026-07-22-c12-audit-trail-design.md`, this plan.
- Frozen identity: `UI-013`, `/governance/audit`, `src/pages/governance/AuditLogPage.tsx`, `e2e/ui-013-governance-audit.spec.ts`, `API-024 GET /mock/audit-logs`, `GET_mock_audit_logs`.
- `UI-012` is system configuration and must never appear as the implemented audit page identity.
- DO-013 fields are exactly `id`, `actorId`, `operatorTerminal`, `action`, `objectType`, `objectId`, `before`, `after`, `reason`, `traceId`, `occurredAt`.
- `auditLogId` belongs only to API envelopes/read observations; never alias it to DO-013 `id`.
- Never modify `docs/baseline/**`, dependency manifests/lockfiles, public contracts, public errors, permission catalog, state-machine catalog, or C11 business implementation.
- Never add a second fixture, Store, Provider, real backend, export/print/archive/download, immutable ledger, signature, external logging service, real time, randomness, or external network.
- API-024 validates transport and identity only; never overwrite Store facts from response items.
- C12 may write only feature-local workflow/read observation. Reset uses the existing runtime reset and must restore frozen Store facts.
- Never write Plan, Recommendation, WorkOrder, WorkNode, Resource, Appointment, DispatchException, Interlock, OfflinePacket, Report, UserRole, `configAudit.audit`, or `configAudit.commandAudit` from C12.
- Frozen DO-013 without command metadata is `RECORDED`, not inferred `SUCCESS`.
- Reset idempotent hit count is `0`; do not create `idempotent: true` or any synthetic DO-013. Only explicit future runtime feedback can classify `IDEMPOTENT_HIT`.
- All target viewports must avoid document horizontal overflow, Chinese truncation, control overlap, tag overlap, and drawer clipping.
- C12 non-React primary TypeScript diagnostics must be 0. Existing React/JSX diagnostics, if any, are classified honestly.
- Frozen hashes must remain:

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

---

### Task 1: Resumable C12 execution prompt

**Files:**
- Create: `docs/conversation-prompts/C12-audit-trail.md`

**Interfaces:**
- Consumes: approved design, this plan, clean C11 base.
- Produces: resumable execution instructions with exact C12 scope and verification gates.

- [ ] **Step 1: Write and commit the execution prompt**

The prompt must state exact branch/base, UI-013 correction, strict DO-013 fields, API-024 identity, TDD gates, forbidden writes, eight UI013 screenshots, verification commands, no merge/push, and the approved idempotent-zero rule.

```powershell
git add docs/conversation-prompts/C12-audit-trail.md
git commit -m "docs(c12): add audit trail execution prompt"
```

---

### Task 2: C12 preflight evidence

**Files:**
- Create: `docs/evidence/C12/preflight.md`
- Create: `docs/evidence/C12/tsc-before.txt`

**Interfaces:**
- Consumes: approved design, committed plan/prompt, clean C11 base, six frozen hashes.
- Produces: measured pre-implementation baseline and contract-gate evidence.

- [ ] **Step 1: Verify topology and frozen hashes**

```powershell
git status --short --branch
git branch --show-current
git merge-base --is-ancestor 507a715046dc7e9858e638b07bb56e920e70da37 HEAD
git rev-list --min-parents=2 --count 507a715046dc7e9858e638b07bb56e920e70da37..HEAD
Get-FileHash -Algorithm SHA256 docs/baseline/README.md,docs/baseline/package-baseline.json,docs/baseline/openapi.yaml,docs/baseline/demo-fixtures.json,docs/baseline/page-task-matrix.csv,docs/baseline/traceability.csv
```

Expected: branch `demo/c12-audit-trail`, C11 ancestor exit 0, merge count `0`, hashes `6/6 MATCH`. Any mismatch re-enters the contract gate.

- [ ] **Step 2: Capture measured baseline**

```powershell
node --version
pnpm --version
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

Save complete TypeScript output in `tsc-before.txt`; record actual exits/counts, C11 handoff comparison, UI-013 correction, DO-013/envelope distinction, current idempotent behavior, and existing slow-test classification in `preflight.md`.

- [ ] **Step 3: Commit preflight**

```powershell
git add docs/evidence/C12/preflight.md docs/evidence/C12/tsc-before.txt
git commit -m "test(c12): record audit trail preflight"
```

---

### Task 3: Strict DO-013 projection, classification, and queries

**Files:**
- Create: `src/features/audit-trail/auditTypes.ts`
- Create: `src/features/audit-trail/auditProjection.ts`
- Create: `src/features/audit-trail/auditQueries.ts`
- Create: `src/features/audit-trail/index.ts`
- Create: `src/features/audit-trail/__tests__/auditProjection.test.ts`
- Create: `src/features/audit-trail/__tests__/auditQueries.test.ts`
- Create: `docs/evidence/C12/audit-core-red.txt`

**Interfaces:**
- Produces `AuditResultCategory`, `AuditSourceModule`, `AuditFilters`, `AuditLedgerItem`, `AuditProjection`, `AuditQueryContext`.
- Produces `projectAuditTrail(state, options?)`, `filterAuditItems(items, filters)`, `parseAuditQuery(input)`.

- [ ] **Step 1: Write failing projection tests**

Use a real fixture Store and assert:

```ts
const projection = projectAuditTrail(store.getState());
expect(projection.items).toHaveLength(9);
expect(Object.keys(projection.items[0]!.record).sort()).toEqual([
  'action', 'actorId', 'after', 'before', 'id', 'objectId', 'objectType',
  'occurredAt', 'operatorTerminal', 'reason', 'traceId',
].sort());
expect(projection.kpis).toMatchObject({
  total: 9, success: 0, denied: 0, versionConflict: 0,
  idempotentHit: 0, businessError: 0,
});
expect(projection.items.every(({ resultCategory }) => resultCategory === 'RECORDED')).toBe(true);
```

Add separate real `CommandAuditEntry` cases for `SUCCESS`, `TOS-AUTH-001`, `DEMO-VERSION-001`, another `FAILED` public error, and explicit `{ auditLogId, idempotent: true }` feedback. Assert feedback may enhance an existing item only and never creates a new item. Assert duplicate same-ID/same-record merges metadata, duplicate same-ID/different-record throws, input state is unchanged, output is deeply frozen, and source prefixes `AUD-C03` through `AUD-C11` map deterministically.

- [ ] **Step 2: Write failing query/filter tests**

```ts
expect(parseAuditQuery(
  '?auditId=AUD-C11-001&module=C11&result=SUCCESS&actorId=USER-1&traceId=TRACE-1&period=2026-07-16&scenarioId=SCN-01&from=reports',
)).toEqual({
  auditId: 'AUD-C11-001', module: 'C11', result: 'SUCCESS', actorId: 'USER-1',
  traceId: 'TRACE-1', period: '2026-07-16', scenarioId: 'SCN-01', from: 'reports',
});
expect(parseAuditQuery('?module=C99&result=OK&auditId=%00%20')).toEqual({});
```

Cover every filter field, combined AND behavior, case-insensitive text matching, trim/control-character cleanup, length bound, Store-empty versus filter-empty, and `auditId` selected/not-found behavior.

- [ ] **Step 3: Run RED and save evidence**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditProjection.test.ts src/features/audit-trail/__tests__/auditQueries.test.ts
```

Save the missing-module failure to `docs/evidence/C12/audit-core-red.txt`.

- [ ] **Step 4: Implement the minimal projection and parser**

Exact public signatures:

```ts
export function projectAuditTrail(
  state: DemoRootState,
  options?: Readonly<{ explicitFeedback?: readonly ExplicitAuditFeedback[] }>,
): AuditProjection;
export function filterAuditItems(
  items: readonly AuditLedgerItem[], filters: AuditFilters,
): readonly AuditLedgerItem[];
export function parseAuditQuery(input: string | URLSearchParams): AuditQueryContext;
```

Classification order is `IDEMPOTENT_HIT`, `DENIED`, `VERSION_CONFLICT`, `BUSINESS_ERROR`, `SUCCESS`, `RECORDED`. `explicitFeedback` must match an existing `record.id` through `auditLogId`; unmatched feedback is ignored. Sort ledger items by `occurredAt` descending and original stable index.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditProjection.test.ts src/features/audit-trail/__tests__/auditQueries.test.ts
git add src/features/audit-trail docs/evidence/C12/audit-core-red.txt
git commit -m "feat(c12): add audit projections and queries"
```

---

### Task 4: API-024 Gateway and deterministic fault injection

**Files:**
- Create: `src/features/audit-trail/auditGateway.ts`
- Create: `src/features/audit-trail/__tests__/auditGateway.test.ts`
- Modify: `src/features/audit-trail/index.ts`
- Modify: `src/mocks/handlers.ts`
- Modify: `src/mocks/__tests__/handlers.test.ts`

**Interfaces:**
- Produces `AuditGateway`, `AuditGatewayQuery`, `AuditGatewayResult`, `createAuditGateway(fetcher)`.
- Method `listAuditLogs(query?): Promise<AuditGatewayResult>`.

- [ ] **Step 1: Write failing Gateway tests**

Assert the request has no query string and validates strict identities:

```ts
const result = await gateway.listAuditLogs({
  expectedScenarioId: 'SCN-01', expectedAuditId: 'AUD-001',
});
expect(fetcher).toHaveBeenCalledWith('/mock/audit-logs', expect.objectContaining({ headers: {} }));
expect(result).toMatchObject({
  ok: true,
  data: { apiId: 'API-024', operationId: 'GET_mock_audit_logs', scenarioId: 'SCN-01' },
});
```

Cover standard error envelope, bad JSON, malformed success, wrong apiId, wrong operationId, scenario mismatch, missing expected DO-013 `id`, extra/invalid DO-013 fields, network rejection, and envelope auditLogId distinct from record id. Assert no Store dependency exists in the Gateway.

- [ ] **Step 2: Verify RED**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditGateway.test.ts
```

- [ ] **Step 3: Implement strict API-024 validation**

```ts
const auditGatewayDataSchema = z.object({
  apiId: z.literal('API-024'),
  operationId: z.literal('GET_mock_audit_logs'),
  now: z.string().min(1),
  scenarioId: z.string().min(1),
  items: z.array(do013Schema),
}).strict();
```

Preserve error envelopes unchanged. A JSON/schema/identity failure rejects with an Error so the page maps it to malformed-response. Fault headers are feature-local only: `x-demo-c12-fault: network|malformed|business`.

- [ ] **Step 4: Add C12 mock faults**

In `src/mocks/handlers.ts`, before normal API-024 resolution:

```ts
if (contract.apiId === 'API-024' && fault === 'network') return HttpResponse.error();
if (contract.apiId === 'API-024' && fault === 'malformed') {
  return HttpResponse.json({ ok: true, data: { malformed: true } });
}
if (contract.apiId === 'API-024' && fault === 'business') {
  return failureResponse(runtime, {
    status: 409, errorCode: 'DEMO-SCENARIO-001',
    message: '当前场景不允许读取审计日志。',
  });
}
```

Do not change OpenAPI, API-024 parameters, schemas, or any other API behavior.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditGateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/audit-trail src/mocks/handlers.ts src/mocks/__tests__/handlers.test.ts
git commit -m "feat(c12): add audit api gateway"
```

---

### Task 5: Trace aggregation, workflow, and shared runtime wiring

**Files:**
- Create: `src/features/audit-trail/auditRuntime.ts`
- Create: `src/features/audit-trail/__tests__/auditRuntime.test.ts`
- Modify: `src/features/audit-trail/auditProjection.ts`
- Modify: `src/features/audit-trail/__tests__/auditProjection.test.ts`
- Modify: `src/features/audit-trail/index.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces `buildAuditTrace(items, traceId)`, `createAuditTrailWorkflowStore()`, `AuditTrailWorkflowState`.
- Runtime exports `runtime.auditTrail.gateway`, `.workflow` and `useAuditTrailWorkflow(selector)`.

- [ ] **Step 1: Write failing trace tests**

```ts
const trace = buildAuditTrace(items, 'TRACE-SHARED');
expect(trace?.entries.map(({ record }) => record.id)).toEqual(['AUD-EARLY', 'AUD-LATE']);
expect(buildAuditTrace(items, 'TRACE-MISSING')).toBeUndefined();
```

Assert order by occurredAt ascending then stable input, one-record chains remain one record, no cross-trace leakage, and frozen output.

- [ ] **Step 2: Write failing workflow/runtime tests**

Exact initial state:

```ts
expect(workflow.getState()).toEqual({
  filters: {}, detailDrawerOpen: false, pending: false, readState: { kind: 'idle' },
});
```

Cover setFilters, select/open/close detail, expand/collapse trace, beginRead, success observation, business observation, malformed/network state, success feedback, stale trace feedback, and reset. Assert shared runtime exposes C12 and existing reset returns C12 state to initial without a second Provider.

- [ ] **Step 3: Verify RED**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditProjection.test.ts src/features/audit-trail/__tests__/auditRuntime.test.ts src/runtime/__tests__/runtime.test.tsx
```

- [ ] **Step 4: Implement workflow and runtime wiring**

```ts
export type AuditTrailWorkflowState = Readonly<{
  filters: AuditFilters;
  selectedAuditId?: string;
  expandedTraceId?: string;
  detailDrawerOpen: boolean;
  pending: boolean;
  readState: AuditReadState;
  readObservation?: AuditReadObservation;
  lastFeedback?: Readonly<{ kind: 'success' | 'stale-trace'; message: string }>;
}>;
```

Wire `auditTrail` beside existing feature runtimes in `createDemoRuntime`, add `workflow.reset()` to `onSuccessfulReset`, and expose `useAuditTrailWorkflow` through `useSyncExternalStore`. Do not add commands or domain mutations.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditProjection.test.ts src/features/audit-trail/__tests__/auditRuntime.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/audit-trail src/runtime
git commit -m "feat(c12): add audit trace aggregation"
```

---

### Task 6: UI-013 audit workbench page

**Files:**
- Modify: `src/pages/governance/AuditLogPage.tsx`
- Create: `src/features/audit-trail/audit-trail.css`
- Create: `src/features/audit-trail/components/AuditContextHeader.tsx`
- Create: `src/features/audit-trail/components/AuditKpiGrid.tsx`
- Create: `src/features/audit-trail/components/AuditFilterBar.tsx`
- Create: `src/features/audit-trail/components/AuditLedger.tsx`
- Create: `src/features/audit-trail/components/AuditDetailDrawer.tsx`
- Create: `src/features/audit-trail/components/AuditTracePanel.tsx`
- Create: `src/features/audit-trail/components/AuditReadBanner.tsx`
- Create: `src/pages/__tests__/auditTrailTestHarness.tsx`
- Create: `src/pages/__tests__/AuditLogPage.render.test.tsx`
- Create: `src/pages/__tests__/AuditLogPage.action.test.tsx`
- Create: `src/pages/__tests__/AuditLogPage.permission.test.tsx`
- Create: `docs/evidence/C12/audit-page-red.txt`

**Interfaces:**
- Produces accessible UI-013 overview, KPI, local filters, ledger, detail drawer, trace panel, API read/retry, reset, and explanatory copy.
- Page reads `projectAuditTrail`, `filterAuditItems`, `buildAuditTrace`, `useDemoSelector`, `useAuditTrailWorkflow`, and `runtime.auditTrail` only.

- [ ] **Step 1: Write failing render/state tests**

Assert UI-013 identity, `/governance/audit`, API-024 badge, Demo disclosure, exact idempotent-zero explanation, 9 frozen rows, 6 numeric KPI plus recent trace, strict DO-013 detail fields, loading, Store empty, filter empty, invalid auditId, network, malformed, business error, stale trace, and success feedback.

- [ ] **Step 2: Write failing action/permission tests**

```ts
await user.selectOptions(screen.getByLabelText('来源模块'), 'BASELINE');
await user.type(screen.getByLabelText('操作人 actorId'), 'USER-001');
expect(screen.getByRole('row', { name: /AUD-001/ })).toBeInTheDocument();
await user.click(screen.getByRole('button', { name: /查看 AUD-001 详情/ }));
expect(screen.getByRole('dialog', { name: '审计详情 AUD-001' })).toBeVisible();
```

Assert trace opening, close behavior, retry, reset, disabled controls while pending, no export/print/download buttons, AUDITOR access, non-authorized role 403 with zero API-024 calls, and byte-equivalent upstream/configAudit snapshots across reads/errors/UI actions.

- [ ] **Step 3: Save RED evidence**

```powershell
pnpm vitest run src/pages/__tests__/AuditLogPage.render.test.tsx src/pages/__tests__/AuditLogPage.action.test.tsx src/pages/__tests__/AuditLogPage.permission.test.tsx
```

Save the meaningful scaffold failure to `docs/evidence/C12/audit-page-red.txt`.

- [ ] **Step 4: Implement page and components**

Start API-024 read only after the route permission boundary renders the page. On success record the envelope observation but render ledger from Store projection. On error render the correct banner and preserve Store rows. JSON before/after uses deterministic pretty printing. Missing metadata/request/response displays `未提供`.

- [ ] **Step 5: Implement responsive CSS**

- Above 1360px: KPI in responsive seven-card strip; ledger/trace uses `minmax(680px, 1.7fr) minmax(300px, .7fr)`.
- At 1360px or below: filters wrap into two rows, trace panel stacks below ledger, primary KPI/filter/ledger remain before or at the first viewport boundary.
- Every grid child has `min-width: 0`; IDs and Chinese labels wrap without ellipsis; table/card layout must not create document horizontal overflow.

- [ ] **Step 6: Run GREEN and commit**

```powershell
pnpm vitest run src/pages/__tests__/AuditLogPage.render.test.tsx src/pages/__tests__/AuditLogPage.action.test.tsx src/pages/__tests__/AuditLogPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx
git add src/features/audit-trail src/pages/governance/AuditLogPage.tsx src/pages/__tests__ docs/evidence/C12/audit-page-red.txt
git commit -m "feat(c12): implement audit trail page"
```

---

### Task 7: Audit integration and immutability

**Files:**
- Create: `src/features/audit-trail/__tests__/auditIntegration.test.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces verified API/Store separation, real runtime audit projection, reset determinism, and upstream immutability.

- [ ] **Step 1: Write integration tests**

Use existing command services to create one real success and one real failure/denial entry, then project them with the 9 frozen records. Assert categories and source modules are derived from actual appended `CommandAuditEntry`, not direct fixture insertion.

Capture before/after snapshots of every Store slice. API-024 success/network/malformed/business and all C12 workflow actions must leave the entire Store byte-equivalent. Existing reset may restore the Store from frozen snapshot; after reset require 9 base audit rows, zero command audit, zero idempotent hits, and initial C12 workflow.

- [ ] **Step 2: Implement only fixes proven by RED**

Confine fixes to audit-trail exports and `DemoRuntimeContext.tsx`. Do not edit upstream commands, permissions, state machines, baseline, fixture, or C11 implementation.

- [ ] **Step 3: Run GREEN and commit**

```powershell
pnpm vitest run src/features/audit-trail/__tests__/auditIntegration.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/audit-trail src/runtime
git commit -m "test(c12): verify audit integration"
```

---

### Task 8: UI-013 Playwright flows and eight screenshots

**Files:**
- Create: `e2e/ui-013-governance-audit.spec.ts`
- Create: `docs/evidence/C12/audit-e2e-red.txt`
- Create: `docs/evidence/C12/screenshot-index.md`
- Create: exactly eight `docs/evidence/C12/C12-UI013-*.png`

**Interfaces:**
- Produces deterministic UI-013 E2E coverage and exactly eight inspected screenshots.

- [ ] **Step 1: Write E2E first and save RED**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-013-governance-audit.spec.ts
```

Save the first meaningful failure to `docs/evidence/C12/audit-e2e-red.txt` before any E2E-driven implementation fix.

- [ ] **Step 2: Implement C12 E2E flows**

1. Seed AUDITOR and open SCN-01 UI-013; assert 9 base records, Demo disclosure, API-024 identity, `RECORDED` semantics, and idempotent count 0.
2. Filter by actor/object/trace, open AUD-001 detail, verify every strict field and absence explanations.
3. Open TRACE-001 chain and assert exactly one truthful entry.
4. In a separate SPA flow, use existing upstream UI commands to generate actual command audit result categories, switch the stored session to AUDITOR without reloading the runtime, navigate to UI-013, and assert at least two real key categories. Do not inject command audit directly.
5. Inject API-024 network/malformed/business faults and assert visible errors, recovery, and serialized Store equality.
6. Reset and assert frozen 9/zero-command-audit/zero-idempotent state.

- [ ] **Step 3: Generate exact screenshots**

```text
C12-UI013-SCN01-OVERVIEW-1440x900.png
C12-UI013-SCN01-OVERVIEW-1280x720.png
C12-UI013-SCN01-FILTERED-1440x900.png
C12-UI013-SCN01-FILTERED-1280x720.png
C12-UI013-SCN01-DETAIL-1440x900.png
C12-UI013-SCN01-DETAIL-1280x720.png
C12-UI013-SCN01-TRACE-1440x900.png
C12-UI013-SCN01-TRACE-1280x720.png
```

- [ ] **Step 4: Inspect images and raw dimensions**

Use `view_image` on all eight. Verify no horizontal overflow, clipping, overlap, missing Chinese glyphs, stale loading masks, hidden primary KPI, obscured controls, or clipped drawer/trace content. Read PNG dimensions and require filename dimensions exactly.

- [ ] **Step 5: Run C12 E2E GREEN and commit**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-013-governance-audit.spec.ts
git add e2e/ui-013-governance-audit.spec.ts docs/evidence/C12/audit-e2e-red.txt docs/evidence/C12/screenshot-index.md docs/evidence/C12/C12-UI013-*.png
git commit -m "test(c12): cover audit trail demo flows"
```

---

### Task 9: Full verification, evidence, and C12→C13 handoff

**Files:**
- Create: `docs/evidence/C12/coverage.json`
- Create: `docs/evidence/C12/verification.md`
- Create: `docs/evidence/C12/tsc-after.txt`
- Create: `docs/handoffs/C12-audit-trail.md`

**Interfaces:**
- Produces auditable final verification and C13 handoff.

- [ ] **Step 1: Attempt focused coverage without changing dependencies**

```powershell
pnpm vitest run src/features/audit-trail src/pages/__tests__/AuditLogPage.render.test.tsx src/pages/__tests__/AuditLogPage.action.test.tsx src/pages/__tests__/AuditLogPage.permission.test.tsx --coverage.enabled --coverage.reporter=json --coverage.reporter=text
```

If the frozen install lacks the official V8 provider, create `coverage.json` that records the command, exit, provider error, and that no trustworthy percentage was produced. Do not install or fake coverage.

- [ ] **Step 2: Run fresh full verification**

```powershell
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-013-governance-audit.spec.ts
pnpm test:e2e
git diff --check
```

Save complete TypeScript output to `tsc-after.txt`. Record actual tests, failures, modules, timings, screenshot dimensions, diagnostic classes, isolated slow-test reruns, and coverage limitation in `verification.md`.

- [ ] **Step 3: Run frozen and forbidden-scope checks**

Require:

```text
6/6 frozen hashes match
0 merge commits since C11 base
0 changes under docs/baseline
0 changes to package.json/pnpm-lock.yaml
0 changes to public contracts, permissions, or state machines
0 changes to C11 business implementation
0 C12 writes to configAudit or C04-C11 domain slices
0 UI-012 audit identities in C12 production/test artifacts
exactly 8 C12 UI013 PNG files with exact dimensions
```

- [ ] **Step 4: Write handoff**

Document branch/base/final HEAD, UI-013 correction, API-024 identity, strict DO-013 fields, `id` versus envelope `auditLogId`, Store/API separation, result categories, idempotent-zero rule, reset, stable exports, test counts, screenshots, coverage limitation, known diagnostics, full commit chain, and C13 starting instructions.

- [ ] **Step 5: Commit evidence and handoff**

```powershell
git add docs/evidence/C12 docs/handoffs/C12-audit-trail.md
git commit -m "docs(c12): add audit trail evidence and handoff"
```

- [ ] **Step 6: Final cleanliness verification**

```powershell
git status --porcelain=v1
git log --oneline 507a715046dc7e9858e638b07bb56e920e70da37..HEAD
git rev-list --min-parents=2 --count 507a715046dc7e9858e638b07bb56e920e70da37..HEAD
```

Expected: empty porcelain status, complete intentional C12 commit chain, merge count `0`; do not merge or push.
