# C06 任务拆解 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a customer-demo-ready UI-004 task-decomposition workspace that turns a C05-confirmed reception recommendation into deterministic, editable C06 work-order drafts, confirms them as READY through API-007, and opens UI-005 without implementing dispatch.

**Architecture:** Extend the single C05 `DemoRuntimeProvider` with a strict API-007 gateway, deterministic task rule/edit engines, three required selectors, page-only workflow state, and a C03-executor-backed command service. C06-owned strict DO-005/DO-006 records live in the existing `workOrder` slice and are identified by C06 IDs plus `C06-DEMO-RULE-1.0`; API responses validate transport and Plan identity only and never replace Store truth. Five actions use SM-007 for the technical pipeline, then validate the existing DO-001/DO-005 transitions inside one atomic commit.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Work only on clean `demo/c06-task-decomposition`. C05 source/handoff is `83363ff18bf606150d6d03a3248046c6bc0f1dfd`; C06 design is `ab952b11b43dbe1f9c563cfc5518f50f080f3a6e`.
- Read `docs/handoffs/C05-recommendation.md` and `docs/superpowers/specs/2026-07-21-c06-task-decomposition-design.md` before editing.
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

- Preserve the 14 strict domain schemas, 25 API entries, 9 public error codes, 12 Store slices, 13 roles, 45 permission codes, 13 routes, state-machine catalog, existing C04/C05 behavior, and current SCN-02 resolved-fault boundary.
- UI-004 route is `/dispatch/plans/:planId/tasks`; UI-005 route is `/dispatch/work-orders`.
- Do not implement API-008/API-009, resource-instance assignment, dispatch, acknowledgment, execution, UI-005 business content, or later modules.
- C06 may append only strict DO-005/DO-006 records it owns. Never rewrite `WO-001`～`WO-012`, `NODE-001`～`NODE-012`, Plan facts before TD-05, Resource facts, or a C05 RecommendationDraft.
- C06 ownership requires the `C06-WO-`/`C06-NODE-` ID prefixes and `ruleVersion === 'C06-DEMO-RULE-1.0'`; every mutation filters by planId and ownership before changing arrays.
- Domain facts live only in the existing Store. Workflow contains selected IDs, drawer state, mode, form text, and last error only.
- API-007 request body is exactly `{ ruleVersion, mode }`; target IDs, reason, versions, actor, action, and generation metadata remain in the C06 command payload.
- A valid API-007 response contains one matching strict Plan and proves transport only. Never write its Plan/WorkOrder/WorkNode items into Store.
- All five writes use C03 `createCommandExecutor`, SM-007 `execute`, one `replaceDomainState` per successful command, and one append-only audit for success/failure/denial.
- Validate Plan, C05 draft, C06 objects, and resource version snapshots before API, after API, and inside commit. Return `DEMO-VERSION-001` for version drift with no partial write.
- `INTERLOCK_FORCE_STOP` blocks every C06 write with `TOS-IL-001`; it has no override in C06 and links to UI-009.
- C06 READY means “task definition ready for dispatch.” Resource type must exist in the frozen catalog, but `resourceId` and `teamId` remain empty until UI-005; never invent resources or borrow AREA-B/C instances.
- AT-TOS-004 displays the 2-train/80-car/160-container acceptance benchmark separately from Plan facts. Do not generate 160 fake containers or claim PLAN-001 has an actual quantity field.
- Treat strict Plan fixtures as AREA-A, and apply `dataScope` before object lookup, counts, mapping, task projection, or version capture.
- Use only `session.demoTime`, input timestamps, deterministic IDs, and resettable counters. Do not use `Date.now`, uninjected current time, `Math.random`, random UUIDs, or localStorage as domain truth.
- Preserve C01 UI-004 smoke markers: exact `UI-004`, heading `任务拆解`, and `当前路由：/dispatch/plans/<planId>/tasks`.
- C05 baseline is 42 Vitest files / 322 tests, build 1,875 modules, Playwright 23/23, and 360 known React/JSX TypeScript diagnostics: TS2604=18, TS7016=63, TS7026=279. C06 non-React primary diagnostics must be 0; report global tsc honestly.
- Follow four TDD gates. Save meaningful red outputs before implementation; do not manufacture red evidence after code exists.

---

### Task 1: Freeze the C06 branch and capture preflight evidence

**Files:**
- Create: `docs/evidence/C06/preflight.md`
- Create: `docs/evidence/C06/tsc-before.txt`

**Interfaces:**
- Consumes: clean C06 design branch, C05 handoff, C06 design, six frozen baselines.
- Produces: an evidence-backed before-state used by all later gates.

- [ ] **Step 1: Verify branch, ancestry, status, and forbidden changes**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor 83363ff18bf606150d6d03a3248046c6bc0f1dfd HEAD
git merge-base --is-ancestor ab952b11b43dbe1f9c563cfc5518f50f080f3a6e HEAD
git diff --name-only 83363ff18bf606150d6d03a3248046c6bc0f1dfd..HEAD
```

Expected: branch is `demo/c06-task-decomposition`; worktree is clean; both ancestor commands exit 0; the only C06 change is the approved design/plan/prompt documentation. Unknown source changes are a hard stop; do not reset or overwrite them.

- [ ] **Step 2: Verify all six frozen hashes**

```powershell
$expected = @{
  'docs/baseline/README.md' = 'bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34'
  'docs/baseline/package-baseline.json' = 'ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810'
  'docs/baseline/openapi.yaml' = '1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83'
  'docs/baseline/demo-fixtures.json' = 'b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba'
  'docs/baseline/page-task-matrix.csv' = 'c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b'
  'docs/baseline/traceability.csv' = 'a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2'
}
$rows = foreach ($path in $expected.Keys) {
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
  [pscustomobject]@{ Path = $path; Expected = $expected[$path]; Actual = $actual; Match = $actual -eq $expected[$path] }
}
$rows | Format-Table -AutoSize
if ($rows.Match -contains $false) { throw 'C06 frozen baseline mismatch.' }
```

Expected: six `Match=True` rows. On mismatch, report expected/actual/status/recent log and stop; never regenerate the file.

- [ ] **Step 3: Capture the complete C05 regression baseline**

```powershell
New-Item -ItemType Directory -Force docs/evidence/C06 | Out-Null
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C06/tsc-before.txt
pnpm test -- --run
pnpm build
pnpm test:e2e
```

Expected: tsc exits 1 with the measured C05 React/JSX diagnostics; Vitest is 42 files / 322 tests; build succeeds around 1,875 modules; Playwright is 23/23. If tests, build, or E2E regress, restore a clean C05-derived baseline before C06 work.

- [ ] **Step 4: Record exact preflight results**

Write this structure with measured values, not copied claims:

```markdown
# C06 Preflight

