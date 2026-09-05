# C13A Config Contract Baseline Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Register DO-015 ConfigVersion as the only system-configuration domain object, repair API-022/023 to use it, add dedicated Store ownership and the approved lifecycle, then freeze a verified 0.3.0 baseline for C13.

**Architecture:** `docs/baseline/demo-fixtures.json` remains the sole deterministic seed and gains exactly one DO-015 record. Runtime contracts strictly parse that record, MSW exposes it as transport observation without mutation, and a dedicated `systemConfig` Store slice owns the only mutable copy. The common state-machine catalog records lifecycle legality; C13 feature commands remain out of scope and will perform later atomic commits.

**Tech Stack:** TypeScript 7, Zod 4, Zustand 5, MSW 2, Vitest 4, Vite 8, Playwright 1.61, PowerShell, Git.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-07-22-c13a-config-contract-baseline-design.md` at commit `fea914c68b5f4d51dfb81a73e986b5e5f144a634`.
- Working branch: `demo/c13-config-contract-baseline`; C12 base: `f85ede995c6958bbf36e1aa561b1ca7332fb8588`.
- Do not create `demo/c13-system-settings`, merge, or push.
- Baseline version becomes `0.3.0`; domain identities become exactly DO-001 through DO-015.
- DO-001 through DO-014, all 25 API IDs, 13 page IDs, seven scenarios, nine public errors, and exact dependencies remain unchanged.
- DO-014 remains UserRole with exactly 13 records; DO-015 is ConfigVersion with exactly one record.
- Editable DO-015 fields are exactly `displayName`, `defaultScenarioId`, `ruleVersion`, `dispatchStrategy`, `recommendationEnabled`, `offlineSyncEnabled`, `reportPeriod`, and `auditRetentionDays`.
- API-023 `edit` requires non-empty, strictly typed `changes`; `submit/approve/publish/rollback` reject `changes`.
- MSW never mutates DO-015. `systemConfig` is the only mutable domain owner.
- Do not modify pages, layouts, CSS, RBAC catalogs, C12 audit-trail implementation, dependencies, or C04-C12 feature code.
- Use TDD: every production change follows a focused failing test whose expected failure is captured before implementation.
- Use `apply_patch` for source and evidence files; command output must be read before concise exact evidence is written.
- No screenshots are generated because C13A has no UI change.

---

## File Structure

### Baseline and evidence

- Modify `docs/baseline/openapi.yaml`: add ConfigVersion enums, DO015, strict API023Request, API022/023 data/success schemas, and exact 200-response references/examples.
- Modify `docs/baseline/demo-fixtures.json`: append exactly one `DO-015` collection containing `CFG-001`.
- Modify `docs/baseline/README.md`: freeze baseline 0.3.0 and 15-domain-object invariant.
- Preserve `docs/baseline/package-baseline.json`, `page-task-matrix.csv`, and `traceability.csv` byte-for-byte.
- Modify `docs/baseline/SHA256SUMS.txt`: record fresh SHA-256 for all six official baseline files.
- Create `docs/evidence/C13A/preflight.md`, `tsc-before.txt`, `config-contract-red.txt`, `config-store-red.txt`, `SHA256SUMS-before.txt`, `SHA256SUMS-after.txt`, `tsc-after.txt`, and `verification.md`.

### Runtime contract and transport

- Modify `src/contracts/enums.ts`: add ConfigStatus, DispatchStrategy, and ConfigReportPeriod values/schemas.
- Modify `src/contracts/schemas.ts`: add `do015Schema`, ConfigVersion type, domain ID/count, and exact API-022/023 data/success schemas.
- Modify `src/contracts/requests.ts`: replace the legacy API023Request with the strict command discriminated union.
- Modify `src/contracts/api.ts`: allow API-022/023 specialized success schema names.
- Modify `src/mocks/fixtures.ts`: validate 15 object groups and DO-015 through the shared catalog.
- Modify `src/mocks/handlers.ts`: map API-022/023 to DO-015 and return a business error for unknown API-023 IDs.

### Store and lifecycle

- Modify `src/stores/types.ts`: add `SystemConfigSlice` and `systemConfig` to `DemoRootState`.
- Modify `src/stores/slices.ts`: add `createSystemConfigSlice`.
- Modify `src/stores/initialState.ts`: initialize, validate, freeze, and reset DO-015 through the Store.
- Modify `src/commands/stateMachines.ts`: register DO-015 and its five approved transitions.

### Tests and handoff

- Modify `src/contracts/__tests__/schemas.test.ts` and `openapi-consistency.test.ts`.
- Modify `src/mocks/__tests__/fixtures.test.ts` and `handlers.test.ts`.
- Modify `src/stores/__tests__/store.test.ts`.
- Modify `src/commands/__tests__/stateMachines.test.ts`.
- Create `docs/handoffs/C13A-config-contract-baseline.md`.
- Create `docs/conversation-prompts/C13-system-settings.md`.

---

### Task 1: Record the C13A preflight and frozen starting hashes

**Files:**
- Create: `docs/evidence/C13A/preflight.md`
- Create: `docs/evidence/C13A/tsc-before.txt`
- Create: `docs/evidence/C13A/SHA256SUMS-before.txt`

**Interfaces:**
- Consumes: approved C13A design commit, C12 base, current six-file `SHA256SUMS.txt`.
- Produces: immutable starting evidence used by the final scope and hash comparison.

- [ ] **Step 1: Verify the branch, base ancestry, worktree, merges, and current hashes**

Run:

```powershell
git status --short --branch
git rev-parse HEAD
git merge-base --is-ancestor f85ede995c6958bbf36e1aa561b1ca7332fb8588 HEAD
git rev-list --merges --count f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
Get-FileHash docs/baseline/README.md,docs/baseline/package-baseline.json,docs/baseline/openapi.yaml,docs/baseline/demo-fixtures.json,docs/baseline/page-task-matrix.csv,docs/baseline/traceability.csv -Algorithm SHA256
```

Expected: branch is `demo/c13-config-contract-baseline`, HEAD is the approved design commit, ancestor check exits 0, merge count is 0, worktree is clean, and all six hashes equal the current manifest.

- [ ] **Step 2: Capture the known TypeScript baseline**

Run:

```powershell
pnpm exec tsc --noEmit --pretty false
```

Expected: either exit 0 or only the already documented frozen React/JSX declaration diagnostics. Count and classify the output; do not change dependencies.

- [ ] **Step 3: Write concise exact evidence with `apply_patch`**

Create the three evidence files with the observed branch/HEAD/status, six exact hashes, TypeScript exit code, total diagnostic count, and unique diagnostic-code families. Do not write predicted results.

- [ ] **Step 4: Verify and commit the preflight evidence**

Run:

```powershell
git diff --check
git add docs/evidence/C13A
git diff --cached --check
git commit -m "test(c13a): record config baseline preflight"
```

Expected: commit succeeds with only C13A evidence files.

---

### Task 2: Add failing DO-015 and API-023 contract tests

**Files:**
- Modify: `src/contracts/__tests__/schemas.test.ts`
- Modify: `src/contracts/__tests__/openapi-consistency.test.ts`
- Modify: `src/mocks/__tests__/fixtures.test.ts`
- Create: `docs/evidence/C13A/config-contract-red.txt`

**Interfaces:**
- Consumes: existing `domainObjectIds`, `domainSchemas`, `requestSchemas`, OpenAPI parser, fixture validator.
- Produces: executable acceptance tests for the 15-object catalog, strict ConfigVersion shape, API-023 union, and specialized API-022/023 envelopes.

- [ ] **Step 1: Change the domain catalog expectations from 14 to 15**

In `schemas.test.ts`, make the first test expect IDs 1 through 15 and this exact count map addition:

```ts
const expectedIds = Array.from(
  { length: 15 },
  (_, index) => `DO-${String(index + 1).padStart(3, '0')}`,
);

