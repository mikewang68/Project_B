import { describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../../contracts';
import { createFixtureSnapshot, type DemoScenario } from '../../../mocks/fixtures';
import { createDemoStore } from '../../../stores';
import { createPlanEntryCommandService } from '../commands';
import { createPlanEntryGateway } from '../gateway';
import type { SupplementPlanValues } from '../components/SupplementPlanForm';
import { createPlanEntryWorkflowStore } from '../workflow';

type StrictEnvelope = ApiSuccessEnvelope | ApiErrorEnvelope;
type StrictRequest = Readonly<{
  path: string;
  body: unknown;
  call: number;
}>;

const supplements: SupplementPlanValues = {
  trackNo: 'T1',
  reason: 'Resolve the missing plan track.',
  effectiveUntil: '2026-07-16T12:30',
  reviewerId: 'USER-002',
};

function success(call: number, data: Record<string, unknown> = {}): ApiSuccessEnvelope {
  return {
    ok: true,
    data,
    traceId: `MOCK-TRACE-${call}`,
    auditLogId: `MOCK-AUD-${call}`,
  };
}

function failure(call: number, errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict gateway failure ${errorCode}`,
    traceId: `MOCK-TRACE-${call}`,
    auditLogId: `MOCK-AUD-${call}`,
  };
}

function createStrictGateway(
  respond: (request: StrictRequest) => StrictEnvelope = ({ call }) =>
    success(call, {
      status: 'CANCELLED',
      version: 999,
      trackNo: 'GATEWAY-MUST-NOT-WIN',
    }),
) {
  const requests: StrictRequest[] = [];
  const fetcher = vi.fn(
    async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const rawUrl = input instanceof Request ? input.url : String(input);
      const path = new URL(rawUrl, 'http://localhost').pathname;
      const rawBody = input instanceof Request ? await input.clone().text() : String(init?.body ?? '');
      const body: unknown = rawBody ? JSON.parse(rawBody) : undefined;
      const request = { path, body, call: requests.length + 1 } as const;
      requests.push(request);
      const envelope = respond(request);
      return new Response(JSON.stringify(envelope), {
        status: envelope.ok ? 200 : 409,
        headers: { 'content-type': 'application/json' },
      });
    },
  );
  return {
    fetcher,
    gateway: createPlanEntryGateway(fetcher as typeof fetch),
    requests,
  };
}

function createStorage() {
  const records = new Map<string, string>();
  return {
    records,
    storage: {
      setItem: vi.fn((key: string, value: string) => {
        records.set(key, value);
      }),
    },
  };
}

function createServiceContext(
  scenarioId: DemoScenario['id'] = 'SCN-01',
  roleCode: RoleCode = 'DISPATCHER',
  actorId = 'USER-001',
  gatewayFixture = createStrictGateway(),
  idFormatters?: Readonly<{
    command?: (sequence: number) => string;
    trace?: (sequence: number) => string;
    audit?: (sequence: number) => string;
  }>,
  onSuccessfulReset?: () => void,
) {
  const store = createDemoStore(createFixtureSnapshot(), {
    actorId,
    roleCode,
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId,
  });
  const workflow = createPlanEntryWorkflowStore();
  const storageFixture = createStorage();
  const commands = createPlanEntryCommandService({
    store,
    workflow,
    gateway: gatewayFixture.gateway,
    storage: storageFixture.storage,
    idFormatters,
    onSuccessfulReset,
  });
  return { commands, store, workflow, storageFixture, ...gatewayFixture };
}

function planFrom(context: ReturnType<typeof createServiceContext>, planId: string) {
  const plan = context.store.getState().plan.plans.find(({ id }) => id === planId);
  if (!plan) throw new Error(`Missing test plan ${planId}`);
  return plan;
}

describe('PlanEntryCommandService Gate C integration', () => {
  it('confirms PLAN-001 through API-004 with Store truth, one atomic plan commit, and one deterministic audit', async () => {
    const context = createServiceContext();
    const before = structuredClone(context.store.getState());

    const result = await context.commands.confirmPlan('PLAN-001');

    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C04-001',
      traceId: 'TRACE-C04-001',
      auditLogId: 'AUD-C04-001',
    });
    expect(context.requests).toEqual([
      { path: '/mock/plans/PLAN-001/confirm', body: {}, call: 1 },
    ]);
    const plan = planFrom(context, 'PLAN-001');
    expect(plan).toEqual({
      ...before.plan.plans.find(({ id }) => id === 'PLAN-001'),
      status: 'CONFIRMED',
      version: 2,
      updatedAt: '2026-07-16T09:00:00+08:00',
    });
    expect(plan.trackNo).toBe('T1');
    expect(context.store.getState().plan.plans.filter(({ id }) => id !== 'PLAN-001')).toEqual(
      before.plan.plans.filter(({ id }) => id !== 'PLAN-001'),
    );
    const audits = context.store.getState().configAudit.commandAudit;
    expect(audits).toHaveLength(1);
    expect(audits[0]).toMatchObject({
      record: {
        id: 'AUD-C04-001',
        action: 'confirm',
        objectId: 'PLAN-001',
        traceId: 'TRACE-C04-001',
        occurredAt: '2026-07-16T09:00:00+08:00',
      },
      metadata: {
        result: 'SUCCESS',
        clientTime: '2026-07-16T09:00:00+08:00',
        serverTime: '2026-07-16T09:00:00+08:00',
      },
    });
  });

  it('adjusts then confirms SCN-02 PLAN-002 with complete supplements, resolved trackNo, versions 1→2→3, and exactly two audits', async () => {
    const context = createServiceContext('SCN-02');

    const adjusted = await context.commands.supplementPlan('PLAN-002', supplements);
    expect(adjusted).toMatchObject({ ok: true, commandId: 'CMD-C04-001' });
    expect(planFrom(context, 'PLAN-002')).toMatchObject({
      trackNo: 'T1',
      status: 'ADJUSTED',
      version: 2,
      updatedAt: '2026-07-16T10:00:00+08:00',
    });
    expect(context.workflow.getState().resolvedFields).toEqual({ 'PLAN-002': ['trackNo'] });

    const confirmed = await context.commands.confirmPlan('PLAN-002', supplements);
    expect(confirmed).toMatchObject({ ok: true, commandId: 'CMD-C04-002' });
    expect(planFrom(context, 'PLAN-002')).toMatchObject({
      trackNo: 'T1',
      status: 'CONFIRMED',
      version: 3,
      updatedAt: '2026-07-16T10:00:00+08:00',
    });
    expect(context.requests.map(({ body }) => body)).toEqual([
      {
        reason: supplements.reason,
        supplements: {
          trackNo: supplements.trackNo,
          effectiveUntil: supplements.effectiveUntil,
          reviewerId: supplements.reviewerId,
        },
      },
      {
        reason: supplements.reason,
        supplements: {
          trackNo: supplements.trackNo,
          effectiveUntil: supplements.effectiveUntil,
          reviewerId: supplements.reviewerId,
        },
      },
    ]);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(2);
    expect(context.store.getState().configAudit.commandAudit.map(({ record }) => record.action)).toEqual([
      'adjust',
      'confirm',
    ]);
  });

  it('denies same actor and reviewer in the permission phase, leaves PLAN-002 unchanged, sends no request, and writes one denial audit', async () => {
    const context = createServiceContext('SCN-02', 'DISPATCHER', 'USER-001');
    const before = structuredClone(planFrom(context, 'PLAN-002'));

    const result = await context.commands.supplementPlan('PLAN-002', {
      ...supplements,
      reviewerId: 'USER-001',
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(planFrom(context, 'PLAN-002')).toEqual(before);
    expect(context.fetcher).not.toHaveBeenCalled();
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit[0]).toMatchObject({
      metadata: { result: 'DENIED', errorCode: 'TOS-AUTH-001' },
    });
  });

  it('denies confirm values with the same actor and reviewer before Gateway while preserving the adjusted plan', async () => {
    const context = createServiceContext('SCN-02', 'DISPATCHER', 'USER-001');
    const adjusted = await context.commands.supplementPlan('PLAN-002', supplements);
    expect(adjusted).toMatchObject({ ok: true, commandId: 'CMD-C04-001' });
    const beforeConfirm = structuredClone(planFrom(context, 'PLAN-002'));

    const result = await context.commands.confirmPlan('PLAN-002', {
      ...supplements,
      reviewerId: 'USER-001',
    });

    expect(result).toMatchObject({
      ok: false,
      commandId: 'CMD-C04-002',
      errorCode: 'TOS-AUTH-001',
    });
    expect(planFrom(context, 'PLAN-002')).toEqual(beforeConfirm);
    expect(context.fetcher).toHaveBeenCalledTimes(1);
    expect(
      context.store.getState().configAudit.commandAudit.filter(
        ({ metadata }) => metadata.result === 'DENIED',
      ),
    ).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit[1]).toMatchObject({
      record: { action: 'confirm', objectId: 'PLAN-002' },
      metadata: { result: 'DENIED', errorCode: 'TOS-AUTH-001' },
    });
  });

  it('rejects confirm supplements that differ from the adjusted Store track before Gateway or commit', async () => {
    const context = createServiceContext('SCN-02');

    const adjusted = await context.commands.supplementPlan('PLAN-002', supplements);
    expect(adjusted).toMatchObject({ ok: true, commandId: 'CMD-C04-001' });
    const afterAdjust = structuredClone(planFrom(context, 'PLAN-002'));

    context.commands.switchRole('INTERFACE_OPS');
    const confirmed = await context.commands.confirmPlan('PLAN-002', {
      ...supplements,
      trackNo: 'T2',
    });

    expect(confirmed).toMatchObject({
      ok: false,
      commandId: 'CMD-C04-002',
      errorCode: 'DEMO-SCENARIO-001',
    });
    expect(planFrom(context, 'PLAN-002')).toEqual(afterAdjust);
    expect(context.requests).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(2);
    expect(context.store.getState().configAudit.commandAudit[1]).toMatchObject({
      record: { action: 'confirm', objectId: 'PLAN-002' },
      metadata: { result: 'FAILED', errorCode: 'DEMO-SCENARIO-001' },
    });
  });

  it('detects a gateway-window version race as DEMO-VERSION-001 and preserves the complete concurrent Store plan without a partial command commit', async () => {
    let context: ReturnType<typeof createServiceContext>;
    const gatewayFixture = createStrictGateway(({ call }) => {
      context.store.replaceDomainState((candidate) => {
        const concurrent = candidate.plan.plans.find(({ id }) => id === 'PLAN-002');
        if (!concurrent) throw new Error('Missing concurrent plan');
        concurrent.version = 7;
      });
      return success(call, { status: 'BLOCKED' });
    });
    context = createServiceContext('SCN-02', 'DISPATCHER', 'USER-001', gatewayFixture);

    const result = await context.commands.supplementPlan('PLAN-002', supplements);

    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(planFrom(context, 'PLAN-002')).toMatchObject({
      trackNo: 'T2',
      status: 'BLOCKED',
      version: 7,
      updatedAt: '2026-07-16T07:02:00+08:00',
    });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit[0]).toMatchObject({
      metadata: { result: 'FAILED', errorCode: 'DEMO-VERSION-001' },
    });
  });

  it('opens SCN-03 after three unique audited TOS-EXT-001 results and returns the same deeply frozen third result without a fourth side effect', async () => {
    const gatewayFixture = createStrictGateway(({ call }) => failure(call, 'TOS-EXT-001'));
    const context = createServiceContext('SCN-03', 'DISPATCHER', 'USER-001', gatewayFixture);
    const rootBefore = structuredClone(context.store.getState());

    const first = await context.commands.syncPlans('SCN-03');
    const second = await context.commands.syncPlans('SCN-03');
    const third = await context.commands.syncPlans('SCN-03');
    const workflowBeforeFourth = context.workflow.getState();
    const storeBeforeFourth = context.store.getState();
    const fourth = await context.commands.syncPlans('SCN-03');

    expect([first, second, third].map(({ commandId }) => commandId)).toEqual([
      'CMD-C04-001',
      'CMD-C04-002',
      'CMD-C04-003',
    ]);
    for (const result of [first, second, third]) {
      expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    }
    expect(context.fetcher).toHaveBeenCalledTimes(3);
    expect(context.workflow.getState()).toMatchObject({ retryCount: 3, circuitOpen: true });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(3);
    expect(
      context.store.getState().configAudit.commandAudit.map(({ metadata }) => metadata.errorCode),
    ).toEqual(['TOS-EXT-001', 'TOS-EXT-001', 'TOS-EXT-001']);
    expect(context.store.getState().plan).toEqual(rootBefore.plan);
    expect(fourth).toBe(third);
    expect(Object.isFrozen(fourth)).toBe(true);
    expect(context.workflow.getState()).toBe(workflowBeforeFourth);
    expect(context.store.getState()).toBe(storeBeforeFourth);
  });

  it('denies DISPATCHER recovery, then INTERFACE_OPS recovery atomically resets gateway/Store/workflow/storage/caches and permits a fresh successful sync', async () => {
    const gatewayFixture = createStrictGateway(({ path, call }) =>
      path === '/mock/plans/sync' && call <= 3
        ? failure(call, 'TOS-EXT-001')
        : success(call, { status: 'FAILED' }),
    );
    const context = createServiceContext('SCN-03', 'DISPATCHER', 'USER-001', gatewayFixture);
    await context.commands.syncPlans('SCN-03');
    await context.commands.syncPlans('SCN-03');
    await context.commands.syncPlans('SCN-03');
    const stateBeforeDenial = context.store.getState();
    const workflowBeforeDenial = context.workflow.getState();

    const denied = await context.commands.resetScenario('SCN-01');
    expect(denied).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(context.fetcher).toHaveBeenCalledTimes(3);
    expect(context.store.getState().scenario.activeScenarioId).toBe('SCN-03');
    expect(context.store.getState().plan).toEqual(stateBeforeDenial.plan);
    expect(context.workflow.getState()).toBe(workflowBeforeDenial);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(4);

    context.commands.switchRole('INTERFACE_OPS');
    expect(JSON.parse(context.storageFixture.records.get(DEMO_SESSION_STORAGE_KEY) ?? '{}')).toEqual({
      actorId: 'USER-001',
      roleCode: 'INTERFACE_OPS',
      dataScope: ['AREA-A'],
      online: true,
    });
    const recovered = await context.commands.resetScenario('SCN-01');
    expect(recovered).toMatchObject({ ok: true });
    expect(context.requests[3]).toEqual({
      path: '/mock/demo/reset',
      body: { scenarioId: 'SCN-01' },
      call: 4,
    });
    expect(context.store.getState()).toMatchObject({
      session: { roleCode: 'INTERFACE_OPS', scenarioId: 'SCN-01', demoTime: '2026-07-16T09:00:00+08:00' },
      scenario: { activeScenarioId: 'SCN-01' },
    });
    expect(context.workflow.getState()).toEqual({
      retryCount: 0,
      circuitOpen: false,
      resolvedFields: {},
    });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit[0].record.action).toBe('execute');

    const synced = await context.commands.syncPlans('SCN-01');
    expect(synced).toMatchObject({ ok: true });
    expect(context.fetcher).toHaveBeenCalledTimes(5);
    expect(context.workflow.getState()).toMatchObject({
      retryCount: 0,
      circuitOpen: false,
      lastSuccessAt: '2026-07-16T09:00:00+08:00',
    });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(2);
  });

  it('replays a resolved commandId through the C03 executor without a second gateway, commit, workflow, or audit effect', async () => {
    const context = createServiceContext('SCN-01', 'DISPATCHER', 'USER-001', undefined, {
      command: () => 'CMD-C04-REPLAY',
    });
    const replaceSpy = vi.spyOn(context.store, 'replaceDomainState');

    const first = await context.commands.confirmPlan('PLAN-001');
    const callsAfterFirst = replaceSpy.mock.calls.length;
    const rootAfterFirst = context.store.getState();
    const second = await context.commands.confirmPlan('PLAN-001');

    expect(second).toBe(first);
    expect(context.fetcher).toHaveBeenCalledTimes(1);
    expect(replaceSpy).toHaveBeenCalledTimes(callsAfterFirst);
    expect(context.store.getState()).toBe(rootAfterFirst);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('resetCommandState clears replay/failure caches and restarts deterministic counters while accounting for retained audit IDs', async () => {
    const context = createServiceContext('SCN-01', 'DISPATCHER', 'USER-001', undefined, {
      command: () => 'CMD-C04-REPLAY',
    });
    const first = await context.commands.syncPlans('SCN-01');
    const replay = await context.commands.syncPlans('SCN-01');
    expect(replay).toBe(first);
    expect(context.fetcher).toHaveBeenCalledTimes(1);

    context.commands.resetCommandState();
    const executedAgain = await context.commands.syncPlans('SCN-01');

    expect(executedAgain).not.toBe(first);
    expect(context.fetcher).toHaveBeenCalledTimes(2);
    expect(context.store.getState().configAudit.commandAudit.map(({ record }) => record.id)).toEqual([
      'AUD-C04-001',
      'AUD-C04-002',
    ]);
  });

  it('a successful sync changes workflow lastSuccessAt only and never imports gateway domain arrays into Store', async () => {
    const gatewayFixture = createStrictGateway(({ call }) =>
      success(call, {
        status: 'CONFIRMED',
        items: [{ id: 'PLAN-GATEWAY', status: 'CONFIRMED', version: 999 }],
      }),
    );
    const context = createServiceContext('SCN-01', 'DISPATCHER', 'USER-001', gatewayFixture);
    const domainBefore = structuredClone(context.store.getState().plan);

    const result = await context.commands.syncPlans('SCN-01');

    expect(result).toMatchObject({ ok: true });
    expect(context.store.getState().plan).toEqual(domainBefore);
    expect(context.workflow.getState()).toEqual({
      retryCount: 0,
      circuitOpen: false,
      resolvedFields: {},
      lastSuccessAt: '2026-07-16T09:00:00+08:00',
    });
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('invokes the successful reset callback exactly once after the reset Store commit', async () => {
    let context: ReturnType<typeof createServiceContext>;
    const onSuccessfulReset = vi.fn(() => {
      expect(context.store.getState().scenario.activeScenarioId).toBe('SCN-01');
    });
    context = createServiceContext(
      'SCN-01',
      'DISPATCHER',
      'USER-001',
      undefined,
      undefined,
      onSuccessfulReset,
    );

    const result = await context.commands.resetScenario('SCN-01');

    expect(result).toMatchObject({ ok: true });
    expect(onSuccessfulReset).toHaveBeenCalledTimes(1);
  });
});