- Branch: `demo/c06-task-decomposition`
- C05 source ancestor: `83363ff18bf606150d6d03a3248046c6bc0f1dfd`
- C06 design ancestor: `ab952b11b43dbe1f9c563cfc5518f50f080f3a6e`
- Frozen baseline: `6/6 MATCH`
- Vitest baseline: `42 files / 322 tests PASS`
- Build baseline: `PASS; 1,875 modules`
- Playwright baseline: `23/23 PASS`
- TypeScript baseline: `exit 1; TS2604=18, TS7016=63, TS7026=279; total=360`
```

Also record Node/pnpm versions, timestamps, command exit codes, actual hashes, and worktree status.

- [ ] **Step 5: Commit only preflight evidence**

```powershell
git add docs/evidence/C06/preflight.md docs/evidence/C06/tsc-before.txt
git commit -m "test(c06): record task decomposition preflight"
```

Expected: one evidence-only commit and a clean worktree.

---

### Task 2: Build the deterministic C06 task model, ownership, and route engine

**TDD gate:** Gate A core red. Do not write commands or UI before these pure contracts pass.

**Files:**
- Create: `src/features/task-decomposition/constants.ts`
- Create: `src/features/task-decomposition/types.ts`
- Create: `src/features/task-decomposition/ownership.ts`
- Create: `src/features/task-decomposition/ruleEngine.ts`
- Create: `src/features/task-decomposition/__tests__/ownership.test.ts`
- Create: `src/features/task-decomposition/__tests__/ruleEngine.test.ts`
- Create: `src/features/task-decomposition/index.ts`
- Create: `docs/evidence/C06/task-core-red.txt`

**Interfaces:**
- Consumes: strict `Plan`, `Waybill`, `Material`, `Resource`, `WorkOrder`, `WorkNode`, C05 `RecommendationDraft`, and injected `demoTime`.
- Produces: `TASK_DECOMPOSITION_RULE_VERSION`, ownership predicates, `generateTaskDraft`, `projectTaskGraph`, exact generated IDs, strict DO-005/006 records, route explanations, and immutable projections.

- [ ] **Step 1: Define public constants and types in failing tests first**

Use these exact public contracts:

```ts
export const TASK_DECOMPOSITION_RULE_VERSION = 'C06-DEMO-RULE-1.0' as const;
export const taskModes = ['AUTO', 'SPLIT', 'MERGE', 'REGENERATE', 'CONFIRM'] as const;
export type TaskMode = (typeof taskModes)[number];
export type TaskStage = 'RECOGNITION' | 'UNLOAD' | 'TRANSFER' | 'STORAGE';

export type GenerateTaskDraftInput = Readonly<{
  plan: Plan;
  recommendation: RecommendationDraft;
  waybills: readonly Waybill[];
  materials: readonly Material[];
  resources: readonly Resource[];
  demoTime: string;
  generationVersion: number;
}>;

export type TaskGeneration = Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
}>;

export function parseGenerationVersion(id: string): number | undefined;
export function validateTaskGeneration(
  generation: TaskGeneration,
  planId: string,
): TaskGeneration;
export function projectTaskGraph(input: Readonly<{
  plan: Plan;
  recommendation: RecommendationDraft;
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  waybills: readonly Waybill[];
  materials: readonly Material[];
  resources: readonly Resource[];
}>): readonly TaskTreeNodeView[];

export type TaskTreeNodeView = Readonly<{
  workOrderId: string;
  workOrderNo: string;
  nodeId: string;
  nodeNo: string;
  sequence: number;
  stage: TaskStage;
  taskType: WorkOrder['type'];
  title: string;
  objectId: string;
  dependencyIds: readonly string[];
  ruleVersion: typeof TASK_DECOMPOSITION_RULE_VERSION;
  status: WorkOrder['status'];
  nodeStatus: WorkNode['status'];
  requiredResourceType: Resource['resourceType'];
  resourceCandidateIds: readonly string[];
  sourceRefs: readonly string[];
  workOrderVersion: number;
  nodeVersion: number;
}>;
```

Tests must fail because these modules and exports do not exist.

- [ ] **Step 2: Write ownership and invariant tests**

Cover exact predicates and reject near-matches:

```ts
expect(isC06WorkOrder({
  ...baseWorkOrder,
  id: 'C06-WO-PLAN-001-G001-01',
  workOrderNo: 'C06-WO-PLAN-001-G001-01',
  planId: 'PLAN-001',
  ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
}, 'PLAN-001')).toBe(true);

expect(isC06WorkOrder({ ...baseWorkOrder, id: 'WO-001' }, 'PLAN-001')).toBe(false);
expect(isC06WorkOrder({ ...baseWorkOrder, planId: 'PLAN-002' }, 'PLAN-001')).toBe(false);
```

Add `validateTaskGeneration` tests for unique IDs/numbers, one WorkNode per WorkOrder, valid parent references, no cycle, no orphan, contiguous sequences, DRAFT/WAITING generation status, strict DO-005/006 parsing, and deep-frozen returned arrays/objects.

- [ ] **Step 3: Write exact PLAN-001 FLY_ASH generation tests**

With generation version 1, assert these IDs and chain:

```text
C06-WO-PLAN-001-G001-01  识别与路由确认  INSPECT   parent=""
C06-WO-PLAN-001-G001-02  卸料准备        UNLOAD    parent=<01 id>
C06-WO-PLAN-001-G001-03  输送转运        TRANSFER  parent=<02 id>
C06-WO-PLAN-001-G001-04  筒仓入库        LOAD      parent=<03 id>
```

Assert each WorkOrder has `priority='HIGH'`, `status='DRAFT'`, `ackStatus='PENDING'`, empty resource/team/block fields, version 1, fixed rule version, and deterministic timestamps. Assert nodes are WAITING and split PLAN-001's 08:01–10:01 window into four exact 30-minute intervals.

For the projection, assert required resource types `TEAM`, `TIPPER`, `CONVEYOR`, `SILO`; sourceRefs include PLAN-001, the selected C05 candidate, same-cargo Waybill/Material IDs, and the rule version. Resource candidates include only matching type IDs, sorted by ID; status is displayed but availability does not erase a required type.

- [ ] **Step 4: Cover every frozen cargo type and documented limitation**

Use table tests for:

```ts
const expectedRoutes = {
  FLY_ASH: ['识别与路由确认', '卸料准备', '输送转运', '筒仓入库'],
  CEMENT: ['识别与路由确认', '卸料准备', '输送转运', '应急筒仓入库'],
  STEEL: ['规格重量校验', '重载吊装', 'AGV 转运', '货位入库'],
  GENERAL_CARGO: ['箱号包装校验', '掏装/卸载', '分拣转运', '入库或发运准备'],
} as const;
```

Assert GENERAL_CARGO explanation explicitly says the frozen enum cannot distinguish electromechanical equipment from living supplies. Assert same-cargo Waybill/Material mapping is stable by ID and labeled `DEMO_STABLE_MAPPING`; do not synthesize a foreign key or quantity.

- [ ] **Step 5: Save the valid core red output**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/ownership.test.ts src/features/task-decomposition/__tests__/ruleEngine.test.ts *>&1 | Tee-Object docs/evidence/C06/task-core-red.txt
```

Expected: failure due to missing C06 exports/modules, while imported C02/C05 contracts still parse.

- [ ] **Step 6: Implement ownership and graph validation**

Implement strict prefix/version checks, a DFS cycle check, parent existence, exact node/work-order pairing, contiguous sequences, and clone-then-freeze output. Ownership must be conjunctive:

```ts
export function isC06WorkOrder(order: WorkOrder, planId: string): boolean {
  return order.planId === planId
    && order.id.startsWith('C06-WO-')
    && order.workOrderNo.startsWith('C06-WO-')
    && order.ruleVersion === TASK_DECOMPOSITION_RULE_VERSION;
}

export function isC06WorkNode(node: WorkNode): boolean {
  return node.id.startsWith('C06-NODE-') && node.nodeNo.startsWith('C06-N-');
}
```

Do not treat prefix alone as ownership.

- [ ] **Step 7: Implement the pure route engine**

Use a frozen internal route table and deterministic ID format:

```ts
const key = `${plan.id}-G${String(generationVersion).padStart(3, '0')}`;
const workOrderId = `C06-WO-${key}-${String(index + 1).padStart(2, '0')}`;
const nodeId = `C06-NODE-${key}-${String(index + 1).padStart(2, '0')}`;
const nodeNo = `C06-N-${key}-${String(index + 1).padStart(2, '0')}`;
```

Parse only the injected arrival/departure timestamps. Divide total milliseconds across four stages with the final finish forced to the original departure, so rounding cannot extend the plan window. Parse every generated object with `do005Schema`/`do006Schema`, validate the graph, then freeze.

- [ ] **Step 8: Run core and strict-contract regressions**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/ownership.test.ts src/features/task-decomposition/__tests__/ruleEngine.test.ts src/contracts/__tests__/schemas.test.ts src/stores/__tests__/store.test.ts src/features/recommendation/__tests__/schemas.test.ts
```

Expected: all pass; fixture counts, Store slices, C05 draft Schema, and strict DO-005/006 remain unchanged.

- [ ] **Step 9: Commit the deterministic core**

```powershell
git add src/features/task-decomposition/constants.ts src/features/task-decomposition/types.ts src/features/task-decomposition/ownership.ts src/features/task-decomposition/ruleEngine.ts src/features/task-decomposition/__tests__ src/features/task-decomposition/index.ts docs/evidence/C06/task-core-red.txt
git commit -m "feat(c06): add deterministic task decomposition core"
```

---

### Task 3: Add strict API-007 gateway, required selectors, and workflow

**TDD gate:** Complete Gate A before command mutations.

**Files:**
- Create: `src/features/task-decomposition/gateway.ts`
- Create: `src/features/task-decomposition/selectors.ts`
- Create: `src/features/task-decomposition/workflow.ts`
- Modify: `src/features/task-decomposition/types.ts`
- Modify: `src/features/task-decomposition/index.ts`
- Create: `src/features/task-decomposition/__tests__/gateway.test.ts`
- Create: `src/features/task-decomposition/__tests__/selectors.test.ts`
- Create: `src/features/task-decomposition/__tests__/workflow.test.ts`

**Interfaces:**
- Consumes: API-007 request/envelope contracts, existing Store, Task 2 ownership/rule projection, strict C05 draft Schema.
- Produces: `createTaskDecompositionGateway`, `selectCargoSummary`, `selectRuleExplanation`, `selectTaskTree`, `selectResourcePreview`, and `createTaskDecompositionWorkflowStore`.

- [ ] **Step 1: Write strict gateway tests**

Test this exact public API:

```ts
export type TaskDecompositionGateway = {
  decomposePlan: (
    planId: string,
    input: { ruleVersion: string; mode: TaskMode },
  ) => Promise<TaskDecompositionGatewayResult>;
};
```

Assert `POST /mock/plans/PLAN-001/decompose`, content type JSON, and exact body:

```json
{"ruleVersion":"C06-DEMO-RULE-1.0","mode":"AUTO"}
```

Reject extra fields, numeric ruleVersion, invalid mode at the feature boundary, malformed success/error envelopes, wrong apiId/operationId, zero/two items, and a Plan ID different from the path. A valid response must have `apiId='API-007'`, `operationId='POST_mock_plans_id_decompose'`, scenarioId, now, and exactly one strict Plan.

- [ ] **Step 2: Write selector tests with AREA-A first**

The required selectors are exact:

```ts
selectCargoSummary(state, planId)
selectRuleExplanation(state, planId)
selectTaskTree(state, planId)
```

Also expose `selectResourcePreview`. Tests must prove:

- dataScope outside AREA-A returns undefined/empty before parsing the raw C05 draft or counting C06 records;
- C05 raw draft must be strict and CONFIRMED, but Plan may be CONFIRMED or DECOMPOSED so READY UI-004 remains readable;
- only C06-owned records for the requested plan enter the tree;
- legacy PLAN-001 work orders never enter the C06 tree;
- orphan/cycle/invalid strict records throw into a controlled page business error rather than being silently omitted;
- all arrays and nested values are frozen;
- cargo summary distinguishes Plan fact, Demo stable mapping, and AT-TOS-004 benchmark.

- [ ] **Step 3: Write workflow tests**

Use this state contract:

```ts
export type TaskDecompositionWorkflowState = Readonly<{
  selectedNodeIds: readonly string[];
  editDrawerOpen: boolean;
  rulePanelOpen: boolean;
  mode?: Exclude<TaskMode, 'AUTO' | 'CONFIRM'>;
  targetNodeId?: string;
  reason: string;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;
```

The store exposes `selectNodes`, `openEditor`, `closeEditor`, `setReason`, `setRulePanelOpen`, `recordCommandError`, and `reset`. Test immutable snapshots, subscriber notification, preserved reason on command failure, cleared selection/reason after success, and exact reset state.

- [ ] **Step 4: Implement the gateway by following C05 transport shape**

Parse the outgoing body with `api007RequestSchema` and add a feature-level `z.enum(taskModes)` check before fetch. Parse success as:

```ts
const api007DataSchema = (planId: string) => z.object({
  apiId: z.literal('API-007'),
  operationId: z.literal('POST_mock_plans_id_decompose'),
  now: z.string(),
  scenarioId: z.string(),
  items: do001Schema.array().length(1),
}).strict().superRefine((data, context) => {
  if (data.items[0]?.id !== planId) context.addIssue({
    code: 'custom', path: ['items', 0, 'id'], message: `Expected response Plan ${planId}.`,
  });
});
```

Do not accept returned DO-005/006, and do not expose a Store mutator.

- [ ] **Step 5: Implement selectors and workflow**

Centralize `canReadAreaA` in the feature. Parse `state.recommendation.drafts[planId]` with `recommendationDraftSchema` and require `status === 'CONFIRMED'`. Pair C06 WorkOrder/WorkNode by workOrderNo, call Task 2 projection, sort by sequence then ID, and clone/freeze before return.

The workflow follows the C05 external-store pattern; it never imports Store domain arrays.

- [ ] **Step 6: Run focused Gateway/selector/workflow tests**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/gateway.test.ts src/features/task-decomposition/__tests__/selectors.test.ts src/features/task-decomposition/__tests__/workflow.test.ts src/features/task-decomposition/__tests__/ownership.test.ts src/features/task-decomposition/__tests__/ruleEngine.test.ts src/features/recommendation/__tests__/selectors.test.ts src/mocks/__tests__/handlers.test.ts
```

Expected: all pass; API-007 handler remains generic and frozen; C05 selectors retain their original behavior.

- [ ] **Step 7: Commit read-side contracts**

```powershell
git add src/features/task-decomposition/gateway.ts src/features/task-decomposition/selectors.ts src/features/task-decomposition/workflow.ts src/features/task-decomposition/types.ts src/features/task-decomposition/index.ts src/features/task-decomposition/__tests__
git commit -m "feat(c06): add task projections and api gateway"
```

---

### Task 4: Implement TD-01 automatic generation and TD-04 regeneration

**TDD gate:** Gate B command red starts here.

**Files:**
- Create: `src/features/task-decomposition/commands.ts`
- Create: `src/features/task-decomposition/__tests__/commands.test.ts`
- Create: `docs/evidence/C06/task-command-red.txt`
- Modify: `src/features/task-decomposition/index.ts`

**Interfaces:**
- Consumes: Task 2 engines, Task 3 gateway/workflow, C03 executor/authorization/audit, one DemoStore.
- Produces: `createTaskDecompositionCommandService`, TD-01/TD-04 behavior, version snapshots, deterministic IDs, audit actions, and resettable command state.

- [ ] **Step 1: Define the complete service and payload contract in failing tests**

Public service:

```ts
export type TaskDecompositionCommandService = {
  generateTasks: (planId: string) => Promise<CommandResult>;
  splitTask: (input: { planId: string; targetNodeId: string; reason: string }) => Promise<CommandResult>;
  mergeTasks: (input: { planId: string; nodeIds: readonly [string, string]; reason: string }) => Promise<CommandResult>;
  regenerateTasks: (planId: string) => Promise<CommandResult>;
  confirmTasks: (planId: string) => Promise<CommandResult>;
  resetCommandState: () => void;
};
```

Strict command payload:

```ts
type TaskCommandPayload = {
  current: 'ACCEPTED';
  businessAction: 'TD-01' | 'TD-02' | 'TD-03' | 'TD-04' | 'TD-05';
  mode: TaskMode;
  planVersion: number;
  recommendationDraftVersion: number;
  resourceVersions: Record<string, number>;
  workOrderVersions: Record<string, number>;
  nodeVersions: Record<string, number>;
  generationVersion: number;
  targetNodeId?: string;
  selectedNodeIds: string[];
  reason?: string;
};
```

`.strict()` rejects extra keys. Every generated command uses `entityType='SM-007'`, `action='execute'`, `payload.current='ACCEPTED'`.

- [ ] **Step 2: Write TD-01 allow/deny and atomic tests**

Build a runtime state with C04-confirmed Plan and C05 CONFIRMED draft. Assert TD-01:

- requires `task:decompose`, AREA-A, Plan CONFIRMED/complete, strict confirmed C05 draft, and no C06 READY records;
- sends only `{ ruleVersion, mode:'AUTO' }` to API-007;
- adds exactly four C06 WorkOrders/nodes in one Store commit;
- does not modify Plan, C05 draft, resources, legacy work orders/nodes, or any other slice;
- writes exactly one TD-01 audit and returns `CMD-C06-001`/`TRACE-C06-001` with injected default formatters;
- rejects missing/unconfirmed recommendation before API with one FAILED audit;
- rejects a second AUTO when DRAFT exists and instructs TD-04, without duplicate records.

- [ ] **Step 3: Write TD-04 regeneration tests**

After TD-01, make a valid manual draft variant in test setup, then assert regeneration:

- API body is mode REGENERATE;
- only current-plan C06 DRAFT records are replaced;
- generation changes from G001 to G002 and route returns to the four system nodes;
- legacy/other-plan/READY records survive unchanged;
- audit preserves TD-04 and previous audit entries;
- a failure, malformed response, version drift, or READY record leaves the original draft intact.

- [ ] **Step 4: Save command red before production code**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/commands.test.ts *>&1 | Tee-Object docs/evidence/C06/task-command-red.txt
```

Expected: tests fail because the command service is missing.

- [ ] **Step 5: Implement shared authorization and version helpers**

Create one `assertStoredVersions` that checks Plan, strict C05 draft, Resource, C06 WorkOrder, and C06 WorkNode snapshots. Map policy by action:

```ts
const permission = payload.businessAction === 'TD-02' || payload.businessAction === 'TD-03'
  ? 'task:edit'
  : 'task:decompose';
```

Before entity lookup, require AREA-A. If `state.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP'`, return a permission-style denial with `TOS-IL-001` and a precise message. Do not call API.

- [ ] **Step 6: Implement TD-01 and TD-04 staging plus one-commit writes**

Store generated candidates in a private `Map<commandId, TaskGeneration>`. `invokeMock` calls the gateway, rechecks versions, computes the candidate from current Store truth, validates it, and stages it. `commit` rechecks versions and executes exactly one `replaceDomainState`.

Generation version is derived from existing C06 IDs, not from an unpersisted page variable:

```ts
const generationVersion = Math.max(0, ...ownedIds.map(parseGenerationVersion)) + 1;
```

TD-04 removes only owned DRAFT pairs after the replacement candidate is fully validated. Delete the staged result after commit. Never write the API response item.

- [ ] **Step 7: Implement audit adapter, result recording, and reset**

Follow the C05 service pattern. Audit clones the command only for DO-013 action:

```ts
action: taskPayload(input.command).businessAction
```

Use `CMD-C06-###`, `TRACE-C06-###`, `AUD-C06-###`. `resetCommandState` clears sequences, trace maps, staged candidates, processed IDs, and rebuilds the executor. Workflow error changes only once per non-replayed result.

- [ ] **Step 8: Run TD-01/04 and C03/C05 regressions**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/commands.test.ts src/commands/__tests__/pipeline.test.ts src/commands/__tests__/authorization-integration.test.ts src/governance/__tests__/audit.test.ts src/features/recommendation/__tests__/commands.test.ts src/stores/__tests__/store.test.ts
```

Expected: all pass; one commit per C06 success; C04/C05 audit order remains append-only.

- [ ] **Step 9: Commit generation commands**

```powershell
git add src/features/task-decomposition/commands.ts src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/index.ts docs/evidence/C06/task-command-red.txt
git commit -m "feat(c06): add task generation command pipeline"
```

---

### Task 5: Implement pure split/merge edits and TD-02/TD-03

**Files:**
- Create: `src/features/task-decomposition/editEngine.ts`
- Create: `src/features/task-decomposition/__tests__/editEngine.test.ts`
- Modify: `src/features/task-decomposition/commands.ts`
- Modify: `src/features/task-decomposition/__tests__/commands.test.ts`
- Modify: `src/features/task-decomposition/index.ts`

**Interfaces:**
- Consumes: validated C06 DRAFT pairs and command reason/version snapshots.
- Produces: `splitTaskDraft`, `mergeTaskDraft`, deterministic edit IDs, graph rewiring, and TD-02/TD-03 command paths.

- [ ] **Step 1: Write pure split tests**

Public function:

```ts
export function splitTaskDraft(input: Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  targetNodeId: string;
  demoTime: string;
}>): TaskGeneration;
```

For target stage 02, assert two replacement pairs named with deterministic `-S001-A`/`-S001-B` suffixes. A inherits the original parent; B's parent is A; the old stage-03 parent becomes B; all sequences and planned time intervals are recomputed contiguously. Original records disappear only from the candidate result. Reject INSPECT, READY/non-DRAFT, legacy, unknown, cross-plan, already executed, invalid graph, and empty/zero-duration target.

- [ ] **Step 2: Write pure merge tests**

Public function:

```ts
export function mergeTaskDraft(input: Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  nodeIds: readonly [string, string];
  demoTime: string;
}>): TaskGeneration;
```

Assert only two adjacent direct-chain DRAFT nodes with identical WorkOrder.type, derived objectId, and rule version may merge. The result uses deterministic `-M001`, inherits first parent, takes the combined interval, rewires the second node's child, and reindexes. Reject reversed/nonadjacent nodes, siblings, different types/objects/rules, legacy/READY/executed/cross-plan nodes, and graphs with external children that would create ambiguity.

- [ ] **Step 3: Run the edit-engine red**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/editEngine.test.ts
```