expect(domainObjectCounts).toEqual({
  'DO-001': 3,
  'DO-002': 8,
  'DO-003': 4,
  'DO-004': 8,
  'DO-005': 12,
  'DO-006': 12,
  'DO-007': 10,
  'DO-008': 6,
  'DO-009': 5,
  'DO-010': 4,
  'DO-011': 4,
  'DO-012': 3,
  'DO-013': 9,
  'DO-014': 13,
  'DO-015': 1,
});
```

Update descriptions that hard-code 14 to 15. Preserve the exact DO-014 assertions.

- [ ] **Step 2: Add strict ConfigVersion and API023Request behavior tests**

Import `do015Schema` and `api023RequestSchema`, then add:

```ts
it('accepts only the approved DO-015 fields and edit whitelist', () => {
  const config = fixtures.objects['DO-015'][0];
  expect(do015Schema.parse(config)).toEqual(config);
  expect(do015Schema.safeParse({ ...config, areaCode: 'AREA-A' }).success).toBe(false);
  expect(do015Schema.safeParse({ ...config, auditRetentionDays: 0 }).success).toBe(false);
  expect(do015Schema.safeParse({ ...config, dispatchStrategy: 'RANDOM' }).success).toBe(false);

  expect(api023RequestSchema.safeParse({
    command: 'edit',
    expectedVersion: 1,
    changes: { displayName: '新的 Demo 名称', offlineSyncEnabled: false },
  }).success).toBe(true);
  expect(api023RequestSchema.safeParse({
    command: 'edit', expectedVersion: 1, changes: {},
  }).success).toBe(false);
  expect(api023RequestSchema.safeParse({
    command: 'edit', expectedVersion: 1, changes: { areaCode: 'AREA-A' },
  }).success).toBe(false);
  expect(api023RequestSchema.safeParse({
    command: 'submit', expectedVersion: 1, changes: { displayName: 'forbidden' },
  }).success).toBe(false);
  expect(api023RequestSchema.safeParse({
    command: 'publish', expectedVersion: 1,
  }).success).toBe(true);
});
```

- [ ] **Step 3: Require specialized OpenAPI schemas for API-022/023**

In `openapi-consistency.test.ts`, retain generic envelopes for the other 23 APIs and require:

```ts
const expectedSuccessSchema = operation['x-api-id'] === 'API-022'
  ? 'API022SuccessEnvelope'
  : operation['x-api-id'] === 'API-023'
    ? 'API023SuccessEnvelope'
    : 'ApiSuccessEnvelope';

