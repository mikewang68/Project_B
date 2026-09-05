# C13 System Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the UI-012 scaffold with a deterministic system-settings workbench that reads the single DO-015 ConfigVersion from Store, edits only the approved eight-field whitelist, previews changes in an in-page fixed summary, saves through API-023 with atomic Store/audit semantics, and proves the result at 1440×900 and 1280×720.

**Architecture:** `state.systemConfig.configVersions` remains the only mutable configuration truth. A dedicated `system-settings` feature owns transport validation, URL query normalization, ephemeral draft/workflow state, and the save command; API-022/023 responses are observations only. The shared runtime mounts the feature and clears its local workflow/idempotency cache after an already-authorized API-025 reset. The page composes narrow feature components and never owns contracts, audit construction, or commit logic.

**Tech Stack:** React 19, React Router 7, TypeScript 7, Zod 4, Zustand 5, Ant Design 6, MSW 2, Vitest 4, Testing Library 16, Playwright 1.61, Vite 8, PowerShell, Git.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-07-22-c13-system-settings-design.md` at commit `69026e7af01d760ec6e807be5e5c8c24f3f7034d`.
- Working branch: `demo/c13-system-settings`; authoritative C13A base: `a35a93916fa80d1506cdcbd6460b53504e0a88f9`; C12 base: `f85ede995c6958bbf36e1aa561b1ca7332fb8588`.
- Do not merge or push. Keep history linear and merge count zero.
- Do not modify `docs/baseline/**`, `package.json`, `pnpm-lock.yaml`, public ConfigVersion/API contracts, Store ownership, DO-015 state machine, route catalog, or permission catalog.
- DO-015 is the sole configuration identity. DO-014 remains UserRole. Do not create a second mutable configuration copy in the Gateway, MockRuntime, workflow, page, or API observation.
- Editable keys are exactly `displayName`, `defaultScenarioId`, `ruleVersion`, `dispatchStrategy`, `recommendationEnabled`, `offlineSyncEnabled`, `reportPeriod`, and `auditRetentionDays`.
- `id`, `configVersion`, `status`, `version`, `createdAt`, `updatedAt`, and `updatedBy` are read-only. Session/runtime fields are read-only projections and never enter API-023 `changes`.
- UI-012 exposes API-023 `edit` only. It must not expose `submit`, `approve`, `publish`, or `rollback`.
- The page reset control remains disabled because all UI-012 roles lack `demo:reset`; do not weaken RBAC or call API-025 from the page. The shared runtime must still clear C13 local state when another authorized flow completes API-025 reset.
- Use TDD for every behavior: add a focused test, run it, confirm the failure is caused by the missing behavior, preserve concise RED evidence, then add only enough production code to make that test pass.
- Use `node_modules\.bin\vitest.CMD` and `node_modules\.bin\tsc.CMD` directly. Do not install coverage or type packages.
- Use `apply_patch` for source, test, plan, evidence, and handoff edits. Never replace user changes or use destructive Git operations.
- Preserve C04-C12 domain slices and feature-local state. Every C13 mutation test must deep-compare upstream state before and after.
- The page must visibly say `Demo 系统配置视图，非生产配置中心` and must not claim production persistence, credential management, approval, publishing, rollback, or compliance archiving.

---

## Target File Structure

### Feature implementation

- Create `src/features/system-settings/systemSettingsTypes.ts`: group IDs, editable-value types, read/save observations, draft validation, change preview, and feedback types.
- Create `src/features/system-settings/systemSettingsProjection.ts`: strict DO-015 + session/runtime projection and five stable field groups.
- Create `src/features/system-settings/systemSettingsQueries.ts`: first-value query parsing and normalization.
- Create `src/features/system-settings/systemSettingsDraft.ts`: draft creation, allowlisted update, validation, diff, request changes, and display formatting.
- Create `src/features/system-settings/systemSettingsGateway.ts`: specialized API-022/023 Gateway and identity validation.
- Create `src/features/system-settings/systemSettingsRuntime.ts`: feature-local immutable workflow store.
- Create `src/features/system-settings/systemSettingsCommands.ts`: permission, validation, Gateway-first save, re-read, transition, atomic config/audit commit, failure audit, and idempotency.
- Create `src/features/system-settings/components/SystemSettingsContextHeader.tsx`.
- Create `src/features/system-settings/components/SystemSettingsGroupNav.tsx`.
- Create `src/features/system-settings/components/SystemSettingsFields.tsx`.
- Create `src/features/system-settings/components/SystemSettingsChangeSummary.tsx`.
- Create `src/features/system-settings/components/SystemSettingsReadBanner.tsx`.
- Create `src/features/system-settings/components/SystemSettingsCommandFeedback.tsx`.
- Create `src/features/system-settings/system-settings.css`.
- Create `src/features/system-settings/index.ts`.

### Integration

- Modify `src/runtime/DemoRuntimeContext.tsx`: expose `runtime.systemSettings`, instantiate Gateway/workflow/commands, add `useSystemSettingsWorkflow`, and reset C13 local state only from the existing successful reset callback.
- Modify `src/mocks/handlers.ts`: add feature-local `x-demo-c13-fault` behavior for API-022/023 only.
- Replace `src/pages/settings/SystemSettingsPage.tsx`: compose the feature and preserve Store ownership.

### Tests and evidence

- Create core tests under `src/features/system-settings/__tests__/`.
- Create frozen page tests `src/pages/__tests__/SystemSettingsPage.render.test.tsx`, `SystemSettingsPage.permission.test.tsx`, and `SystemSettingsPage.action.test.tsx` plus `systemSettingsTestHarness.tsx`.
- Create `e2e/ui-012-settings-system.spec.ts`.
- Create `docs/evidence/C13/preflight.md`, `tsc-before.txt`, `settings-core-red.txt`, `settings-page-red.txt`, `settings-e2e-red.txt`, `screenshot-index.md`, `coverage.json`, `verification.md`, and `tsc-after.txt`.
- Create `docs/handoffs/C13-system-settings.md` and update `docs/conversation-prompts/C13-system-settings.md` to point at this design/plan and the completed evidence.

---

### Task 1: Freeze the C13 preflight and execution boundary

**Files:**
- Create: `docs/evidence/C13/preflight.md`
- Create: `docs/evidence/C13/tsc-before.txt`
- Modify: `docs/conversation-prompts/C13-system-settings.md`

**Interfaces:**
- Consumes: design commit, C13A base/handoff, six frozen 0.3.0 hashes, current full-test limitations.
- Produces: exact starting evidence and a prompt that cannot drift from the approved C13 scope.

- [ ] **Step 1: Verify branch, ancestry, status, hashes, and history shape**

Run each command and retain its actual output:

```powershell
git status --short --branch
git rev-parse HEAD
git merge-base --is-ancestor a35a93916fa80d1506cdcbd6460b53504e0a88f9 HEAD
git rev-list --merges --count a35a93916fa80d1506cdcbd6460b53504e0a88f9..HEAD
Get-FileHash docs/baseline/README.md,docs/baseline/package-baseline.json,docs/baseline/openapi.yaml,docs/baseline/demo-fixtures.json,docs/baseline/page-task-matrix.csv,docs/baseline/traceability.csv -Algorithm SHA256
```

Expected: branch `demo/c13-system-settings`, HEAD is the approved design commit, worktree clean, C13A ancestor check exits 0, merge count 0, and all six hashes match `docs/baseline/SHA256SUMS.txt`.

- [ ] **Step 2: Re-run the C13A focused baseline and capture TypeScript before**

Run:

```powershell
node_modules\.bin\vitest.CMD run src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/mocks/__tests__/fixtures.test.ts src/mocks/__tests__/handlers.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts src/runtime/__tests__/runtime.test.tsx
node_modules\.bin\tsc.CMD --noEmit --pretty false
```

Expected: C13A focused suite remains 149/149. TypeScript remains the frozen 783 primary diagnostics (`TS2604=65`, `TS7016=126`, `TS7026=592`) and no other code family; record actual output rather than predicting success.

- [ ] **Step 3: Write exact preflight evidence and align the execution prompt**

Use `apply_patch` to record observed commit/hash/test/type results. Update the prompt to include:

```text
design = docs/superpowers/specs/2026-07-22-c13-system-settings-design.md
plan   = docs/superpowers/plans/2026-07-22-c13-system-settings.md
branch = demo/c13-system-settings
```

Retain its DO-015 ownership, whitelist, Gateway-first, reset-permission, no-merge, and no-push rules.

- [ ] **Step 4: Verify and commit the non-production boundary**

```powershell
git diff --check
git add docs/evidence/C13/preflight.md docs/evidence/C13/tsc-before.txt docs/conversation-prompts/C13-system-settings.md
git diff --cached --check
git commit -m "test(c13): record system settings preflight"
```

---

### Task 2: Drive projection, query, and draft behavior from failing tests

**Files:**
- Create: `src/features/system-settings/__tests__/systemSettingsProjection.test.ts`
- Create: `src/features/system-settings/__tests__/systemSettingsQueries.test.ts`
- Create: `src/features/system-settings/__tests__/systemSettingsDraft.test.ts`
- Create: `src/features/system-settings/systemSettingsTypes.ts`
- Create: `src/features/system-settings/systemSettingsProjection.ts`
- Create: `src/features/system-settings/systemSettingsQueries.ts`
- Create: `src/features/system-settings/systemSettingsDraft.ts`
- Create: `src/features/system-settings/index.ts`
- Create: `docs/evidence/C13/settings-core-red.txt`

**Interfaces:**

```ts
export const systemSettingsGroupIds = [
  'overview', 'dispatch', 'integration', 'governance', 'access',
] as const;

export const editableConfigKeys = [
  'displayName', 'defaultScenarioId', 'ruleVersion', 'dispatchStrategy',
  'recommendationEnabled', 'offlineSyncEnabled', 'reportPeriod',
  'auditRetentionDays',
] as const;

export type SystemSettingsQuery = Readonly<{
  group: SystemSettingsGroupId;
  configId?: string;
  scenarioId?: string;
  from?: string;
}>;

export type SystemSettingsDraft = Readonly<{
  values: EditableConfigValues;
  baseVersion: number;
  reason: string;
}>;
```

- [ ] **Step 1: Write projection tests before modules exist**

Test with `createFixtureSnapshot` + `createDemoStore` that `projectSystemSettings(state)`:

- returns exactly `CFG-001` parsed by `do015Schema`;
- produces groups in the fixed five-ID order;
- places only the approved editable fields in editable group rows;
- places metadata/session/runtime rows in read-only groups;
- does not add `areaCode`, `timezone`, `roleCode`, `dataScope`, `shiftId`, `online`, `demoTime`, or API state to the ConfigVersion object;
- returns an empty projection when `configVersions` is empty;
- returns safe not-found when a requested `configId` differs from `CFG-001`.

- [ ] **Step 2: Write query normalization tests**

Cover:

```ts
parseSystemSettingsQuery('?group=dispatch&configId=%20CFG-001%20&scenarioId=SCN-01&from=overview')
parseSystemSettingsQuery('?group=unknown&group=access&configId=%20%20')
```

Require first repeated value, trim, unknown group → `overview`, empty text omission, and no invented defaults for arbitrary text fields.

- [ ] **Step 3: Write draft and strict-change tests**

Require:

- `createSystemSettingsDraft(config)` copies exactly eight editable fields and `version`;
- `updateSystemSettingsDraft` accepts only `EditableConfigKey` at type/runtime boundaries;
- validation uses `configVersionChangesSchema`/`do015Schema`-derived rules;
- whitespace-only display/rule names, invalid enum/scenario, and non-integer/out-of-range retention fail at the correct field;
- non-empty trimmed reason is a C13 form rule;
- `deriveConfigChanges` returns only real differences in whitelist order;
- unchanged values create no `changes` and a form-level error;
- preview entries have deterministic Chinese labels and before/after display values;
- discard returns to Store values and removes all errors/preview state.

- [ ] **Step 4: Run the three tests and confirm RED**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsProjection.test.ts src/features/system-settings/__tests__/systemSettingsQueries.test.ts src/features/system-settings/__tests__/systemSettingsDraft.test.ts
```

Expected: failure is caused by missing `system-settings` modules/exports. Save the command, exit code, failed files, and representative missing-behavior message in `docs/evidence/C13/settings-core-red.txt`.

- [ ] **Step 5: Implement immutable types, projection, query, and draft helpers**

Key implementation rules:

```ts
const config = state.systemConfig.configVersions
  .find(({ id }) => id === requestedConfigId) ?? state.systemConfig.configVersions[0];

const changes = configVersionChangesSchema.parse(
  Object.fromEntries(editableConfigKeys
    .filter((key) => !Object.is(config[key], draft.values[key]))
    .map((key) => [key, draft.values[key]])),
);
```

Do not let query selection fall back to `CFG-001` when an explicit unknown `configId` was provided. Deep-freeze returned projection, draft result, errors, and preview collections.

- [ ] **Step 6: Prove GREEN and commit**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsProjection.test.ts src/features/system-settings/__tests__/systemSettingsQueries.test.ts src/features/system-settings/__tests__/systemSettingsDraft.test.ts
git diff --check
git add src/features/system-settings docs/evidence/C13/settings-core-red.txt
git diff --cached --check
git commit -m "feat(c13): add settings projections and drafts"
```

---

### Task 3: Drive the API-022/023 Gateway and fault mapping from failing tests

**Files:**
- Create: `src/features/system-settings/__tests__/systemSettingsGateway.test.ts`
- Modify: `src/mocks/__tests__/handlers.test.ts`
- Create: `src/features/system-settings/systemSettingsGateway.ts`
- Modify: `src/features/system-settings/index.ts`
- Modify: `src/mocks/handlers.ts`
- Modify: `docs/evidence/C13/settings-core-red.txt`

**Interfaces:**

```ts
export type ListConfigQuery = Readonly<{
  expectedScenarioId: string;
  expectedNow: string;
  expectedConfigId: string;
  fault?: 'network' | 'malformed' | 'business';
}>;

export type EditConfigInput = Readonly<{
  configId: string;
  expectedScenarioId: string;
  expectedNow: string;
  expectedVersion: number;
  changes: ConfigVersionChanges;
  reason: string;
  fault?: 'network' | 'malformed' | 'business';
}>;

export type SystemSettingsGateway = Readonly<{
  listConfig(query: ListConfigQuery): Promise<SystemSettingsGatewayResult>;
  editConfig(input: EditConfigInput): Promise<SystemSettingsGatewayResult>;
}>;
```

- [ ] **Step 1: Write specialized Gateway tests**

For API-022, require GET `/mock/config`, strict `api022SuccessEnvelopeSchema`, exact API/operation IDs, expected scenario/time, unique target, deeply frozen observation, public error parsing, network rejection, malformed rejection, scenario/time mismatch rejection, and missing/duplicate target rejection.

For API-023, inspect the fetch request and require:

```json
{
  "command": "edit",
  "expectedVersion": 1,
  "changes": { "displayName": "新的 Demo 名称" },
  "reason": "演示配置变更"
}
```

Require strict `api023RequestSchema`, exact API/operation IDs, expected scenario/time, exactly one DO-015 item matching the URL target, and separate transport `auditLogId`/`traceId`.

- [ ] **Step 2: Add failing handler assertions for the feature-local fault header**

Extend handler tests so `x-demo-c13-fault` affects API-022 and API-023 only:

- `network` yields a transport error;
- `malformed` returns a 200 body rejected by specialized schemas;
- `business` returns 409 `DEMO-SCENARIO-001` with real envelope IDs;
- the same header does not alter unrelated APIs;
- API-023 still returns the frozen fixture observation and never mutates MockRuntime.

- [ ] **Step 3: Run focused tests and confirm RED**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsGateway.test.ts src/mocks/__tests__/handlers.test.ts
```

Append the actual Gateway/handler RED result to `settings-core-red.txt`. The failure must be missing Gateway/fault behavior, not an invalid fixture or modified public contract.

- [ ] **Step 4: Implement the Gateway and narrow fault mapping**

Use `api022SuccessEnvelopeSchema`, `api023SuccessEnvelopeSchema`, `apiErrorEnvelopeSchema`, and `api023RequestSchema` directly. Parse success first, parse public error second, and throw a contract error only when neither parses. Send `x-demo-c13-fault` only when the test/runtime explicitly requests it.

In `handlers.ts`, gate on:

```ts
const isC13Api = contract.apiId === 'API-022' || contract.apiId === 'API-023';
const c13Fault = isC13Api ? request.headers.get('x-demo-c13-fault') : null;
```

Do not add public schema/header declarations or a mutable configuration record to MockRuntime.

- [ ] **Step 5: Prove GREEN and commit**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsGateway.test.ts src/mocks/__tests__/handlers.test.ts
git diff --check
git add src/features/system-settings src/mocks/handlers.ts src/mocks/__tests__/handlers.test.ts docs/evidence/C13/settings-core-red.txt
git diff --cached --check
git commit -m "feat(c13): add settings api gateway"
```

---

### Task 4: Drive workflow and atomic save semantics from failing tests

**Files:**
- Create: `src/features/system-settings/__tests__/systemSettingsRuntime.test.ts`
- Create: `src/features/system-settings/__tests__/systemSettingsCommands.test.ts`
- Create: `src/features/system-settings/systemSettingsRuntime.ts`
- Create: `src/features/system-settings/systemSettingsCommands.ts`
- Modify: `src/features/system-settings/index.ts`
- Modify: `docs/evidence/C13/settings-core-red.txt`

**Interfaces:**

```ts
export type SaveSystemSettingsInput = Readonly<{
  commandId?: string;
  configId: string;
  expectedVersion: number;
  changes: ConfigVersionChanges;
  reason: string;
}>;

export type SystemSettingsCommandFeedback = Readonly<{
  ok: boolean;
  commandId: string;
  traceId: string;
  transportAuditLogId: string;
  domainAuditLogId?: string;
  message: string;
  errorCode?: PublicErrorCode;
  idempotent: boolean;
  version?: number;
}>;

export type SystemSettingsCommandService = Readonly<{
  save(input: SaveSystemSettingsInput): Promise<CommandResult>;
  resetCommandState(): void;
}>;
```

- [ ] **Step 1: Write immutable workflow-store tests**

Cover initial state, selected group, read pending/success/failure, begin draft, allowlisted field update, reason update, validation errors, save pending, failure retaining draft, success clearing draft, discard, reset, subscriber notification, and deep freezing. Require `readObservation` and API feedback to remain observations rather than ConfigVersion ownership.

- [ ] **Step 2: Write command precondition/failure tests**

Use a fake Gateway and real Demo Store. Cover:

- route/action permission denial for roles outside UI-012;
- offline denial from existing `authorize` for `settings:edit`;
- blank command/config/reason, unknown target, empty/unknown/invalid changes;
- non-DRAFT target and expected-version mismatch;
- Gateway network/malformed/business result;
- response-time Store version drift;
- forbidden transition and commit validation failure;
- every failure leaves `systemConfig.configVersions` byte-for-byte unchanged;
- attempted commands may append exactly one honest failure audit with identical before/after, but pre-cache replay never appends again.

- [ ] **Step 3: Write success, audit, atomicity, and replay tests**

Require a successful `CFG-001` edit to:

```ts
expect(after).toEqual({
  ...before,
  displayName: '新的 Demo 名称',
  recommendationEnabled: false,
  version: before.version + 1,
  updatedAt: state.session.demoTime,
  updatedBy: state.session.actorId,
});
```

Require one `CommandAuditEntry` with `AUD-C13-001`, action `SS-05`, objectType `DO-015`, objectId `CFG-001`, full before/after, trimmed reason, actual trace, session time, `SUCCESS`, and null errorCode. Capture the API envelope audit ID only in feedback as `transportAuditLogId`. Assert the config update and success audit appear in the same single Store publication.

Call the same explicit `commandId` twice and require: same frozen result, one Gateway call, one Store publication, one audit, and replay feedback with `idempotent: true` without changing the original audit.

- [ ] **Step 4: Run tests and confirm RED**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsRuntime.test.ts src/features/system-settings/__tests__/systemSettingsCommands.test.ts
```

Append actual failures to `settings-core-red.txt`.

- [ ] **Step 5: Implement workflow and command sequence exactly**

The command order is cache → authorize → validate → pending → Gateway → re-read → transition → construct/parse after → one atomic Store commit → success feedback/cache. Use `transitionState({ machineId: 'DO-015', current: current.status, command: 'edit' })` and reject any result other than `DRAFT`.

Generate independent IDs:

```text
CMD-C13-###             command identity
TRACE-C13-###           local command trace when transport has not supplied one
AUD-C13-###             real DO-013 domain audit identity
transport auditLogId    API envelope observation only
```

Cache only fully resolved results. On replay, publish a cloned feature feedback with `idempotent: true` but return the original result and create no domain side effect.

- [ ] **Step 6: Prove GREEN, run ownership regressions, and commit**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings/__tests__/systemSettingsRuntime.test.ts src/features/system-settings/__tests__/systemSettingsCommands.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts
git diff --check
git add src/features/system-settings docs/evidence/C13/settings-core-red.txt
git diff --cached --check
git commit -m "feat(c13): add settings workflow and commands"
```

---

### Task 5: Mount C13 in the shared runtime and prove authorized external reset

**Files:**
- Modify: `src/runtime/__tests__/runtime.test.tsx`
- Create: `src/features/system-settings/__tests__/systemSettingsIntegration.test.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/features/system-settings/index.ts`
- Modify: `docs/evidence/C13/settings-core-red.txt`

**Interfaces:**

```ts
export type SystemSettingsRuntime = {
  gateway: SystemSettingsGateway;
  workflow: SystemSettingsWorkflowStore;
  commands: SystemSettingsCommandService;
};

export function useSystemSettingsWorkflow<T>(
  selector: (state: SystemSettingsWorkflowState) => T,
): T;
```

- [ ] **Step 1: Add runtime-mount and hook tests**

Require `createDemoRuntime(fetcher).systemSettings` to expose one Gateway/workflow/command trio bound to the same root Store. Render a hook probe under `DemoRuntimeProvider` and require reactive workflow updates through `useSyncExternalStore`.

- [ ] **Step 2: Add the external-reset integration test**

Use an actor/context already authorized for the existing reset command (not a UI-012 page role shortcut). Save a C13 draft/config edit, populate C13 feedback/idempotency cache, then complete the existing API-025 reset path. Require:

- `state.systemConfig.configVersions` returns to the frozen `CFG-001` snapshot;
- C13 workflow returns to initial state;
- the same old command ID is no longer cached and may execute anew;
- no UI-012 page action or RBAC change was introduced.

- [ ] **Step 3: Run tests and confirm RED**

```powershell
node_modules\.bin\vitest.CMD run src/runtime/__tests__/runtime.test.tsx src/features/system-settings/__tests__/systemSettingsIntegration.test.ts
```

Append the exact missing-runtime/reset failure to `settings-core-red.txt`.

- [ ] **Step 4: Mount the feature with safe construction order**

Instantiate Gateway → workflow → commands before the plan-entry reset command is created. Add workflow/command reset calls inside the existing `onSuccessfulReset` callback and return `systemSettings` from the runtime. Do not add a second API-025 client or change reset authorization.

- [ ] **Step 5: Prove GREEN and commit**

```powershell
node_modules\.bin\vitest.CMD run src/runtime/__tests__/runtime.test.tsx src/features/system-settings/__tests__/systemSettingsIntegration.test.ts
git diff --check
git add src/runtime/DemoRuntimeContext.tsx src/runtime/__tests__/runtime.test.tsx src/features/system-settings docs/evidence/C13/settings-core-red.txt
git diff --cached --check
git commit -m "test(c13): verify settings runtime integration"
```

---

### Task 6: Drive the UI-012 page from frozen render, permission, and action tests

**Files:**
- Create: `src/pages/__tests__/systemSettingsTestHarness.tsx`
- Create: `src/pages/__tests__/SystemSettingsPage.render.test.tsx`
- Create: `src/pages/__tests__/SystemSettingsPage.permission.test.tsx`
- Create: `src/pages/__tests__/SystemSettingsPage.action.test.tsx`
- Create: `src/features/system-settings/components/SystemSettingsContextHeader.tsx`
- Create: `src/features/system-settings/components/SystemSettingsGroupNav.tsx`
- Create: `src/features/system-settings/components/SystemSettingsFields.tsx`
- Create: `src/features/system-settings/components/SystemSettingsChangeSummary.tsx`
- Create: `src/features/system-settings/components/SystemSettingsReadBanner.tsx`
- Create: `src/features/system-settings/components/SystemSettingsCommandFeedback.tsx`
- Create: `src/features/system-settings/system-settings.css`
- Modify: `src/features/system-settings/index.ts`
- Replace: `src/pages/settings/SystemSettingsPage.tsx`
- Create: `docs/evidence/C13/settings-page-red.txt`

**Interfaces:**
- The harness owns only deterministic test fetch responses and runtime setup; it may not bypass the real page/runtime/Store path.
- Stable accessible names are the page contract for Vitest and Playwright.

- [ ] **Step 1: Build the deterministic page harness**

Support:

```ts
type SystemSettingsApiMode = 'success' | 'network' | 'malformed' | 'business' | 'unresolved';
type SystemSettingsPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  online?: boolean;
  preparation?: 'normal' | 'empty' | 'submitted';
  api022Mode?: SystemSettingsApiMode;
  api023Mode?: SystemSettingsApiMode | 'version-conflict';
  search?: string;
}>;
```

Return the real runtime, spy fetcher, API mode controls, unresolved-read resolver, and router. Do not mutate API response into Store.

- [ ] **Step 2: Write render-state tests**

Require:

- title `系统配置`, badge `UI-012`, route, API-022/API-023 identity, and Demo boundary statement;
- five group navigation controls and query hydration/fallback;
- overview editable rows + seven read-only metadata rows;
- dispatch/integration/governance/access contents matching the design;
- integration statement `未纳入 DO-015，不提供生产连接参数` and no invented TOS/ECS/PLC/appointment values;
- loading, Store empty, explicit config not-found, network, malformed, and business read states;
- Store values remain visible/recoverable when read observation fails;
- page contains an in-flow summary region (`aria-label="配置变更摘要"`) and no dialog/drawer.

- [ ] **Step 3: Write permission tests**

Require `SYS_ADMIN`, `INTERFACE_OPS`, and `SAFETY` to view. A disallowed role must hit existing `403 无权访问` before API-022. Require `settings:edit` to control the edit button; offline view remains readable but save is disabled with the existing authorization reason. The reset control must always be disabled on UI-012 with a truthful `demo:reset` explanation.

- [ ] **Step 4: Write action and ownership tests**

Cover:

- enter edit mode and modify all eight field types;
- summary updates in whitelist order and contains before/after labels;
- invalid displayName/retention and blank reason block API-023 and leave Store unchanged;
- discard clears draft/errors/summary and restores current Store values;
- success sends strict changes only, increments version once, shows new version/trace/transport audit/domain audit, clears draft, and appends one DO-013 command audit;
- network/malformed/business/version failures retain draft and never partially write config;
- repeated command identity has one side effect;
- deep snapshot of plan/recommendation/workOrder/resource/vehicle/exception/interlock/offline/report/userRoles/audit is unchanged;
- no API-023 state command other than `edit`, no API-021 export, and no page API-025 call.

- [ ] **Step 5: Run the page tests and confirm RED**

```powershell
node_modules\.bin\vitest.CMD run src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx
```

Expected: failures identify the current scaffold/missing controls. Save actual command, exit code, failed names, and representative missing heading/region/control assertions in `settings-page-red.txt`.

- [ ] **Step 6: Implement the page in small GREEN slices**

Implement in this order, rerunning the relevant single file after each slice:

1. load/read state + header + Demo boundary;
2. group navigation/query selection;
3. read-only field rendering;
4. edit mode + draft controls;
5. in-page sticky summary + reason + validation;
6. save/discard/feedback;
7. truthful disabled reset and failure recovery.

At widths ≥1320px use a left navigation, main detail, and sticky right summary. Below 1320px place the summary after the form. Use CSS grid/minmax and `overflow-wrap` so neither viewport has horizontal scroll.

- [ ] **Step 7: Prove GREEN and commit**

```powershell
node_modules\.bin\vitest.CMD run src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx
node_modules\.bin\vitest.CMD run src/features/system-settings src/runtime/__tests__/runtime.test.tsx src/mocks/__tests__/handlers.test.ts
git diff --check
git add src/pages/settings/SystemSettingsPage.tsx src/pages/__tests__/systemSettingsTestHarness.tsx src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx src/features/system-settings docs/evidence/C13/settings-page-red.txt
git diff --cached --check
git commit -m "feat(c13): implement system settings page"
```

---

### Task 7: Drive the browser journey and eight screenshots from a failing E2E spec

**Files:**
- Create: `e2e/ui-012-settings-system.spec.ts`
- Create: `docs/evidence/C13/settings-e2e-red.txt`
- Create: exactly eight PNG files under the repository's existing screenshot output convention.

**Interfaces:**
- Real route: `/settings/system?group=overview&configId=CFG-001&scenarioId=SCN-01&from=dispatch-overview`.
- Stable journey states: `OVERVIEW`, `EDITING`, `VALIDATION`, `SAVED`.

- [ ] **Step 1: Write the E2E behavior tests before selector support is complete**

Cover:

- SCN-01 overview identity/groups/fields/Demo warning;
- changing groups updates visible content and preserves query context;
- edit → dirty summary → strict save → version/audit feedback;
- empty reason/invalid retention validation without Store/API mutation;
- discard restoration;
- route forbidden for a disallowed role;
- offline edit/save denial;
- business/network/malformed/version conflict presentation and draft preservation;
- reset disabled and no page API-025 request;
- upstream page/domain snapshot unchanged after C13 edit.

- [ ] **Step 2: Add the screenshot matrix test**

Capture exactly these names, with no debug or duplicate PNGs:

```text
C13-UI012-SCN01-OVERVIEW-1440x900.png
C13-UI012-SCN01-OVERVIEW-1280x720.png
C13-UI012-SCN01-EDITING-1440x900.png
C13-UI012-SCN01-EDITING-1280x720.png
C13-UI012-SCN01-VALIDATION-1440x900.png
C13-UI012-SCN01-VALIDATION-1280x720.png
C13-UI012-SCN01-SAVED-1440x900.png
C13-UI012-SCN01-SAVED-1280x720.png
```

Before each image, wait for the named state and all fonts/network activity needed by the page. Use `page.setViewportSize` and assert `document.documentElement.scrollWidth <= window.innerWidth`.

- [ ] **Step 3: Run the new spec and confirm RED**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-012-settings-system.spec.ts
```

Record exact failing test names/selector or behavior mismatch in `settings-e2e-red.txt`. Do not manufacture a RED by breaking production code.

- [ ] **Step 4: Add only the selector/layout/test-state fixes needed for GREEN**

Prefer accessible roles/names and `data-testid` only for state that cannot be expressed accessibly. Any fault injection must remain C13-internal and must not alter public API contracts or normal page behavior.

- [ ] **Step 5: Prove E2E GREEN and commit**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-012-settings-system.spec.ts
git diff --check
git add e2e/ui-012-settings-system.spec.ts src/pages/settings/SystemSettingsPage.tsx src/features/system-settings docs/evidence/C13/settings-e2e-red.txt
git diff --cached --check
git commit -m "test(c13): cover system settings demo flows"
```

---

### Task 8: Inspect every screenshot and close responsive/accessibility defects

**Files:**
- Modify only if tests reveal a defect: `src/features/system-settings/components/**`, `src/features/system-settings/system-settings.css`, page/E2E tests.
- Create: `docs/evidence/C13/screenshot-index.md`

- [ ] **Step 1: Verify the screenshot set and original dimensions**

List C13 PNGs and verify there are exactly eight. Read each image's real width/height; four must be 1440×900 and four 1280×720.

- [ ] **Step 2: Visually inspect all eight at original detail**

Use the image viewer on every PNG. Check:

- no horizontal overflow, clipping, overlap, or hidden primary action;
- Chinese labels and long IDs remain readable;
- group selection, editable/read-only distinction, and Demo warning are obvious;
- summary is sticky/right at 1440 and in flow after the form at 1280;
- validation and success feedback are visible and not confused with transport/domain audit identities;
- disabled reset has a visible reason;
- screenshot contains no debug UI or browser artifacts.

- [ ] **Step 3: Fix defects through TDD**

For any defect, first add a page or E2E assertion that fails for it, observe RED, then patch the smallest CSS/component behavior and rerun both viewports. Recapture only the authoritative image with the same filename.

- [ ] **Step 4: Write and commit the screenshot index**

For each image record state, viewport, actual dimensions, visual result, and key assertions. Then run:

```powershell
git diff --check
git add docs/evidence/C13/screenshot-index.md src/features/system-settings e2e/ui-012-settings-system.spec.ts
git diff --cached --check
git commit -m "test(c13): verify settings responsive evidence"
```

Skip the commit only if Task 7 already produced perfect images and the index can be included in Task 9.

---

### Task 9: Run full verification and publish truthful C13 evidence/handoff

**Files:**
- Create: `docs/evidence/C13/coverage.json`
- Create: `docs/evidence/C13/tsc-after.txt`
- Create: `docs/evidence/C13/verification.md`
- Create: `docs/handoffs/C13-system-settings.md`
- Modify: `docs/conversation-prompts/C13-system-settings.md`
- Modify if needed: `docs/evidence/C13/screenshot-index.md`

- [ ] **Step 1: Run the complete focused C13 and contract regression suites**

```powershell
node_modules\.bin\vitest.CMD run src/features/system-settings src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx src/runtime/__tests__/runtime.test.tsx src/mocks/__tests__/handlers.test.ts src/contracts/__tests__/schemas.test.ts src/contracts/__tests__/openapi-consistency.test.ts src/stores/__tests__/store.test.ts src/commands/__tests__/stateMachines.test.ts
```

Record files/tests/duration/exit code exactly.

- [ ] **Step 2: Attempt official coverage without changing dependencies**

```powershell
node_modules\.bin\vitest.CMD run --coverage src/features/system-settings src/pages/__tests__/SystemSettingsPage.render.test.tsx src/pages/__tests__/SystemSettingsPage.permission.test.tsx src/pages/__tests__/SystemSettingsPage.action.test.tsx
```

If the frozen dependency set lacks `@vitest/coverage-v8`, write `coverage.json` with the command, exit code, provider-unavailable message, `percentages: null`, and `truthfulCoverageAvailable: false`. Do not install a provider or invent numbers. If it works, record the machine-generated summary unchanged.

- [ ] **Step 3: Run full Vitest and isolate only reproduced aggregate timeouts**

```powershell
node_modules\.bin\vitest.CMD run
```

If the known UI-002/UI-013 30-second aggregate-load timeouts recur, rerun only their exact files unchanged and record both the full-suite failure and isolated result. Do not raise timeouts. Investigate any new C13 or assertion failure before proceeding.

- [ ] **Step 4: Run production build, C13 Playwright, and full Playwright**

```powershell
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-012-settings-system.spec.ts
pnpm test:e2e
```

Record exact transformed-module/test counts, duration, warnings, and exit codes. Restore only known generated binary changes outside C13 after verifying their paths; never discard source changes.

- [ ] **Step 5: Compare TypeScript after with the frozen before**

```powershell
node_modules\.bin\tsc.CMD --noEmit --pretty false
```

Write `tsc-after.txt` with line count, primary diagnostic count, per-code families, non-TSX locations, and exact comparison to `tsc-before.txt`. C13 must introduce zero diagnostic families and zero non-frozen diagnostics. Do not claim `tsc` passed if it exits nonzero.

- [ ] **Step 6: Audit immutable scope, ownership, hashes, and history**

Run read-only comparisons proving:

- all six 0.3.0 hashes still match `SHA256SUMS.txt` (`6/6`);
- `docs/baseline/**`, public contract/request schemas, state machine, Store ownership types/slices, route catalog, permission catalog, dependencies, and C04-C12 feature source are unchanged from `a35a93916fa80d1506cdcbd6460b53504e0a88f9` except the explicitly approved runtime/handler integration files;
- DO-001 through DO-014 and fixture/runtime scenario metadata deep-compare unchanged;
- no second `ConfigVersion[]` owner or mutable MockRuntime config exists;
- API-023 page calls contain only `command: edit` and allowlisted `changes`;
- `git diff --check` passes and merge count is zero.

- [ ] **Step 7: Write verification and handoff**

`verification.md` must state the real status of every command, screenshot, hash, scope check, known warning, aggregate timeout, and type baseline. `handoff.md` must identify:

```text
route                  /settings/system
identity               UI-012 / DO-015 / CFG-001
read/write             API-022 / API-023 edit
owner                   state.systemConfig.configVersions
editable keys           exact eight-field whitelist
audit identities        transport receipt vs AUD-C13 domain audit
reset boundary          disabled on page; external authorized callback integrated
known limitations       Demo-only, no production persistence/approval/publish/rollback
```

Update the conversation prompt from “implementation start” to a final replay/maintenance prompt that points at design, plan, evidence, and handoff without claiming merge/push.

- [ ] **Step 8: Commit evidence, verify final clean state, and report**

```powershell
git diff --check
git add docs/evidence/C13 docs/handoffs/C13-system-settings.md docs/conversation-prompts/C13-system-settings.md
git diff --cached --check
git commit -m "docs(c13): complete system settings handoff"
git status --short --branch
git rev-list --merges --count a35a93916fa80d1506cdcbd6460b53504e0a88f9..HEAD
```

Expected: clean `demo/c13-system-settings`, merge count 0, no push/merge, and a final message that distinguishes fully passing checks from inherited or truthfully unresolved checks.

---

## Completion Criteria

- [ ] UI-012 is no longer a scaffold and the page states it is a Demo view, not a production configuration center.
- [ ] API-022/023 observations remain separate from the sole Store-owned DO-015 fact.
- [ ] Exactly eight fields can be edited; metadata and session/runtime context remain read-only.
- [ ] The page has an in-page fixed/sticky change summary, not a Drawer or Modal.
- [ ] Save is Gateway-first, version-checked after response, lifecycle-checked, and committed atomically with one real DO-013 audit.
- [ ] Failures preserve draft and never partially mutate DO-015; replay has no second side effect.
- [ ] API envelope audit ID and `AUD-C13-*` domain audit ID are visibly distinct.
- [ ] Reset is truthfully disabled on UI-012 while authorized external API-025 reset clears C13 local state.
- [ ] All page/core/E2E RED evidence precedes the matching production behavior.
- [ ] Exactly eight named screenshots pass original-dimension and visual inspection.
- [ ] Baseline hashes, public contracts, RBAC, lifecycle, dependencies, and upstream domain state remain frozen.
- [ ] Verification, coverage limitation, TypeScript comparison, handoff, history, and final status are truthful and reproducible.