Expected: FAIL because the pure edit engine does not exist.

- [ ] **Step 4: Implement clone-edit-validate-freeze operations**

Never mutate input arrays. Find exact owned WorkOrder/WorkNode pairs, clone, apply replacement, rewire one direct child, recompute sequence and input-derived planned intervals, then call Task 2 `validateTaskGeneration`. IDs derive the next edit number by scanning existing suffixes, so reload does not reset uniqueness.

Do not encode `reason` into title or blockReason; reason belongs to command/audit.

- [ ] **Step 5: Add TD-02/TD-03 command tests**

Assert both require `task:edit`, exact nonempty reason, current C06 DRAFT ownership, matching version maps, and API modes SPLIT/MERGE. Verify:

```ts
expect(JSON.parse(requestBody)).toEqual({
  ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
  mode: 'SPLIT',
});
expect(requestBody).not.toContain('reason');
expect(requestBody).not.toContain('targetNodeId');
```

Success replaces only the current plan's C06 DRAFT pairs in one commit and appends TD-02/TD-03 audit with reason. Failure preserves Store plus workflow reason/selection. Same commandId replay is proven at executor level not to repeat commit/audit.

- [ ] **Step 6: Implement command branches and workflow success rules**

Build the pure edit candidate only after API success and second version check. On first successful TD-02/03 result, close the editor, clear selected nodes/reason, and clear last error. On failure, leave all editor state intact.

