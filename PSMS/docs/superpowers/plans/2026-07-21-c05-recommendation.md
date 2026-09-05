# C05 接车计划推荐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a customer-demo-ready UI-003 reception recommendation workspace that calculates deterministic track/window recommendations, explains and adjusts them, confirms the selected recommendation through API-006, and opens the existing UI-004 route without implementing task decomposition.

**Architecture:** Extend the single C04 `DemoRuntimeProvider` with a strict C05 gateway, feature-local Zod recommendation draft, pure rule engine, three required selectors, page-only workflow state, and a command service backed by the C03 executor. API-005 validates the frozen transport response before the rule engine projects Store Plan/Track/WorkOrder facts into `recommendation.drafts`; API-006 confirms a version-checked draft atomically and writes an RC-04 audit action. A narrowly bounded Mock runtime marker lets only the same SCN-02 plan's API-005/API-006 calls recognize a successfully resolved API-004 missing-field fault.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start from clean `demo/c04-plan-entry`; C04 source handoff is `55944a18e22eecef437ea6394c543e9177380a2b` and C05 design is `5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61`. Implement on `demo/c05-recommendation`.
- Read `docs/handoffs/C04-plan-entry.md` and `docs/superpowers/specs/2026-07-21-c05-recommendation-design.md` before editing.
- Never modify `docs/baseline/**`, `package.json`, or `pnpm-lock.yaml`; do not install, remove, or upgrade packages.
- Frozen SHA-256 values:

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

- Preserve the 14 domain schemas, 25 API catalog entries, 9 public error codes, 12 Store slices, 13 roles, 45 permission codes, 13 route paths, strict DO-013 wrapper, and all existing C04 public behavior.
- The only approved Mock semantic supplement is the exact SCN-02 `resolvedFaultObjects` rule in Task 2. It may not clear other faults or change any public contract.
- UI-003 is restricted to exact role `DISPATCHER`. Calculation requires `plan:recommend`; a non-first candidate also requires `plan:adjust`; final confirmation requires `plan:recommend` and `plan:confirm`.
- UI-004 remains the existing scaffold. C05 may only link to `/dispatch/plans/:planId/tasks`; do not implement API-007, decomposition, work-order generation, or UI-004 business content.
- Domain facts live only in the existing C03 Store. Recommendation domain results live only in `recommendation.drafts`; the feature workflow contains IDs and drawer/error state only.
- Keep `RecommendationSlice` as `drafts: Record<string, unknown>`. Parse with the C05 strict Zod schema before every write and selector read.
- Every API-005/API-006 success and failure envelope is strictly parsed. A valid Mock success proves transport and matching plan identity only; it never overwrites Store Plan/Track.
- All writes use the C03 command executor and exactly one `replaceDomainState` per successful C05 command. Failure, denial, or version conflict leaves Store and form input intact.
- API-006 body is exactly `{ trackNo, window, reason? }`. Reviewer, versions, affected work-order IDs, and business action stay only in the C05 command payload.
- Use SM-007 `execute` as the technical state-machine command. Audit action is RC-01 or RC-04 through an audit-only cloned command; do not change the C03 state-machine catalog.
- Treat all Plan fixtures as AREA-A. Apply session data scope before object lookup, counts, candidate derivation, conflict projection, and work-order association.
- Use only `session.demoTime` and resettable counters for generated time/IDs. Do not use `Date.now`, `new Date()` without an input timestamp, `Math.random`, random UUIDs, or localStorage as domain truth.
- Preserve C01 UI-003 smoke markers: exact `UI-003`, heading `接车计划推荐`, and `当前路由：/dispatch/plans/<planId>/recommendation`.
- Existing C04 baseline is 32 Vitest files / 257 tests, build 1,860 modules, and Playwright 19/19. Global TypeScript has 286 known React declaration diagnostics; C05 non-React primary diagnostics must be 0 and the global result must be reported honestly.
- Use TDD and save meaningful red outputs for recommendation core, compatibility, page, and actions before implementation.

---

### Task 1: Freeze the C05 branch and preflight evidence

**Files:**
- Create: `docs/evidence/C05/preflight.md`
- Create: `docs/evidence/C05/tsc-before.txt`

**Interfaces:**
- Consumes: clean C04 branch, C04 handoff, C05 design, and six frozen baseline files.
- Produces: isolated `demo/c05-recommendation` branch and an evidence-backed before-state used by all later gates.

- [ ] **Step 1: Verify the exact base and create the work branch**

Run:

```powershell
git status --short --branch
git rev-parse HEAD
git merge-base --is-ancestor 55944a18e22eecef437ea6394c543e9177380a2b HEAD
git merge-base --is-ancestor 5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61 HEAD
git switch -c demo/c05-recommendation
```

Expected: the starting worktree is clean; both ancestor checks return exit code 0; the new current branch is `demo/c05-recommendation`. If the branch already exists, inspect its log and status and continue only when it is the intended clean C05 branch; never overwrite it.

- [ ] **Step 2: Verify all six hashes with one fixed script**

```powershell
$expected = @{
  'docs/baseline/README.md' = 'bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34'
  'docs/baseline/package-baseline.json' = 'ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810'
  'docs/baseline/openapi.yaml' = '1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83'
  'docs/baseline/demo-fixtures.json' = 'b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba'
  'docs/baseline/page-task-matrix.csv' = 'c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b'
  'docs/baseline/traceability.csv' = 'a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2'
}
$failed = foreach ($path in $expected.Keys) {
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
  [pscustomobject]@{ Path = $path; Expected = $expected[$path]; Actual = $actual; Match = $actual -eq $expected[$path] }
}
$failed | Format-Table -AutoSize
if ($failed.Match -contains $false) { throw 'C05 frozen baseline mismatch.' }
```

Expected: six rows with `Match=True`. Any mismatch is a hard stop with expected/actual/status/recent log; do not repair or regenerate a baseline.

- [ ] **Step 3: Capture the C04 regression baseline**

```powershell
New-Item -ItemType Directory -Force docs/evidence/C05 | Out-Null
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C05/tsc-before.txt
pnpm test -- --run
pnpm build
pnpm test:e2e
```

Expected: TypeScript exits 1 with the known 286 React declaration diagnostics; Vitest is 32 files / 257 tests; build succeeds; Playwright is 19/19. If Vitest, build, or Playwright regresses, restore the C04 baseline before C05 work.

- [ ] **Step 4: Write the preflight record**

`preflight.md` must contain the branch, full HEAD, both ancestor checks, six actual hashes, Node/pnpm versions, command timestamps, exit codes, Vitest/build/E2E counts, and the TypeScript diagnostic category counts:

```markdown
# C05 Preflight

- Branch: `demo/c05-recommendation`
- C04 source ancestor: `55944a18e22eecef437ea6394c543e9177380a2b`
- C05 design ancestor: `5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61`
- Frozen baseline: `6/6 MATCH`
- Vitest baseline: `32 files / 257 tests PASS`
- Build baseline: `PASS`
- Playwright baseline: `19/19 PASS`
- TypeScript baseline: `exit 1; TS2604=13, TS7016=51, TS7026=222; total=286`
```