expect(runtime?.responseSchemas[200]).toBe(expectedSuccessSchema);
expect(schemaName(operation.responses['200'].content['application/json'].schema.$ref))
  .toBe(expectedSuccessSchema);
```

Change the domain component assertion to `DO001` through `DO015`. Add an assertion that OpenAPI `API023Request` is a `oneOf` with one edit branch and one state-command branch, and that the API-022/023 examples contain `CFG-001` rather than `USER-001`.

- [ ] **Step 4: Require exactly 15 fixture groups**

In `fixtures.test.ts`, update the expected IDs and counts:

```ts
const expectedCounts = [3, 8, 4, 8, 12, 12, 10, 6, 5, 4, 4, 3, 9, 13, 1];
expect(Object.keys(snapshot.objects)).toEqual(
  Array.from({ length: 15 }, (_, index) => `DO-${String(index + 1).padStart(3, '0')}`),
);
```

- [ ] **Step 5: Run the focused contract tests and verify RED**

Run:

```powershell
pnpm exec vitest run src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/mocks/__tests__/fixtures.test.ts
```

Expected: FAIL because DO-015, `do015Schema`, the new API023 shape, and specialized response schemas do not exist. The failure must be about the missing feature, not syntax or a bad import; if necessary, temporarily access missing exports through the module object until the test can fail as an assertion.

- [ ] **Step 6: Record the exact red result**

Create `docs/evidence/C13A/config-contract-red.txt` with the command, exit code, failed test names, and representative expected/actual lines.

---

### Task 3: Implement the 0.3.0 DO-015 contract baseline

**Files:**
- Modify: `docs/baseline/openapi.yaml`
- Modify: `docs/baseline/demo-fixtures.json`
- Modify: `docs/baseline/README.md`
- Modify: `src/contracts/enums.ts`
- Modify: `src/contracts/schemas.ts`
- Modify: `src/contracts/requests.ts`
- Modify: `src/contracts/api.ts`
- Test: files from Task 2

**Interfaces:**
- Consumes: Task 2 failing assertions and the approved field/status values.
- Produces: `ConfigVersion`, `api023RequestSchema`, `api022SuccessEnvelopeSchema`, `api023SuccessEnvelopeSchema`, and a 15-object fixture catalog.

- [ ] **Step 1: Add the three frozen enum families**

Add to `enums.ts`:

```ts
export const configStatuses = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED', 'ROLLED_BACK'] as const;
export const dispatchStrategies = ['BALANCED', 'PRIORITY_FIRST', 'RESOURCE_FIRST'] as const;
export const configReportPeriods = ['SHIFT', 'DAILY', 'MONTHLY'] as const;