- [ ] **Step 7: Run edit, command, ownership, and audit tests**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/editEngine.test.ts src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/__tests__/ownership.test.ts src/features/task-decomposition/__tests__/workflow.test.ts src/commands/__tests__/pipeline.test.ts src/governance/__tests__/audit.test.ts
```

Expected: all pass; no existing state machine or permission catalog changes.

- [ ] **Step 8: Commit manual task editing**

```powershell
git add src/features/task-decomposition/editEngine.ts src/features/task-decomposition/__tests__/editEngine.test.ts src/features/task-decomposition/commands.ts src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/index.ts
git commit -m "feat(c06): add task split and merge editing"
```

---

### Task 6: Implement TD-05 confirmation and integrate the single runtime/reset

**Files:**
- Modify: `src/features/task-decomposition/commands.ts`
- Modify: `src/features/task-decomposition/__tests__/commands.test.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/index.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`
- Modify: `src/features/task-decomposition/index.ts`

**Interfaces:**
- Consumes: fully validated C06 DRAFT graph, C05 confirmed draft, Plan/Resource/object versions, C03 transitions.
- Produces: TD-05 atomic Plan/WorkOrder confirmation, READY read model, `runtime.taskDecomposition`, `useTaskDecompositionWorkflow`, and complete reset behavior.

- [ ] **Step 1: Write TD-05 precondition and transition tests**

Start from a successful TD-01 result. Assert TD-05 refuses before API when any of these are true:

```text
Plan is not CONFIRMED or has missingFields
C05 RecommendationDraft is missing, invalid, or not CONFIRMED
zero C06 draft nodes
any owned WorkOrder is not DRAFT
any owned WorkNode is not WAITING
orphan, cycle, duplicate ID/number, noncontiguous sequence, or mismatched pair
required resource type is absent from the frozen resource catalog
INTERLOCK_FORCE_STOP is active
Plan, recommendation, Resource, WorkOrder, or WorkNode version changed
```

Use the strict public error mapping from the design: authorization/data scope → `TOS-AUTH-001`; force-stop → `TOS-IL-001`; version → `DEMO-VERSION-001`; graph/business validation → `DEMO-SCENARIO-001`.

- [ ] **Step 2: Write exact atomic success assertions**

After TD-05 success assert:

```ts
expect(plan).toMatchObject({ status: 'DECOMPOSED', version: beforePlanVersion + 1 });
expect(ownedOrders.every(({ status }) => status === 'READY')).toBe(true);
expect(ownedOrders.every(({ resourceId, teamId }) => resourceId === '' && teamId === '')).toBe(true);
expect(ownedNodes.every(({ status }) => status === 'WAITING')).toBe(true);
```

Every owned WorkOrder and WorkNode increments version exactly once and gets `updatedAt=session.demoTime`; createdAt stays unchanged. Legacy objects, C05 draft, resources, other plans, and other slices remain byte-for-byte equal. Exactly one TD-05 audit is appended. API body is exact mode CONFIRM.

- [ ] **Step 3: Prove state-machine validation without changing its catalog**

Spy or unit-test calls equivalent to:

```ts
transitionState({ machineId: 'DO-001', current: 'CONFIRMED', command: 'decompose' });
transitionState({ machineId: 'DO-005', current: 'DRAFT', command: 'assign' });
```

Both must be `ok: true`; if any object would not transition, commit rejects the whole candidate. Do not add `confirmTasks`, READY, or C06 actions to `stateMachineCatalog`; SM-007 remains the technical executor transition.

- [ ] **Step 4: Test failure after API and commit rejection**

Mutate each version family between Gateway resolve and commit in controlled tests. Assert strict `DEMO-VERSION-001` is returned where the post-API checker catches drift; if the executor converts a commit-time thrown version assertion to `DEMO-SCENARIO-001`, move the final drift check before staging/commit so all specified version conflicts remain `DEMO-VERSION-001`. No Plan or task object may partially transition.

Also test malformed API-007 and fetch rejection preserve DRAFT records and workflow input.

- [ ] **Step 5: Implement TD-05 candidate validation and atomic commit**

In `invokeMock`, call API-007, validate the strict response, run `assertStoredVersions`, validate graph and resource-type catalog, and stage the IDs to confirm. In `commit`, re-read owned records, rerun all checks, validate each DO-001/DO-005 transition, and mutate in one `replaceDomainState`:

```ts
candidatePlan.status = 'DECOMPOSED';
candidatePlan.version += 1;
candidatePlan.updatedAt = candidateState.session.demoTime;