Use actual measured values if the verified environment differs; do not copy a pass claim over a failed command.

- [ ] **Step 5: Commit only the preflight evidence**

```powershell
git add docs/evidence/C05/preflight.md docs/evidence/C05/tsc-before.txt
git commit -m "test(c05): record recommendation preflight"
```

Expected: one evidence-only commit and a clean worktree.

---

### Task 2: Add the approved SCN-02 resolved-fault boundary

**TDD gate:** Gate A compatibility red. Do not start recommendation code until this narrow rule and all C02/C04 handler regressions are green.

**Files:**
- Modify: `src/mocks/scenarios.ts`
- Modify: `src/mocks/handlers.ts`
- Modify: `src/mocks/__tests__/scenarios.test.ts`
- Modify: `src/mocks/__tests__/handlers.test.ts`
- Create: `docs/evidence/C05/compatibility-red.txt`

**Interfaces:**
- Consumes: C04 `hasCompleteScenarioSupplements`, immutable fixture snapshots, API-004/005/006 catalog entries.
- Produces: `MockRuntime.resolveFaultFields`, `MockRuntime.hasResolvedFaultFields`, and exact reset semantics for same-object SCN-02 continuation.

- [ ] **Step 1: Write runtime marker tests before production changes**

Add tests with this public contract:

```ts
const runtime = createMockRuntime();
runtime.reset('SCN-02');
expect(runtime.getResolvedFaultObjects()).toEqual({});

runtime.resolveFaultFields('PLAN-002', ['trackNo']);
expect(runtime.hasResolvedFaultFields('PLAN-002', ['trackNo'])).toBe(true);
expect(runtime.hasResolvedFaultFields('PLAN-001', ['trackNo'])).toBe(false);

runtime.reset('SCN-02');
expect(runtime.getResolvedFaultObjects()).toEqual({});
```

Also prove returned arrays/objects are clones: mutating the returned value must not change runtime state.

Add a projection assertion required by the strict API response: the default `getProjectedSnapshot()` must still omit PLAN-002.trackNo, while `getProjectedSnapshot({ restoreResolvedObjectId: 'PLAN-002' })` restores only resolved fields for that object. PLAN-001 and every unresolved field remain unchanged.

- [ ] **Step 2: Write handler allow/deny matrix tests**

In `handlers.test.ts`, use the existing MSW server and exact requests:

```ts
await fetch('/mock/plans/PLAN-002/confirm', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ supplements: { trackNo: 'T1' } }),
});
await fetch('/mock/plans/PLAN-002/recommendation?inputVersion=2');
await fetch('/mock/plans/PLAN-002/recommendation/confirm', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ trackNo: 'T1', window: '2026-07-16T10:00:00+08:00/2026-07-16T12:00:00+08:00' }),
});
```

Assert API-005 and API-006 return 200 only after the complete API-004 for PLAN-002. Assert `TOS-EXT-002` remains for: API-005 before API-004, incomplete API-004, PLAN-001, a reset runtime, a non-SCN-02 runtime, and any other scenario error. Assert API-004 invalid Schema still returns 400 `DEMO-SCENARIO-001` and does not mark a resolution.

- [ ] **Step 3: Save the compatibility red**

```powershell
node_modules\.bin\vitest.CMD run src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts *>&1 | Tee-Object docs/evidence/C05/compatibility-red.txt
```

Expected: new tests fail because the marker methods and API-005/API-006 bypass do not exist; existing tests remain green.

- [ ] **Step 4: Implement resettable marker state**

Extend `RuntimeState` and `MockRuntime` exactly around field names rather than arbitrary fault booleans:

```ts
type RuntimeState = {
  fixtures: FixtureSnapshot;
  activeScenarioId: string;
  clock: string;
  ids: EnvelopeIdSequence;
  forcedFailure?: RuntimeFailure;
  resolvedFaultObjects: Record<string, string[]>;
};

resolveFaultFields(objectId: string, fields: readonly string[]): void {
  const existing = this.state.resolvedFaultObjects[objectId] ?? [];
  this.state.resolvedFaultObjects[objectId] = [...new Set([...existing, ...fields])].sort();
}

hasResolvedFaultFields(objectId: string, fields: readonly string[]): boolean {
  const resolved = new Set(this.state.resolvedFaultObjects[objectId] ?? []);
  return fields.length > 0 && fields.every((field) => resolved.has(field));
}

getResolvedFaultObjects(): Readonly<Record<string, readonly string[]>> {
  return clone(this.state.resolvedFaultObjects);
}
```

Initialize `resolvedFaultObjects: {}` in `createState`; `reset` already replaces the entire state and therefore clears it atomically. Extend the projection signature as follows and skip an omitted field only for the explicitly requested resolved object:

```ts
getProjectedSnapshot(options: { restoreResolvedObjectId?: string } = {}): FixtureSnapshot {
  const projected = this.getSnapshot();
  const scenario = this.getActiveScenario();
  for (const mutation of scenario.fault.mutations) {
    const object = findObject(projected, mutation.objectId);
    if (!object) throw new Error(`Scenario mutation target not found: ${mutation.objectId}`);
    for (const field of mutation.omitFields ?? []) {
      const restored =
        options.restoreResolvedObjectId === mutation.objectId &&
        this.hasResolvedFaultFields(mutation.objectId, [field]);
      if (!restored) delete object[field];
    }
    if (mutation.replaceValues) Object.assign(object, clone(mutation.replaceValues));
  }
  return projected;
}
```

- [ ] **Step 5: Record only a successful complete API-004 and bypass only matching API-005/API-006**

Add helpers that select mutation fields for `validated.pathId`, not every object in the scenario:

```ts
function mutationFieldsFor(scenario: DemoScenario, pathId?: string): string[] {
  if (!pathId) return [];
  return [...new Set(
    scenario.fault.mutations
      .filter(({ objectId }) => objectId === pathId)
      .flatMap(({ omitFields = [] }) => omitFields),
  )].sort();
}

function hasResolvedScenarioFault(
  runtime: MockRuntime,
  contract: ApiContract,
  scenario: DemoScenario,
  validated: ValidatedRequest,
): boolean {
  if (
    scenario.id !== 'SCN-02' ||
    (contract.apiId !== 'API-005' && contract.apiId !== 'API-006') ||
    !validated.pathId
  ) return false;
  return runtime.hasResolvedFaultFields(
    validated.pathId,
    mutationFieldsFor(scenario, validated.pathId),
  );
}
```

In `scenarioFailure`, return no fault when `hasResolvedScenarioFault` is true. After forced-failure and scenario-failure handling succeeds, but before `successResponse`, mark fields only when `contract.apiId === 'API-004'` and `hasCompleteScenarioSupplements(...)` is true. Never mark on invalid, forced-failure, or scenario-failure responses.

