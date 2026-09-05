# C11 Reporting Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UI-011 as a deterministic reporting dashboard that projects strict DO-012 reports, derives operational metrics from the shared C04-C10 runtime, and refreshes one report snapshot without mutating upstream business objects.

**Architecture:** Add an isolated `src/features/reporting/**` slice for strict DO-012 projection, query parsing, deterministic metrics, API-020 transport validation, feature-local workflow, and report refresh commands. Domain facts remain in the existing shared Store; API-020 validates transport/identity only, while a successful local command atomically updates one DO-012 report plus C11 audit and leaves every upstream slice byte-equivalent.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, ECharts 6.1.0, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start point: `demo/c10-offline-sync` at `7fb529c50059b39f088859d42f421d423e7780f7`.
- Work branch: `demo/c11-reporting-dashboard`; do not merge or push.
- Read before editing: `docs/handoffs/C10-offline-sync.md`, `docs/superpowers/specs/2026-07-22-c11-reporting-dashboard-design.md`, this plan.
- Frozen route/page/E2E: `/reports/operations`, `src/pages/reports/OperationReportPage.tsx`, `e2e/ui-011-reports-operations.spec.ts`.
- Never modify `docs/baseline/**`, dependency manifests/lockfiles, public contracts, public errors, permission catalog, state-machine catalog, or C10 business implementation.
- Never call or implement API-021 `/mock/reports/export`; do not render export/print controls.
- DO-012 fields are exactly `id`, `reportType`, `period`, `generateStatus`, `metrics`, `generatedAt`; do not add `reportId`, `version`, or `updatedAt` to the domain object.
- API-020 responses validate transport and identity only; never overwrite Store facts from response items.
- C11 may update only one target DO-012 report, C11 workflow, and C11 command audit. All C04-C10 upstream slices remain unchanged.
- All metrics and timestamps are deterministic and use current Store data plus `session.demoTime`; never use real current time, randomness, or an external service.
- C11 non-React primary TypeScript diagnostics must be 0. Global existing React/JSX diagnostics are classified honestly.
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

### Task 1: C11 preflight evidence

**Files:**
- Create: `docs/evidence/C11/preflight.md`
- Create: `docs/evidence/C11/tsc-before.txt`

**Interfaces:**
- Consumes: clean C10 base, approved C11 design, six frozen hashes.
- Produces: measured pre-implementation baseline and contract gate record.

