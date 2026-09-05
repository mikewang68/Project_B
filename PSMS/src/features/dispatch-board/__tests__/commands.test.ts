import { describe, expect, it, vi } from 'vitest';
import { ZodError } from 'zod';
import { transitionState } from '../../../commands';
import type { ApiErrorEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  calculateReceptionRecommendation,
  recommendationDraftSchema,
} from '../../recommendation';
import { generateTaskDraft } from '../../task-decomposition';
import {
  createDispatchBoardCommandService,
  dispatchCommandPayloadSchema,
} from '../commands';
import type {
  DispatchBoardGateway,
  DispatchBoardGatewaySuccess,
} from '../gateway';
import { createDispatchBoardWorkflowStore } from '../workflow';

const PLAN_ID = 'PLAN-001';
const WORK_ORDER_ID = 'C06-WO-PLAN-001-G001-02';
const WORK_NODE_ID = 'C06-NODE-PLAN-001-G001-02';
const RESOURCE_ID = 'RESOURCE-001';

function gatewaySuccess(
  apiId: 'API-008' | 'API-009',
  workOrderId = WORK_ORDER_ID,
): DispatchBoardGatewaySuccess {
  return {
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-008'
        ? 'POST_mock_work_orders_id_assign'
        : 'POST_mock_work_orders_id_dispatch',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [{ id: workOrderId }],
    },
    auditLogId: `MOCK-AUD-${apiId}`,
    traceId: `MOCK-TRACE-${apiId}`,
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict dispatch failure ${errorCode}`,
    auditLogId: 'MOCK-AUD-FAIL',
    traceId: 'MOCK-TRACE-FAIL',
  };
}

function seedReadyTasks(store: ReturnType<typeof createDemoStore>): void {
  store.replaceDomainState((candidate) => {
    const plan = candidate.plan.plans.find(({ id }) => id === PLAN_ID);
    if (!plan) throw new Error('PLAN-001 missing.');
    plan.status = 'CONFIRMED';
    plan.version = 2;
    const calculation = calculateReceptionRecommendation({
      plan,
      tracks: candidate.resource.tracks,
      generatedAt: candidate.session.demoTime,
    });
    const selected = calculation.candidates[0];
    if (!selected) throw new Error('Recommendation candidate missing.');
    const recommendation = recommendationDraftSchema.parse({
      planId: plan.id,
      draftVersion: 2,
      status: 'CONFIRMED',
      inputPlanVersion: plan.version,
      ruleVersion: 'C05-DEMO-RULE-1.0',
      generatedAt: candidate.session.demoTime,
      candidates: calculation.candidates,
      excluded: calculation.excluded,
      selectedCandidateId: selected.candidateId,
      confirmation: {
        actorId: candidate.session.actorId,
        roleCode: candidate.session.roleCode,
        confirmedAt: candidate.session.demoTime,
        commandId: 'CMD-C05-001',
        traceId: 'TRACE-C05-001',
      },
    });
    candidate.recommendation.drafts[plan.id] = recommendation;
    const generation = generateTaskDraft({
      plan,
      recommendation,
      waybills: candidate.plan.waybills,
      materials: candidate.resource.materials,
      resources: candidate.resource.resources,
      demoTime: candidate.session.demoTime,
      generationVersion: 1,
    });
    candidate.workOrder.workOrders.push(...generation.workOrders.map((order) => ({
      ...order,
      status: 'READY' as const,
      version: order.version + 1,
    })));
    candidate.workOrder.nodes.push(...generation.nodes.map((node) => ({
      ...node,
      version: node.version + 1,
    })));
    plan.status = 'DECOMPOSED';
    plan.version += 1;
  });
}

function createContext(options: Readonly<{
  roleCode?: DemoSessionSeed['roleCode'];
  dataScope?: string[];
  scenarioId?: DemoSessionSeed['scenarioId'];
  commandFormatter?: (sequence: number) => string;
  assign?: DispatchBoardGateway['assignWorkOrder'];
  dispatch?: DispatchBoardGateway['dispatchWorkOrder'];
}> = {}) {
  const store = createDemoStore(createFixtureSnapshot(), {
    actorId: 'USER-001',
    roleCode: options.roleCode ?? 'DISPATCHER',
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: options.scenarioId ?? 'SCN-01',
  });
  seedReadyTasks(store);
  const workflow = createDispatchBoardWorkflowStore();
  const assignWorkOrder = vi.fn<DispatchBoardGateway['assignWorkOrder']>(
    options.assign ?? (async () => gatewaySuccess('API-008')),
  );
  const dispatchWorkOrder = vi.fn<DispatchBoardGateway['dispatchWorkOrder']>(
    options.dispatch ?? (async () => gatewaySuccess('API-009')),
  );
  const gateway = { assignWorkOrder, dispatchWorkOrder } satisfies DispatchBoardGateway;
  const commands = createDispatchBoardCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, assignWorkOrder, dispatchWorkOrder };
}

function selectedState(context: ReturnType<typeof createContext>) {
  const state = context.store.getState();
  return {
    order: state.workOrder.workOrders.find(({ id }) => id === WORK_ORDER_ID)!,
    node: state.workOrder.nodes.find(({ id }) => id === WORK_NODE_ID)!,
    resource: state.resource.resources.find(({ id }) => id === RESOURCE_ID)!,
  };
}

function c07Audits(context: ReturnType<typeof createContext>) {
  return context.store.getState().configAudit.commandAudit.filter(
    ({ record }) => record.action.startsWith('DB-'),
  );
}

function arrangeDispatched(context: ReturnType<typeof createContext>): void {
  context.store.replaceDomainState((candidate) => {
    const order = candidate.workOrder.workOrders.find(({ id }) => id === WORK_ORDER_ID);
    const node = candidate.workOrder.nodes.find(({ id }) => id === WORK_NODE_ID);
    if (!order || !node) throw new Error('C07 pair missing.');
    Object.assign(order, {
      status: 'DISPATCHED' as const,
      resourceId: RESOURCE_ID,
      teamId: 'DEVICE-001',
      version: order.version + 2,
    });
    Object.assign(node, { status: 'READY' as const, version: node.version + 1 });
  });
}

describe('C07 DispatchCommandPayload', () => {
  it('accepts the exact DB-01 SM-007 snapshot and rejects extra context', () => {
    const payload = {
      current: 'ACCEPTED',
      businessAction: 'DB-01',
      planId: PLAN_ID,
      planVersion: 3,
      workOrderVersion: 2,
      workNodeId: WORK_NODE_ID,
      workNodeVersion: 2,
      resourceId: RESOURCE_ID,
      resourceVersion: 1,
      reason: '演示资源绑定',
    };

    expect(dispatchCommandPayloadSchema.parse(payload)).toEqual(payload);
    expect(() => dispatchCommandPayloadSchema.parse({ ...payload, actor: 'USER-001' }))
      .toThrow(ZodError);
  });
});

describe('DispatchBoardCommandService DB-01/DB-02', () => {
  it('binds a matching AVAILABLE resource without changing READY/WAITING or domain sources', async () => {
    const context = createContext();
    const before = structuredClone(context.store.getState());
    const snapshotInsideApi: Array<{ resourceId: string; status: string }> = [];
    context.assignWorkOrder.mockImplementationOnce(async () => {
      const { order } = selectedState(context);
      snapshotInsideApi.push({ resourceId: order.resourceId, status: order.status });
      return gatewaySuccess('API-008');
    });

    const result = await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
      reason: '演示资源绑定',
    });

    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C07-001',
      traceId: 'TRACE-C07-001',
      auditLogId: 'AUD-C07-001',
    });
    expect(snapshotInsideApi).toEqual([{ resourceId: '', status: 'READY' }]);
    expect(context.assignWorkOrder).toHaveBeenCalledWith(WORK_ORDER_ID, {
      resourceId: RESOURCE_ID,
      reason: '演示资源绑定',
    });
    const { order, node } = selectedState(context);
    expect(order).toMatchObject({
      status: 'READY',
      resourceId: RESOURCE_ID,
      teamId: 'DEVICE-001',
      version: before.workOrder.workOrders.find(({ id }) => id === WORK_ORDER_ID)!.version + 1,
      updatedAt: before.session.demoTime,
    });
    expect(node).toEqual(before.workOrder.nodes.find(({ id }) => id === WORK_NODE_ID));
    expect(context.store.getState().plan).toEqual(before.plan);
    expect(context.store.getState().recommendation).toEqual(before.recommendation);
    expect(context.store.getState().resource).toEqual(before.resource);
    expect(context.store.getState().workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-')))
      .toEqual(before.workOrder.workOrders.filter(({ id }) => !id.startsWith('C06-WO-')));
    expect(c07Audits(context)).toHaveLength(1);
    expect(c07Audits(context)[0]).toMatchObject({
      record: { id: 'AUD-C07-001', action: 'DB-01', objectId: WORK_ORDER_ID },
      metadata: { result: 'SUCCESS', errorCode: null },
    });
  });

  it('dispatches only an assigned READY order and advances its WAITING node atomically', async () => {
    const context = createContext();
    await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });
    const beforeDispatch = structuredClone(context.store.getState());
    const snapshotInsideApi: string[] = [];
    context.dispatchWorkOrder.mockImplementationOnce(async () => {
      snapshotInsideApi.push(selectedState(context).order.status);
      return gatewaySuccess('API-009');
    });

    const result = await context.commands.dispatchWorkOrder(WORK_ORDER_ID);

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C07-002' });
    expect(snapshotInsideApi).toEqual(['READY']);
    expect(context.dispatchWorkOrder).toHaveBeenCalledWith(WORK_ORDER_ID, {
      target: 'AREA-A',
      simulateReceipt: true,
    });
    const { order, node } = selectedState(context);
    expect(order).toMatchObject({
      status: 'DISPATCHED',
      version: beforeDispatch.workOrder.workOrders.find(({ id }) => id === WORK_ORDER_ID)!.version + 1,
      updatedAt: beforeDispatch.session.demoTime,
    });
    expect(node).toMatchObject({
      status: 'READY',
      version: beforeDispatch.workOrder.nodes.find(({ id }) => id === WORK_NODE_ID)!.version + 1,
      updatedAt: beforeDispatch.session.demoTime,
    });
    expect(c07Audits(context).map(({ record }) => record.action)).toEqual(['DB-01', 'DB-02']);
    expect(transitionState({
      machineId: 'DO-005', current: 'READY', command: 'dispatch',
    })).toEqual({
      ok: true, previous: 'READY', next: 'DISPATCHED', command: 'dispatch',
    });
    expect(transitionState({
      machineId: 'DO-005', current: 'READY', command: 'assign',
    })).toMatchObject({ ok: false, reason: 'FORBIDDEN_TRANSITION' });
  });

  it.each([
    ['missing permission', { roleCode: 'BUSINESS' as const }, RESOURCE_ID],
    ['AREA-B data scope', { dataScope: ['AREA-B'] }, RESOURCE_ID],
    ['resource type mismatch', {}, 'RESOURCE-004'],
    ['unavailable resource', {}, 'RESOURCE-007'],
  ])('rejects %s before API and appends one audit without a partial write', async (
    _label,
    options,
    resourceId,
  ) => {
    const context = createContext(options);
    const before = structuredClone(context.store.getState().workOrder);

    const result = await context.commands.bindDispatchResource({ workOrderId: WORK_ORDER_ID, resourceId });

    expect(result).toMatchObject({ ok: false });
    expect(context.assignWorkOrder).not.toHaveBeenCalled();
    expect(context.store.getState().workOrder).toEqual(before);
    expect(c07Audits(context)).toHaveLength(1);
  });

  it('rejects dispatch before API when no resource is bound', async () => {
    const context = createContext();
    const before = structuredClone(context.store.getState().workOrder);

    const result = await context.commands.dispatchWorkOrder(WORK_ORDER_ID);

    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(context.dispatchWorkOrder).not.toHaveBeenCalled();
    expect(context.store.getState().workOrder).toEqual(before);
  });

  it('maps post-API WorkOrder or Resource drift to DEMO-VERSION-001 without assignment', async () => {
    let resolveAssign: ((value: DispatchBoardGatewaySuccess) => void) | undefined;
    const context = createContext({
      assign: () => new Promise((resolve) => { resolveAssign = resolve; }),
    });
    const pending = context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });
    await vi.waitFor(() => expect(context.assignWorkOrder).toHaveBeenCalledOnce());
    context.store.replaceDomainState((candidate) => {
      candidate.resource.resources.find(({ id }) => id === RESOURCE_ID)!.version += 1;
    });
    if (!resolveAssign) throw new Error('Assign resolver missing.');
    resolveAssign(gatewaySuccess('API-008'));

    await expect(pending).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
    });
    expect(selectedState(context).order).toMatchObject({ status: 'READY', resourceId: '', teamId: '' });
    expect(selectedState(context).node.status).toBe('WAITING');
    expect(c07Audits(context)).toHaveLength(1);
  });

  it('replays one frozen command result without repeating API, commit, workflow, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C07-REPLAY' });
    context.workflow.selectWorkOrder(WORK_ORDER_ID);
    context.workflow.selectResource(RESOURCE_ID);
    let workflowNotifications = 0;
    context.workflow.subscribe(() => { workflowNotifications += 1; });

    const first = await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });
    const versionAfterFirst = selectedState(context).order.version;
    const notificationsAfterFirst = workflowNotifications;
    const second = await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });

    expect(second).toBe(first);
    expect(context.assignWorkOrder).toHaveBeenCalledOnce();
    expect(selectedState(context).order.version).toBe(versionAfterFirst);
    expect(workflowNotifications).toBe(notificationsAfterFirst);
    expect(c07Audits(context)).toHaveLength(1);
  });

  it('blocks SCN-05 before API and preserves workflow selections for recovery', async () => {
    const context = createContext({ scenarioId: 'SCN-05' });
    context.workflow.selectWorkOrder(WORK_ORDER_ID);
    context.workflow.selectResource(RESOURCE_ID);
    const before = structuredClone(context.store.getState().workOrder);

    const result = await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-IL-001' });
    expect(context.assignWorkOrder).not.toHaveBeenCalled();
    expect(context.store.getState().workOrder).toEqual(before);
    expect(context.workflow.getState()).toMatchObject({
      selectedWorkOrderId: WORK_ORDER_ID,
      selectedResourceId: RESOURCE_ID,
      lastCommandError: { errorCode: 'TOS-IL-001' },
    });
  });

  it('maps strict gateway failure to one failed audit and no partial write', async () => {
    const context = createContext({ assign: async () => gatewayFailure('TOS-EXT-001') });
    const before = structuredClone(context.store.getState().workOrder);

    const result = await context.commands.bindDispatchResource({
      workOrderId: WORK_ORDER_ID,
      resourceId: RESOURCE_ID,
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    expect(context.store.getState().workOrder).toEqual(before);
    expect(c07Audits(context)).toHaveLength(1);
  });
});

describe('DispatchBoardCommandService DB-03/DB-04 local Demo feedback', () => {
  it('acknowledges, starts, pauses, resumes, and completes with frozen transitions and node sync', async () => {
    const context = createContext();
    await context.commands.bindDispatchResource({ workOrderId: WORK_ORDER_ID, resourceId: RESOURCE_ID });
    await context.commands.dispatchWorkOrder(WORK_ORDER_ID);
    const gatewayCalls = {
      assign: context.assignWorkOrder.mock.calls.length,
      dispatch: context.dispatchWorkOrder.mock.calls.length,
    };

    await expect(context.commands.acknowledgeWorkOrder(WORK_ORDER_ID))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-003' });
    expect(selectedState(context)).toMatchObject({
      order: { status: 'ACKNOWLEDGED', ackStatus: 'ACKNOWLEDGED' },
      node: { status: 'READY', actualStartTime: '', actualFinishTime: '' },
    });

    await expect(context.commands.startWorkOrder(WORK_ORDER_ID))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-004' });
    const firstStartTime = selectedState(context).node.actualStartTime;
    expect(selectedState(context)).toMatchObject({
      order: { status: 'IN_PROGRESS' },
      node: { status: 'IN_PROGRESS', actualStartTime: context.store.getState().session.demoTime },
    });

    await expect(context.commands.pauseWorkOrder(WORK_ORDER_ID))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-005' });
    expect(selectedState(context)).toMatchObject({
      order: { status: 'PAUSED' },
      node: { status: 'IN_PROGRESS', actualStartTime: firstStartTime },
    });

    await expect(context.commands.startWorkOrder(WORK_ORDER_ID))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-006' });
    expect(selectedState(context).node.actualStartTime).toBe(firstStartTime);

    await expect(context.commands.completeWorkOrder(WORK_ORDER_ID))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-007' });
    expect(selectedState(context)).toMatchObject({
      order: { status: 'COMPLETED' },
      node: {
        status: 'COMPLETED',
        actualStartTime: firstStartTime,
        actualFinishTime: context.store.getState().session.demoTime,
      },
    });
    expect(context.assignWorkOrder).toHaveBeenCalledTimes(gatewayCalls.assign);
    expect(context.dispatchWorkOrder).toHaveBeenCalledTimes(gatewayCalls.dispatch);
    expect(c07Audits(context).map(({ record }) => record.action)).toEqual([
      'DB-01', 'DB-02', 'DB-03', 'DB-04', 'DB-04', 'DB-04', 'DB-04',
    ]);
    expect(transitionState({ machineId: 'DO-005', current: 'DISPATCHED', command: 'ack' }))
      .toMatchObject({ ok: true, next: 'ACKNOWLEDGED' });
    expect(transitionState({ machineId: 'DO-005', current: 'ACKNOWLEDGED', command: 'start' }))
      .toMatchObject({ ok: true, next: 'IN_PROGRESS' });
    expect(transitionState({ machineId: 'DO-005', current: 'IN_PROGRESS', command: 'pause' }))
      .toMatchObject({ ok: true, next: 'PAUSED' });
    expect(transitionState({ machineId: 'DO-005', current: 'PAUSED', command: 'start' }))
      .toMatchObject({ ok: true, next: 'IN_PROGRESS' });
    expect(transitionState({ machineId: 'DO-005', current: 'IN_PROGRESS', command: 'complete' }))
      .toMatchObject({ ok: true, next: 'COMPLETED' });
  });

  it('rejects invalid feedback state and pause permission without any partial write', async () => {
    const invalid = createContext();
    const invalidBefore = structuredClone(invalid.store.getState().workOrder);
    await expect(invalid.commands.startWorkOrder(WORK_ORDER_ID)).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
    });
    expect(invalid.store.getState().workOrder).toEqual(invalidBefore);
    expect(invalid.assignWorkOrder).not.toHaveBeenCalled();
    expect(invalid.dispatchWorkOrder).not.toHaveBeenCalled();

    const denied = createContext();
    arrangeDispatched(denied);
    await denied.commands.acknowledgeWorkOrder(WORK_ORDER_ID);
    await denied.commands.startWorkOrder(WORK_ORDER_ID);
    denied.store.replaceDomainState((candidate) => {
      candidate.session.roleCode = 'BUSINESS';
    });
    const deniedBefore = structuredClone(denied.store.getState().workOrder);

    await expect(denied.commands.pauseWorkOrder(WORK_ORDER_ID)).resolves.toMatchObject({
      ok: false,
      errorCode: 'TOS-AUTH-001',
    });
    expect(denied.store.getState().workOrder).toEqual(deniedBefore);
  });

  it('performs a second version check after the local boundary', async () => {
    const context = createContext();
    arrangeDispatched(context);

    const pending = context.commands.acknowledgeWorkOrder(WORK_ORDER_ID);
    context.store.replaceDomainState((candidate) => {
      candidate.workOrder.workOrders.find(({ id }) => id === WORK_ORDER_ID)!.version += 1;
    });

    await expect(pending).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
    });
    expect(selectedState(context)).toMatchObject({
      order: { status: 'DISPATCHED', ackStatus: 'PENDING' },
      node: { status: 'READY' },
    });
  });

  it('replays local feedback once and blocks SCN-05 before the local boundary', async () => {
    const replay = createContext({ commandFormatter: () => 'CMD-C07-LOCAL-REPLAY' });
    arrangeDispatched(replay);
    const first = await replay.commands.acknowledgeWorkOrder(WORK_ORDER_ID);
    const versionAfterFirst = selectedState(replay).order.version;
    const second = await replay.commands.acknowledgeWorkOrder(WORK_ORDER_ID);

    expect(second).toBe(first);
    expect(selectedState(replay).order.version).toBe(versionAfterFirst);
    expect(c07Audits(replay)).toHaveLength(1);
    expect(replay.assignWorkOrder).not.toHaveBeenCalled();
    expect(replay.dispatchWorkOrder).not.toHaveBeenCalled();

    const interlocked = createContext({ scenarioId: 'SCN-05' });
    arrangeDispatched(interlocked);
    const before = structuredClone(interlocked.store.getState().workOrder);
    await expect(interlocked.commands.acknowledgeWorkOrder(WORK_ORDER_ID)).resolves.toMatchObject({
      ok: false,
      errorCode: 'TOS-IL-001',
    });
    expect(interlocked.store.getState().workOrder).toEqual(before);
    expect(interlocked.assignWorkOrder).not.toHaveBeenCalled();
    expect(interlocked.dispatchWorkOrder).not.toHaveBeenCalled();
  });
});
