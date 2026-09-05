# C10 Offline Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build UI-010 offline sync so strict DO-011 packets can be uploaded, validated, merged, rejected, retried, conflict-inspected, and reset through the existing C03 command/runtime architecture.

**Architecture:** Add an isolated `src/features/offline-sync/**` slice for strict DO-011 projection, query hydration, API-018/API-019 transport, workflow state, command execution, and focused UI components. Domain facts remain in the existing `offline.packets` slice; successful commands use one C03 atomic Store replacement and append-only audit, while API results validate transport/identity only.

**Tech Stack:** React 19.2.7, TypeScript 7.0.2, Vite 8.1.4, Ant Design 6.5.1, React Router DOM 7.18.0, Zustand 5.0.14, Zod 4.4.3, MSW 2.15.0, Vitest 4.1.10, Testing Library 16.3.2, Playwright 1.61.1.

## Global Constraints

- Repository: `E:\魔法入门与精通\自己写的神奇玩应\研究\工作\B项目\production-dispatch-demo`.
- Start point: `demo/c09-safety-interlock` at `de81e5a22cf1972133cbdee03e3b281723aca2da`.
- Work branch: `demo/c10-offline-sync`; do not merge or push.
- Read before editing: `docs/handoffs/C09-safety-interlock.md`, `docs/superpowers/specs/2026-07-22-c10-offline-sync-design.md`, this plan.
- Never modify `docs/baseline/**`, dependency manifests/lockfiles, public contracts, public errors, permission catalog, state-machine catalog, or C09 business implementation.
- Frozen hashes must remain:

```text
bc41651fc1876b0e9eb674de700bf43c5d93e24dfd2c93234b0508595be4ec34  docs/baseline/README.md
ca501290c82f2742cb555c099b04c85cd505bf156b38d7b6a9062bcd1f4c1810  docs/baseline/package-baseline.json
1ad194bab44074abcadc4afed252c104af623bf81c9b08c0a11a1aa721887b83  docs/baseline/openapi.yaml
b0f1506db2d89e291baf4772ed604561978ab66c43b607ec1f859f2c0ab907ba  docs/baseline/demo-fixtures.json
c2fc700d07d962a03441824a1fa5b6b30564fda9b73f85f31f8c6c7745f90c3b  docs/baseline/page-task-matrix.csv
a83e7df6c0963a184527ee55bdecdbd6f7691874502dc55c400b4ea860491dd2  docs/baseline/traceability.csv
```

- C10 only handles DO-011 fields: `id`, `offlinePackageNo`, `terminalId`, `workOrderNo`, `packageVersion`, `serverVersion`, `validation`, `mergeStatus`, `version`, `createdAt`, `updatedAt`.
- API-018 and API-019 responses validate transport and identity only; never overwrite Store facts from returned packet objects.
- API-019 uses a feature-local strict `{ action, reason, validation? }` Schema through the existing handler override pattern; do not alter `src/contracts/requests.ts`.
- SCN-06 is conflict recognition and recovery guidance only. SCN-01 owns the standard processing loop.
- C10 non-React primary TypeScript diagnostics must be 0. Global existing React/JSX diagnostics are classified honestly.

---

### Task 1: C10 preflight evidence

**Files:**
- Create: `docs/evidence/C10/preflight.md`
- Create: `docs/evidence/C10/tsc-before.txt`

**Interfaces:**
- Consumes: approved C10 design, clean C09 base, six frozen hashes.
- Produces: a measured pre-implementation baseline and contract gate result.

- [ ] **Step 1: Verify branch ancestry, worktree, and commit topology**

Run:

```powershell
git status --short --branch
git branch --show-current
git rev-parse HEAD
git merge-base --is-ancestor de81e5a22cf1972133cbdee03e3b281723aca2da HEAD
git rev-list --min-parents=2 --count de81e5a22cf1972133cbdee03e3b281723aca2da..HEAD
```

Expected: `demo/c10-offline-sync`, clean status before evidence creation, C09 ancestor exit 0, merge count `0`.

- [ ] **Step 2: Verify six frozen hashes**

Run `Get-FileHash -Algorithm SHA256` for the six Global Constraints files and compare lowercase hashes exactly. Expected: `6/6 MATCH`; any mismatch stops implementation at the contract gate.

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

Write the complete TypeScript output to `docs/evidence/C10/tsc-before.txt`. Record exit code, test counts, modules, Playwright count, known UI-002 timeout behavior, and C09 handoff comparison in `preflight.md`.

