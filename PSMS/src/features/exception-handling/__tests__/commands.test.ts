import { describe, expect, it, vi } from 'vitest';
import { ZodError } from 'zod';

import type { ApiErrorEnvelope, DispatchException } from '../../../contracts';
import { do009Schema } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  createExceptionHandlingCommandService,
  exceptionHandlingCommandPayloadSchema,
} from '../commands';
import type {
  ExceptionHandlingGateway,
  ExceptionHandlingGatewaySuccess,
} from '../gateway';
import { createExceptionHandlingWorkflowStore } from '../workflow';

function fixtureException(id: string): DispatchException {
  const raw = createFixtureSnapshot().objects['DO-009'].find((item) => item.id === id);
  if (!raw) throw new Error(`Fixture exception missing: ${id}`);
  return do009Schema.parse(raw);
}

function gatewaySuccess(id: string, scenarioId = 'SCN-01'): ExceptionHandlingGatewaySuccess {
  return {
    ok: true,
    data: {
      apiId: 'API-015',
      operationId: 'POST_mock_exceptions_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId,
      items: [fixtureException(id)],
    },
    auditLogId: 'MOCK-AUD-015',
    traceId: 'MOCK-TRACE-015',
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict API-015 failure ${errorCode}`,
    auditLogId: 'MOCK-AUD-FAIL',
    traceId: 'MOCK-TRACE-FAIL',
  };
}

function createContext(options: Readonly<{
  roleCode?: DemoSessionSeed['roleCode'];
  dataScope?: string[];
  scenarioId?: DemoSessionSeed['scenarioId'];
  commandFormatter?: (sequence: number) => string;
  command?: ExceptionHandlingGateway['commandException'];
}> = {}) {
  const session: DemoSessionSeed = {
    actorId: options.roleCode === 'BUSINESS' ? 'USER-BUSINESS' : 'USER-DISPATCHER',
    roleCode: options.roleCode ?? 'DISPATCHER',
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: options.scenarioId ?? 'SCN-01',
  };
  const store = createDemoStore(createFixtureSnapshot(), session);
  const workflow = createExceptionHandlingWorkflowStore();
  const commandException = vi.fn<ExceptionHandlingGateway['commandException']>(
    options.command ?? (async (id) => gatewaySuccess(id, options.scenarioId)),
  );
  const gateway = {
    listExceptions: vi.fn<ExceptionHandlingGateway['listExceptions']>(),
    commandException,
  } satisfies ExceptionHandlingGateway;
  const commands = createExceptionHandlingCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, commandException, session };
}

function exceptionOf(context: ReturnType<typeof createContext>, id: string) {
  return context.store.getState().exception.exceptions.find((item) => item.id === id)!;
}

function c08Audits(context: ReturnType<typeof createContext>) {
  return context.store.getState().configAudit.commandAudit.filter(
    ({ record }) => /^EX-0[1-5]$/.test(record.action),
  );
}

describe('C08 command payload and workflow', () => {
  it('accepts only the strict EX command snapshot and workflow-owned temporary fields', () => {
    const payload = {
      current: 'ACCEPTED',
      businessAction: 'EX-02',
      domainCommand: 'assign',
      exceptionVersion: 1,
      reason: '分派处理',
      owner: 'TEAM-09',
      evidence: [],
    };

    expect(exceptionHandlingCommandPayloadSchema.parse(payload)).toEqual(payload);
    expect(() => exceptionHandlingCommandPayloadSchema.parse({
      ...payload,
      workOrderId: 'WO-001',
    })).toThrow(ZodError);

    const workflow = createExceptionHandlingWorkflowStore();
    workflow.selectException('EX-001');
    workflow.setDrawerOpen(true);
    workflow.setMode('ASSIGN');
    workflow.setReason('分派处理');
    workflow.setOwnerDraft('TEAM-09');
    workflow.setEvidenceDraft(['EVIDENCE-009']);
    expect(workflow.getState()).toEqual({
      selectedExceptionId: 'EX-001',
      drawerOpen: true,
      mode: 'ASSIGN',
      reason: '分派处理',
      ownerDraft: 'TEAM-09',
      evidenceDraft: ['EVIDENCE-009'],
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().evidenceDraft)).toBe(true);
  });
});

describe('ExceptionHandlingCommandService EX-01..EX-05', () => {
  it('runs API before one DO-009 commit for the complete review/close/reopen chain', async () => {
    const context = createContext();
    const upstreamBefore = structuredClone({
      workOrder: context.store.getState().workOrder,
      interlock: context.store.getState().interlock,
    });
    const statusesInsideApi: string[] = [];
    context.commandException.mockImplementation(async (id) => {
      statusesInsideApi.push(exceptionOf(context, id).status);
      return gatewaySuccess(id);
    });

    await expect(context.commands.ackException({
      exceptionId: 'EX-001', reason: '确认设备离线',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-001' });
    await expect(context.commands.assignException({
      exceptionId: 'EX-001', owner: 'TEAM-09', reason: '分派现场处理',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-002' });
    await expect(context.commands.submitExceptionHandling({
      exceptionId: 'EX-001', evidence: ['EVIDENCE-HANDLE-001'], reason: '提交处置证据',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-003' });
    await expect(context.commands.reviewException({
      exceptionId: 'EX-001', reason: '证据需补充',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-004' });
    await context.commands.submitExceptionHandling({
      exceptionId: 'EX-001', evidence: ['EVIDENCE-HANDLE-002'], reason: '补充处置证据',
    });
    await expect(context.commands.closeException({
      exceptionId: 'EX-001', reason: '复核通过并关闭',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-006' });
    await expect(context.commands.reopenException({
      exceptionId: 'EX-001', reason: '现场复发，重新打开',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-007' });

    expect(statusesInsideApi).toEqual([
      'OPEN',
      'ACKNOWLEDGED',
      'HANDLING',
      'PENDING_REVIEW',
      'HANDLING',
      'PENDING_REVIEW',
      'CLOSED',
    ]);
    expect(context.commandException.mock.calls.map(([id, body]) => [id, body.action])).toEqual([
      ['EX-001', 'ACK'],
      ['EX-001', 'ASSIGN'],
      ['EX-001', 'HANDLE'],
      ['EX-001', 'REVIEW'],
      ['EX-001', 'HANDLE'],
      ['EX-001', 'CLOSE'],
      ['EX-001', 'REOPEN'],
    ]);
    expect(exceptionOf(context, 'EX-001')).toMatchObject({
      status: 'REOPENED',
      owner: 'TEAM-09',
      evidence: ['EVIDENCE-001', 'EVIDENCE-HANDLE-001', 'EVIDENCE-HANDLE-002'],
      version: 8,
      updatedAt: context.store.getState().session.demoTime,
    });
    expect(c08Audits(context).map(({ record, metadata }) => [record.action, metadata.result])).toEqual([
      ['EX-01', 'SUCCESS'],
      ['EX-02', 'SUCCESS'],
      ['EX-03', 'SUCCESS'],
      ['EX-04', 'SUCCESS'],
      ['EX-03', 'SUCCESS'],
      ['EX-04', 'SUCCESS'],
      ['EX-05', 'SUCCESS'],
    ]);
    expect(context.store.getState().workOrder).toEqual(upstreamBefore.workOrder);
    expect(context.store.getState().interlock).toEqual(upstreamBefore.interlock);
  });

  it('rejects permission, scope, reason, owner, evidence, and illegal state before API with one audit', async () => {
    const cases = [
      {
        context: createContext({ roleCode: 'BUSINESS' }),
        run: (context: ReturnType<typeof createContext>) => context.commands.ackException({ exceptionId: 'EX-001', reason: '确认' }),
      },
      {
        context: createContext({ dataScope: ['AREA-B'] }),
        run: (context: ReturnType<typeof createContext>) => context.commands.ackException({ exceptionId: 'EX-001', reason: '确认' }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.ackException({ exceptionId: 'EX-001', reason: '   ' }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.assignException({ exceptionId: 'EX-002', owner: ' ', reason: '分派' }),
      },
      {
        context: createContext(),
        arrange: (context: ReturnType<typeof createContext>) => context.store.replaceDomainState((candidate) => {
          candidate.exception.exceptions.find(({ id }) => id === 'EX-002')!.status = 'HANDLING';
        }),
        run: (context: ReturnType<typeof createContext>) => context.commands.submitExceptionHandling({ exceptionId: 'EX-002', evidence: [], reason: '提交' }),
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.ackException({ exceptionId: 'EX-005', reason: '非法确认' }),
      },
    ];

    for (const item of cases) {
      item.arrange?.(item.context);
      const before = structuredClone(item.context.store.getState().exception);
      const result = await item.run(item.context);
      expect(result.ok).toBe(false);
      expect(item.context.commandException).not.toHaveBeenCalled();
      expect(item.context.store.getState().exception).toEqual(before);
      expect(c08Audits(item.context)).toHaveLength(1);
    }
  });

  it('maps gateway failure and INTERLOCK boundary to failed audits without domain writes', async () => {
    const failed = createContext({ command: async () => gatewayFailure('TOS-EXT-001') });
    const failedBefore = structuredClone(failed.store.getState());
    const interlock = createContext();
    const interlockBefore = structuredClone(interlock.store.getState());

    await expect(failed.commands.ackException({
      exceptionId: 'EX-001', reason: '确认',
    })).resolves.toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    await expect(interlock.commands.submitExceptionHandling({
      exceptionId: 'EX-003', reason: '不得在 C08 解除联锁', evidence: ['EVIDENCE-X'],
    })).resolves.toMatchObject({ ok: false, errorCode: 'TOS-IL-001' });

    expect(failed.store.getState().exception).toEqual(failedBefore.exception);
    expect(interlock.store.getState().exception).toEqual(interlockBefore.exception);
    expect(failed.commandException).toHaveBeenCalledOnce();
    expect(interlock.commandException).not.toHaveBeenCalled();
    expect(c08Audits(failed)).toHaveLength(1);
    expect(c08Audits(interlock)).toHaveLength(1);
  });

  it('returns DEMO-VERSION-001 when the exception drifts after API and makes no C08 write', async () => {
    let resolveGateway: ((value: ExceptionHandlingGatewaySuccess) => void) | undefined;
    const context = createContext({
      command: () => new Promise((resolve) => { resolveGateway = resolve; }),
    });
    const pending = context.commands.ackException({ exceptionId: 'EX-001', reason: '确认' });
    await vi.waitFor(() => expect(context.commandException).toHaveBeenCalledOnce());
    context.store.replaceDomainState((candidate) => {
      candidate.exception.exceptions.find(({ id }) => id === 'EX-001')!.version += 1;
    });
    if (!resolveGateway) throw new Error('API-015 resolver missing.');
    resolveGateway(gatewaySuccess('EX-001'));

    await expect(pending).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
    });
    expect(exceptionOf(context, 'EX-001')).toMatchObject({ status: 'OPEN', version: 2 });
    expect(c08Audits(context)).toHaveLength(1);
  });

  it('replays the first frozen commandId without repeating API, commit, workflow, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C08-REPLAY' });
    context.workflow.selectException('EX-001');
    const listener = vi.fn();
    context.workflow.subscribe(listener);

    const first = await context.commands.ackException({ exceptionId: 'EX-001', reason: '确认' });
    const version = exceptionOf(context, 'EX-001').version;
    const notifications = listener.mock.calls.length;
    const second = await context.commands.ackException({ exceptionId: 'EX-001', reason: '确认' });

    expect(second).toBe(first);
    expect(context.commandException).toHaveBeenCalledOnce();
    expect(exceptionOf(context, 'EX-001').version).toBe(version);
    expect(listener).toHaveBeenCalledTimes(notifications);
    expect(c08Audits(context)).toHaveLength(1);
  });
});