for (const order of candidateState.workOrder.workOrders.filter(
  (item) => isC06WorkOrder(item, command.entityId),
)) {
  order.status = 'READY';
  order.resourceId = '';
  order.teamId = '';
  order.version += 1;
  order.updatedAt = candidateState.session.demoTime;
}
const ownedNumbers = new Set(
  candidateState.workOrder.workOrders
    .filter((item) => isC06WorkOrder(item, command.entityId))
    .map(({ workOrderNo }) => workOrderNo),
);
for (const node of candidateState.workOrder.nodes.filter(
  (item) => isC06WorkNode(item) && ownedNumbers.has(item.workOrderNo),
)) {
  node.version += 1;
  node.updatedAt = candidateState.session.demoTime;
}
```

Parse changed records with strict schemas before the candidate is accepted.

- [ ] **Step 6: Write runtime integration tests before modifying runtime**

Assert one runtime exposes:

```ts
runtime.taskDecomposition.gateway
runtime.taskDecomposition.workflow
runtime.taskDecomposition.commands
```

Prove a C04 confirmation is visible to C05, a C05 CONFIRMED draft is immediately visible to C06, C06 records are in the same Store, and TD-05's DECOMPOSED/READY result is visible without page reload. Add a hook consumer for `useTaskDecompositionWorkflow`.

For API-025 reset, first create C05 and C06 state, edit workflow, then assert:

```ts
expect(selectTaskTree(runtime.store.getState(), 'PLAN-001')).toEqual([]);
expect(runtime.taskDecomposition.workflow.getState()).toEqual({
  selectedNodeIds: [],
  editDrawerOpen: false,
  rulePanelOpen: false,
  reason: '',
});
```

The next generation after reset must again use `CMD-C06-001`, `TRACE-C06-001`, `AUD-C06-001`, and G001.

- [ ] **Step 7: Integrate C06 without a second Store or Provider**

Create C06 gateway/workflow/commands from the same `store` before the plan-entry reset callback is closed over. Extend runtime types:

```ts
export type TaskDecompositionRuntime = {
  gateway: TaskDecompositionGateway;
  workflow: TaskDecompositionWorkflowStore;
  commands: TaskDecompositionCommandService;
};

export type DemoRuntime = {
  store: DemoStoreApi;
  gateway: PlanEntryGateway;
  workflow: PlanEntryWorkflowStore;
  commands: PlanEntryCommandService;
  recommendation: RecommendationRuntime;
  taskDecomposition: TaskDecompositionRuntime;
};
```

The existing `onSuccessfulReset` calls both C05 and C06 workflow/command reset methods. Do not reset C06 separately outside API-025.

- [ ] **Step 8: Run full command/runtime regression**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/__tests__/selectors.test.ts src/runtime/__tests__/runtime.test.tsx src/features/recommendation/__tests__/commands.test.ts src/features/plan-entry/__tests__/commands.test.ts src/commands/__tests__/stateMachines.test.ts src/commands/__tests__/pipeline.test.ts src/stores/__tests__/store.test.ts
```

Expected: all pass; root Store still has exactly 12 slices; state-machine catalog is unchanged.

- [ ] **Step 9: Commit confirmation and runtime integration**

```powershell
git add src/features/task-decomposition/commands.ts src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/index.ts src/runtime/DemoRuntimeContext.tsx src/runtime/index.ts src/runtime/__tests__/runtime.test.tsx
git commit -m "feat(c06): confirm task drafts in shared runtime"
```

---

### Task 7: Build the UI-004 task-decomposition workspace

**TDD gate:** Gate C page red. Keep C01 smoke behavior while replacing the scaffold.

**Files:**
- Create: `src/features/task-decomposition/components/TaskContextHeader.tsx`
- Create: `src/features/task-decomposition/components/CargoSummaryCard.tsx`
- Create: `src/features/task-decomposition/components/TaskTreePanel.tsx`
- Create: `src/features/task-decomposition/components/RuleExplainPanel.tsx`
- Create: `src/features/task-decomposition/components/ResourcePreview.tsx`
- Create: `src/features/task-decomposition/components/TaskEditDrawer.tsx`
- Create: `src/features/task-decomposition/task-decomposition.css`
- Create: `src/features/task-decomposition/components/__tests__/TaskEditDrawer.test.tsx`
- Modify: `src/pages/dispatch/TaskDecompositionPage.tsx`
- Create: `src/pages/__tests__/TaskDecompositionPage.render.test.tsx`
- Create: `src/pages/__tests__/TaskDecompositionPage.permission.test.tsx`
- Create: `src/pages/__tests__/TaskDecompositionPage.action.test.tsx`
- Modify: `src/app/__tests__/routeRender.test.tsx`
- Create: `docs/evidence/C06/task-page-red.txt`

**Interfaces:**
- Consumes: Task 3 selectors/workflow, Task 4–6 command service, existing route/auth/status components.
- Produces: customer-demo-ready UI-004 content, five accessible actions, six error states, four business progress states, responsive layout, UI-003 return and UI-005 entry.

- [ ] **Step 1: Build a deterministic page-test harness**

Create a helper that stores a DISPATCHER AREA-A session, constructs one `createDemoRuntime(fetcher)`, and uses the public C04/C05 commands to reach a CONFIRMED recommendation before rendering `/dispatch/plans/PLAN-001/tasks?scenarioId=SCN-01`. The fetcher must return strict API-004/005/006/007 envelopes by path and never mutate Store directly.

Add a second helper for SHIFT_LEADER view/action tests and explicit helpers for BUSINESS forbidden, AREA-B not-found, malformed API-007, and force-stop scenario.

- [ ] **Step 2: Write render tests before page code**

Assert exact page identity and content:

```text
UI-004
任务拆解
当前路由：/dispatch/plans/PLAN-001/tasks
计划与推荐摘要
货物摘要
任务树
规则说明
资源预览
高峰验收基准：2 列 / 80 节 / 160 箱
本计划实际箱量：数据未提供
```

Before TD-01, assert EMPTY state and enabled “自动拆解”. After generation, assert four exact stages, direct dependencies, fixed rule version, Demo stable mapping label, resource type/status rows, and no legacy WO-001 in the task tree.

- [ ] **Step 3: Write action and form tests**

Cover:

1. TD-01 generates and renders the task tree with trace/audit feedback.
2. Selecting a splittable row opens `TaskEditDrawer` with mode SPLIT, target node, required reason, version warning, and disabled submit until reason is nonempty.
3. Selecting two valid direct-chain rows opens MERGE; invalid pairs show an exact disabled reason before command invocation.
4. TD-02/TD-03 success updates tree and clears editor; failure preserves target/reason.
5. TD-04 shows destructive confirmation, restores four system nodes, increments generation, and retains prior audits.
6. TD-05 confirms DECOMPOSED/READY, locks every editing action, shows empty resource instance as “待 UI-005 分配”, and exposes `/dispatch/work-orders?planId=PLAN-001&scenarioId=SCN-01&from=task-decomposition`.

Use accessible names such as `自动拆解`, `拆分所选节点`, `合并所选节点`, `重新生成系统建议`, `确认工单草稿`, `进入派工看板`.

- [ ] **Step 4: Write six-state and permission tests**

Render and assert:

- loading during an unresolved API-007 promise;
- empty before generation or after filters remove all nodes;
- business-error for missing/unconfirmed C05 draft, invalid graph, or missing resource type;
- network-error for rejected fetch or malformed envelope, with draft/form retained and “重试原动作”;
- forbidden for a role outside route policy before module/API behavior;
- not-found for unknown/out-of-scope Plan without leaking plan/task counts.

DISPATCHER and SHIFT_LEADER both use the frozen `task:view`, `task:decompose`, and `task:edit` policies. Do not create a page-local role table.