- [ ] **Step 4: Commit preflight**

```powershell
git add docs/evidence/C10/preflight.md docs/evidence/C10/tsc-before.txt
git commit -m "test(c10): record offline sync preflight"
```

---

### Task 2: DO-011 projection and query hydration

**Files:**
- Create: `src/features/offline-sync/offlinePacketTypes.ts`
- Create: `src/features/offline-sync/offlinePacketProjection.ts`
- Create: `src/features/offline-sync/offlinePacketQueries.ts`
- Create: `src/features/offline-sync/index.ts`
- Create: `src/features/offline-sync/__tests__/offlinePacketProjection.test.ts`
- Create: `src/features/offline-sync/__tests__/offlinePacketQueries.test.ts`
- Create: `docs/evidence/C10/offline-core-red.txt`

**Interfaces:**
- Produces: `parseOfflinePacketQuery`, `selectOfflinePacketBoard`, `selectOfflinePacketKpis`.
- Produces types: `OfflinePacketQueryContext`, `OfflinePacketLedgerItem`, `OfflinePacketBoard`, `OfflinePacketKpis`, `OfflinePacketActionAvailability`.

- [ ] **Step 1: Write failing query tests**

Use these assertions:

```ts
expect(parseOfflinePacketQuery(
  '?packetId=OFF-001&terminalId=PDA-01&workOrderNo=WO-006&mergeStatus=CONFLICT&scenarioId=SCN-06&from=monitor',
)).toEqual({
  packetId: 'OFF-001', terminalId: 'PDA-01', workOrderNo: 'WO-006',
  mergeStatus: 'CONFLICT', scenarioId: 'SCN-06', from: 'monitor',
});
expect(parseOfflinePacketQuery('?mergeStatus=UNKNOWN&packetId=%00%20')).toEqual({});
```

- [ ] **Step 2: Write failing projection tests**

Assert all seven KPI counts, strict DO-011 keys, deep freezing, terminal/work-order/status filtering, version delta, validation issue projection, SCN-06 recovery guidance, and actions:

```ts
expect(board.items.find(({ packet }) => packet.id === 'OFF-001')).toMatchObject({
  versionDelta: 1,
  conflict: true,
  validationIssues: ['VERSION_CONFLICT'],
  availableActions: { upload: false, validate: false, merge: false, reject: true, retry: true },
});
expect(Object.isFrozen(board.items[0]!.packet.validation)).toBe(true);
```

- [ ] **Step 3: Run RED and save evidence**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketProjection.test.ts src/features/offline-sync/__tests__/offlinePacketQueries.test.ts
```

Save the expected missing-module failure to `docs/evidence/C10/offline-core-red.txt`.

- [ ] **Step 4: Implement parser and projection**

Use exact signatures:

```ts
export function parseOfflinePacketQuery(
  input: string | URLSearchParams,
): OfflinePacketQueryContext;

export function selectOfflinePacketBoard(
  state: DemoRootState,
  context: OfflinePacketQueryContext,
): OfflinePacketBoard;

export function selectOfflinePacketKpis(state: DemoRootState): OfflinePacketKpis;
```

Projection applies data scope before filtering, reconstructs only the eleven DO-011 fields, computes action availability with `transitionState({ machineId: 'DO-011', ... })` plus `authorize`, and deep-freezes every returned object.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketProjection.test.ts src/features/offline-sync/__tests__/offlinePacketQueries.test.ts
git add src/features/offline-sync docs/evidence/C10/offline-core-red.txt
git commit -m "feat(c10): add offline packet projections"
```

---

### Task 3: API-018/API-019 Gateway and mock support

**Files:**
- Create: `src/features/offline-sync/offlinePacketGateway.ts`
- Create: `src/features/offline-sync/__tests__/offlinePacketGateway.test.ts`
- Modify: `src/mocks/handlers.ts`
- Modify: `src/mocks/__tests__/handlers.test.ts`

**Interfaces:**
- Produces: `offlinePacketCommandRequestSchema`, `OfflinePacketGateway`, `createOfflinePacketGateway`.
- `listPackets(): Promise<OfflinePacketGatewayResult>`.
- `commandPacket(packetId, input): Promise<OfflinePacketGatewayResult>`.

- [ ] **Step 1: Write failing strict Gateway tests**

Cover API identity, operation identity, scenario identity, path identity, error envelopes, malformed responses, network rejection, and exact request body:

```ts
expect(JSON.parse(String(fetcher.mock.calls[0]![1]!.body))).toEqual({
  action: 'VALIDATE',
  reason: '重新校验离线包',
  validation: { valid: true, issues: [] },
});
await expect(gateway.commandPacket('OFF-001', {
  action: 'MERGE', reason: ' ',
})).rejects.toThrow();
```

- [ ] **Step 2: Verify RED**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketGateway.test.ts
```

Expected: missing Gateway module failure.

- [ ] **Step 3: Implement strict Gateway**

Define:

```ts
export const offlinePacketCommandRequestSchema = z.object({
  action: z.enum(['UPLOAD', 'VALIDATE', 'MERGE', 'REJECT', 'RETRY']),
  reason: z.string().trim().min(1),
  validation: z.record(z.string(), z.unknown()).optional(),
}).strict();
```

API-018 accepts an array of strict `do011Schema`; API-019 accepts exactly one item whose ID matches the encoded path ID. Parse public error envelopes unchanged.

- [ ] **Step 4: Extend handler dispatch without changing public contracts**

In `validateRequest`, select `offlinePacketCommandRequestSchema` only when `contract.apiId === 'API-019'`. Add `x-demo-c10-fault: network|malformed` handling for API-018/API-019 parallel to C09. Do not alter scenario fault routing.

- [ ] **Step 5: Run GREEN and commit**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketGateway.test.ts src/mocks/__tests__/handlers.test.ts
git add src/features/offline-sync/offlinePacketGateway.ts src/features/offline-sync/__tests__/offlinePacketGateway.test.ts src/mocks/handlers.ts src/mocks/__tests__/handlers.test.ts
git commit -m "feat(c10): add offline packet api gateway"
```

---

### Task 4: OS-01 through OS-05 command/runtime integration

**Files:**
- Create: `src/features/offline-sync/offlinePacketCommands.ts`
- Create: `src/features/offline-sync/offlinePacketRuntime.ts`
- Create: `src/features/offline-sync/__tests__/offlinePacketCommands.test.ts`
- Modify: `src/features/offline-sync/index.ts`
- Modify: `src/runtime/DemoRuntimeContext.tsx`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: `createOfflinePacketCommandService`, `createOfflinePacketWorkflowStore`.
- Runtime exports `runtime.offlineSync.gateway`, `.workflow`, `.commands` and `useOfflinePacketWorkflow(selector)`.
- Command methods: `uploadPacket`, `validatePacket`, `mergePacket`, `rejectPacket`, `retryPacket`, `resetCommandState`.

- [ ] **Step 1: Write failing state-transition tests**

Use a real fixture packet for the main chain and the two frozen recovery packets:

```ts
const context = createOfflineCommandTestContext();
await expect(context.commands.uploadPacket({
  packetId: 'OFF-004', reason: '上传缓存包',
})).resolves.toMatchObject({ ok: true });
await expect(context.commands.validatePacket({
  packetId: 'OFF-004', reason: '校验上传包', validation: { valid: true, issues: [] },
})).resolves.toMatchObject({ ok: true });
await expect(context.commands.mergePacket({
  packetId: 'OFF-004', reason: '合并有效离线包',
})).resolves.toMatchObject({ ok: true });
expect(packetOf(context, 'OFF-004')).toMatchObject({
  mergeStatus: 'MERGED', serverVersion: 5, version: 4,
});
await expect(context.commands.retryPacket({
  packetId: 'OFF-001', reason: '冲突包进入重试',
})).resolves.toMatchObject({ ok: true });
await expect(context.commands.retryPacket({
  packetId: 'OFF-002', reason: '驳回包进入重试',
})).resolves.toMatchObject({ ok: true });
```

Add separate assertions for permissions, data scope, blank reason, invalid merge validation, illegal transition, version drift during API wait, SCN-06 `TOS-OFF-001`, network/malformed, idempotent constant command ID, one audit per first result, and unchanged `workOrder`, `exception`, `interlock`, `plan`, and `resource` slices.