In `successData`, request the restored projection only for a resolved API-005/API-006 call:

```ts
const restoreResolvedObjectId = hasResolvedScenarioFault(runtime, contract, scenario, validated)
  ? validated.pathId
  : undefined;
const snapshot = runtime.getProjectedSnapshot({ restoreResolvedObjectId });
```

This is necessary for the API-005/API-006 `items` array to remain a strict DO-001 object after trackNo has been resolved. API-002 and every other API continue using the unresolved scenario projection.

- [ ] **Step 6: Run compatibility and complete Mock regression**

```powershell
node_modules\.bin\vitest.CMD run src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/features/plan-entry/__tests__/commands.test.ts
```

Expected: all pass; no frozen Schema, API catalog, C04 plan flow, or public error behavior changes.

- [ ] **Step 7: Commit the isolated compatibility supplement**

```powershell
git add src/mocks/scenarios.ts src/mocks/handlers.ts src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts docs/evidence/C05/compatibility-red.txt
git commit -m "fix(c05): preserve resolved SCN-02 plan fault"
```

---

### Task 3: Define and test the strict recommendation model and pure rule engine

**TDD gate:** Gate A recommendation core red.

**Files:**
- Create: `src/features/recommendation/types.ts`
- Create: `src/features/recommendation/schemas.ts`
- Create: `src/features/recommendation/ruleEngine.ts`
- Create: `src/features/recommendation/__tests__/schemas.test.ts`
- Create: `src/features/recommendation/__tests__/ruleEngine.test.ts`
- Create: `src/features/recommendation/index.ts`
- Create: `docs/evidence/C05/recommendation-red.txt`

**Interfaces:**
- Consumes: strict C02 `Plan`, `Track`, and `WorkOrder` types plus an injected generation time.
- Produces: `recommendationDraftSchema`, `RecommendationDraft`, `calculateReceptionRecommendation`, fixed rule version, deterministic candidates/exclusions, and immutable results.

- [ ] **Step 1: Write the feature types and failing Schema tests**

Define these exact public types through `z.infer` from strict schemas:

```ts
export const RECOMMENDATION_RULE_VERSION = 'C05-DEMO-RULE-1.0' as const;
export const recommendationStatuses = ['CALCULATED', 'CONFIRMED'] as const;
export const exclusionCodes = [
  'TRACK_BLOCKED',
  'CARGO_INCOMPATIBLE',
  'UNSUPPORTED_OCCUPANCY',
  'INVALID_RELEASE_TIME',
] as const;

export type RecommendationCalculationInput = Readonly<{
  plan: Plan;
  tracks: readonly Track[];
  generatedAt: string;
}>;

export type RecommendationCalculation = Readonly<{
  candidates: readonly RecommendationCandidate[];
  excluded: readonly RecommendationExclusion[];
}>;
```

The strict draft schema fields are:

```ts
{
  planId, draftVersion, status, inputPlanVersion, ruleVersion, generatedAt,
  candidates, excluded, selectedCandidateId?, adjustment?, confirmation?
}
```

Candidate fields are exactly `candidateId`, `trackId`, `trackNo`, `trackVersion`, `occupyStatus`, `windowStart`, `windowEnd`, `score`, `rank`, `recommended`, `scoreBreakdown`, `reasons`, `sourceRefs`. Exclusion fields are exactly `trackId`, `trackNo`, `exclusionCode`, `reason`, `sourceRefs`. Use `.strict()` at every object level and freeze the parsed result.

Use these exact nested shapes:

```ts
scoreBreakdown: {
  availability: number;
  timing: number;
  continuity: number;
  authority: number;
};
adjustment?: {
  originalCandidateId: string;
  finalCandidateId: string;
  reason: string;
  affectedWorkOrderIds: string[];
  reviewerId?: string;
};
confirmation?: {
  actorId: string;
  roleCode: RoleCode;
  confirmedAt: string;
  commandId: string;
  traceId: string;
};
```

`draftVersion`, `trackVersion`, `score`, and `rank` are integers; versions/rank are positive, score is 0..100. `reasons`, `sourceRefs`, and affectedWorkOrderIds are non-empty string arrays where applicable. Add a schema refinement that requires `confirmation` and a valid selectedCandidateId when status is CONFIRMED, forbids confirmation while status is CALCULATED, and requires adjustment.finalCandidateId to equal selectedCandidateId.

Tests must reject: an extra property, invalid status, invalid exclusion code, score outside 0..100, absent trackVersion, a CONFIRMED draft without confirmation, and a selectedCandidateId not present in candidates. Tests must accept one complete CALCULATED and one complete CONFIRMED example.

- [ ] **Step 2: Write exact rule-engine expectations**

With PLAN-001 and the four frozen tracks, assert:

```ts
expect(result.candidates.map(({ trackNo, score, rank }) => [trackNo, score, rank])).toEqual([
  ['T1', 100, 1],
  ['T3', 62, 2],
  ['T2', 52, 3],
]);
expect(result.excluded).toMatchObject([
  { trackNo: 'T4', exclusionCode: 'TRACK_BLOCKED' },
]);
expect(result.candidates[0]).toMatchObject({
  candidateId: 'PLAN-001:TRACK-001:2026-07-16T08:01:00+08:00',
  windowStart: '2026-07-16T08:01:00+08:00',
  windowEnd: '2026-07-16T10:01:00+08:00',
  recommended: true,
});
```

Also test cargo incompatibility before scoring, invalid non-FREE release time, same-score ordering by windowStart then trackNo, preserved two-hour duration, no mutation of inputs, and deep-frozen outputs.

- [ ] **Step 3: Save the core red**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts *>&1 | Tee-Object docs/evidence/C05/recommendation-red.txt
```

Expected: fail because the recommendation model and engine do not exist.

- [ ] **Step 4: Implement exact time/window helpers**

Parse only supplied ISO timestamps. Preserve the original offset string style by formatting with the input plan's offset; do not read system time. The pure calculation is:

```ts
const durationMs = departureMs - arrivalMs;
const candidateStartMs = track.occupyStatus === 'FREE'
  ? arrivalMs
  : Math.max(arrivalMs, Date.parse(track.estimateReleaseTime));