export const configStatusSchema = z.enum(configStatuses);
export const dispatchStrategySchema = z.enum(dispatchStrategies);
export const configReportPeriodSchema = z.enum(configReportPeriods);
```

Also add `ConfigStatus`, `DispatchStrategy`, and `ConfigReportPeriod` to `enumValuesByComponent` so OpenAPI equality tests enforce the values.

- [ ] **Step 2: Add the strict DO-015 runtime Schema and specialized response schemas**

Add to `schemas.ts` after DO-014:

```ts
export const do015Schema = z.object({
  id: z.string().trim().min(1),
  configVersion: z.string().trim().min(1),
  displayName: z.string().trim().min(1).max(64),
  defaultScenarioId: z.enum(['SCN-01', 'SCN-02', 'SCN-03', 'SCN-04', 'SCN-05', 'SCN-06', 'SCN-07']),
  ruleVersion: z.string().trim().min(1).max(32),
  dispatchStrategy: dispatchStrategySchema,
  recommendationEnabled: z.boolean(),
  offlineSyncEnabled: z.boolean(),
  reportPeriod: configReportPeriodSchema,
  auditRetentionDays: z.number().int().min(1).max(3650),
  status: configStatusSchema,
  version: z.number().int().positive(),
  createdAt: z.string().min(1),
  updatedAt: z.string().min(1),
  updatedBy: z.string().trim().min(1),
}).strict();

export const api022DataSchema = z.object({
  apiId: z.literal('API-022'),
  operationId: z.literal('GET_mock_config'),
  now: z.string().min(1),
  scenarioId: z.string().min(1),
  items: do015Schema.array(),
}).strict();

export const api023DataSchema = z.object({
  apiId: z.literal('API-023'),
  operationId: z.literal('POST_mock_config_id_command'),
  now: z.string().min(1),
  scenarioId: z.string().min(1),
  items: do015Schema.array().length(1),
}).strict();

export const api022SuccessEnvelopeSchema = apiSuccessEnvelopeSchema.extend({ data: api022DataSchema }).strict();
export const api023SuccessEnvelopeSchema = apiSuccessEnvelopeSchema.extend({ data: api023DataSchema }).strict();
```

Append DO-015 to `domainObjectIds`, `domainSchemas`, and `domainObjectCounts`; export `ConfigVersion` and the two specialized envelope types.

- [ ] **Step 3: Replace the legacy API023Request with a discriminated union**

In `requests.ts`, define the reusable changes Schema and exact union:

```ts
export const configVersionChangesSchema = do015Schema.pick({
  displayName: true,
  defaultScenarioId: true,
  ruleVersion: true,
  dispatchStrategy: true,
  recommendationEnabled: true,
  offlineSyncEnabled: true,
  reportPeriod: true,
  auditRetentionDays: true,
}).partial().strict().refine((changes) => Object.keys(changes).length > 0, {
  message: 'edit changes must contain at least one approved field.',
});

const configCommandBase = {
  expectedVersion: z.number().int().positive(),
  reason: z.string().trim().min(1).optional(),
};