- [ ] **Step 2: Verify RED**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketCommands.test.ts src/runtime/__tests__/runtime.test.tsx
```

Expected: missing command/runtime exports.

- [ ] **Step 3: Implement workflow store**

State is exact and UI-only:

```ts
type OfflinePacketWorkflowState = Readonly<{
  selectedPacketId?: string;
  reason: string;
  validationDraft: Readonly<Record<string, unknown>>;
  pendingAction?: 'UPLOAD' | 'VALIDATE' | 'MERGE' | 'REJECT' | 'RETRY';
  lastFeedback?: Readonly<{
    ok: boolean; traceId: string; auditLogId: string;
    commandId: string; errorCode?: PublicErrorCode; message: string; idempotent: boolean;
  }>;
}>;
```

- [ ] **Step 4: Implement C03 command pipeline adapter**

Map `UPLOAD→upload`, `VALIDATE→validate`, `MERGE→merge`, `REJECT→reject`, `RETRY→retry`. Build C03 commands using `entityType: 'SM-007'`, `action: 'execute'`, expected DO-011 version in payload, then make `invokeMock` return accepted SM-007 state after API/scenario/version checks. `commit` rechecks DO-011 and performs one `replaceDomainState`.

Commit semantics are exact:

```ts
const next = do011Schema.parse({
  ...packet,
  mergeStatus: transition.next,
  ...(domainCommand === 'validate' ? { validation: structuredClone(payload.validation) } : {}),
  ...(domainCommand === 'merge' ? { serverVersion: packet.packageVersion } : {}),
  version: packet.version + 1,
  updatedAt: candidate.session.demoTime,
});
```

- [ ] **Step 5: Wire shared runtime and reset**

Create `offlineSync` gateway/workflow/commands in `createDemoRuntime`. Add workflow and command-state reset to `onSuccessfulReset`; expose `useOfflinePacketWorkflow` using `useSyncExternalStore`.

- [ ] **Step 6: Run GREEN and commit**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketCommands.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/offline-sync src/runtime
git commit -m "feat(c10): add offline packet commands"
```

---

### Task 5: UI-010 workspace and page tests

**Files:**
- Modify: `src/pages/operations/OfflineSyncPage.tsx`
- Create: `src/features/offline-sync/offline-sync.css`
- Create: `src/features/offline-sync/components/OfflineContextHeader.tsx`
- Create: `src/features/offline-sync/components/OfflineKpiStrip.tsx`
- Create: `src/features/offline-sync/components/OfflinePacketLedger.tsx`
- Create: `src/features/offline-sync/components/OfflinePacketDetail.tsx`
- Create: `src/features/offline-sync/components/OfflinePacketActionPanel.tsx`
- Create: `src/features/offline-sync/components/OfflineCommandFeedback.tsx`
- Create: `src/pages/__tests__/offlineSyncTestHarness.tsx`
- Create: `src/pages/__tests__/OfflineSyncPage.render.test.tsx`
- Create: `src/pages/__tests__/OfflineSyncPage.action.test.tsx`
- Create: `src/pages/__tests__/OfflineSyncPage.permission.test.tsx`
- Create: `docs/evidence/C10/offline-page-red.txt`

**Interfaces:**
- Produces: accessible UI-010 ledger/detail/action/feedback workspace.
- Page reads `selectOfflinePacketBoard`, `useDemoSelector`, `useOfflinePacketWorkflow`, and `runtime.offlineSync` only.

- [ ] **Step 1: Write failing page render tests**

Assert heading/current route, six query labels, seven KPI labels, four fixture rows, strict detail fields, conflict reason, version comparison, status flow, reset guidance, loading, empty, forbidden, not-found, network, malformed, and business error.

- [ ] **Step 2: Write failing action/permission tests**

Assert exact button enablement:

```ts
expect(button('上传')).toBeEnabled();       // OFF-004 CACHED
expect(button('校验')).toBeDisabled();
expect(button('合并')).toBeDisabled();
expect(button('驳回')).toBeDisabled();
expect(button('重试')).toBeDisabled();
```

Then execute CACHED→PENDING_UPLOAD→VALIDATING→MERGED, verify feedback includes trace/audit IDs, and verify role/data-scope denial does not call API-018/API-019.

- [ ] **Step 3: Save RED evidence**

```powershell
pnpm vitest run src/pages/__tests__/OfflineSyncPage.render.test.tsx src/pages/__tests__/OfflineSyncPage.action.test.tsx src/pages/__tests__/OfflineSyncPage.permission.test.tsx
```

Save the meaningful scaffold failure to `docs/evidence/C10/offline-page-red.txt`.

- [ ] **Step 4: Implement page and components**