const delayMinutes = Math.max(0, Math.floor((candidateStartMs - arrivalMs) / 60_000));
const timing = Math.max(0, 30 - Math.floor(delayMinutes / 10));
const availability = { FREE: 45, RELEASING: 35, OCCUPIED: 25 }[track.occupyStatus];
const continuity = track.trackNo === plan.trackNo ? 15 : 5;
const authority = plan.sourceSystem === 'RAIL_PLAN' && plan.sourceTime && track.updatedAt ? 10 : 0;
const score = availability + timing + continuity + authority;
```

Reject a plan interval with invalid timestamps or non-positive duration before candidate calculation. `Date.parse(value)` is allowed because the value is an injected domain timestamp; system-clock reads are forbidden.

- [ ] **Step 5: Implement hard exclusions and stable ranking**

Evaluate in this order: BLOCKED, cargo incompatibility, unsupported occupancy, invalid release. Internal exclusion codes never enter `PublicErrorCode`. Sort candidates using:

```ts
candidates.sort(
  (left, right) =>
    right.score - left.score ||
    left.windowStart.localeCompare(right.windowStart) ||
    left.trackNo.localeCompare(right.trackNo),
);
```

Then assign one-based rank and `recommended: rank === 1`. Include score breakdown labels and source refs for Plan and Track. Deep-freeze the complete result.

- [ ] **Step 6: Run focused tests and commit the pure core**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts
git add src/features/recommendation/types.ts src/features/recommendation/schemas.ts src/features/recommendation/ruleEngine.ts src/features/recommendation/index.ts src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts docs/evidence/C05/recommendation-red.txt
git commit -m "feat(c05): add deterministic recommendation core"
```

---

### Task 4: Add strict Gateway, selectors, and workflow state

**TDD gate:** Complete Gate A before any UI component is implemented.

**Files:**
- Create: `src/features/recommendation/gateway.ts`
- Create: `src/features/recommendation/selectors.ts`
- Create: `src/features/recommendation/workflow.ts`
- Create: `src/features/recommendation/__tests__/gateway.test.ts`
- Create: `src/features/recommendation/__tests__/selectors.test.ts`
- Create: `src/features/recommendation/__tests__/workflow.test.ts`
- Modify: `src/features/recommendation/types.ts`
- Modify: `src/features/recommendation/index.ts`

**Interfaces:**
- Consumes: C04 runtime/store, C02 generic envelopes, frozen API-005/API-006, strict C05 draft schema, AREA-A session rule.
- Produces: `RecommendationGateway`, three required selectors, and `RecommendationWorkflowStore`; Task 5 consumes them to extend the single runtime.

- [ ] **Step 1: Write strict Gateway tests**

Use an injected fetch spy and this exact interface:

```ts
export type RecommendationGateway = {
  getRecommendation: (
    planId: string,
    inputVersion: number,
  ) => Promise<RecommendationGatewayResult>;
  confirmRecommendation: (
    planId: string,
    input: Api006Request,
  ) => Promise<RecommendationGatewayResult>;
};
```

Assert:

```text
GET /mock/plans/PLAN-001/recommendation?inputVersion=2
POST /mock/plans/PLAN-001/recommendation/confirm
body = {"trackNo":"T1","window":"2026-07-16T08:01:00+08:00/2026-07-16T10:01:00+08:00"}
```

The local success data schema is strict and requires `apiId`, exact `operationId`, `now`, `scenarioId`, and `items: do001Schema.array().length(1)`. It rejects a wrong API ID, wrong operationId, absent matching Plan, extra data field, malformed success envelope, and malformed failure envelope. Assert API-006 rejects reviewer/version fields in its body through `api006RequestSchema`.

- [ ] **Step 2: Implement the Gateway without changing C04 Gateway**

Normalize only after both generic and operation-specific parsing:

```ts
const recommendationDataSchema = (apiId: 'API-005' | 'API-006', operationId: string) =>
  z.object({
    apiId: z.literal(apiId),
    operationId: z.literal(operationId),
    now: z.string(),
    scenarioId: z.string(),
    items: do001Schema.array().length(1),
  }).strict();
```

URL-encode `planId`; append `inputVersion` with `URLSearchParams`; send JSON content type only for API-006. Return the parsed error envelope unchanged. For success, additionally require `items[0].id === planId` but never use that Plan to replace Store facts.

- [ ] **Step 3: Write the three selector tests before implementation**

The exact required signatures are:

```ts
export function selectConfirmedPlan(state: DemoRootState, planId: string): Readonly<Plan> | undefined;
export function selectReceptionRecommendations(state: DemoRootState, planId: string): RecommendationDraft | undefined;
export function selectTrackConflicts(state: DemoRootState, planId: string): readonly TrackConflictViewModel[];
```

Assert that UI-003 returns PLAN-001 only after C04 confirms it, hides every plan/draft/conflict when session scope lacks AREA-A, rejects malformed `drafts[planId]`, returns immutable copies, maps exclusions in deterministic track order, and never counts or associates out-of-scope data. Add a feature helper `selectAffectedWorkOrders(state, planId)` that returns linked work orders in stable ID order for adjustment checks.

- [ ] **Step 4: Implement selector ownership and view models**

Use one internal predicate:

```ts
function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*') ||
    state.session.dataScope.includes('GLOBAL') ||
    state.session.dataScope.includes('AREA-A');
}
```

`selectConfirmedPlan` requires `status === 'CONFIRMED'`. `selectReceptionRecommendations` calls `recommendationDraftSchema.parse` on a structured clone and freezes it. `selectTrackConflicts` projects only the draft's `excluded` items; its codes are feature-internal and never parsed by `publicErrorCodeSchema`. `selectAffectedWorkOrders` reads only `state.workOrder.workOrders.filter(({planId}) => ...)` after scope validation.

- [ ] **Step 5: Write and implement the page-only workflow store**

Use exactly this state and API:

```ts
export type RecommendationWorkflowState = Readonly<{
  selectedCandidateId?: string;
  adjustmentDrawerOpen: boolean;
  ruleDrawerOpen: boolean;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type RecommendationWorkflowStore = {
  getState: () => RecommendationWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectCandidate: (candidateId?: string) => void;
  setAdjustmentDrawerOpen: (open: boolean) => void;
  setRuleDrawerOpen: (open: boolean) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
};
```

Tests prove initial state, immutable replacement, listener notifications, retained form-independent selection after command error, and exact reset.

- [ ] **Step 6: Run the Task 4 Gate A tests**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts src/features/recommendation/__tests__/gateway.test.ts src/features/recommendation/__tests__/selectors.test.ts src/features/recommendation/__tests__/workflow.test.ts src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts
```

Expected: all listed tests pass. The command/runtime part of Gate A is completed in Task 5; do not start Gate B until Task 5 is also green.

- [ ] **Step 7: Commit Gateway/selectors/workflow**

```powershell
git add src/features/recommendation/gateway.ts src/features/recommendation/selectors.ts src/features/recommendation/workflow.ts src/features/recommendation/types.ts src/features/recommendation/index.ts src/features/recommendation/__tests__/gateway.test.ts src/features/recommendation/__tests__/selectors.test.ts src/features/recommendation/__tests__/workflow.test.ts
git commit -m "feat(c05): add recommendation projections and gateway"
```

Do not include a knowingly failing runtime test in this commit; keep runtime edits for Task 5 if the command service is not yet present.

---

### Task 5: Build calculation and confirmation commands with audit/reset semantics

**TDD gate:** Gate A command core and Gate C non-UI behavior.

**Files:**
- Create: `src/features/recommendation/commands.ts`
- Create: `src/features/recommendation/__tests__/commands.test.ts`
- Modify: `src/features/recommendation/index.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`
- Modify: `src/features/plan-entry/commands.ts`
- Modify: `src/features/plan-entry/__tests__/commands.test.ts`

**Interfaces:**
- Consumes: C03 executor/authorization/audit, C05 Gateway/rule engine/schemas/selectors/workflow, C04 reset service.
- Produces: `RecommendationCommandService.calculateRecommendation`, `confirmRecommendation`, `resetCommandState`, complete runtime wiring, and cross-feature atomic reset.

- [ ] **Step 1: Write command tests against this public contract**

```ts
export type ConfirmRecommendationInput = Readonly<{
  planId: string;
  candidateId: string;
  reason?: string;
  reviewerId?: string;
}>;