export const api023RequestSchema = z.discriminatedUnion('command', [
  z.object({ command: z.literal('edit'), ...configCommandBase, changes: configVersionChangesSchema }).strict(),
  z.object({
    command: z.enum(['submit', 'approve', 'publish', 'rollback']),
    ...configCommandBase,
  }).strict(),
]);
```

Import `do015Schema`; keep `requestSchemas.API023Request` and all other request schemas unchanged.

- [ ] **Step 4: Add the deterministic DO-015 fixture**

Append the exact approved `CFG-001` object from the design to `objects."DO-015"`. Do not alter the serialized content of DO-001 through DO-014, roles, scenarios, acceptance scenarios, or errors.

- [ ] **Step 5: Repair OpenAPI components and API-022/023 operations**

Add enum components with the exact names used by `enumValuesByComponent`, add strict `DO015`, replace `API023Request` with `oneOf` edit/state-command branches, and add `API022Data`, `API023Data`, `API022SuccessEnvelope`, and `API023SuccessEnvelope`.

Change only the API-022/API-023 200 refs and examples:

```json
"schema": { "$ref": "#/components/schemas/API022SuccessEnvelope" }
```

and:

```json
"schema": { "$ref": "#/components/schemas/API023SuccessEnvelope" }
```

Both examples include `apiId`, exact `operationId`, fixed `now`, `scenarioId: "SCN-01"`, and DO-015 `items`; API-023 has one item. Keep 400/403/409 public error refs unchanged.

- [ ] **Step 6: Let the runtime catalog name the specialized success Schemas**

Broaden `ApiContract.responseSchemas[200]` to:

```ts
200: 'ApiSuccessEnvelope' | 'API022SuccessEnvelope' | 'API023SuccessEnvelope';
```

Give API-022 and API-023 their own response maps while preserving the other 23 operations exactly.

- [ ] **Step 7: Update the baseline README**

Set the baseline version to 0.3.0, describe 15 domain objects, identify C13A as the repair stage, and state that UI-012 remains unimplemented. Do not change dependency or page counts.

- [ ] **Step 8: Run the focused contract tests and verify GREEN**

Run:

```powershell
pnpm exec vitest run src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/mocks/__tests__/fixtures.test.ts
```

Expected: all selected tests pass with zero failures.

- [ ] **Step 9: Verify untouched baseline sections and commit**

Compare `DO-001` through `DO-014`, roles, scenarios, acceptance scenarios, errors, package baseline, page matrix, and traceability against the C12 base with a read-only PowerShell/JSON comparison. Run `git diff --check`, then commit:

```powershell
git add docs/baseline src/contracts src/mocks/__tests__/fixtures.test.ts docs/evidence/C13A/config-contract-red.txt
git diff --cached --check
git commit -m "fix(c13a): add DO-015 config contract baseline"
```

Expected: no upstream object or unrelated baseline delta.

---

### Task 4: Repair API-022/023 Mock mapping without creating a second owner

**Files:**
- Modify: `src/mocks/__tests__/handlers.test.ts`
- Modify: `src/mocks/handlers.ts`

**Interfaces:**
- Consumes: `do015Schema`, API023Request, frozen DO-015 fixture.
- Produces: API-022 DO-015 list observation and API-023 one-item target observation.

- [ ] **Step 1: Add the failing handler tests**

Import `do015Schema` and add:

```ts
it('API-022 lists DO-015 and API-023 returns only CFG-001', async () => {
  const list = await fetch(new URL('/mock/config', window.location.origin));
  const listPayload = await list.json();
  const command = await fetch(new URL('/mock/config/CFG-001/command', window.location.origin), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      command: 'edit',
      expectedVersion: 1,
      changes: { displayName: 'B项目生产调度 Demo（草稿）' },
    }),
  });
  const commandPayload = await command.json();

  expect(listPayload.data.items).toHaveLength(1);
  expect(listPayload.data.items[0]).toMatchObject({ id: 'CFG-001', configVersion: 'CFG-1.0' });
  expect(do015Schema.safeParse(listPayload.data.items[0]).success).toBe(true);
  expect(commandPayload.data.items).toHaveLength(1);
  expect(commandPayload.data.items[0].id).toBe('CFG-001');
  expect(do015Schema.safeParse(commandPayload.data.items[0]).success).toBe(true);
});

