import { describe, expect, it, vi } from 'vitest';
import { ZodError } from 'zod';

import { do010Schema, type ApiErrorEnvelope, type Interlock } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { FORCE_STOP_WARNING } from '../constants';
import {
  createSafetyInterlockCommandService,
  safetyInterlockCommandPayloadSchema,
} from '../commands';
import type {
  SafetyInterlockGateway,
  SafetyInterlockGatewaySuccess,
} from '../gateway';
import { createSafetyInterlockWorkflowStore } from '../workflow';

function fixtureInterlock(id: string): Interlock {
  const raw = createFixtureSnapshot().objects['DO-010'].find((item) => item.id === id);
  if (!raw) throw new Error(`Fixture interlock missing: ${id}`);
  return do010Schema.parse(raw);
}

function gatewaySuccess(id: string, scenarioId = 'SCN-01'): SafetyInterlockGatewaySuccess {
  return {
    ok: true,
    data: {
      apiId: 'API-017',
      operationId: 'POST_mock_interlocks_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId,
      items: [fixtureInterlock(id)],
    },
    auditLogId: 'MOCK-AUD-017',
    traceId: 'MOCK-TRACE-017',
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict API-017 failure ${errorCode}`,
    auditLogId: 'MOCK-AUD-FAIL',
    traceId: 'MOCK-TRACE-FAIL',
  };
}

function createContext(options: Readonly<{
  roleCode?: DemoSessionSeed['roleCode'];
  actorId?: string;
  dataScope?: string[];
  scenarioId?: DemoSessionSeed['scenarioId'];
  commandFormatter?: (sequence: number) => string;
  command?: SafetyInterlockGateway['commandInterlock'];
}> = {}) {
  const session: DemoSessionSeed = {
    actorId: options.actorId ?? (options.roleCode === 'BUSINESS' ? 'USER-009' : 'USER-008'),
    roleCode: options.roleCode ?? 'SAFETY',
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: options.scenarioId ?? 'SCN-01',
  };
  const store = createDemoStore(createFixtureSnapshot(), session);
  const workflow = createSafetyInterlockWorkflowStore();
  const commandInterlock = vi.fn<SafetyInterlockGateway['commandInterlock']>(
    options.command ?? (async (id) => gatewaySuccess(id, options.scenarioId)),
  );
  const gateway = {
    listInterlocks: vi.fn<SafetyInterlockGateway['listInterlocks']>(),
    commandInterlock,
  } satisfies SafetyInterlockGateway;
  const commands = createSafetyInterlockCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, commandInterlock, session };
}

function interlockOf(context: ReturnType<typeof createContext>, id: string) {
  return context.store.getState().interlock.interlocks.find((item) => item.id === id)!;
}

function c09Audits(context: ReturnType<typeof createContext>) {
  return context.store.getState().configAudit.commandAudit.filter(
    ({ record }) => /^SI-0[1-5]$/.test(record.action),
  );
}

describe('C09 command payload and workflow', () => {
  it('accepts only a strict SI snapshot and workflow-owned temporary fields', () => {
    const payload = {
      current: 'ACCEPTED',
      businessAction: 'SI-02',
      domainCommand: 'requestReset',
      interlockVersion: 1,
      reason: '申请复位',
      resetRequest: { requested: true, checklist: ['现场清场'] },
    };

    expect(safetyInterlockCommandPayloadSchema.parse(payload)).toEqual(payload);
    expect(() => safetyInterlockCommandPayloadSchema.parse({
      ...payload,
      exceptionId: 'EX-003',
    })).toThrow(ZodError);

    const workflow = createSafetyInterlockWorkflowStore();
    workflow.selectInterlock('IL-001');
    workflow.setDrawerOpen(true);
    workflow.setMode('REQUEST_RESET');
    workflow.setReason('申请复位');
    workflow.setApprovalDraft('USER-004');
    workflow.setResetRequestDraft({ requested: true, checklist: ['现场清场'] });
    expect(workflow.getState()).toEqual({
      selectedInterlockId: 'IL-001',
      drawerOpen: true,
      mode: 'REQUEST_RESET',
      reason: '申请复位',
      approvalDraft: 'USER-004',
      resetRequestDraft: { requested: true, checklist: ['现场清场'] },
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().resetRequestDraft)).toBe(true);
    expect(Object.isFrozen(workflow.getState().resetRequestDraft.checklist)).toBe(true);
  });
});

describe('SafetyInterlockCommandService SI-01..SI-05', () => {
  it('runs API before one DO-010 commit for request-reset, approve, and restore', async () => {
    const context = createContext();
    const upstreamBefore = structuredClone({
      exception: context.store.getState().exception,
      workOrder: context.store.getState().workOrder,
    });
    const statusesInsideApi: string[] = [];
    context.commandInterlock.mockImplementation(async (id) => {
      statusesInsideApi.push(interlockOf(context, id).status);
      return gatewaySuccess(id);
    });

    await expect(context.commands.requestReset({
      interlockId: 'IL-001',
      reason: '现场清场并申请复位',
      resetRequest: { requested: true, checklist: ['现场清场', '传感器复核'] },
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C09-001' });
    await expect(context.commands.approveInterlock({
      interlockId: 'IL-001',
      reason: '复位审批通过',
      approvalUserId: 'USER-004',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C09-002' });
    await expect(context.commands.restoreInterlock({
      interlockId: 'IL-001',
      reason: '仅登记 Demo 恢复记录',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C09-003' });

    expect(statusesInsideApi).toEqual(['LOCKED', 'RESET_REQUESTED', 'APPROVED']);
    expect(context.commandInterlock.mock.calls.map(([id, body]) => [id, body])).toEqual([
      ['IL-001', {
        action: 'REQUEST_RESET',
        reason: '现场清场并申请复位',
        resetRequest: { requested: true, checklist: ['现场清场', '传感器复核'] },
      }],
      ['IL-001', {
        action: 'APPROVE',
        reason: '复位审批通过',
        approvalUserId: 'USER-004',
      }],
      ['IL-001', { action: 'RESTORE', reason: '仅登记 Demo 恢复记录' }],
    ]);
    expect(interlockOf(context, 'IL-001')).toMatchObject({
      status: 'RESTORED',
      resetRequest: { requested: true, checklist: ['现场清场', '传感器复核'] },
      approvalChain: ['USER-001', 'USER-004'],
      version: 4,
      updatedAt: context.store.getState().session.demoTime,
    });
    expect(c09Audits(context).map(({ record, metadata }) => [record.action, metadata.result]))
      .toEqual([
        ['SI-02', 'SUCCESS'],
        ['SI-03', 'SUCCESS'],
        ['SI-04', 'SUCCESS'],
      ]);
    expect(context.store.getState().exception).toEqual(upstreamBefore.exception);
    expect(context.store.getState().workOrder).toEqual(upstreamBefore.workOrder);
  });

  it('executes the frozen SI-01 trigger and two-step receipt transitions', async () => {
    const context = createContext();
    context.store.replaceDomainState((candidate) => {
      const interlock = candidate.interlock.interlocks.find(({ id }) => id === 'IL-001')!;
      interlock.status = 'TRIGGERED';
      interlock.receiptStatus = 'PENDING';
    });
    const statusesInsideApi: string[] = [];
    context.commandInterlock.mockImplementation(async (id) => {
      statusesInsideApi.push(interlockOf(context, id).status);
      return gatewaySuccess(id);
    });

    await context.commands.triggerInterlock({ interlockId: 'IL-001', reason: '触发安全联锁' });
    await context.commands.receiptInterlock({ interlockId: 'IL-001', reason: '动作指令已下发' });
    await context.commands.receiptInterlock({ interlockId: 'IL-001', reason: '回执确认并锁定' });

    expect(statusesInsideApi).toEqual(['TRIGGERED', 'ACTION_ISSUED', 'WAITING_RECEIPT']);
    expect(context.commandInterlock.mock.calls.map(([, body]) => body.action))
      .toEqual(['TRIGGER', 'RECEIPT', 'RECEIPT']);
    expect(interlockOf(context, 'IL-001')).toMatchObject({
      status: 'LOCKED',
      receiptStatus: 'RECEIVED',
      version: 4,
    });
    expect(c09Audits(context).map(({ record }) => record.action))
      .toEqual(['SI-01', 'SI-01', 'SI-01']);
  });

  it('executes the SI-05 override request and approval without touching DO-009', async () => {
    const context = createContext();
    const exceptionsBefore = structuredClone(context.store.getState().exception);

    await context.commands.requestOverride({
      interlockId: 'IL-001', reason: '申请受控旁路演示',
    });
    await context.commands.approveInterlock({
      interlockId: 'IL-001', reason: '旁路审批通过', approvalUserId: 'USER-004',
    });

    expect(context.commandInterlock.mock.calls.map(([, body]) => body.action))
      .toEqual(['REQUEST_OVERRIDE', 'APPROVE']);
    expect(interlockOf(context, 'IL-001')).toMatchObject({
      status: 'OVERRIDDEN',
      approvalChain: ['USER-001', 'USER-004'],
      version: 3,
    });
    expect(c09Audits(context).map(({ record }) => record.action)).toEqual(['SI-05', 'SI-05']);
    expect(context.store.getState().exception).toEqual(exceptionsBefore);
  });

  it('rejects permission, scope, reason, approval user, reset request, and illegal state before API', async () => {
    const cases = [
      {
        context: createContext({ roleCode: 'BUSINESS' }),
        run: (context: ReturnType<typeof createContext>) => context.commands.requestReset({
          interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
        }),
      },
      {
        context: createContext({ dataScope: ['AREA-B'] }),
        run: (context: ReturnType<typeof createContext>) => context.commands.requestReset({
          interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
        }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.requestReset({
          interlockId: 'IL-001', reason: '   ', resetRequest: { requested: true },
        }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.approveInterlock({
          interlockId: 'IL-002', reason: '审批', approvalUserId: 'USER-DOES-NOT-EXIST',
        }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.requestReset({
          interlockId: 'IL-001', reason: '申请', resetRequest: { requested: false },
        }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.restoreInterlock({
          interlockId: 'IL-001', reason: '非法恢复',
        }),
      },
    ];

    for (const item of cases) {
      const before = structuredClone(item.context.store.getState().interlock);
      const result = await item.run(item.context);
      expect(result.ok).toBe(false);
      expect(item.context.commandInterlock).not.toHaveBeenCalled();
      expect(item.context.store.getState().interlock).toEqual(before);
      expect(c09Audits(item.context)).toHaveLength(1);
    }
  });

  it('maps gateway and scenario identity failures to failed audits without domain writes', async () => {
    const failed = createContext({ command: async () => gatewayFailure('TOS-EXT-001') });
    const wrongScenario = createContext({ command: async (id) => gatewaySuccess(id, 'SCN-02') });
    const failedBefore = structuredClone(failed.store.getState().interlock);
    const scenarioBefore = structuredClone(wrongScenario.store.getState().interlock);

    await expect(failed.commands.requestReset({
      interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
    })).resolves.toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    await expect(wrongScenario.commands.requestReset({
      interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
    })).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });

    expect(failed.store.getState().interlock).toEqual(failedBefore);
    expect(wrongScenario.store.getState().interlock).toEqual(scenarioBefore);
    expect(c09Audits(failed)).toHaveLength(1);
    expect(c09Audits(wrongScenario)).toHaveLength(1);
  });

  it('returns DEMO-VERSION-001 after API version drift and performs no C09 command write', async () => {
    let resolveGateway: ((value: SafetyInterlockGatewaySuccess) => void) | undefined;
    const context = createContext({
      command: () => new Promise((resolve) => { resolveGateway = resolve; }),
    });
    const pending = context.commands.requestReset({
      interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
    });
    await vi.waitFor(() => expect(context.commandInterlock).toHaveBeenCalledOnce());
    context.store.replaceDomainState((candidate) => {
      candidate.interlock.interlocks.find(({ id }) => id === 'IL-001')!.version += 1;
    });
    if (!resolveGateway) throw new Error('API-017 resolver missing.');
    resolveGateway(gatewaySuccess('IL-001'));

    await expect(pending).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
    });
    expect(interlockOf(context, 'IL-001')).toMatchObject({ status: 'LOCKED', version: 2 });
    expect(c09Audits(context)).toHaveLength(1);
  });

  it('replays a frozen commandId without repeating API, commit, workflow, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C09-REPLAY' });
    context.workflow.selectInterlock('IL-001');
    const listener = vi.fn();
    context.workflow.subscribe(listener);

    const input = {
      interlockId: 'IL-001', reason: '申请', resetRequest: { requested: true },
    };
    const first = await context.commands.requestReset(input);
    const version = interlockOf(context, 'IL-001').version;
    const notifications = listener.mock.calls.length;
    const second = await context.commands.requestReset(input);

    expect(second).toBe(first);
    expect(context.commandInterlock).toHaveBeenCalledOnce();
    expect(interlockOf(context, 'IL-001').version).toBe(version);
    expect(listener).toHaveBeenCalledTimes(notifications);
    expect(c09Audits(context)).toHaveLength(1);
  });

  it('records FORCE_STOP restore only inside strict DO-010 and preserves the safety disclosure', async () => {
    const context = createContext();
    const snapshotBefore = structuredClone(interlockOf(context, 'IL-003').inputSnapshot);
    const exceptionBefore = structuredClone(context.store.getState().exception);

    await expect(context.commands.restoreInterlock({
      interlockId: 'IL-003',
      reason: 'Demo 恢复记录，不代表真实设备已复位',
    })).resolves.toMatchObject({ ok: true });

    expect(FORCE_STOP_WARNING).toBe(
      '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。',
    );
    expect(interlockOf(context, 'IL-003')).toMatchObject({
      status: 'RESTORED',
      actionLevel: 'FORCE_STOP',
      inputSnapshot: snapshotBefore,
      version: 2,
    });
    expect(context.commandInterlock).toHaveBeenCalledOnce();
    expect(context.store.getState().exception).toEqual(exceptionBefore);
    expect(c09Audits(context)).toHaveLength(1);
  });
});