export type RecommendationCommandService = {
  calculateRecommendation: (planId: string) => Promise<CommandResult>;
  confirmRecommendation: (input: ConfirmRecommendationInput) => Promise<CommandResult>;
  resetCommandState: () => void;
};
```

Tests must prove:

1. PLAN-001 must first be CONFIRMED/version 2; calculation calls API-005 with inputVersion 2 and writes one CALCULATED draft with scores 100/62/52 and RC-01 audit.
2. Recalculation replaces the previous draft atomically, increments `draftVersion`, selects no candidate in domain state, and clears workflow selection.
3. Confirming T1 calls API-006 with exact `{trackNo, window}` and writes CONFIRMED status, actor/role/time/commandId/traceId without changing Plan or Track.
4. Selecting T3 requires non-empty reason; because PLAN-001 has ACKNOWLEDGED and PAUSED work orders, reviewer is required and must differ from actor.
5. Same reviewer returns `TOS-AUTH-001`, sends no API request, changes no draft, and appends exactly one DENIED RC-04 audit.
6. Missing reason, unknown candidate, completed linked work order, or ineligible candidate returns `DEMO-SCENARIO-001` with one failed audit and no Gateway call.
7. Changed Plan version or chosen Track version returns `DEMO-VERSION-001`, retains the CALCULATED draft/selection, and requires recalculation.
8. A repeated injected commandId returns the frozen first result without a second Gateway call, commit, or audit.
9. API/fetch failure retains draft and workflow values and appends exactly one failed audit.
10. C04 API-025 reset clears Store draft, C05 workflow, C05 idempotence/ID counters, and Mock `resolvedFaultObjects`.

- [ ] **Step 2: Append a valid command/action red**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/commands.test.ts src/runtime/__tests__/runtime.test.tsx *>&1 | Add-Content docs/evidence/C05/recommendation-red.txt
```

Expected: fail because the service and runtime nesting are absent.

- [ ] **Step 3: Define a strict technical payload and deterministic IDs**

Use:

```ts
type RecommendationCommandPayload = {
  current: 'ACCEPTED';
  businessAction: 'RC-01' | 'RC-04';
  inputPlanVersion: number;
  selectedCandidateId?: string;
  trackVersions: Record<string, number>;
  reason?: string;
  reviewerId?: string;
  affectedWorkOrderIds: string[];
};
```

Commands use `action: 'execute'`, `entityType: 'SM-007'`, `entityId: planId`, and current Store Plan version as `expectedVersion`. Default IDs are `CMD-C05-001`, `TRACE-C05-001`, `AUD-C05-001`. Use `session.demoTime`; retain the C04 injectable formatter pattern so idempotence can be tested.

- [ ] **Step 4: Implement ordered authorization for all required permissions**

Within the executor's `authorize` dependency, evaluate in this order and return the first denial:

```ts
const contexts: PolicyContext[] = [
  { session, pageId: 'UI-003', permission: 'plan:recommend', objectScope, expectedVersion, actualVersion },
  ...(isAlternative ? [{ session, pageId: 'UI-003', permission: 'plan:adjust', objectScope }] : []),
  ...(isConfirm ? [{
    session,
    pageId: 'UI-003',
    permission: 'plan:confirm',
    objectScope,
    ...(highRisk ? { highRisk: true, applicantId: session.actorId, approverId: reviewerId } : {}),
  }] : []),
];
```

Before returning allow, compare the current Plan version and every stored trackVersion; return `DEMO-VERSION-001` on a mismatch. Page access remains exact DISPATCHER through C03 route policy; do not add roles or permissions.

- [ ] **Step 5: Implement calculation invocation and one atomic draft commit**

For RC-01: validate Plan exists, is CONFIRMED, and is AREA-A-visible; call API-005 with current version; require returned Plan identity; calculate with current Store tracks and `session.demoTime`; build a strict CALCULATED draft with `draftVersion = previous?.draftVersion + 1 ?? 1`; then use exactly:

```ts
store.replaceDomainState((candidate) => {
  candidate.recommendation.drafts[planId] = recommendationDraftSchema.parse(draft);
});
workflow.selectCandidate(undefined);
```

Do not update Plan, Track, WorkOrder, fixture, or Mock objects.

- [ ] **Step 6: Implement confirmation validation and exact API-006 call**

Read and parse the CALCULATED draft. The selected candidate must still exist and be eligible. `isAlternative` means `candidate.rank !== 1`. For alternatives require `reason.trim().length > 0`. Stable affected work orders are linked by `planId`; high-risk statuses are exactly `DISPATCHED | ACKNOWLEDGED | IN_PROGRESS | PAUSED`. If any linked order is COMPLETED, reject the alternative as `DEMO-SCENARIO-001`. High-risk alternatives require reviewerId and separation of duties.

Send only:

```ts
api006RequestSchema.parse({
  trackNo: candidate.trackNo,
  window: `${candidate.windowStart}/${candidate.windowEnd}`,
  ...(reason?.trim() ? { reason: reason.trim() } : {}),
});
```

On success, re-check Plan/Track versions inside the single Store candidate and write a CONFIRMED draft containing selectedCandidateId, optional adjustment, and:

```ts
confirmation: {
  actorId: session.actorId,
  roleCode: session.roleCode,
  confirmedAt: session.demoTime,
  commandId: command.commandId,
  traceId: command.traceId,
}
```

Do not write auditLogId into the draft after commit.

- [ ] **Step 7: Adapt the audit action without changing the state machine**

Wrap the existing appender with an audit-only command clone:

```ts
const baseAppender = createCommandAuditAppender(ledger);
const appendRecommendationAudit: AuditAppender = (input) =>
  baseAppender({
    ...input,
    command: {
      ...input.command,
      action: recommendationPayload(input.command).businessAction,
    },
  });
```

The executor still receives the original SM-007 `execute` command for transition. Verify DO-013 `action` is RC-01/RC-04 and `objectId` is planId.