it('API-023 rejects legacy bodies, broadened changes, and unknown config ids', async () => {
  for (const [id, body] of [
    ['CFG-001', { edit: 'edit', submit: 'submit', approve: 'approve', publish: 'publish', rollback: 'rollback' }],
    ['CFG-001', { command: 'edit', expectedVersion: 1, changes: { areaCode: 'AREA-A' } }],
    ['CFG-404', { command: 'edit', expectedVersion: 1, changes: { displayName: 'missing' } }],
  ] as const) {
    const response = await fetch(new URL(`/mock/config/${id}/command`, window.location.origin), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    expect(response.status).toBe(400);
    expect(await response.json()).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
  }
});
```

- [ ] **Step 2: Run the handler tests and verify RED**

Run:

```powershell
pnpm exec vitest run src/mocks/__tests__/handlers.test.ts
```

Expected: the new API-022 assertion sees DO-014 user records or the API-023 request/identity assertion fails.

- [ ] **Step 3: Change only API-022/API-023 object mapping**

In `handlers.ts`, replace:

```ts
'API-022': ['DO-014'],
'API-023': ['DO-014'],
```

with:

```ts
'API-022': ['DO-015'],
'API-023': ['DO-015'],
```

Before the success response, detect an API-023 path ID absent from the projected DO-015 records and return status 400 `DEMO-SCENARIO-001`. Do not call `updateObject`, `replaceDomainState`, or add mutable config state to MockRuntime.

- [ ] **Step 4: Run handler and contract tests and verify GREEN**

Run:

```powershell
pnpm exec vitest run src/mocks/__tests__/handlers.test.ts src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts
```

Expected: all selected tests pass; the existing 25-operation loop accepts the new API-023 example.

- [ ] **Step 5: Commit the mapping repair**

```powershell
git add src/mocks/handlers.ts src/mocks/__tests__/handlers.test.ts
git diff --cached --check
git commit -m "fix(c13a): map config apis to DO-015"
```

---

### Task 5: Add dedicated Store ownership and deterministic reset

**Files:**
- Modify: `src/stores/__tests__/store.test.ts`
- Modify: `src/stores/types.ts`
- Modify: `src/stores/slices.ts`
- Modify: `src/stores/initialState.ts`
- Create: `docs/evidence/C13A/config-store-red.txt`

**Interfaces:**
- Consumes: `ConfigVersion`, `do015Schema`, fixture DO-015.
- Produces: `SystemConfigSlice`, `createSystemConfigSlice`, `state.systemConfig.configVersions`.

- [ ] **Step 1: Add failing Store ownership tests**

Append `systemConfig` to `sliceKeys`, change the exact slice count to 13, and add `configVersions` to the expected counts. Add:

```ts
it('owns one deeply frozen DO-015 record outside configAudit', () => {
  const runtime = createMockRuntime();
  const store = createDemoStore(runtime.getSnapshot(), baseSession);
  const config = store.getState().systemConfig.configVersions[0];

  expect(config).toEqual(runtime.getSnapshot().objects['DO-015'][0]);
  expect(config.id).toBe('CFG-001');
  expect(store.getState().configAudit).not.toHaveProperty('configVersions');
  expect(() => { config.displayName = 'caller mutation'; }).toThrow();
});

it('atomically validates and resets DO-015 without touching upstream slices', () => {
  const runtime = createMockRuntime();
  const snapshot = runtime.getSnapshot();
  const store = createDemoStore(snapshot, baseSession);
  const upstream = structuredClone({
    plan: store.getState().plan,
    workOrder: store.getState().workOrder,
    configAudit: store.getState().configAudit,
  });

  store.replaceDomainState((candidate) => {
    candidate.systemConfig.configVersions[0] = {
      ...candidate.systemConfig.configVersions[0], displayName: '草稿名称', version: 2,
    };
  });
  expect(store.getState().systemConfig.configVersions[0].displayName).toBe('草稿名称');
  expect(store.getState().plan).toEqual(upstream.plan);
  expect(store.getState().workOrder).toEqual(upstream.workOrder);
  expect(store.getState().configAudit).toEqual(upstream.configAudit);

  store.resetFromSnapshot(snapshot, baseSession);
  expect(store.getState().systemConfig.configVersions).toEqual(snapshot.objects['DO-015']);
});
```

- [ ] **Step 2: Run the Store test and verify RED**

Run:

```powershell
pnpm exec vitest run src/stores/__tests__/store.test.ts
```

Expected: FAIL because `systemConfig` does not exist.

- [ ] **Step 3: Record the exact Store red result**

Create `config-store-red.txt` with the command, exit code, failing test names, and the missing-slice assertion/type error.

- [ ] **Step 4: Implement the dedicated slice**

In `types.ts`:

```ts
export type SystemConfigSlice = { configVersions: ConfigVersion[] };
```

Import `ConfigVersion`, add `systemConfig: SystemConfigSlice` to `DemoRootState`, and leave `ConfigAuditSlice` unchanged.

In `slices.ts`:

```ts
export function createSystemConfigSlice(value: SystemConfigSlice): SystemConfigSlice {
  return value;
}
```

In both `createInitialState` and `validateDemoRootState`:

```ts
systemConfig: createSystemConfigSlice({
  configVersions: do015Schema.array().parse(structuredClone(input.objects['DO-015'])),
}),
```

and for root validation use `input.systemConfig.configVersions`. Do not alter `createDemoStore`; its existing root deep-freeze and reset path must cover the new slice automatically.

- [ ] **Step 5: Run Store, fixture, and runtime tests and verify GREEN**

Run:

```powershell
pnpm exec vitest run src/stores/__tests__/store.test.ts src/mocks/__tests__/fixtures.test.ts src/runtime/__tests__/runtime.test.tsx
```

Expected: all selected tests pass; existing reset semantics remain intact.

- [ ] **Step 6: Commit Store ownership**

```powershell
git add src/stores docs/evidence/C13A/config-store-red.txt
git diff --cached --check
git commit -m "fix(c13a): add config store ownership"
```

---

### Task 6: Register the DO-015 lifecycle

**Files:**
- Modify: `src/commands/__tests__/stateMachines.test.ts`
- Modify: `src/commands/stateMachines.ts`

**Interfaces:**
- Consumes: ConfigStatus and approved command names.
- Produces: `MachineId` support and deterministic transition results for DO-015.

- [ ] **Step 1: Add the exact expected machine and forbidden paths**

Add to `expectedMachines`:

```ts
'DO-015': {
  states: ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED', 'ROLLED_BACK'],
  commands: ['edit', 'submit', 'approve', 'publish', 'rollback'],
  transitions: [
    ['DRAFT', 'edit', 'DRAFT'],
    ['DRAFT', 'submit', 'SUBMITTED'],
    ['SUBMITTED', 'approve', 'APPROVED'],
    ['APPROVED', 'publish', 'PUBLISHED'],
    ['PUBLISHED', 'rollback', 'ROLLED_BACK'],
  ],
},
```

Add representative forbidden rows for `DRAFT + approve`, `SUBMITTED + publish`, and `ROLLED_BACK + edit`. Update the exact machine count description from seven to eight.

- [ ] **Step 2: Run the state-machine test and verify RED**

Run:

```powershell
pnpm exec vitest run src/commands/__tests__/stateMachines.test.ts
```

Expected: FAIL because DO-015 is not a `MachineId` or catalog member.

- [ ] **Step 3: Add DO-015 to the production catalog**

Extend `MachineId` with `'DO-015'` and copy the exact machine definition above into `stateMachineCatalog`. Do not change any existing machine.

- [ ] **Step 4: Run state-machine and command regressions**

```powershell
pnpm exec vitest run src/commands/__tests__/stateMachines.test.ts src/commands/__tests__/pipeline.test.ts src/commands/__tests__/authorization-integration.test.ts
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit the lifecycle**

```powershell
git add src/commands/stateMachines.ts src/commands/__tests__/stateMachines.test.ts
git diff --cached --check
git commit -m "fix(c13a): register config lifecycle"
```

---

### Task 7: Freeze 0.3.0 hashes and verify the complete repair

**Files:**
- Modify: `docs/baseline/SHA256SUMS.txt`
- Create: `docs/evidence/C13A/SHA256SUMS-after.txt`
- Create: `docs/evidence/C13A/tsc-after.txt`
- Create: `docs/evidence/C13A/verification.md`

**Interfaces:**
- Consumes: completed contract, Mock, Store, lifecycle, and all test output.
- Produces: reproducible six-file freeze and evidence-backed completion decision.

- [ ] **Step 1: Compute the six final hashes**

Run `Get-FileHash -Algorithm SHA256` for README, package-baseline, OpenAPI, fixtures, page matrix, and traceability. Update `SHA256SUMS.txt` and `SHA256SUMS-after.txt` through `apply_patch` with the exact lowercase hashes and existing filename order.

- [ ] **Step 2: Verify the hash manifest 6/6**

Parse every manifest line, recompute the corresponding file hash, and compare case-insensitively.

Expected: exactly six entries and six matches.

- [ ] **Step 3: Run C13A focused Vitest**

```powershell
pnpm exec vitest run src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/mocks/__tests__/fixtures.test.ts src/mocks/__tests__/handlers.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts src/runtime/__tests__/runtime.test.tsx
```

Expected: zero failed tests.

- [ ] **Step 4: Run full Vitest**

```powershell
pnpm test
```

Expected: zero failures. If the known UI-002 SCN-02 case alone times out under aggregate load, record the full failure and rerun exactly that test in isolation; do not report the full suite as passing.

- [ ] **Step 5: Run TypeScript and classify diagnostics**

```powershell
pnpm exec tsc --noEmit --pretty false
```

Write exact exit code and counts to `tsc-after.txt`. Compare with `tsc-before.txt`; C13A must introduce zero non-React/JSX diagnostic families.

- [ ] **Step 6: Run production build and full Playwright**

```powershell
pnpm build
pnpm test:e2e
```

Expected: build exits 0 and all Playwright tests pass. No C13A screenshots are expected.

- [ ] **Step 7: Run scope and Git verification**

Run:

```powershell
git diff --check f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
git rev-list --merges --count f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
git diff --name-only f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
git status --short --branch
```

Also compare DO-001～DO-014 JSON, dependencies, page files, RBAC catalog, and C12 feature paths against the C12 base.

Expected: diff check passes, merge count is 0, only approved paths changed, no forbidden path changed, and no unstaged/untracked files remain after the final documentation commit.

- [ ] **Step 8: Write verification evidence**

Create `verification.md` with exact command timestamps, exit codes, test counts, build module count, Playwright count, TypeScript classification, 6/6 hash result, unchanged-upstream proof, merge count, and known limitations. Do not infer or fabricate coverage percentages.

---

### Task 8: Write the C13A handoff and C13 restart prompt

**Files:**
- Create: `docs/handoffs/C13A-config-contract-baseline.md`
- Create: `docs/conversation-prompts/C13-system-settings.md`
- Modify: `docs/evidence/C13A/verification.md` if final commit IDs require completion

**Interfaces:**
- Consumes: final verified branch state, new hash manifest, approved C13A exports.
- Produces: an exact C13 start point that no longer permits contract guessing.

- [ ] **Step 1: Write the C13A handoff**

Document:

- DO-015 exact fields, enums, fixture ID/count, Store path, API-022/023 contracts, and lifecycle;
- API transport versus Store ownership boundary;
- preserved DO-014 and upstream invariants;
- all verification results and known limitations;
- exact branch, C12 base, commit chain, new hash manifest, and no-merge/no-push status.

- [ ] **Step 2: Write the C13 restart prompt**

Require C13 to:

- start from the final `demo/c13-config-contract-baseline` tip and create `demo/c13-system-settings` only then;
- verify the new 0.3.0 6/6 hashes;
- consume DO-015 from `state.systemConfig.configVersions`;
- use API-022 for read observation and API-023 `edit` with allowlisted changes;
- expose no approval/publish/rollback UI;
- perform Gateway-first, revalidation, atomic Store commit, and supported DO-013 audit append;
- never change the newly frozen baseline, common contracts, Store ownership, or lifecycle.

- [ ] **Step 3: Run fresh final verification after documentation changes**

At minimum rerun:

```powershell
git diff --check
pnpm exec vitest run src/contracts/__tests__/schemas.test.ts src/mocks/__tests__/handlers.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts
```

Reconfirm the six baseline hashes because handoff documents are not part of the manifest.

- [ ] **Step 4: Commit evidence and handoff**

```powershell
git add docs/baseline/SHA256SUMS.txt docs/evidence/C13A docs/handoffs/C13A-config-contract-baseline.md docs/conversation-prompts/C13-system-settings.md
git diff --cached --check
git commit -m "docs(c13a): add config contract evidence and handoff"
```

- [ ] **Step 5: Verify the final clean branch**

```powershell
git status --short --branch
git rev-parse HEAD
git log --oneline --reverse f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
git rev-list --merges --count f85ede995c6958bbf36e1aa561b1ca7332fb8588..HEAD
```

Expected: clean `demo/c13-config-contract-baseline`, linear commits, merge count 0, and no push.
