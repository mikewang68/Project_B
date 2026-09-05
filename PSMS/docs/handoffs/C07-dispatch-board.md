# C07 / UI-005 Dispatch Board Handoff

## Delivered boundary

C07 implements only UI-005 at `/dispatch/work-orders`. It consumes C06-owned READY-or-later WorkOrder/WorkNode pairs, assigns visible AREA-A resources, dispatches through frozen API-008/API-009 semantics, and demonstrates local execution feedback through frozen DO-005/DO-006 transitions. UI-006 and later modules remain untouched.

The owned input contract is intentionally strict:

- WorkOrder `id` and `workOrderNo` start with `C06-WO-`;
- WorkNode `id` starts with `C06-NODE-`, `nodeNo` starts with `C06-N-`, and its `workOrderNo` belongs to an owned order;
- `ruleVersion === 'C06-DEMO-RULE-1.0'`;
- selected Plan is `DECOMPOSED`;
- only READY-or-later frozen WorkOrder states are projected;
- historical `WO-001..012` / `NODE-001..012` records are excluded.

## Public runtime surface

`DemoRuntime` now exposes:

```ts
type DispatchBoardRuntime = {
  gateway: DispatchBoardGateway;
  workflow: DispatchBoardWorkflowStore;
  commands: DispatchBoardCommandService;
};

runtime.dispatchBoard
useDispatchBoardWorkflow(selector)
```

The feature barrel `src/features/dispatch-board/index.ts` exports commands, constants, gateway, ownership predicates, selectors, types, and workflow creation.

Command service methods:

```ts
bindDispatchResource({ workOrderId, resourceId, reason? })
dispatchWorkOrder(workOrderId)
acknowledgeWorkOrder(workOrderId)
startWorkOrder(workOrderId)
pauseWorkOrder(workOrderId)
completeWorkOrder(workOrderId)
resetCommandState()
```

## Selectors and projections

- `selectDispatchBoard(state, planId)` returns the frozen UI-005 projection: Plan/C06 provenance, ordered owned pairs, dependencies, resource requirements, candidates, progress, KPI, and DB-05 exception URL.
- `selectAssignableResources(state, requiredResourceType)` applies AREA-A visibility before projection/counting, filters by exact resource type, and keeps non-AVAILABLE candidates visible with a reason while marking them non-assignable.
- `selectDispatchKpis(state, planId)` returns counts for READY, bound, dispatched, executing, completed, and exception entry.
- Progress is derived as `READY_QUEUE`, `ASSIGNED`, `DISPATCHED`, or `EXECUTING`. `EXECUTING` covers frozen later execution states; the displayed domain status remains visible separately.
- DB-05 is read-only and routes to `/monitor/exceptions` with `workOrderId`, `planId`, `scenarioId`, and `from=dispatch-board`.

## Gateway behavior

- API-008: `POST /mock/work-orders/{id}/assign` with `{ resourceId, reason? }`.
- API-009: `POST /mock/work-orders/{id}/dispatch` with `{ target: 'AREA-A', simulateReceipt: true }`.
- The Gateway validates strict success identity (`apiId`, `operationId`, scenario/object identity and exact envelope shape), passes strict error envelopes through, and maps transport/malformed responses at the command/page boundary.
- The Gateway never mutates the Store. Commands recheck versions after the remote/local boundary and commit one atomic domain mutation only after validation succeeds.

## DB-01 through DB-05 semantics

| Action | Behavior |
| --- | --- |
| DB-01 bind/rebind | Requires an owned READY WorkOrder, WAITING WorkNode, matching visible AVAILABLE resource and `dispatch:assign` or `dispatch:reassign`. Writes `resourceId`/`teamId` and increments WorkOrder version. WorkOrder stays READY; WorkNode stays WAITING. It does not call DO-005 `assign`. |
| DB-02 dispatch | Requires the existing valid binding and `dispatch:send`. Calls API-009, applies DO-005 `READY + dispatch -> DISPATCHED`, and advances the WorkNode `WAITING -> READY` atomically. |
| DB-03 acknowledge | Local Demo feedback with `dispatch:send`: WorkOrder `DISPATCHED -> ACKNOWLEDGED`, `ackStatus -> ACKNOWLEDGED`; WorkNode remains READY. |
| DB-04 execute | Local Demo feedback with frozen transitions: START `ACKNOWLEDGED/PAUSED -> IN_PROGRESS`, PAUSE `IN_PROGRESS -> PAUSED` with `dispatch:pause`, COMPLETE `IN_PROGRESS -> COMPLETED`; WorkNode becomes/stays IN_PROGRESS and then COMPLETED, with actual times recorded. |
| DB-05 exception | Read-only scoped link into UI-008; no later-module behavior is implemented here. |