Render seven KPI cards, accessible ledger buttons, strict detail panel, action reason field, five action buttons, SCN-01 reset button, and feedback region. Initial API-018 failures preserve Store projection and show retry/reset guidance. Disable controls while a command is pending.

- [ ] **Step 5: Implement responsive CSS**

- At width above 1360px, use a three-column `grid-template-columns: minmax(280px, .9fr) minmax(400px, 1.25fr) minmax(300px, .9fr)`.
- At width 1360px or below, place `.offline-action-panel` first as a compact full-width row, then ledger/detail in two columns.
- Keep action buttons, selected status, version delta, and latest feedback visible at 1280×720 with no horizontal overflow.

- [ ] **Step 6: Run GREEN and commit**

```powershell
pnpm vitest run src/pages/__tests__/OfflineSyncPage.render.test.tsx src/pages/__tests__/OfflineSyncPage.action.test.tsx src/pages/__tests__/OfflineSyncPage.permission.test.tsx src/app/__tests__/routeRender.test.tsx
git add src/features/offline-sync src/pages/operations/OfflineSyncPage.tsx src/pages/__tests__ docs/evidence/C10/offline-page-red.txt
git commit -m "feat(c10): implement offline sync page"
```

---

### Task 6: Reset, SCN split, and upstream immutability integration

**Files:**
- Create: `src/features/offline-sync/__tests__/offlinePacketIntegration.test.ts`
- Modify: `src/runtime/__tests__/runtime.test.tsx`

**Interfaces:**
- Produces: verified SCN-06 guidance, SCN-01 processing, reset atomicity, and upstream immutability.

- [ ] **Step 1: Write integration tests**

Test this exact sequence:

```ts
const upstreamBefore = structuredClone({
  workOrder: store.getState().workOrder,
  exception: store.getState().exception,
  interlock: store.getState().interlock,
  resource: store.getState().resource,
  plan: store.getState().plan,
});
// SCN-06 command returns TOS-OFF-001 and OFF-001 remains byte-equivalent.
// resetScenario('SCN-01') restores all four packets and clears C10 workflow/id caches.
// OFF-001 then reaches RETRY -> PENDING_UPLOAD -> VALIDATING -> MERGED.
expect(currentUpstream()).toEqual(upstreamBefore);
```

Also assert reset restarts IDs at `CMD-C10-001`, `TRACE-C10-001`, `AUD-C10-001` and keeps a single reset audit per existing C03 semantics.

- [ ] **Step 2: Implement only integration fixes proven by RED**

Confine fixes to C10 exports and `DemoRuntimeContext.tsx`. Do not edit WorkOrder, C09, fixtures, permissions, transitions, or baseline.

- [ ] **Step 3: Run GREEN and commit**

```powershell
pnpm vitest run src/features/offline-sync/__tests__/offlinePacketIntegration.test.ts src/runtime/__tests__/runtime.test.tsx
git add src/features/offline-sync src/runtime
git commit -m "test(c10): verify offline sync integration"
```

---

### Task 7: C10 Playwright flows and screenshots

**Files:**
- Create: `e2e/ui-010-offline-sync.spec.ts`
- Create: `docs/evidence/C10/offline-e2e-red.txt`
- Create: `docs/evidence/C10/screenshot-index.md`
- Create: exactly eight `docs/evidence/C10/C10-UI010-*.png`

**Interfaces:**
- Produces: four deterministic C10 E2E tests and eight inspected screenshots.

