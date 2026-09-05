# C03 State and Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared C03 state, command, authorization, audit, masking, navigation-gate, and 403 infrastructure that all 13 later business pages can consume without copying fixtures or bypassing policy.

**Architecture:** Compose one Zustand vanilla root store from 12 named slices so reset and cross-slice commits are atomic. Keep state transitions and authorization as pure data-driven functions, then inject them into a command executor that calls the existing C02 MSW layer, validates with existing Zod contracts, commits once, appends audit once, and returns structured feedback. Add only the minimum React permission boundary needed to prove hidden navigation, direct-access 403, and action hide/disable behavior.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, React Router 7.18.0, Ant Design 6.5.1, Playwright 1.61.1.

## Global Constraints

- Approved design: `docs/superpowers/specs/2026-07-19-c03-state-and-permissions-design.md` at commit `2ebaf9d3670839ae210ba3ab8fdb7dff80a73f10`.
- C02 source baseline: `94ce3961a8bad0e7b9aceca3980fbb30bfa66877`.
- Start from the latest clean `demo/c02-contracts-mock` containing the C03 design, plan, and prompt, then create `demo/c03-state-permissions`.
- Keep `docs/baseline/**`, `package.json`, `pnpm-lock.yaml`, `src/contracts/**`, and the public behavior of `src/mocks/**` unchanged.
- Do not implement business content for UI-001 through UI-013, the full AppShell redesign, DetailDrawer, CommandFeedback, DemoScenarioBar, UI-012, or UI-013.
- Runtime DTOs remain strict camelCase. Do not add snake_case adapters or a second fixture set.
- Use exactly 12 store slices, seven state machines, 13 roles, 45 permission codes, seven scenarios, and nine public error codes.
- Do not add public error codes. Authorization rejection uses `TOS-AUTH-001`; version mismatch uses `DEMO-VERSION-001`.
- Preserve the six frozen SHA-256 values:

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  traceability.csv
```

- C01's global React/ReactDOM/JSX declaration limitation may remain. Every new C03 source diagnostic must be zero; never install packages or relax TypeScript configuration to hide it.
- Use deterministic fixture clocks and IDs. Production C03 source must not call `Date.now`, `new Date`, `Math.random`, `crypto.randomUUID`, sleep, or raw `setTimeout`.
- Preserve red evidence before implementation and keep commits scoped to the task that produced them.

---

## File Structure

### State and command core

- Create `src/commands/stateMachines.ts`: the seven frozen transition catalogs and pure `transitionState` function.
- Create `src/commands/types.ts`: `DemoCommand`, command result, command dependency, and mutation types.
- Create `src/commands/executeCommand.ts`: fixed eight-step command pipeline and idempotency cache.
- Create `src/commands/index.ts`: stable command exports.
- Create `src/commands/__tests__/stateMachines.test.ts`: exact state/command/transition coverage.
- Create `src/commands/__tests__/pipeline.test.ts`: permission, schema, Mock, transition, atomic commit, idempotency, and failure tests.
- Create `src/stores/types.ts`: the 12 slice shapes and `DemoRootState`.
- Create `src/stores/slices.ts`: slice factories with no public domain mutation actions.
- Create `src/stores/initialState.ts`: map a C02 `FixtureSnapshot` deep copy into 12 slices.
- Create `src/stores/createDemoStore.ts`: one Zustand vanilla store and atomic replacement/reset APIs.
- Create `src/stores/selectors.ts`: pure cross-slice and data-domain selectors.
- Create `src/stores/index.ts`: stable store exports.
- Create `src/stores/__tests__/store.test.ts`: initialization, isolation, selector, atomic commit, and reset tests.

### Authorization and governance

- Create `src/auth/types.ts`: role, permission, scope, decision, and session types.
- Create `src/auth/permissionCatalog.ts`: exact 13 roles, 45 permission codes, route policies, and action policies.
- Create `src/auth/authorize.ts`: fixed six-step decision order.
- Create `src/auth/dataScope.ts`: pure scope filtering and membership checks.
- Create `src/auth/masking.ts`: pure name, mobile, and vehicle masking.
- Create `src/auth/sessionBootstrap.ts`: deterministic default session and test/demo storage bootstrap.
- Create `src/auth/index.ts`: stable authorization exports.
- Create `src/auth/__tests__/authorize.test.ts`: role/action/scope/SoD/online/version tests.
- Create `src/auth/__tests__/masking.test.ts`: fixed-format and no-mutation tests.
- Create `src/governance/audit.ts`: append-only command audit wrapper around strict C02 `AuditLog`.
- Create `src/governance/__tests__/audit.test.ts`: append, failure, clone, and immutability tests.

### Minimum permission UI

- Create `src/components/PermissionGate.tsx`: hide/disable/explain action gate.
- Create `src/components/ForbiddenState.tsx`: safe 403 state with no business data.
- Create `src/app/RoutePermissionBoundary.tsx`: direct route permission check.
- Modify `src/app/routeCatalog.ts`: add `requiredPermission` and `allowedRoles` metadata.
- Modify `src/app/router.tsx`: wrap each lazy business route in the route boundary.
- Modify `src/layouts/SkeletonLayout.tsx`: derive navigation items from the current session and route catalog.
- Modify `e2e/route-smoke.spec.ts`: seed an authorized role for each route while retaining exactly 14 tests.
- Create `src/components/__tests__/permissionGate.test.tsx`: hide/disable/reason tests.
- Create `src/app/__tests__/routePermission.test.tsx`: menu filtering and direct 403 tests.

### Evidence and handoff

- Create `docs/evidence/C03/tsc-before.txt` and `tsc-after.txt`.
- Create `docs/evidence/C03/state-red.txt` and `permission-red.txt`.
- Create `docs/evidence/C03/state-machine-coverage.json`, `store-coverage.json`, and `permission-coverage.json`.
- Create `docs/evidence/C03/verification.md`.
- Create `docs/handoffs/C03-state-and-permissions.md`.

---

### Task 1: Freeze the C03 execution boundary and capture preflight evidence

**Files:**
- Read: `docs/handoffs/C02-contracts-and-mock.md`
- Read: `docs/superpowers/specs/2026-07-19-c03-state-and-permissions-design.md`
- Read: `docs/baseline/SHA256SUMS.txt`
- Create: `docs/evidence/C03/tsc-before.txt`
- Create: `docs/evidence/C03/preflight.md`

**Interfaces:**
- Consumes: clean C02 source commit, six baseline hashes, existing 33 tests.
- Produces: `demo/c03-state-permissions` and immutable preflight evidence used by every later task.

- [ ] **Step 1: Verify source and create the feature branch**

Run:

```powershell
git status --porcelain
git merge-base --is-ancestor 94ce3961a8bad0e7b9aceca3980fbb30bfa66877 HEAD
git switch -c demo/c03-state-permissions
```

Expected: no status output, merge-base exit 0, new branch name printed. If the branch already exists, stop and inspect it; do not force or delete it.

- [ ] **Step 2: Verify all six baseline hashes**

Run the same filename-order SHA-256 comparison used in C02. Expected: six `MATCH` rows and zero mismatches. Save the command and output in `preflight.md`.

- [ ] **Step 3: Record the TypeScript baseline before C03**

Run:

```powershell
pnpm exec tsc --noEmit
```

Expected: global exit 1 is allowed only for the 38 previously recorded React/ReactDOM/JSX diagnostics; no diagnostic may originate from `src/contracts/**` or `src/mocks/**`. Save exact output, timestamps, exit code, primary diagnostic count, and C03 diagnostic count in `tsc-before.txt`.

- [ ] **Step 4: Run the C02 regression baseline**

Run:

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
```

Expected: 10/10 test files and 33/33 tests, build exit 0, Playwright 14/14. Record the existing large-chunk warning as non-blocking.

- [ ] **Step 5: Commit only preflight evidence**

```powershell
git add docs/evidence/C03/tsc-before.txt docs/evidence/C03/preflight.md
git commit -m "test(c03): capture state and permission baseline"
```

---

### Task 2: Implement the seven pure state-machine catalogs

**Files:**
- Create: `src/commands/stateMachines.ts`
- Create: `src/commands/__tests__/stateMachines.test.ts`
- Create: `docs/evidence/C03/state-red.txt`
- Create: `docs/evidence/C03/state-machine-coverage.json`

**Interfaces:**
- Produces: `MachineId`, `MachineState`, `MachineCommand`, `TransitionRequest`, `TransitionResult`, `stateMachineCatalog`, and `transitionState(request)`.
- Consumed by: Task 4 command executor and later business-page commands.

- [ ] **Step 1: Write the failing transition-catalog tests**

Use this public shape in the test imports:

```ts
type MachineId = 'DO-001' | 'DO-005' | 'DO-008' | 'DO-009' | 'DO-010' | 'DO-011' | 'SM-007';

type TransitionRequest = {
  machineId: MachineId;
  current: string;
  command: string;
};

type TransitionResult =
  | { ok: true; previous: string; next: string; command: string }
  | { ok: false; previous: string; command: string; reason: 'UNKNOWN_STATE' | 'UNKNOWN_COMMAND' | 'FORBIDDEN_TRANSITION' };
```

Assert all seven machine IDs, every frozen state, every frozen command, every allowed transition, and representative forbidden paths. Assert `DO-001 BLOCKED + decompose`, `DO-005 DISPATCHED + start`, and `DO-010 RESET_REQUESTED + restore` are rejected.

- [ ] **Step 2: Run the focused test and preserve red output**

```powershell
pnpm exec vitest run src/commands/__tests__/stateMachines.test.ts
```

Expected: fail because `src/commands/stateMachines.ts` does not exist. Save full output and exit code in `state-red.txt`.

- [ ] **Step 3: Implement the frozen catalogs**

Use arrays of explicit triples; do not infer next state by array position. The adopted Demo transitions are:

```ts
const allowedTransitions = {
  'DO-001': [
    ['RECEIVED', 'sync', 'VALIDATING'],
    ['VALIDATING', 'validate', 'PENDING_CONFIRM'],
    ['PENDING_CONFIRM', 'confirm', 'CONFIRMED'],
    ['CONFIRMED', 'adjust', 'ADJUSTED'],
    ['BLOCKED', 'adjust', 'ADJUSTED'],
    ['CONFIRMED', 'decompose', 'DECOMPOSED'],
    ['ADJUSTED', 'decompose', 'DECOMPOSED'],
  ],
  'DO-005': [
    ['DRAFT', 'assign', 'READY'],
    ['READY', 'dispatch', 'DISPATCHED'],
    ['DISPATCHED', 'ack', 'ACKNOWLEDGED'],
    ['ACKNOWLEDGED', 'start', 'IN_PROGRESS'],
    ['PAUSED', 'start', 'IN_PROGRESS'],
    ['IN_PROGRESS', 'pause', 'PAUSED'],
    ['IN_PROGRESS', 'complete', 'COMPLETED'],
  ],
  'DO-008': [
    ['DRAFT', 'submit', 'SUBMITTED'],
    ['NEED_FIX', 'submit', 'SUBMITTED'],
    ['SUBMITTED', 'approve', 'APPROVED'],
    ['APPROVED', 'call', 'CALLED'],
    ['QUEUED', 'call', 'CALLED'],
    ['CALLED', 'gateIn', 'ENTERED'],
    ['OPERATING', 'release', 'RELEASED'],
    ['RELEASED', 'gateOut', 'EXITED'],
  ],
  'DO-009': [
    ['OPEN', 'ack', 'ACKNOWLEDGED'],
    ['REOPENED', 'ack', 'ACKNOWLEDGED'],
    ['ACKNOWLEDGED', 'assign', 'HANDLING'],
    ['HANDLING', 'handle', 'PENDING_REVIEW'],
    ['PENDING_REVIEW', 'review', 'HANDLING'],
    ['PENDING_REVIEW', 'close', 'CLOSED'],
    ['CLOSED', 'reopen', 'REOPENED'],
  ],
  'DO-010': [
    ['TRIGGERED', 'trigger', 'ACTION_ISSUED'],
    ['ACTION_ISSUED', 'receipt', 'WAITING_RECEIPT'],
    ['WAITING_RECEIPT', 'receipt', 'LOCKED'],
    ['LOCKED', 'requestReset', 'RESET_REQUESTED'],
    ['RESET_REQUESTED', 'approve', 'APPROVED'],
    ['APPROVED', 'restore', 'RESTORED'],
    ['LOCKED', 'requestOverride', 'OVERRIDE_PENDING'],
    ['OVERRIDE_PENDING', 'approve', 'OVERRIDDEN'],
  ],
  'DO-011': [
    ['CACHED', 'upload', 'PENDING_UPLOAD'],
    ['RETRY', 'upload', 'PENDING_UPLOAD'],
    ['PENDING_UPLOAD', 'validate', 'VALIDATING'],
    ['VALIDATING', 'merge', 'MERGED'],
    ['VALIDATING', 'reject', 'REJECTED'],
    ['CONFLICT', 'reject', 'REJECTED'],
    ['CONFLICT', 'retry', 'RETRY'],
    ['REJECTED', 'retry', 'RETRY'],
  ],
  'SM-007': [
    ['ACCEPTED', 'accept', 'ACCEPTED'],
    ['ACCEPTED', 'execute', 'EXECUTING'],
    ['EXECUTING', 'succeed', 'SUCCESS'],
    ['EXECUTING', 'fail', 'FAILED'],
    ['EXECUTING', 'timeout', 'TIMEOUT'],
    ['FAILED', 'retry', 'EXECUTING'],
    ['TIMEOUT', 'retry', 'EXECUTING'],
  ],
} as const;
```

States such as `CANCELLED`, `BLOCKED`, `FAILED`, `REJECTED`, `EXCEPTION`, and `ESCALATED` that have no approved inbound command remain valid externally seeded states but do not gain guessed transitions.

- [ ] **Step 4: Run the focused test and generate coverage**

Expected: state-machine test passes. Write `state-machine-coverage.json` with seven machine entries, exact state/command counts, allowed triple count, and test name for every triple.

- [ ] **Step 5: Commit the state-machine layer**

```powershell
git add src/commands/stateMachines.ts src/commands/__tests__/stateMachines.test.ts docs/evidence/C03/state-red.txt docs/evidence/C03/state-machine-coverage.json
git commit -m "feat(c03): add deterministic state machines"
```

---

### Task 3: Compose the 12-slice atomic Zustand store

**Files:**
- Create: `src/stores/types.ts`
- Create: `src/stores/slices.ts`
- Create: `src/stores/initialState.ts`
- Create: `src/stores/createDemoStore.ts`
- Create: `src/stores/selectors.ts`
- Create: `src/stores/index.ts`
- Create: `src/stores/__tests__/store.test.ts`
- Create: `docs/evidence/C03/store-coverage.json`

**Interfaces:**
- Consumes: `FixtureSnapshot`, `DemoScenario`, and domain types from C02.
- Produces: `DemoRootState`, `DemoStoreApi`, `createDemoStore(snapshot, session)`, `replaceDomainState(mutator)`, `resetFromSnapshot(snapshot, session)`, and pure selectors.

- [ ] **Step 1: Write failing store tests**

Tests must assert the exact 12 keys:

```ts
const sliceKeys = [
  'session', 'scenario', 'plan', 'recommendation', 'workOrder', 'resource',
  'vehicle', 'exception', 'interlock', 'offline', 'report', 'configAudit',
] as const;
```

Assert fixture counts, deep-copy isolation from `mockRuntime.getSnapshot()`, a single notification for cross-slice commit, zero notification/state change when the candidate throws, deterministic reset after mutations, and selector referential stability for unchanged inputs.

- [ ] **Step 2: Run the focused test to verify red**

```powershell
pnpm exec vitest run src/stores/__tests__/store.test.ts
```

Expected: fail because the store public module does not exist.

- [ ] **Step 3: Define the root state and deterministic initializer**

Use C02 domain types and the following ownership mapping:

```ts
type DemoRootState = {
  session: SessionSlice;
  scenario: ScenarioSlice;
  plan: { plans: Plan[] };
  recommendation: { drafts: Record<string, unknown> };
  workOrder: { workOrders: WorkOrder[]; nodes: WorkNode[] };
  resource: { tracks: Track[]; materials: Material[]; resources: Resource[] };
  vehicle: { appointments: Appointment[] };
  exception: { exceptions: DispatchException[] };
  interlock: { interlocks: Interlock[] };
  offline: { packets: OfflinePacket[] };
  report: { reports: Report[] };
  configAudit: { userRoles: UserRole[]; audit: AuditLog[] };
};
```

Map DO-001 through DO-014 explicitly. Recommendation drafts start empty because no independent recommendation fixture exists; they may only be populated later by validated API results.

- [ ] **Step 4: Implement atomic replace/reset and pure selectors**

Use `createStore<DemoRootState>()` from `zustand/vanilla`. Build the complete candidate outside `setState`, validate it, then call `store.setState(candidate, true)` once. Export selectors as `(state: DemoRootState) => value`; never call `getState()` inside selector modules.

- [ ] **Step 5: Run tests and write store coverage**

Expected: store test passes. `store-coverage.json` must contain all 12 slice keys, source object groups, initializer test, isolation test, atomic commit test, reset test, and cross-slice selector test.

- [ ] **Step 6: Commit the store layer**

```powershell
git add src/stores docs/evidence/C03/store-coverage.json
git commit -m "feat(c03): add atomic domain store"
```

---

### Task 4: Implement the command pipeline with injected policy and audit ports

**Files:**
- Create: `src/commands/types.ts`
- Create: `src/commands/executeCommand.ts`
- Create: `src/commands/index.ts`
- Create: `src/commands/__tests__/pipeline.test.ts`

**Interfaces:**
- Consumes: `transitionState`, C02 request schemas/API catalog, a `DemoStoreApi`, injected authorization/Mock/audit ports, and deterministic clock.
- Produces: `createCommandExecutor(dependencies).execute(command)`.

- [ ] **Step 1: Write failing pipeline tests using fakes**

Define and use this contract:

```ts
type DemoCommand<TPayload = Record<string, unknown>> = {
  commandId: string;
  action: string;
  entityType: MachineId;
  entityId: string;
  expectedVersion: number;
  payload: TPayload;
  actor: CommandActor;
  traceId: string;
  clientTime: string;
};

type CommandActor = {
  actorId: string;
  roleCode: string;
  dataScope: readonly string[];
  online: boolean;
};

type CommandPermissionDecision =
  | { allow: true }
  | { allow: false; errorCode: 'TOS-AUTH-001' | 'DEMO-VERSION-001'; message: string };

type CommandPermissionEvaluator = (command: DemoCommand) => CommandPermissionDecision;
type AuditAppender = (input: {
  command: DemoCommand;
  result: CommandResult;
  serverTime: string;
}) => string;

type CommandResult =
  | { ok: true; commandId: string; traceId: string; auditLogId: string }
  | { ok: false; commandId: string; traceId: string; auditLogId: string; errorCode: PublicErrorCode; message: string };
```

Test exact order: permission → schema → Mock → transition → commit → audit. Test permission failure makes no Mock/transition/commit call; schema/Mock/transition failure makes no commit; repeated commandId returns the first result without a second Mock, commit, or audit; expectedVersion mismatch returns `DEMO-VERSION-001`.

- [ ] **Step 2: Run the test and record Gate A red**

Append full failure output to `docs/evidence/C03/state-red.txt`. Expected: command module missing while state-machine/store tests remain green.

- [ ] **Step 3: Implement the dependency-injected executor**

The dependency shape is fixed:

```ts
type CommandDependencies = {
  authorize: CommandPermissionEvaluator;
  validate: (command: DemoCommand) => void;
  invokeMock: (command: DemoCommand) => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  transition: typeof transitionState;
  commit: (command: DemoCommand, response: ApiSuccessEnvelope) => void;
  appendAudit: AuditAppender;
  nextAuditId: () => string;
  now: () => string;
};
```

Store resolved `CommandResult` values in a private `Map<string, CommandResult>`. Do not cache in-flight failures before an audit record exists. Build the complete commit candidate before calling the store commit port.

- [ ] **Step 4: Run Gate A tests**

Run state-machine, store, and pipeline suites together. Expected: all pass; permission and audit ports are represented by deterministic fakes only at this gate.

- [ ] **Step 5: Commit the command core**

```powershell
git add src/commands docs/evidence/C03/state-red.txt
git commit -m "feat(c03): add atomic command pipeline"
```

---

### Task 5: Freeze and implement the 13-role, 45-code authorization engine

**Files:**
- Create: `src/auth/types.ts`
- Create: `src/auth/permissionCatalog.ts`
- Create: `src/auth/authorize.ts`
- Create: `src/auth/dataScope.ts`
- Create: `src/auth/masking.ts`
- Create: `src/auth/sessionBootstrap.ts`
- Create: `src/auth/index.ts`
- Create: `src/auth/__tests__/authorize.test.ts`
- Create: `src/auth/__tests__/masking.test.ts`
- Create: `docs/evidence/C03/permission-red.txt`
- Create: `docs/evidence/C03/permission-coverage.json`

**Interfaces:**
- Produces: `RoleCode`, `PermissionCode`, `SessionContext`, `PolicyContext`, `PermissionDecision`, `permissionCodes`, `routePolicies`, `actionPolicies`, `authorize`, `filterByDataScope`, `maskName`, `maskMobile`, and `maskVehicleNo`.
- Consumed by: command executor, route boundary, navigation, and later pages.

- [ ] **Step 1: Write failing catalog and decision tests**

Assert exactly 13 C02 role codes and these 45 permission codes:

```ts
export const permissionCodes = [
  'audit:export', 'audit:verify', 'audit:view', 'demo:reset',
  'dispatch:assign', 'dispatch:pause', 'dispatch:reassign', 'dispatch:send', 'dispatch:view',
  'exception:ack', 'exception:close', 'exception:handle', 'exception:reopen', 'exception:review',
  'export:summary', 'interface:retry',
  'interlock:approve', 'interlock:request-override', 'interlock:reset', 'interlock:view',
  'monitor:view', 'offline:resolve', 'offline:retry', 'offline:view', 'overview:view',
  'plan:adjust', 'plan:confirm', 'plan:recommend', 'plan:view',
  'report:export', 'report:generate', 'report:view',
  'settings:approve', 'settings:edit', 'settings:publish', 'settings:rollback', 'settings:view',
  'task:decompose', 'task:edit', 'task:view',
  'yard:call', 'yard:gate', 'yard:release', 'yard:review', 'yard:submit',
] as const;
```

Assert decision order by constructing contexts where multiple checks fail and confirming the earlier check wins. Assert data-scope filtering removes unauthorized objects before count. Assert same-person high-risk approval, offline reset/override/release/permission change, SYS_ADMIN self-elevation approval, and AUDITOR mutation are denied.

- [ ] **Step 2: Run focused tests and preserve permission red**

Expected: missing auth modules. Save exact output in `permission-red.txt`.

- [ ] **Step 3: Implement route and action catalogs**

Route policy roles are fixed:

```ts
export const routePolicies = {
  'UI-001': ['DISPATCHER', 'SHIFT_LEADER', 'BUSINESS'],
  'UI-002': ['DISPATCHER', 'INTERFACE_OPS'],
  'UI-003': ['DISPATCHER'],
  'UI-004': ['DISPATCHER', 'SHIFT_LEADER'],
  'UI-005': ['DISPATCHER', 'SHIFT_LEADER'],
  'UI-006': ['DRIVER', 'GATE_GUARD', 'DISPATCHER'],
  'UI-007': ['DISPATCHER', 'SHIFT_LEADER', 'BUSINESS'],
  'UI-008': ['DISPATCHER', 'SHIFT_LEADER', 'SAFETY'],
  'UI-009': ['SAFETY', 'MAINTAINER', 'DISPATCHER'],
  'UI-010': ['INTERFACE_OPS', 'SHIFT_LEADER', 'DISPATCHER'],
  'UI-011': ['BUSINESS', 'REGULATOR', 'DISPATCHER', 'AUDITOR'],
  'UI-012': ['SYS_ADMIN', 'INTERFACE_OPS', 'SAFETY'],
  'UI-013': ['AUDITOR', 'REGULATOR', 'SYS_ADMIN'],
} as const;
```

Use this complete action policy catalog; every permission code must have a non-empty frozen role set and a coverage row:

```ts
export const actionPolicies = {
  'audit:export': ['AUDITOR'],
  'audit:verify': ['AUDITOR'],
  'audit:view': ['AUDITOR'],
  'demo:reset': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:assign': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:pause': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:reassign': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:send': ['DISPATCHER', 'SHIFT_LEADER'],
  'dispatch:view': ['DISPATCHER', 'SHIFT_LEADER'],
  'exception:ack': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:close': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:handle': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:reopen': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'exception:review': ['DISPATCHER', 'SAFETY', 'SHIFT_LEADER'],
  'export:summary': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'interface:retry': ['DISPATCHER', 'INTERFACE_OPS'],
  'interlock:approve': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:request-override': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:reset': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'interlock:view': ['DISPATCHER', 'MAINTAINER', 'SAFETY'],
  'monitor:view': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'offline:resolve': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'offline:retry': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'offline:view': ['DISPATCHER', 'INTERFACE_OPS', 'SHIFT_LEADER'],
  'overview:view': ['BUSINESS', 'DISPATCHER', 'SHIFT_LEADER'],
  'plan:adjust': ['DISPATCHER'],
  'plan:confirm': ['DISPATCHER', 'INTERFACE_OPS'],
  'plan:recommend': ['DISPATCHER'],
  'plan:view': ['DISPATCHER', 'INTERFACE_OPS'],
  'report:export': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR', 'SHIFT_LEADER'],
  'report:generate': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR'],
  'report:view': ['AUDITOR', 'BUSINESS', 'DISPATCHER', 'REGULATOR'],
  'settings:approve': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:edit': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:publish': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:rollback': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'settings:view': ['INTERFACE_OPS', 'SAFETY', 'SYS_ADMIN'],
  'task:decompose': ['DISPATCHER', 'SHIFT_LEADER'],
  'task:edit': ['DISPATCHER', 'SHIFT_LEADER'],
  'task:view': ['DISPATCHER', 'SHIFT_LEADER'],
  'yard:call': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:gate': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:release': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:review': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
  'yard:submit': ['DISPATCHER', 'DRIVER', 'GATE_GUARD'],
} as const satisfies Record<PermissionCode, readonly RoleCode[]>;
```

- [ ] **Step 4: Implement fixed decision order and data-scope filtering**

Use this result type:

```ts
type PermissionDecision =
  | { allow: true }
  | {
      allow: false;
      errorCode: 'TOS-AUTH-001' | 'DEMO-VERSION-001';
      stage: 'PAGE' | 'ACTION' | 'DATA_SCOPE' | 'SEPARATION_OF_DUTIES' | 'ONLINE' | 'VERSION';
      reason: string;
    };
```

Do not throw for an ordinary denial. Unknown roles, permissions, or scope types return a denial at their corresponding stage.

- [ ] **Step 5: Implement masking and deterministic session bootstrap**

`maskName('张三')` returns `张**`; `maskMobile('13800121234')` returns `138****1234`; `maskVehicleNo('川A·12345')` returns `川A·***45`. Return new objects from masking views. Default session is DISPATCHER, actor `USER-001`, scopes `['AREA-A']`, online true, demo clock from the active scenario. Allow E2E to override this session through one documented localStorage key; never add a role outside the 13-role catalog.

- [ ] **Step 6: Run Gate B engine tests and generate coverage**

`permission-coverage.json` must list 13 roles, 45 codes, 13 routes, all six decision stages, four offline-denied action families, the SoD cases, three masking formats, and exact test names.

- [ ] **Step 7: Commit the authorization engine**

```powershell
git add src/auth docs/evidence/C03/permission-red.txt docs/evidence/C03/permission-coverage.json
git commit -m "feat(c03): add role and data-scope policies"
```

---

### Task 6: Add append-only audit and bind real policy to commands

**Files:**
- Create: `src/governance/audit.ts`
- Create: `src/governance/__tests__/audit.test.ts`
- Modify: `src/commands/executeCommand.ts`
- Modify: `src/commands/__tests__/pipeline.test.ts`
- Modify: `src/stores/types.ts`

**Interfaces:**
- Produces: `CommandAuditEntry`, `createAuditLedger(initial)`, `AuditLedger.append(entry)`, `AuditLedger.list()`.
- Consumed by: real command dependency factory and later UI-013.

- [ ] **Step 1: Write failing audit tests**

Use a wrapper that preserves strict DO-013 rather than extending it:

```ts
type CommandAuditEntry = {
  record: AuditLog;
  metadata: {
    roleCode: RoleCode;
    dataScope: readonly string[];
    result: 'SUCCESS' | 'DENIED' | 'FAILED';
    errorCode: PublicErrorCode | null;
    clientTime: string;
    serverTime: string;
  };
};
```

Assert `record` passes the existing strict DO-013 schema, returned lists are deep copies, prior entries cannot be changed, denied commands append exactly once, and idempotent replay does not append again.

- [ ] **Step 2: Run the focused test to verify red**

Expected: audit module missing; auth and Gate A suites stay green.

- [ ] **Step 3: Implement the ledger and real command bindings**

Generate `record.id` from the existing deterministic audit sequence, use operatorTerminal `WEB-DEMO`, carry before/after JSON in the strict fields, put denial explanation in `reason`, and use deterministic scenario time for `occurredAt`/serverTime. Append by constructing and validating a complete new array, then replacing it once.

- [ ] **Step 4: Run command, auth, audit, and store suites together**

Expected: all pass; verify authorization occurs before schema/Mock, audit is written after success or final failure, and no denied entity appears in Store state.

- [ ] **Step 5: Commit governance integration**

```powershell
git add src/governance src/commands src/stores/types.ts
git commit -m "feat(c03): append audited command decisions"
```

---

### Task 7: Add the minimum permission UI, route gate, and navigation filtering

**Files:**
- Create: `src/components/PermissionGate.tsx`
- Create: `src/components/ForbiddenState.tsx`
- Create: `src/app/RoutePermissionBoundary.tsx`
- Create: `src/components/__tests__/permissionGate.test.tsx`
- Create: `src/app/__tests__/routePermission.test.tsx`
- Modify: `src/app/routeCatalog.ts`
- Modify: `src/app/router.tsx`
- Modify: `src/layouts/SkeletonLayout.tsx`
- Modify: `e2e/route-smoke.spec.ts`

**Interfaces:**
- Consumes: current `SessionContext`, `routePolicies`, and `authorize`.
- Produces: `PermissionGate`, `ForbiddenState`, `RoutePermissionBoundary`, and session-filtered navigation.

- [ ] **Step 1: Write failing component and route tests**

`PermissionGate` props are fixed:

```ts
type PermissionGateProps = {
  permission: PermissionCode;
  context: PolicyContext;
  mode: 'hide' | 'disable' | 'explain';
  children: ReactNode;
};
```

Test allowed child, hidden child, cloned disabled control with `aria-disabled`, and visible denial reason. Test unauthorized navigation item absence, authorized item presence, direct 403 heading, required permission, current scopes, and absence of page object text/network calls.

- [ ] **Step 2: Run focused tests and append Gate B red evidence**

Expected: missing permission UI modules. Append exact output to `permission-red.txt`.

- [ ] **Step 3: Add route metadata and route boundary**

Add `requiredPermission: PermissionCode` and `allowedRoles: readonly RoleCode[]` to every route item. Use one view permission per route: overview, plan, task, dispatch, yard, monitor, exception, interlock, offline, report, settings, or audit. Wrap the lazy page element with `RoutePermissionBoundary`; do not import or render the page module when the page decision is denied.

- [ ] **Step 4: Filter navigation and implement the safe 403 state**

Build menu items inside `SkeletonLayout` from the current session so role changes update navigation. `ForbiddenState` renders status 403, required permission, current data scopes, and a link to the first allowed route. It never receives fixture objects.

- [ ] **Step 5: Preserve the 14 existing E2E cases**

Update the smoke helper to seed an authorized existing role through the documented localStorage session key before each route. Keep the 13 route test cases plus the resolution/404 case—exactly 14. Add new authorization behavior to Vitest rather than increasing the frozen C01 E2E count.

- [ ] **Step 6: Run Gate B and E2E tests**

Run auth, audit, PermissionGate, route permission, and the complete Playwright suite. Expected: all Gate B tests pass and E2E remains 14/14.

- [ ] **Step 7: Commit the minimum UI gate**

```powershell
git add src/components/PermissionGate.tsx src/components/ForbiddenState.tsx src/components/__tests__/permissionGate.test.tsx src/app/RoutePermissionBoundary.tsx src/app/routeCatalog.ts src/app/router.tsx src/app/__tests__/routePermission.test.tsx src/layouts/SkeletonLayout.tsx e2e/route-smoke.spec.ts docs/evidence/C03/permission-red.txt
git commit -m "feat(c03): enforce route and action permissions"
```

---

### Task 8: Verify C03, write evidence and handoff, and freeze the branch

**Files:**
- Create: `docs/evidence/C03/tsc-after.txt`
- Create: `docs/evidence/C03/verification.md`
- Create: `docs/handoffs/C03-state-and-permissions.md`

**Interfaces:**
- Consumes: completed Gate A/Gate B source, coverage JSON, C02 baseline evidence.
- Produces: reviewer-ready C03 branch and exact next-stage handoff.

- [ ] **Step 1: Run focused C03 tests**

Run all `src/commands`, `src/stores`, `src/auth`, `src/governance`, permission component, and route permission suites. Expected: zero failures and every coverage JSON row marked passed.

- [ ] **Step 2: Run the complete regression**

```powershell
pnpm test -- --run
pnpm build
pnpm test:e2e
```

Expected: all legacy and new tests pass, build exit 0, Playwright exactly 14/14. Record exact file/test counts rather than assuming a count in advance.

- [ ] **Step 3: Record post-C03 TypeScript diagnostics**

Run `pnpm exec tsc --noEmit`; write exact exit code, primary diagnostics, and `C03_PRIMARY_DIAGNOSTICS`. Expected: C03 count 0. Compare the remaining global diagnostics to `tsc-before.txt` and fail if any new non-C03 diagnostic was introduced by modified shared files.

- [ ] **Step 4: Recheck frozen boundaries**

Verify six SHA-256 values, `git diff --check`, zero diff in `package.json`, `pnpm-lock.yaml`, `docs/baseline/**`, `src/contracts/**`, business page files, and C01/C02 evidence. Search C03 production source for system clocks, randomness, raw timers, snake_case DTO fields, unknown role codes, and public error codes outside the frozen nine.

- [ ] **Step 5: Write verification and handoff**

`verification.md` records every command, exit code, test count, build warning, E2E 14/14, TypeScript delta, hashes, changed files, red evidence, and coverage paths. `C03-state-and-permissions.md` lists public exports, the 12 slices, seven machines, 13 roles, 45 codes, decision order, audit wrapper, session bootstrap, test override key, known limitations, and the next business-page entry point.

- [ ] **Step 6: Commit final C03 evidence**

```powershell
git add docs/evidence/C03 docs/handoffs/C03-state-and-permissions.md
git commit -m "docs(c03): add state and permission handoff"
```

- [ ] **Step 7: Perform the final clean-worktree check**

Expected: branch `demo/c03-state-permissions`, clean status, C02 source commit is an ancestor, no baseline/lock/source-scope violation, and the log contains only the planned C03 commits after the documentation preparation commits.

---

## Final Acceptance Checklist

- [ ] Twelve named slices exist in one atomic Zustand root store.
- [ ] Seven explicit state-machine catalogs and all approved triples are tested.
- [ ] Permission, schema, Mock, transition, commit, audit order is proven.
- [ ] Version conflict and commandId replay are deterministic.
- [ ] Thirteen roles, 45 permission codes, 13 route policies, six decision stages, SoD, offline rules, and masking pass.
- [ ] PermissionGate hide/disable/explain and safe direct-access 403 pass.
- [ ] Strict DO-013 remains unchanged; command audit metadata lives in the wrapper.
- [ ] Store/commands never copy fixtures or hold Mock mutable references.
- [ ] Full tests and build pass; Playwright remains 14/14.
- [ ] C03 TypeScript diagnostics are zero and six baseline hashes match.
- [ ] Business pages, dependencies, lock file, frozen baseline, contracts, and Mock public behavior remain unchanged.
- [ ] Evidence, coverage JSON, handoff, scoped commits, and clean worktree are present.