Every command uses SM-007 context, frozen policy authorization, AREA-A object scope, version snapshots, idempotent replay, a stable trace, and exactly one audit outcome. Invalid state, permission, scope, resource, version, gateway, and interlock results make no partial domain write.

## Permissions and safety

- Route visibility requires `dispatch:view`; DISPATCHER and SHIFT_LEADER can view, BUSINESS receives the route-level forbidden state before page data/API work.
- DB-01 uses `dispatch:assign` or `dispatch:reassign` depending on existing binding.
- DB-02, DB-03, START and COMPLETE use `dispatch:send`.
- PAUSE uses `dispatch:pause`.
- AREA-B and unknown Plan requests return the safe not-found state without leaking Plan/work-order facts.
- In SCN-05, `INTERLOCK_FORCE_STOP` blocks DB-01 through DB-04 before API/local feedback with `TOS-IL-001`. UI-005 disables writes and exposes the UI-009 link; there is no override.
- The page prevents concurrent double submission with one in-flight Promise guard and preserves work-order/resource selection for retry.

## Reset behavior

API-025 scenario reset remains the single reset entry. After a successful reset it restores fixture domain state, clears C07 selection/drawers/mode/error, clears command replay state, and restarts C07 command/trace/audit sequences at `001`. C07 does not introduce a second reset path.

## UI states and responsive evidence

UI-005 implements the required `loading`, `empty`, `business-error`, `network-error`, `forbidden`, and `not-found` states. The working board shows C06 provenance, KPIs, queue, selected detail, resource truth, action controls, audit/trace feedback, safety recovery links, and the disclosure `Demo 本地执行反馈，不代表现场系统回执`.

The 1440 viewport uses a three-column board. At 1280, queue/detail remain primary and the resource section stacks below. Queue times use compact `MM-DD HH:mm`; no content is horizontal-only.

## Evidence

- Final verification: [`docs/evidence/C07/verification.md`](../evidence/C07/verification.md)
- Coverage map: [`docs/evidence/C07/coverage.json`](../evidence/C07/coverage.json)
- TypeScript output: [`docs/evidence/C07/tsc-after.txt`](../evidence/C07/tsc-after.txt)
- Screenshot index: [`docs/evidence/C07/screenshot-index.md`](../evidence/C07/screenshot-index.md)
- Selector/command red evidence: [`docs/evidence/C07/dispatch-core-red.txt`](../evidence/C07/dispatch-core-red.txt)
- Page red evidence: [`docs/evidence/C07/dispatch-page-red.txt`](../evidence/C07/dispatch-page-red.txt)
- E2E red evidence: [`docs/evidence/C07/dispatch-e2e-red.txt`](../evidence/C07/dispatch-e2e-red.txt)

Final measured results: focused Vitest `49/49`, full Vitest `499/499`, build `1,907` modules, C07 Playwright `4/4`, final full Playwright `31/31`, frozen hashes `6/6`, screenshots `8/8`, and C07 non-React primary TypeScript diagnostics `0`.

## Known limitations

- DB-03/DB-04 feedback is local Demo truth only, not a field-system receipt.
- Global TypeScript still reports only the frozen dependency baseline's React/JSX declaration categories; C07 non-React primary diagnostics are zero.
- Existing Ant Design development warnings for `Message` during render and deprecated `List` remain outside this UI-005 scope.
- The board intentionally shows one deterministic C06-generated chain and does not fabricate production capacity, live device telemetry, yard appointments, or exception-processing results.

## Recommended C08 entry

C08 should consume public Store projections and the frozen cross-page query context. Treat C07 WorkOrder/WorkNode/resource state as read-only upstream truth unless the C08 contract explicitly owns a mutation. Do not reuse the UI-005 local feedback disclosure as evidence of a field-system receipt, and do not broaden UI-005 DB-05 into UI-008 implementation work.