- [ ] **Step 1: Verify branch ancestry, worktree, and topology**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor 7fb529c50059b39f088859d42f421d423e7780f7 HEAD
git rev-list --min-parents=2 --count 7fb529c50059b39f088859d42f421d423e7780f7..HEAD
```

Expected: `demo/c11-reporting-dashboard`, only approved C11 documentation commits after the base, ancestor exit 0, merge count `0`.

- [ ] **Step 2: Verify six frozen hashes**

Run `Get-FileHash -Algorithm SHA256` for the six Global Constraints files and compare lowercase hashes exactly. Expected: `6/6 MATCH`; any mismatch stops at the contract gate.

- [ ] **Step 3: Capture measured baseline**

Run:

```powershell
node --version
pnpm --version
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
pnpm test:e2e
```

Save complete TypeScript output to `docs/evidence/C11/tsc-before.txt`. Record exit codes, actual Vitest count, build module count, Playwright count, C10 handoff comparison, authority resolution, and known React/JSX diagnostic classification in `preflight.md`.

- [ ] **Step 4: Commit preflight**

```powershell
git add docs/evidence/C11/preflight.md docs/evidence/C11/tsc-before.txt
git commit -m "test(c11): record reporting dashboard preflight"
```

---

### Task 2: DO-012 projection, query parsing, and deterministic metrics

**Files:**
- Create: `src/features/reporting/reportTypes.ts`
- Create: `src/features/reporting/reportProjection.ts`
- Create: `src/features/reporting/reportMetrics.ts`
- Create: `src/features/reporting/reportQueries.ts`
- Create: `src/features/reporting/index.ts`
- Create: `src/features/reporting/__tests__/reportProjection.test.ts`
- Create: `src/features/reporting/__tests__/reportMetrics.test.ts`
- Create: `src/features/reporting/__tests__/reportQueries.test.ts`
- Create: `docs/evidence/C11/report-core-red.txt`

**Interfaces:**
- Produces: `parseReportQuery`, `selectReportLedger`, `deriveReportMetrics`, `selectReportDashboard`.
- Produces types: `ReportQueryContext`, `ReportLedgerItem`, `ReportMetricSnapshot`, `ReportRate`, `ReportDistribution`, `ReportDashboard`.

- [ ] **Step 1: Write failing query tests**

Use exact assertions:

```ts
expect(parseReportQuery(
  '?reportId=RP-002&reportType=DAILY&period=2026-07-17&generateStatus=FAILED&scenarioId=SCN-01&from=monitor',
)).toEqual({
  reportId: 'RP-002', reportType: 'DAILY', period: '2026-07-17',
  generateStatus: 'FAILED', scenarioId: 'SCN-01', from: 'monitor',
});
expect(parseReportQuery('?reportType=UNKNOWN&generateStatus=DONE&reportId=%00%20')).toEqual({});
```

- [ ] **Step 2: Write failing projection tests**

Assert exact six DO-012 keys, table filters, selected report, source labels, data scope before filtering, empty distinctions, and deep freezing:

```ts
expect(Object.keys(ledger.items[0]!.report).sort()).toEqual(
  ['generateStatus', 'generatedAt', 'id', 'metrics', 'period', 'reportType'].sort(),
);
expect(ledger.items.map(({ report }) => report.id)).toEqual(['RP-001', 'RP-002', 'RP-003']);
expect(Object.isFrozen(ledger.items[0]!.report.metrics)).toBe(true);
```

- [ ] **Step 3: Write failing metric tests**

For fresh SCN-01 fixtures assert:

```ts
expect(metrics.kpis).toMatchObject({
  planTotal: 3,
  confirmedPlanCount: 0,
  appliedRecommendationCount: 0,
  generatedWorkOrderCount: 12,
  dispatchedWorkOrderCount: 5,
  exceptionCount: 5,
  interlockCount: 4,
  mergedOfflinePacketCount: 0,
});
expect(metrics.rates.map(({ key, value }))
  .toEqual([
    ['planConfirmationRate', 0],
    ['taskDecompositionRate', 100],
    ['dispatchRate', 42],
    ['exceptionClosureRate', 20],
    ['offlineMergeRate', 0],
  ]);
```

Also assert safe zero denominators, confirmed recommendation drafts, each frozen enum distribution including zero values, formula/source text, deterministic ordering, and unchanged input state.

- [ ] **Step 4: Run RED and save evidence**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportProjection.test.ts src/features/reporting/__tests__/reportMetrics.test.ts src/features/reporting/__tests__/reportQueries.test.ts
```

Save the expected missing-module failure to `docs/evidence/C11/report-core-red.txt`.

- [ ] **Step 5: Implement parser, projection, and metrics**

Use exact signatures:

```ts
export function parseReportQuery(input: string | URLSearchParams): ReportQueryContext;
export function selectReportLedger(
  state: DemoRootState,
  context: ReportQueryContext,
): ReportLedger;
export function deriveReportMetrics(state: DemoRootState): ReportMetricSnapshot;
export function selectReportDashboard(
  state: DemoRootState,
  context: ReportQueryContext,
): ReportDashboard;
```

Use these metric rules:

```ts
const confirmedPlanCount = plans.filter(({ status }) =>
  status === 'CONFIRMED' || status === 'DECOMPOSED').length;
const dispatched = new Set([
  'DISPATCHED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'COMPLETED', 'PAUSED',
]);
const taskPlanIds = new Set(workOrders.map(({ planId }) => planId));
const percent = (numerator: number, denominator: number) =>
  denominator === 0 ? 0 : Math.round((numerator / denominator) * 100);
```

Validate C05 drafts through `recommendationDraftSchema.safeParse` and count only `CONFIRMED`. Project distributions in the frozen enum declaration order. Return deeply frozen copies only.

- [ ] **Step 6: Run GREEN and commit**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportProjection.test.ts src/features/reporting/__tests__/reportMetrics.test.ts src/features/reporting/__tests__/reportQueries.test.ts
git add src/features/reporting docs/evidence/C11/report-core-red.txt
git commit -m "feat(c11): add report projections and metrics"
```

---

### Task 3: API-020 Gateway and C11 fault injection

**Files:**
- Create: `src/features/reporting/reportGateway.ts`
- Create: `src/features/reporting/__tests__/reportGateway.test.ts`
- Modify: `src/features/reporting/index.ts`
- Modify: `src/mocks/handlers.ts`
- Modify: `src/mocks/__tests__/handlers.test.ts`

**Interfaces:**
- Produces: `ReportGateway`, `ReportGatewayQuery`, `ReportGatewayResult`, `createReportGateway`.
- Method: `listReports(query?): Promise<ReportGatewayResult>`.

- [ ] **Step 1: Write failing Gateway tests**

Cover exact URL encoding, success/error envelopes, strict DO-012 items, API identity, operation identity, expected scenario identity, optional report identity, network rejection, and malformed response:

```ts
await gateway.listReports({
  reportType: 'DAILY', period: '2026-07-17', dimensions: ['workArea'],
  expectedScenarioId: 'SCN-01', expectedReportId: 'RP-002',
});
expect(fetcher).toHaveBeenCalledWith(
  '/mock/reports?type=DAILY&period=2026-07-17&dimensions=workArea',
  expect.objectContaining({ headers: expect.any(Object) }),
);
```

Assert API-021 is never referenced by production Gateway or tests.

- [ ] **Step 2: Verify RED**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportGateway.test.ts
```

Expected: missing Gateway module failure.

- [ ] **Step 3: Implement strict API-020 Gateway**

Define data validation as:

```ts
const reportGatewayDataSchema = z.object({
  apiId: z.literal('API-020'),
  operationId: z.literal('GET_mock_reports'),
  now: z.string().min(1),
  scenarioId: z.string().min(1),
  items: do012Schema.array(),
}).strict();
```

After schema parsing, compare `expectedScenarioId` and ensure `expectedReportId` exists when supplied. Parse public error envelopes unchanged. Do not expose any method for API-021.

- [ ] **Step 4: Add C11 internal fault injection**

In `src/mocks/handlers.ts`, parallel C08-C10 handling for API-020 only:

```ts
if (contract.apiId === 'API-020' && request.headers.get('x-demo-c11-fault') === 'network') {
  return HttpResponse.error();
}
if (contract.apiId === 'API-020' && request.headers.get('x-demo-c11-fault') === 'malformed') {
  return HttpResponse.json({ ok: true, data: { malformed: true } }, { status: 200 });
}
```

Do not change API-021 behavior or public contracts.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportGateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/reporting src/mocks/handlers.ts src/mocks/__tests__/handlers.test.ts
git commit -m "feat(c11): add report api gateway"
```

---

### Task 4: Report workflow and refresh command

**Files:**
- Create: `src/features/reporting/reportCommands.ts`
- Create: `src/features/reporting/reportRuntime.ts`
- Create: `src/features/reporting/__tests__/reportCommands.test.ts`
- Create: `src/features/reporting/__tests__/reportRuntime.test.ts`
- Modify: `src/features/reporting/index.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: `createReportCommandService`, `createReportWorkflowStore`.
- Runtime exports `runtime.reporting.gateway`, `.workflow`, `.commands` and `useReportWorkflow(selector)`.
- Command method: `refreshReport(input): Promise<CommandResult>` and `resetCommandState()`.

- [ ] **Step 1: Write failing workflow tests**

Use exact initial state and transitions:

```ts
expect(workflow.getState()).toEqual({
  filters: {}, metricsDrawerOpen: false, snapshotSequence: 0,
});
workflow.selectReport('RP-002');
workflow.setFilters({ reportType: 'DAILY', period: '2026-07-17', generateStatus: 'FAILED' });
workflow.setMetricsDrawerOpen(true);
expect(workflow.getState()).toMatchObject({
  selectedReportId: 'RP-002', metricsDrawerOpen: true,
});
workflow.reset();
expect(workflow.getState()).toEqual({
  filters: {}, metricsDrawerOpen: false, snapshotSequence: 0,
});
```

- [ ] **Step 2: Write failing command tests**

Use a real Store and exact success assertions:

```ts
const upstreamBefore = upstreamSnapshot(store.getState());
const before = reportOf(store, 'RP-002');
const result = await commands.refreshReport({
  commandId: 'CMD-C11-001', reportId: 'RP-002',
  expectedGeneratedAt: before.generatedAt, reason: '刷新日报快照',
});
expect(result).toMatchObject({ ok: true });
expect(reportOf(store, 'RP-002')).toMatchObject({
  generateStatus: 'SUCCESS', generatedAt: store.getState().session.demoTime,
});
expect(reportOf(store, 'RP-002').metrics).toEqual(deriveReportMetrics(store.getState()).flatMetrics);
expect(upstreamSnapshot(store.getState())).toEqual(upstreamBefore);
```

Add separate tests for `report:generate` denial, data scope denial, blank reason, unknown report, stale `expectedGeneratedAt`, second-read drift, idempotent `commandId`, one audit per first result, error feedback, reset, and no API-021 calls.

- [ ] **Step 3: Verify RED**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportCommands.test.ts src/features/reporting/__tests__/reportRuntime.test.ts src/runtime/__tests__/runtime.test.tsx
```

Expected: missing command/runtime exports.

- [ ] **Step 4: Implement feature-local workflow**

State is exact and UI-only:

```ts
type ReportWorkflowState = Readonly<{
  selectedReportId?: string;
  filters: Readonly<{ reportType?: ReportType; period?: string; generateStatus?: GenerateStatus }>;
  metricsDrawerOpen: boolean;
  pendingReportId?: string;
  snapshotSequence: number;
  lastFeedback?: Readonly<{
    ok: boolean; commandId: string; traceId: string; auditLogId: string;
    message: string; errorCode?: PublicErrorCode; idempotent: boolean;
  }>;
}>;
```

- [ ] **Step 5: Implement atomic refresh command**

The commit body is exact:

```ts
store.replaceDomainState((candidate) => {
  const index = candidate.report.reports.findIndex(({ id }) => id === input.reportId);
  if (index < 0) throw new Error(`Unknown report: ${input.reportId}`);
  const current = do012Schema.parse(candidate.report.reports[index]);
  if (current.generatedAt !== input.expectedGeneratedAt) throw new ReportVersionConflict();
  const snapshot = deriveReportMetrics(candidate);
  candidate.report.reports[index] = do012Schema.parse({
    ...current,
    generateStatus: 'SUCCESS',
    metrics: structuredClone(snapshot.flatMetrics),
    generatedAt: candidate.session.demoTime,
  });
  candidate.configAudit.commandAudit.push(buildC11Audit(current, candidate.report.reports[index]));
});
```

Authorize `report:generate`, require AREA-A/global visibility, compare `expectedGeneratedAt` before and inside commit, and map stale data to `DEMO-VERSION-001`. Cache the frozen result by `commandId` before returning. A failure must not alter the target report, upstream slices, or snapshot sequence.

- [ ] **Step 6: Wire shared runtime and reset**

Create `reporting` gateway/workflow/commands in `createDemoRuntime`, add workflow and command-state reset to `onSuccessfulReset`, expose `useReportWorkflow` through `useSyncExternalStore`, and assert deterministic reset IDs `CMD-C11-001`, `TRACE-C11-001`, `AUD-C11-001`.

- [ ] **Step 7: Run GREEN and commit**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportCommands.test.ts src/features/reporting/__tests__/reportRuntime.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/reporting src/runtime
git commit -m "feat(c11): add report commands"
```

---

### Task 5: UI-011 reporting dashboard page

**Files:**
- Modify: `src/pages/reports/OperationReportPage.tsx`
- Create: `src/features/reporting/reporting.css`
- Create: `src/features/reporting/components/ReportContextHeader.tsx`
- Create: `src/features/reporting/components/ReportKpiGrid.tsx`
- Create: `src/features/reporting/components/ReportEfficiencyPanel.tsx`
- Create: `src/features/reporting/components/ReportDistributionGrid.tsx`
- Create: `src/features/reporting/components/ReportLedger.tsx`
- Create: `src/features/reporting/components/ReportDetail.tsx`
- Create: `src/features/reporting/components/ReportCommandFeedback.tsx`
- Create: `src/features/reporting/components/ReportMetricDrawer.tsx`
- Create: `src/pages/__tests__/reportingTestHarness.tsx`
- Create: `src/pages/__tests__/OperationReportPage.render.test.tsx`
- Create: `src/pages/__tests__/OperationReportPage.action.test.tsx`
- Create: `src/pages/__tests__/OperationReportPage.permission.test.tsx`
- Create: `docs/evidence/C11/report-page-red.txt`

**Interfaces:**
- Produces: accessible UI-011 overview, filters, distributions, ledger/detail, metric drawer, refresh action, reset, and feedback.
- Page reads `selectReportDashboard`, `useDemoSelector`, `useReportWorkflow`, and `runtime.reporting` only.

- [ ] **Step 1: Write failing render tests**

Assert page identity and route, deterministic disclosure, 8 KPI labels, 5 rate labels, 4 distribution groups, 3 DO-012 rows, all 6 report fields, source/formula labels, loading, Store empty, filter empty, forbidden, not-found, network, malformed, and business error.

- [ ] **Step 2: Write failing action/permission tests**

Assert filtering, row selection, metric drawer, refresh success, feedback IDs, pending disabled state, stale conflict, reset, and no partial write:

```ts
await user.selectOptions(screen.getByLabelText('报表类型'), 'DAILY');
expect(screen.getByRole('row', { name: /RP-002/ })).toBeInTheDocument();
expect(screen.queryByRole('row', { name: /RP-001/ })).not.toBeInTheDocument();
await user.click(screen.getByRole('button', { name: '生成或刷新快照' }));
expect(await screen.findByText('报表快照已刷新')).toBeInTheDocument();
```

For a role without `report:generate`, the page remains visible but the refresh button is disabled with an explanatory tooltip. For missing `report:view`, render safe not-found and do not call API-020.

- [ ] **Step 3: Save RED evidence**

```powershell
pnpm vitest run src/pages/__tests__/OperationReportPage.render.test.tsx src/pages/__tests__/OperationReportPage.action.test.tsx src/pages/__tests__/OperationReportPage.permission.test.tsx
```

Save the meaningful scaffold failure to `docs/evidence/C11/report-page-red.txt`.

- [ ] **Step 4: Implement page and components**

Use Ant Design cards/table/progress/drawer and ECharts through the already installed package. Every chart receives a textual summary and `aria-label`; `prefers-reduced-motion` disables chart animation. Keep API-020 read state independent from Store projection so transport failure never clears the dashboard facts.

- [ ] **Step 5: Implement responsive CSS**

- Above 1360px: eight KPI cards in four columns, efficiency plus distributions in a `minmax(300px, .8fr) minmax(640px, 1.7fr)` grid, ledger/detail in `minmax(620px, 1.6fr) minmax(320px, .8fr)`.
- At 1360px or below: KPI cards in four columns with reduced padding; efficiency and the first two distributions remain before the fold; ledger/detail stack below.
- At 900px or below: KPI cards in two columns and every chart/table container uses `min-width: 0`; no horizontal document overflow.

- [ ] **Step 6: Run GREEN and commit**

```powershell
pnpm vitest run src/pages/__tests__/OperationReportPage.render.test.tsx src/pages/__tests__/OperationReportPage.action.test.tsx src/pages/__tests__/OperationReportPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx
git add src/features/reporting src/pages/reports/OperationReportPage.tsx src/pages/__tests__ docs/evidence/C11/report-page-red.txt
git commit -m "feat(c11): implement reporting dashboard page"
```

---

### Task 6: Reporting integration and upstream immutability

**Files:**
- Create: `src/features/reporting/__tests__/reportIntegration.test.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: verified API/Store separation, reset determinism, optimistic concurrency, audit, and upstream immutability.

- [ ] **Step 1: Write integration tests**

Test this sequence:

```ts
const upstreamBefore = structuredClone({
  plan: state.plan, recommendation: state.recommendation,
  workOrder: state.workOrder, resource: state.resource, vehicle: state.vehicle,
  exception: state.exception, interlock: state.interlock, offline: state.offline,
});
// API-020 returns strict items but does not replace report slice.
// RP-003 refresh succeeds once and updates only DO-012 + C11 audit/workflow.
// Reusing stale generatedAt returns DEMO-VERSION-001 with no additional write.
// resetScenario('SCN-01') restores all reports and clears C11 workflow/id caches.
expect(currentUpstream()).toEqual(upstreamBefore);
```

Also assert API-020 network/malformed/business errors preserve report and upstream snapshots.

- [ ] **Step 2: Implement only fixes proven by RED**

Confine fixes to reporting exports and `DemoRuntimeContext.tsx`. Do not edit fixtures, API-021, permissions, transitions, baseline, C10, or upstream feature logic.

- [ ] **Step 3: Run GREEN and commit**

```powershell
pnpm vitest run src/features/reporting/__tests__/reportIntegration.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/reporting src/runtime
git commit -m "test(c11): verify reporting integration"
```

---

### Task 7: C11 Playwright flows and eight screenshots

**Files:**
- Create: `e2e/ui-011-reports-operations.spec.ts`
- Create: `docs/evidence/C11/report-e2e-red.txt`
- Create: `docs/evidence/C11/screenshot-index.md`
- Create: exactly eight `docs/evidence/C11/C11-UI011-*.png`

**Interfaces:**
- Produces: deterministic UI-011 E2E coverage and exactly eight inspected screenshots.

- [ ] **Step 1: Write E2E first and save RED**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-011-reports-operations.spec.ts
```

Save the expected first meaningful failure to `docs/evidence/C11/report-e2e-red.txt` before any E2E-driven implementation fix.

- [ ] **Step 2: Implement C11 E2E flows**

1. SCN-01 overview shows eight KPI, five rates, distributions, three reports, and deterministic disclosure.
2. Filter to DAILY/2026-07-17/FAILED, select RP-002, inspect strict fields and metric formulas.
3. Refresh RP-002 and assert SUCCESS, generatedAt Demo time, one C11 audit, and unchanged serialized C04-C10 slices.
4. Verify forbidden, stale generatedAt, API-020 network, malformed, and forced business errors create no report/upstream partial write and recover after reset/reload.

- [ ] **Step 3: Generate exact screenshots**

At 1440×900 and 1280×720 capture:

```text
C11-UI011-SCN01-OVERVIEW-1440x900.png
C11-UI011-SCN01-OVERVIEW-1280x720.png
C11-UI011-SCN01-FILTERED-1440x900.png
C11-UI011-SCN01-FILTERED-1280x720.png
C11-UI011-SCN01-GENERATED-1440x900.png
C11-UI011-SCN01-GENERATED-1280x720.png
C11-UI011-SCN01-METRICS-1440x900.png
C11-UI011-SCN01-METRICS-1280x720.png
```

- [ ] **Step 4: Inspect images and raw dimensions**

Use `view_image` on all eight files. Verify no horizontal overflow, clipping, overlap, missing Chinese glyphs, stale loading masks, chart cutoff, hidden primary KPI, or obscured actions. Read PNG dimensions programmatically and require exact filename dimensions.

- [ ] **Step 5: Run C11 E2E GREEN and commit**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-011-reports-operations.spec.ts
git add e2e/ui-011-reports-operations.spec.ts docs/evidence/C11/report-e2e-red.txt docs/evidence/C11/screenshot-index.md docs/evidence/C11/C11-UI011-*.png
git commit -m "test(c11): cover reporting dashboard demo flows"
```

---

### Task 8: Full verification, evidence, and C11→C12 handoff

**Files:**
- Create: `docs/evidence/C11/coverage.json`
- Create: `docs/evidence/C11/verification.md`
- Create: `docs/evidence/C11/tsc-after.txt`
- Create: `docs/handoffs/C11-reporting-dashboard.md`

**Interfaces:**
- Produces: auditable final verification and C12 handoff.

- [ ] **Step 1: Run focused coverage**

```powershell
pnpm vitest run src/features/reporting src/pages/__tests__/OperationReportPage.render.test.tsx src/pages/__tests__/OperationReportPage.action.test.tsx src/pages/__tests__/OperationReportPage.permission.test.tsx --coverage.enabled --coverage.reporter=json --coverage.reporter=text
```

Copy the generated JSON report to `docs/evidence/C11/coverage.json` without editing dependency manifests.

- [ ] **Step 2: Run fresh full verification**

```powershell
node_modules\.bin\tsc.CMD --noEmit
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-011-reports-operations.spec.ts
pnpm test:e2e
git diff --check
```

Save TypeScript output to `tsc-after.txt`. Record actual tests, failures, modules, timings, screenshot dimensions, and diagnostic classes in `verification.md`.

- [ ] **Step 3: Run frozen and forbidden-scope checks**

Require:

```text
6/6 frozen hashes match
0 merge commits since C10 base
0 changes under docs/baseline
0 changes to package.json/pnpm-lock.yaml
0 changes to public contracts, permissions, or state machines
0 changes to C10 business implementation
0 production references to /mock/reports/export or API-021 in C11
0 C11 writes to C04-C10 upstream slices
exactly 8 C11 PNG files
```

- [ ] **Step 4: Write handoff**

Document stable exports, route, API-020 identity, strict DO-012 fields, metric formulas, refresh atomicity, reset semantics, test counts, screenshot names, known React/JSX baseline diagnostics, API-021 non-implementation, and the exact C11 HEAD for C12.

- [ ] **Step 5: Commit evidence and handoff**

```powershell
git add docs/evidence/C11 docs/handoffs/C11-reporting-dashboard.md
git commit -m "docs(c11): add reporting evidence and handoff"
```

- [ ] **Step 6: Final cleanliness verification**

```powershell
git status --porcelain=v1
git log --oneline 7fb529c50059b39f088859d42f421d423e7780f7..HEAD
git rev-list --min-parents=2 --count 7fb529c50059b39f088859d42f421d423e7780f7..HEAD
```

Expected: empty porcelain status, the complete intentional C11 commit chain, merge count `0`; do not merge or push.