- [ ] **Step 8: Wire reset and one runtime**

Add an optional callback to C04 dependencies:

```ts
export type PlanEntryCommandServiceDependencies = {
  store: DemoStoreApi;
  gateway: PlanEntryGateway;
  workflow: PlanEntryWorkflowStore;
  storage?: SessionStorageWriter;
  idFormatters?: Partial<CommandIdFormatters>;
  onSuccessfulReset?: () => void;
};
```

After a successful C04 reset result and before returning, call `onSuccessfulReset?.()` exactly once, then rebuild C04 command state. In `createDemoRuntime`, create one Store, both workflows, both gateways, then the C05 command service, and pass a callback that calls `recommendationWorkflow.reset()` and `recommendationCommands.resetCommandState()`. Assert existing C04 reset tests still pass and no second Store/provider exists.

Add the runtime types and hook:

```ts
export type RecommendationRuntime = {
  gateway: RecommendationGateway;
  workflow: RecommendationWorkflowStore;
  commands: RecommendationCommandService;
};

export type DemoRuntime = {
  store: DemoStoreApi;
  gateway: PlanEntryGateway;
  workflow: PlanEntryWorkflowStore;
  commands: PlanEntryCommandService;
  recommendation: RecommendationRuntime;
};
```

Export `useRecommendationWorkflow` using `useSyncExternalStore` against `runtime.recommendation.workflow`. Runtime tests prove both features observe the same Store object, one C04 confirmation is immediately visible to C05, and API-025 clears draft, workflow, compatibility marker, and C05 command state.

- [ ] **Step 9: Run complete Gate A and command regressions**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts src/features/recommendation/__tests__/gateway.test.ts src/features/recommendation/__tests__/selectors.test.ts src/features/recommendation/__tests__/workflow.test.ts src/features/recommendation/__tests__/commands.test.ts src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts src/runtime/__tests__/runtime.test.tsx src/features/plan-entry/__tests__/commands.test.ts
```

Expected: all green. Gate A is now complete; no page work starts earlier.

- [ ] **Step 10: Commit the command/runtime closure**

```powershell
git add src/features/recommendation/commands.ts src/features/recommendation/index.ts src/features/recommendation/__tests__/commands.test.ts src/runtime/DemoRuntimeContext.tsx src/runtime/index.ts src/runtime/__tests__/runtime.test.tsx src/features/plan-entry/commands.ts src/features/plan-entry/__tests__/commands.test.ts docs/evidence/C05/recommendation-red.txt
git commit -m "feat(c05): add recommendation command pipeline"
```

---

### Task 6: Build the customer-facing UI-003 recommendation workspace

**TDD gate:** Gate B page display, then Gate C adjustment/confirmation. Preserve both red outputs before implementation.

**Files:**
- Create: `src/features/recommendation/components/PlanSummaryCard.tsx`
- Create: `src/features/recommendation/components/RecommendationCardList.tsx`
- Create: `src/features/recommendation/components/ExcludedOptionList.tsx`
- Create: `src/features/recommendation/components/TrackTimeline.tsx`
- Create: `src/features/recommendation/components/RuleExplanationDrawer.tsx`
- Create: `src/features/recommendation/components/AdjustmentDrawer.tsx`
- Create: `src/features/recommendation/components/__tests__/AdjustmentDrawer.test.tsx`
- Create: `src/features/recommendation/recommendation.css`
- Modify: `src/pages/dispatch/ReceptionRecommendationPage.tsx`
- Create: `src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx`
- Create: `src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx`
- Create: `src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx`
- Modify: `src/app/__tests__/routeRender.test.tsx`
- Create: `docs/evidence/C05/page-red.txt`
- Create: `docs/evidence/C05/action-red.txt`

**Interfaces:**
- Consumes: C05 runtime, selectors, workflow, command service; C04 `PageIdentity`, `PageStatePanel`, session/scenario presentation, and UI-002 return query.
- Produces: complete UI-003 read/calculate/explain/adjust/confirm experience and existing UI-004 link.

- [ ] **Step 1: Write rendering and permission tests first**

The render test starts from a runtime where PLAN-001 has been confirmed through C04 and recommendation calculated through C05. Assert exact smoke markers plus visible labels:

```ts
[
  '计划摘要', '候选股道', '排除选项', '接车时间轴',
  '综合评分', '系统推荐', '规则版本', '权威来源',
  '计划优先级：数据未提供', '关联箱量：数据未提供',
]
```

Assert T1/T3/T2 candidate order, T4 exclusion, score breakdown, and no UI-004 business content. Permission test proves DISPATCHER can render; BUSINESS/SHIFT_LEADER/INTERFACE_OPS receive the existing 403 before the lazy page module issues API-005 or reads plan content. Out-of-scope AREA-B renders not-found with no plan/candidate leakage.

The summary assertion covers planBatchNo, trainNo, cargoType, arrival/departure interval, current trackNo, CONFIRMED status, version, sourceSystem, sourceTime, conflicts, plus the two explicit “数据未提供” fields. It must not infer a waybill, container count, or priority.

- [ ] **Step 2: Save the Gate B red**

```powershell
node_modules\.bin\vitest.CMD run src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx *>&1 | Tee-Object docs/evidence/C05/page-red.txt
```

Expected: UI-003 still renders `PageScaffold`; recommendation labels/components do not exist.

- [ ] **Step 3: Write action tests and component validation red**

Test:

1. Auto-calculate on confirmed PLAN-001, default-select T1, open rationale, then confirm and expose `/dispatch/plans/PLAN-001/tasks`.
2. Select T3, open adjustment, require reason and reviewer because WO-004/WO-007 are high-risk, reject same actor, accept USER-001, and preserve form on failure.
3. Modify Plan or Track version after calculation; confirmation shows `DEMO-VERSION-001`, keeps selection/form, and provides “重新计算”.
4. API-005 rejected/malformed transport enters network-error; unconfirmed direct refresh enters business-error; unknown plan and AREA-A denial enter not-found/forbidden as designed.
5. Empty candidates renders all exclusion reasons rather than a blank page.

Save:

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/components/__tests__/AdjustmentDrawer.test.tsx src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx *>&1 | Tee-Object docs/evidence/C05/action-red.txt
```

Expected: action components and behavior are missing.

- [ ] **Step 4: Implement identity, summary, candidates, exclusions, and timeline**

The page identity must render:

```tsx
<PageIdentity
  pageId="UI-003"
  name="接车计划推荐"
  path={`/dispatch/plans/${planId}/recommendation`}
/>
```

Use Ant Design `Breadcrumb`, `Card`, `Statistic`, `Tag`, `Progress`, `List`, `Timeline`, `Drawer`, `Form`, `Select`, `Input`, `Alert`, `Button`, and `Space`; do not initialize ECharts or MapLibre. Candidate cards are buttons/radios with accessible names `选择候选 T1`, etc. The timeline displays plan arrival/departure, occupied/releasing estimate, candidate window, and “错峰” when delayed.