- [ ] **Step 5: Save page red**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/components/__tests__/TaskEditDrawer.test.tsx src/pages/__tests__/TaskDecompositionPage.render.test.tsx src/pages/__tests__/TaskDecompositionPage.permission.test.tsx src/pages/__tests__/TaskDecompositionPage.action.test.tsx *>&1 | Tee-Object docs/evidence/C06/task-page-red.txt
```

Expected: missing components and scaffold content cause meaningful failures.

- [ ] **Step 6: Implement page composition and controlled selectors**

The page identity is exact:

```tsx
<PageIdentity
  pageId="UI-004"
  name="任务拆解"
  path={`/dispatch/plans/${planId}/tasks`}
/>
```

Use `useDemoSelector` for all domain views and `useTaskDecompositionWorkflow` for local UI state. Components receive frozen view models and callbacks; none imports Store mutators or Gateway. Only page event handlers call `runtime.taskDecomposition.commands`.

`TaskTreePanel` may use Ant Design `Tree`, `Table`, `Steps`, or nested Cards already installed. Do not add a graph library. If a dependency line is hard to read at 1280px, prioritize the tree and textual “上游节点” field.

- [ ] **Step 7: Implement header, cargo, rule, and benchmark disclosure**

Show Plan facts separately from C05 selected candidate and Demo stable mapping. Labels must include:

```text
本计划事实
Demo 稳定映射
高峰验收基准，不是本计划实际箱量
GENERAL_CARGO 未细分机电设备与生活物资（仅在该 cargoType）
```

Never display a fabricated quantity, priority, resource occupancy, safety threshold, or production throughput promise.

- [ ] **Step 8: Implement edit drawer and confirmation UX**

Form contract:

```ts
export type TaskEditValues = {
  mode: 'SPLIT' | 'MERGE';
  targetNodeId?: string;
  reason: string;
};
```

The merge action uses selectedNodeIds from workflow. Show exact affected rows and expected versions. TD-04 modal names the generation to discard. TD-05 modal lists graph checks, resource-type checks, Plan transition, READY meaning, and “不下发资源实例”. Error responses preserve open forms; success closes and clears them.

- [ ] **Step 9: Implement responsive CSS and route tests**

Use a page root and bounded grid:

```css
.task-decomposition-main {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr);
  gap: 16px;
}
.task-decomposition-main > * { min-width: 0; }
@media (max-width: 1320px) {
  .task-decomposition-main { grid-template-columns: minmax(0, 1fr); }
}
```

Tables/trees scroll inside cards, not at document level. Drawer width is viewport-bounded. Route tests still see exactly 13 routes, exact UI-004 marker, and lazy permission denial before business API.

- [ ] **Step 10: Run Gate C and build**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/components/__tests__/TaskEditDrawer.test.tsx src/pages/__tests__/TaskDecompositionPage.render.test.tsx src/pages/__tests__/TaskDecompositionPage.permission.test.tsx src/pages/__tests__/TaskDecompositionPage.action.test.tsx src/app/__tests__/routeRender.test.tsx src/app/__tests__/routePermission.test.tsx src/runtime/__tests__/runtime.test.tsx
pnpm build
```

Expected: all pass; build succeeds; UI-005 remains its existing scaffold.

- [ ] **Step 11: Commit the complete UI-004 workspace**

```powershell
git add src/features/task-decomposition/components src/features/task-decomposition/task-decomposition.css src/pages/dispatch/TaskDecompositionPage.tsx src/pages/__tests__/TaskDecompositionPage.render.test.tsx src/pages/__tests__/TaskDecompositionPage.permission.test.tsx src/pages/__tests__/TaskDecompositionPage.action.test.tsx src/app/__tests__/routeRender.test.tsx docs/evidence/C06/task-page-red.txt
git commit -m "feat(c06): add task decomposition workspace"
```

---

### Task 8: Add exactly four C06 E2E flows and eight screenshots

**TDD gate:** Gate D evidence red.

**Files:**
- Create: `e2e/ui-004-task-decomposition.spec.ts`
- Create: `docs/evidence/C06/task-e2e-red.txt`
- Create: eight PNG files under `docs/evidence/C06/`
- Create: `docs/evidence/C06/screenshot-index.md`

**Interfaces:**
- Consumes: full C04→C05→C06 SPA runtime, C03 session storage, accessible UI-004 actions.
- Produces: exactly four C06 Playwright tests, four UI states at two viewports, and original-resolution visual QA.

- [ ] **Step 1: Write shared E2E setup and save the red**

Reuse C04/C05 session helpers and `assertNoHorizontalOverflow`. Seed:

```ts
localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
  actorId: 'E2E-DISPATCHER',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
}));
```

Navigate through UI-002 confirmation and UI-003 recommendation confirmation rather than injecting C05 domain facts through localStorage. Capture with `fullPage: true` at exactly 1440×900 and 1280×720.

Before implementation is complete, run and save:

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-004-task-decomposition.spec.ts *>&1 | Tee-Object docs/evidence/C06/task-e2e-red.txt
```

Expected: fail on missing UI-004 business controls; do not save passing output as red evidence.

- [ ] **Step 2: Implement E2E 1 — generated and READY mainline**

SCN-01 flow:

```text
UI-002 confirm PLAN-001
→ UI-003 calculate and confirm first candidate
→ UI-004 AUTO
→ verify four stages, rule, traceability, resource preview
→ TD-05 CONFIRM
→ verify Plan DECOMPOSED, all C06 WorkOrders READY, UI-005 link
```

Capture GENERATED and READY at both viewports:

```text
C06-UI004-SCN01-GENERATED-1440x900.png
C06-UI004-SCN01-GENERATED-1280x720.png
C06-UI004-SCN01-READY-1440x900.png
C06-UI004-SCN01-READY-1280x720.png
```

- [ ] **Step 3: Implement E2E 2 — edit and regenerate**

Fresh SCN-01 flow: generate, split the unload stage with reason `按演示卸车波次拆分`, assert two replacements and TD-02 audit, capture EDITED at both viewports, merge the valid pair with reason `恢复连续卸车阶段`, split again, choose “重新生成系统建议”, assert G002 four-node system route and TD-04 audit, then capture REGENERATED at both viewports.

```text
C06-UI004-SCN01-EDITED-1440x900.png
C06-UI004-SCN01-EDITED-1280x720.png
C06-UI004-SCN01-REGENERATED-1440x900.png
C06-UI004-SCN01-REGENERATED-1280x720.png
```

- [ ] **Step 4: Implement E2E 3 — authorization, version, and replay presentation**

In one test with explicit reset/reload points, prove BUSINESS gets 403 without API-007, AREA-B does not reveal PLAN-001, and SHIFT_LEADER can perform frozen-policy actions. Intercept one API-007 response with a strict 409 `DEMO-VERSION-001` envelope to verify form/tree preservation and retry UX; unit command tests remain responsible for real Store version drift. Use a repeated UI action guard or command unit evidence to assert no duplicate C06 record/audit from replay. Do not capture extra screenshots.

- [ ] **Step 5: Implement E2E 4 — transport failure and force-stop**

Intercept one API-007 call with rejected/malformed transport and assert network-error plus preserved draft/form; remove the route and retry successfully. Reset to SCN-05, complete C04/C05 prerequisites, enter UI-004, and assert TD-01 is blocked with `TOS-IL-001`, no API-007 call, no C06 records, and an exact UI-009 link. Do not capture extra screenshots.

- [ ] **Step 6: Run focused C06 E2E**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-004-task-decomposition.spec.ts
```