- [ ] **Step 1: Write E2E before final page wiring and save RED**

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-010-offline-sync.spec.ts
```

Save the expected first meaningful failure to `docs/evidence/C10/offline-e2e-red.txt`.

- [ ] **Step 2: Implement four E2E tests**

1. OFF-004 CACHED→PENDING_UPLOAD→VALIDATING→MERGED, with audit/version assertions.
2. OFF-001 conflict detail, SCN-06 TOS-OFF-001 guidance, reset to SCN-01, then retry/upload/validate/merge.
3. Route permission, data scope, version rejection, illegal transition, delayed double-click idempotency, and upstream snapshots.
4. API-018/API-019 network and malformed responses, recovery, and reject branch.

- [ ] **Step 3: Capture exactly eight screenshots**

```text
C10-UI010-SCN01-CACHED-1440x900.png
C10-UI010-SCN01-CACHED-1280x720.png
C10-UI010-SCN01-UPLOADING-1440x900.png
C10-UI010-SCN01-UPLOADING-1280x720.png
C10-UI010-SCN01-CONFLICT-1440x900.png
C10-UI010-SCN01-CONFLICT-1280x720.png
C10-UI010-SCN01-MERGED-1440x900.png
C10-UI010-SCN01-MERGED-1280x720.png
```

Use viewport screenshots, not full-page screenshots, so original dimensions match filenames. For both viewports assert no horizontal overflow, selected status/version delta/action buttons/feedback visible, and all button boxes have positive dimensions.

- [ ] **Step 4: Inspect original images and commit**

Record filename, width, height, scenario, selected packet, business state, overflow result, clipping/overlap result, and command visibility in `screenshot-index.md`.

```powershell
node_modules\.bin\playwright.CMD test e2e/ui-010-offline-sync.spec.ts
git add e2e/ui-010-offline-sync.spec.ts docs/evidence/C10/offline-e2e-red.txt docs/evidence/C10/screenshot-index.md docs/evidence/C10/C10-UI010-*.png
git commit -m "test(c10): cover offline sync demo flows"
```

---

### Task 8: Final verification, coverage, and C10→C11 handoff

**Files:**
- Create: `docs/evidence/C10/coverage.json`
- Create: `docs/evidence/C10/verification.md`
- Create: `docs/evidence/C10/tsc-after.txt`
- Create: `docs/handoffs/C10-offline-sync.md`

**Interfaces:**
- Produces: final acceptance evidence and C11 entry contract.

- [ ] **Step 1: Run focused and full verification**

```powershell
pnpm vitest run src/features/offline-sync src/pages/__tests__/OfflineSyncPage.render.test.tsx src/pages/__tests__/OfflineSyncPage.action.test.tsx src/pages/__tests__/OfflineSyncPage.permission.test.tsx
pnpm test -- --run --maxWorkers=1
pnpm build
node_modules\.bin\playwright.CMD test e2e/ui-010-offline-sync.spec.ts
pnpm test:e2e
node_modules\.bin\tsc.CMD --noEmit
git diff --check
```

Save full TypeScript output to `docs/evidence/C10/tsc-after.txt`. If full Vitest repeats the known UI-002 timeout, rerun its exact file in isolation and record both results.

- [ ] **Step 2: Recheck hashes, scope, merge count, and image dimensions**

Confirm six hashes `6/6`, merge count `0`, exactly eight PNGs at four 1440×900 and four 1280×720, and no changed file under baseline, dependency manifests, public contract, permission/state-machine, or C09 feature/page paths.

- [ ] **Step 3: Write machine-readable coverage**

`coverage.json` must enumerate projection, query, API-018, API-019, OS-01..OS-05, permissions, data scope, version, illegal transition, idempotency, reset, page states, SCN-06 guidance, SCN-01 loop, four E2E tests, and eight screenshot filenames with actual status.

- [ ] **Step 4: Write verification and handoff**

`verification.md` records commands, timestamps, exit codes, file/test counts, build modules, TypeScript classification, hashes, screenshots, changed scope, branch, HEAD, and known limitations. `C10-offline-sync.md` documents public exports, query semantics, Gateway behavior, OS transitions, field ownership, SCN split, permissions, reset, evidence links, limitations, and C11 entry.

- [ ] **Step 5: Commit final evidence and verify clean status**

```powershell
git add docs/evidence/C10/coverage.json docs/evidence/C10/verification.md docs/evidence/C10/tsc-after.txt docs/handoffs/C10-offline-sync.md
git commit -m "docs(c10): add offline sync evidence and handoff"
git status --short --branch
```

Expected: clean `demo/c10-offline-sync`, no merge, no push.

---

## Plan Self-Review

- Spec coverage: DO-011, API-018/API-019, OS-01..OS-05, query, permissions, version, idempotency, audit, reset, SCN split, page states, E2E, screenshots, evidence, and handoff each map to a task.
- Placeholder scan: no unresolved marker, incomplete code reference, or deferred error-handling instruction.
- Type consistency: stable names are `offlineSync`, `parseOfflinePacketQuery`, `selectOfflinePacketBoard`, `selectOfflinePacketKpis`, `createOfflinePacketGateway`, `createOfflinePacketWorkflowStore`, `createOfflinePacketCommandService`, `uploadPacket`, `validatePacket`, `mergePacket`, `rejectPacket`, and `retryPacket`.
- Scope check: only UI-010/DO-011 and shared C03 integration points change; frozen and C09-owned areas remain unchanged.