- [ ] **Step 5: Implement rule explanation and adjustment form**

`RuleExplanationDrawer` lists the four score parts, hard-exclusion-before-score rule, sort order, input Plan/Track versions, `C05-DEMO-RULE-1.0`, and “仅供 Demo 解释，不构成生产调度承诺”.

`AdjustmentDrawer` uses this complete form contract:

```ts
export type RecommendationAdjustmentValues = {
  candidateId: string;
  reason: string;
  reviewerId?: string;
};
```

For rank 1, reason/reviewer controls may be hidden and confirmation is direct. For rank >1, reason is required. When affected statuses include `DISPATCHED | ACKNOWLEDGED | IN_PROGRESS | PAUSED`, reviewer is required and options come from active `configAudit.userRoles`. Mark the current actor option as “本人，不能复核” but keep the command service as the authoritative separation-of-duties check so the denial path remains demonstrable. Do not copy user-role objects into workflow. Display affected work-order number/status and keep values after any command failure.

- [ ] **Step 6: Implement six states and controlled calculation**

Render:

- `loading`: skeleton and disabled actions while API-005/API-006 is pending.
- `empty`: no candidates, with complete exclusions and return/recalculate actions.
- `business-error`: unconfirmed Plan, input invalid, candidate invalid, or completed-work-order block.
- `network-error`: public error, retry/recalculate, last successful draft retained.
- `forbidden`: existing route boundary only; no page module/API.
- `not-found`: unknown or out-of-scope Plan with return UI-002.

On mount, calculate only when Store Plan is CONFIRMED and no valid draft exists. Never synthesize CONFIRMED on direct refresh. A returned UI-002 URL is `/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=<active>&planId=<id>`; preserve a supplied C04 query when present.

- [ ] **Step 7: Connect confirm feedback and UI-004 link**

After a successful command, read the CONFIRMED draft through `selectReceptionRecommendations`. Replace write actions with:

```tsx
<Link to={`/dispatch/plans/${encodeURIComponent(planId)}/tasks`}>
  进入任务拆解
</Link>
```

Do not render API-007 controls or any TaskDecomposition data. Display command trace and audit action summary without inventing an auditLogId inside the draft.

- [ ] **Step 8: Implement responsive styling**

Use a feature root `.recommendation-page` and CSS grid:

```css
.recommendation-main-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
  gap: 16px;
}

@media (max-width: 1320px) {
  .recommendation-main-grid { grid-template-columns: minmax(0, 1fr); }
}
```

All children use `min-width: 0`; table/list overflow stays inside its component; drawers use a viewport-bounded width and scrollable body. At 1440×900 and 1280×720 the document must have no horizontal overflow.

- [ ] **Step 9: Run Gate B/C page tests and build**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/components/__tests__/AdjustmentDrawer.test.tsx src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx src/app/__tests__/routeRender.test.tsx src/app/__tests__/routePermission.test.tsx
pnpm build
```

Expected: all pass and the build succeeds. Confirm the route test still sees exactly 13 routes and the UI-004 route remains a scaffold.

- [ ] **Step 10: Commit the complete UI-003 page**

```powershell
git add src/features/recommendation/components src/features/recommendation/recommendation.css src/pages/dispatch/ReceptionRecommendationPage.tsx src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx src/app/__tests__/routeRender.test.tsx docs/evidence/C05/page-red.txt docs/evidence/C05/action-red.txt
git commit -m "feat(c05): add reception recommendation workspace"
```

---

### Task 7: Add exactly four C05 E2E flows and eight visual artifacts

**Files:**
- Create: `e2e/ui-003-reception-recommendation.spec.ts`
- Create: eight PNG files under `docs/evidence/C05/`
- Create: `docs/evidence/C05/screenshot-index.md`

**Interfaces:**
- Consumes: full C04→C05 SPA runtime, C03 session localStorage, C05 accessible labels and deterministic commands.
- Produces: exactly four new Playwright tests, four named UI states at two viewports, and visual inspection records.

- [ ] **Step 1: Write the shared E2E helpers**

Reuse the C04 storage key and accessible interaction pattern:

```ts
const evidenceDirectory = resolve(process.cwd(), 'docs', 'evidence', 'C05');

async function seedDispatcher(page: Page, actorId = 'E2E-DISPATCHER'): Promise<void> {
  await page.addInitScript(({ key, actorId }) => {
    localStorage.setItem(key, JSON.stringify({
      actorId,
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
    }));
  }, { key: DEMO_SESSION_STORAGE_KEY, actorId });
}
```

Copy the C04 `assertNoHorizontalOverflow` logic. Capture with `fullPage: true` at exactly 1440×900 and 1280×720. Use role/name/label locators for primary actions.

- [ ] **Step 2: Implement E2E 1 — SCN-01 recommended confirmation**

Start in UI-002 with PLAN-001, confirm it, click “开放推荐入口”, wait for CALCULATED, assert T1/T3/T2 order and T4 exclusion, open the rule drawer, close it, and capture:

```text
C05-UI003-SCN01-CALCULATED-1440x900.png
C05-UI003-SCN01-CALCULATED-1280x720.png
```

Confirm T1, assert CONFIRMED and exact UI-004 link, then capture:

```text
C05-UI003-SCN01-CONFIRMED-1440x900.png
C05-UI003-SCN01-CONFIRMED-1280x720.png
```

- [ ] **Step 3: Implement E2E 2 — SCN-01 manual alternative**

Run a fresh PLAN-001 flow, select T3, assert WO-004/WO-007 impact, require reason/reviewer, use reason `错峰释放 T1，采用 T3`, select `USER-001`, and capture the open adjustment state:

```text
C05-UI003-SCN01-ADJUSTMENT-1440x900.png
C05-UI003-SCN01-ADJUSTMENT-1280x720.png
```

Confirm; assert selected T3, preserved original T1, reason, reviewer, RC-04 audit, and no Plan/Track mutation.

- [ ] **Step 4: Implement E2E 3 — SCN-02 continuous completion**

Start UI-002 SCN-02 PLAN-002, supplement trackNo T1 with USER-001 review, confirm to version 3, follow recommendation link without reload, calculate and confirm the first recommendation. Assert API-005/API-006 are not blocked by the resolved TOS-EXT-002 and capture:

```text
C05-UI003-SCN02-CONFIRMED-1440x900.png
C05-UI003-SCN02-CONFIRMED-1280x720.png
```

- [ ] **Step 5: Implement E2E 4 — authorization, separation, and version recovery**

In one test with explicit reload/reset points, prove: BUSINESS receives 403 with no API-005; a dispatcher whose actorId is `USER-001` can select `USER-001` as the deliberately invalid reviewer and receives TOS-AUTH-001 while the adjustment remains. For the browser-level version recovery presentation, intercept the next API-006 call once and return a strict 409 envelope:

```ts
await page.route('**/mock/plans/PLAN-001/recommendation/confirm', async (route) => {
  await route.fulfill({
    status: 409,
    contentType: 'application/json',
    body: JSON.stringify({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'Expected version 1, actual 2.',
      traceId: 'TRACE-E2E-VERSION-001',
      auditLogId: 'AUD-E2E-VERSION-001',
    }),
  });
});
```

Assert the UI keeps the selection/form, shows `DEMO-VERSION-001`, exposes “重新计算”, and recalculation restores a current draft after removing the one-shot route. Unit command tests remain responsible for proving a real Store Plan/Track version mismatch is caught before API invocation. Do not capture extra C05 screenshots.

- [ ] **Step 6: Run the focused E2E file**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-003-reception-recommendation.spec.ts
```