Expected: exactly 4/4. Diagnose failures; do not weaken assertions or add retries that hide races.

- [ ] **Step 7: Inspect all eight screenshots at original resolution**

For each exact file, record pixel dimensions, state, scenario, E2E test, and PASS/FAIL for: document overflow ≤1px, no clipping/overlap, readable tree indentation/dependencies, visible rule and resource labels, usable drawer/modal, intact Chinese glyphs, no abnormal blank region, and UI-005 entry visible in READY.

- [ ] **Step 8: Commit E2E and visual evidence**

```powershell
git add e2e/ui-004-task-decomposition.spec.ts docs/evidence/C06/task-e2e-red.txt docs/evidence/C06/C06-UI004-*.png docs/evidence/C06/screenshot-index.md
git commit -m "test(c06): cover task decomposition demo flows"
```

Expected: exactly one new C06 E2E file, eight C06 images, red evidence, and one index; no prior screenshots are rewritten.

---

### Task 9: Run final verification, coverage mapping, and C07 handoff

**Files:**
- Create: `docs/evidence/C06/coverage.json`
- Create: `docs/evidence/C06/verification.md`
- Create: `docs/evidence/C06/tsc-after.txt`
- Create: `docs/handoffs/C06-task-decomposition.md`

**Interfaces:**
- Consumes: completed Gate A–D implementation and evidence.
- Produces: verifiable C06 final report and exact UI-005 entry contract for C07.

- [ ] **Step 1: Run the focused C06 Vitest set fresh**

```powershell
node_modules\.bin\vitest.CMD run src/features/task-decomposition/__tests__/ownership.test.ts src/features/task-decomposition/__tests__/ruleEngine.test.ts src/features/task-decomposition/__tests__/gateway.test.ts src/features/task-decomposition/__tests__/selectors.test.ts src/features/task-decomposition/__tests__/workflow.test.ts src/features/task-decomposition/__tests__/editEngine.test.ts src/features/task-decomposition/__tests__/commands.test.ts src/features/task-decomposition/components/__tests__/TaskEditDrawer.test.tsx src/pages/__tests__/TaskDecompositionPage.render.test.tsx src/pages/__tests__/TaskDecompositionPage.permission.test.tsx src/pages/__tests__/TaskDecompositionPage.action.test.tsx src/runtime/__tests__/runtime.test.tsx src/features/recommendation/__tests__/commands.test.ts src/commands/__tests__/stateMachines.test.ts src/commands/__tests__/pipeline.test.ts src/app/__tests__/routeRender.test.tsx src/app/__tests__/routePermission.test.tsx
```

Expected: every listed file/test passes. Record actual counts rather than predicting them.

- [ ] **Step 2: Run complete final quality commands**

```powershell
pnpm test -- --run
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-004-task-decomposition.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit *>&1 | Tee-Object docs/evidence/C06/tsc-after.txt
git diff --check
```

Expected: full Vitest passes; build passes; C06 E2E is 4/4; full Playwright passes with 27 total if no unrelated tests were added; tsc contains only measured React/JSX declaration diagnostics and zero C06 non-React primary diagnostics; diff check passes.

- [ ] **Step 3: Recheck hashes and prohibited scope**

Repeat Task 1's hash script and require 6/6. Then run:

```powershell
git diff --name-only ab952b11b43dbe1f9c563cfc5518f50f080f3a6e..HEAD
git diff -- docs/baseline package.json pnpm-lock.yaml src/commands/stateMachines.ts src/auth/permissionCatalog.ts src/pages/dispatch/DispatchBoardPage.tsx
rg -n "Date\.now|Math\.random|randomUUID|replaceDomainState|resetFromSnapshot" src/features/task-decomposition src/pages/dispatch/TaskDecompositionPage.tsx
rg -n "WO-00[1-9]|WO-01[0-2]|NODE-00[1-9]|NODE-01[0-2]" src/features/task-decomposition
```

Expected: no prohibited diff; system clock/random absent; `replaceDomainState` appears only in command service; legacy IDs appear only in explicit negative tests or explanatory assertions, never mutation lists.

- [ ] **Step 4: Write complete coverage.json**

Use valid JSON with populated checks:

```json
{
  "module": "C06",
  "pages": ["UI-004"],
  "selectors": ["selectCargoSummary", "selectRuleExplanation", "selectTaskTree"],
  "apis": ["API-007"],
  "actions": ["TD-01", "TD-02", "TD-03", "TD-04", "TD-05"],
  "scenarios": ["SCN-01", "SCN-05", "AT-TOS-004"],
  "uiStates": ["loading", "empty", "business-error", "network-error", "forbidden", "not-found"],
  "progressStates": ["EMPTY", "GENERATED", "EDITED", "READY"],
  "e2eExpected": 4,
  "screenshotsExpected": 8,
  "checks": []
}
```

Replace the empty array with exact test file/name/status entries for strict ownership, four cargo routes, graph invariants, Gateway body/envelope, three selectors, five commands, permissions, versions, force-stop, idempotence, audit, reset, four E2E tests, and eight screenshot files.

- [ ] **Step 5: Write measured verification.md**

Record branch/full HEAD/commit chain, environment, start/end timestamps, every command/exit code, focused/full Vitest counts, build modules, C06 4/4 and full Playwright counts, tsc categories, six hashes, eight dimensions/inspection results, forbidden-scope scan, diff check, and final status. State explicitly whether global tsc passed or failed.

- [ ] **Step 6: Write the C06 handoff**

Document:

```text
runtime.taskDecomposition public members and useTaskDecompositionWorkflow
C06-DEMO-RULE-1.0 and ownership predicates
four cargo route templates and stable mapping disclosure
three required selectors plus resource preview and AREA-A behavior
API-007 exact method/path/body/response identity rule
TD-01～TD-05 permissions, versions, atomicity, idempotence, audit, and reset
READY means type-satisfied task definition; resourceId/teamId remain empty until UI-005
SCN-05 force-stop conservative block and UI-009 link
six UI states, four progress states, direct-refresh behavior
four E2E tests, eight screenshots, coverage/verification paths and actual counts
known limits: no actual box count, no multi-parent DAG, no resource instance, UI-005 scaffold only
C07 entry: /dispatch/work-orders with planId/scenarioId/from query after DECOMPOSED/READY
```

- [ ] **Step 7: Commit final evidence and handoff**

```powershell
git add docs/evidence/C06/coverage.json docs/evidence/C06/verification.md docs/evidence/C06/tsc-after.txt docs/handoffs/C06-task-decomposition.md
git commit -m "docs(c06): add task decomposition evidence and handoff"
git status --short --branch
```

Expected: clean `demo/c06-task-decomposition` worktree. Final report includes branch, full HEAD, complete commit chain, changed-file categories, focused/full Vitest, build, C06 4/4 and full Playwright, actual tsc result, six hashes, eight screenshots, coverage, verification, handoff, and known limits.