Expected: exactly 4/4. Preserve traces for failures and diagnose before changing behavior.

- [ ] **Step 7: Inspect all eight images at original resolution**

For every image, verify document overflow ≤1px, no clipping/overlap, readable candidate scores, readable timeline, drawer form fully usable, Chinese glyphs intact, and no abnormal blank region. `screenshot-index.md` contains one table row per exact filename with page, scenario, state, viewport, E2E test, pixel dimensions, and inspection result.

- [ ] **Step 8: Commit E2E and visual evidence**

```powershell
git add e2e/ui-003-reception-recommendation.spec.ts docs/evidence/C05/C05-UI003-*.png docs/evidence/C05/screenshot-index.md
git commit -m "test(c05): cover recommendation demo flows"
```

Expected: only the new C05 E2E file and eight C05 images/index are included; no C01/C04 image rewrite.

---

### Task 8: Run final verification, coverage mapping, and C06 handoff

**Files:**
- Create: `docs/evidence/C05/coverage.json`
- Create: `docs/evidence/C05/verification.md`
- Create: `docs/evidence/C05/tsc-after.txt`
- Create: `docs/handoffs/C05-recommendation.md`

**Interfaces:**
- Consumes: completed Gate A/B/C implementation and evidence.
- Produces: verifiable final C05 report and the exact UI-004 entry contract for C06.

- [ ] **Step 1: Run the C05 focused Vitest set fresh**

```powershell
node_modules\.bin\vitest.CMD run src/features/recommendation/__tests__/schemas.test.ts src/features/recommendation/__tests__/ruleEngine.test.ts src/features/recommendation/__tests__/gateway.test.ts src/features/recommendation/__tests__/selectors.test.ts src/features/recommendation/__tests__/workflow.test.ts src/features/recommendation/__tests__/commands.test.ts src/features/recommendation/components/__tests__/AdjustmentDrawer.test.tsx src/pages/__tests__/ReceptionRecommendationPage.render.test.tsx src/pages/__tests__/ReceptionRecommendationPage.permission.test.tsx src/pages/__tests__/ReceptionRecommendationPage.action.test.tsx src/mocks/__tests__/scenarios.test.ts src/mocks/__tests__/handlers.test.ts src/runtime/__tests__/runtime.test.tsx src/features/plan-entry/__tests__/commands.test.ts src/app/__tests__/routeRender.test.tsx src/app/__tests__/routePermission.test.tsx
```

Expected: all listed files/tests pass. Record actual counts rather than predicting them.

- [ ] **Step 2: Run all final quality commands**

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C05/tsc-after.txt
git diff --check
```

Expected: full Vitest passes; build passes; Playwright is 23/23 (19 existing + 4 C05); TypeScript retains only actual React declaration diagnostics and has zero C05 non-React primary diagnostics; diff check passes.

- [ ] **Step 3: Recheck frozen hashes and prohibited scope**

Repeat Task 1's hash script and require 6/6. Run:

```powershell
git diff --name-only 5d4b21dc3b5cf8e37fad3affdfb14169ebcdfa61..HEAD
git diff -- docs/baseline package.json pnpm-lock.yaml src/pages/dispatch/TaskDecompositionPage.tsx
rg -n "Date\.now|Math\.random|randomUUID|replaceDomainState|resetFromSnapshot" src/features/recommendation src/pages/dispatch/ReceptionRecommendationPage.tsx
```

Expected: no baseline/dependency/UI-004 diff; system clock/random absent; `replaceDomainState` appears only inside the C05 command service, not components/pages.

- [ ] **Step 4: Write complete `coverage.json`**

Use valid JSON with top-level keys:

```json
{
  "module": "C05",
  "pages": ["UI-003"],
  "selectors": ["selectConfirmedPlan", "selectReceptionRecommendations", "selectTrackConflicts"],
  "apis": ["API-005", "API-006"],
  "scenarios": ["SCN-01", "SCN-02"],
  "uiStates": ["loading", "empty", "business-error", "network-error", "forbidden", "not-found"],
  "e2eExpected": 4,
  "screenshotsExpected": 8,
  "checks": []
}
```

Populate `checks` with exact test file/name/status entries for rule version/scoring, strict Gateway, three selectors, permission, version, idempotence, audit, reset, compatibility, four E2E tests, and eight screenshots. Do not leave an empty array in the final file.

- [ ] **Step 5: Write `verification.md` from measured results**

Record branch and full commits, environment versions, start/end time, every command and exit code, focused/full Vitest counts, build output/module count, Playwright 4/4 and 23/23, actual tsc category counts, six hashes, eight original dimensions/review status, diff check, prohibited-scope scan, and worktree status. Explicitly state whether global tsc passed or failed; never convert a known diagnostic baseline into a pass.

- [ ] **Step 6: Write the C05 handoff**

`docs/handoffs/C05-recommendation.md` must document:

```text
runtime.recommendation public members and hooks
RecommendationDraft strict Schema and C05-DEMO-RULE-1.0
100-point score and stable sorting
three required selectors and AREA-A behavior
API-005/API-006 Gateway method/body rules
RC-01/RC-04 command, permission, version, idempotence, atomicity, and audit
SCN-02 resolvedFaultObjects exact allow/deny boundary
six UI states and direct-refresh behavior
four E2E tests and eight screenshot paths
coverage/verification locations and actual test counts
known limits: demo score, no priority/box count, UI-004 scaffold only
C06 entry: /dispatch/plans/:planId/tasks after a CONFIRMED recommendation draft
```

- [ ] **Step 7: Commit final evidence and handoff**

```powershell
git add docs/evidence/C05/coverage.json docs/evidence/C05/verification.md docs/evidence/C05/tsc-after.txt docs/handoffs/C05-recommendation.md
git commit -m "docs(c05): add recommendation evidence and handoff"
git status --short --branch
```

Expected: clean `demo/c05-recommendation` worktree. Final report includes the current branch, full HEAD, commit chain, changed-file categories, focused/full Vitest counts, build, Playwright 4/4 and 23/23, actual tsc status, six hashes, eight screenshots, coverage, verification, handoff, and known limits.
