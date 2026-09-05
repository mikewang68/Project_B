import { ZodError } from 'zod';
import { describe, expect, it, vi } from 'vitest';

import {
  do011Schema,
  type ApiErrorEnvelope,
  type OfflinePacket,
} from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import {
  createDemoStore,
  type DemoSessionSeed,
} from '../../../stores';
import {
  createOfflinePacketCommandService,
  offlinePacketCommandPayloadSchema,
} from '../offlinePacketCommands';
import type {
  OfflinePacketGateway,
  OfflinePacketGatewaySuccess,
} from '../offlinePacketGateway';
import { createOfflinePacketWorkflowStore } from '../offlinePacketRuntime';

function fixturePacket(id: string): OfflinePacket {
  const raw = createFixtureSnapshot().objects['DO-011'].find((item) => item.id === id);
  if (!raw) throw new Error(`Fixture offline packet missing: ${id}`);
  return do011Schema.parse(raw);
}

function gatewaySuccess(id: string, scenarioId = 'SCN-01'): OfflinePacketGatewaySuccess {
  return {
    ok: true,
    data: {
      apiId: 'API-019',
      operationId: 'POST_mock_offline_packets_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId,
      items: [fixturePacket(id)],
    },
    auditLogId: 'MOCK-AUD-019',
    traceId: 'MOCK-TRACE-019',
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict API-019 failure ${errorCode}`,
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
  command?: OfflinePacketGateway['commandPacket'];
}> = {}) {
  const session: DemoSessionSeed = {
    actorId: options.actorId ?? (options.roleCode === 'BUSINESS' ? 'USER-009' : 'USER-001'),
    roleCode: options.roleCode ?? 'DISPATCHER',
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: options.scenarioId ?? 'SCN-01',
  };
  const store = createDemoStore(createFixtureSnapshot(), session);
  const workflow = createOfflinePacketWorkflowStore();
  const commandPacket = vi.fn<OfflinePacketGateway['commandPacket']>(
    options.command ?? (async (id) => gatewaySuccess(id, options.scenarioId)),
  );
  const gateway = {
    listPackets: vi.fn<OfflinePacketGateway['listPackets']>(),
    commandPacket,
  } satisfies OfflinePacketGateway;
  const commands = createOfflinePacketCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, commandPacket };
}

function packetOf(context: ReturnType<typeof createContext>, id: string): OfflinePacket {
  return context.store.getState().offline.packets.find((item) => item.id === id)!;
}

function c10Audits(context: ReturnType<typeof createContext>) {
  return context.store.getState().configAudit.commandAudit.filter(
    ({ record }) => /^OS-0[1-5]$/.test(record.action),
  );
}

describe('C10 command payload and workflow', () => {
  it('accepts only the strict OS snapshot and deep-freezes workflow-owned temporary state', () => {
    const payload = {
      current: 'ACCEPTED',
      businessAction: 'OS-02',
      domainCommand: 'validate',
      packetVersion: 2,
      reason: '校验离线包',
      validation: { valid: true, issues: [] },
    } as const;

    expect(offlinePacketCommandPayloadSchema.parse(payload)).toEqual(payload);
    expect(() => offlinePacketCommandPayloadSchema.parse({
      ...payload,
      workOrderId: 'WO-009',
    })).toThrow(ZodError);
    expect(() => offlinePacketCommandPayloadSchema.parse({
      ...payload,
      businessAction: 'OS-03',
    })).toThrow(ZodError);

    const workflow = createOfflinePacketWorkflowStore();
    workflow.selectPacket('OFF-004');
    workflow.setReason('校验离线包');
    workflow.setValidationDraft({ valid: true, issues: ['CHECKED'] });
    workflow.setPendingAction('VALIDATE');
    workflow.recordFeedback({
      ok: true,
      traceId: 'TRACE-C10-001',
      auditLogId: 'AUD-C10-001',
      commandId: 'CMD-C10-001',
      message: '离线包命令执行成功',
      idempotent: false,
    });

    expect(workflow.getState()).toEqual({
      selectedPacketId: 'OFF-004',
      reason: '校验离线包',
      validationDraft: { valid: true, issues: ['CHECKED'] },
      lastFeedback: {
        ok: true,
        traceId: 'TRACE-C10-001',
        auditLogId: 'AUD-C10-001',
        commandId: 'CMD-C10-001',
        message: '离线包命令执行成功',
        idempotent: false,
      },
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().validationDraft)).toBe(true);
    expect(Object.isFrozen(workflow.getState().validationDraft.issues)).toBe(true);
    expect(Object.isFrozen(workflow.getState().lastFeedback)).toBe(true);
  });
});

describe('OfflinePacketCommandService OS-01..OS-05', () => {
  it('runs API-019 before one commit per step and completes the standard SCN-01 merge path', async () => {
    const context = createContext();
    const upstreamBefore = structuredClone({
      plan: context.store.getState().plan,
      workOrder: context.store.getState().workOrder,
      resource: context.store.getState().resource,
      exception: context.store.getState().exception,
      interlock: context.store.getState().interlock,
    });
    const statesInsideApi: string[] = [];
    context.commandPacket.mockImplementation(async (id) => {
      statesInsideApi.push(packetOf(context, id).mergeStatus);
      return gatewaySuccess(id);
    });

    await expect(context.commands.uploadPacket({
      packetId: 'OFF-004', reason: '上传标准离线包',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C10-001' });
    await expect(context.commands.validatePacket({
      packetId: 'OFF-004',
      reason: '校验通过',
      validation: { valid: true, issues: [] },
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C10-002' });
    await expect(context.commands.mergePacket({
      packetId: 'OFF-004', reason: '合并离线包',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C10-003' });

    expect(statesInsideApi).toEqual(['CACHED', 'PENDING_UPLOAD', 'VALIDATING']);
    expect(context.commandPacket.mock.calls.map(([id, body]) => [id, body])).toEqual([
      ['OFF-004', { action: 'UPLOAD', reason: '上传标准离线包' }],
      ['OFF-004', {
        action: 'VALIDATE',
        reason: '校验通过',
        validation: { valid: true, issues: [] },
      }],
      ['OFF-004', { action: 'MERGE', reason: '合并离线包' }],
    ]);
    expect(packetOf(context, 'OFF-004')).toMatchObject({
      mergeStatus: 'MERGED',
      packageVersion: 5,
      serverVersion: 5,
      validation: { valid: true, issues: [] },
      version: 4,
      updatedAt: context.store.getState().session.demoTime,
    });
    expect(c10Audits(context).map(({ record, metadata }) => [record.action, metadata.result]))
      .toEqual([
        ['OS-01', 'SUCCESS'],
        ['OS-02', 'SUCCESS'],
        ['OS-03', 'SUCCESS'],
      ]);
    expect(context.store.getState().plan).toEqual(upstreamBefore.plan);
    expect(context.store.getState().workOrder).toEqual(upstreamBefore.workOrder);
    expect(context.store.getState().resource).toEqual(upstreamBefore.resource);
    expect(context.store.getState().exception).toEqual(upstreamBefore.exception);
    expect(context.store.getState().interlock).toEqual(upstreamBefore.interlock);
  });

  it('supports retry/upload and both frozen reject transitions without creating new data', async () => {
    const conflict = createContext();
    const rejected = createContext();
    const retry = createContext();
    const validating = createContext();

    await conflict.commands.retryPacket({ packetId: 'OFF-001', reason: '冲突后重试' });
    await conflict.commands.uploadPacket({ packetId: 'OFF-001', reason: '重新上传' });
    await rejected.commands.retryPacket({ packetId: 'OFF-002', reason: '驳回后重试' });
    await retry.commands.uploadPacket({ packetId: 'OFF-003', reason: '上传重试包' });
    await validating.commands.uploadPacket({ packetId: 'OFF-004', reason: '上传' });
    await validating.commands.validatePacket({
      packetId: 'OFF-004',
      reason: '校验不通过',
      validation: { valid: false, issues: ['FIELD_MISMATCH'] },
    });
    await validating.commands.rejectPacket({ packetId: 'OFF-004', reason: '驳回离线包' });
    await conflict.commands.rejectPacket({ packetId: 'OFF-001', reason: '非法重复驳回' });

    expect(packetOf(conflict, 'OFF-001')).toMatchObject({
      mergeStatus: 'PENDING_UPLOAD', version: 3,
    });
    expect(packetOf(rejected, 'OFF-002')).toMatchObject({ mergeStatus: 'RETRY', version: 2 });
    expect(packetOf(retry, 'OFF-003')).toMatchObject({ mergeStatus: 'PENDING_UPLOAD', version: 2 });
    expect(packetOf(validating, 'OFF-004')).toMatchObject({
      mergeStatus: 'REJECTED',
      validation: { valid: false, issues: ['FIELD_MISMATCH'] },
      version: 4,
    });
    expect(conflict.store.getState().offline.packets).toHaveLength(4);
    expect(validating.store.getState().offline.packets).toHaveLength(4);
  });

  it('rejects permission, data scope, blank reason, malformed validation, and illegal transitions before API', async () => {
    const cases = [
      {
        context: createContext({ roleCode: 'BUSINESS' }),
        run: (context: ReturnType<typeof createContext>) => context.commands.retryPacket({
          packetId: 'OFF-001', reason: '重试',
        }),
        errorCode: 'TOS-AUTH-001',
      },
      {
        context: createContext({ dataScope: ['AREA-B'] }),
        run: (context: ReturnType<typeof createContext>) => context.commands.uploadPacket({
          packetId: 'OFF-004', reason: '上传',
        }),
        errorCode: 'TOS-AUTH-001',
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.uploadPacket({
          packetId: 'OFF-004', reason: '   ',
        }),
        errorCode: 'DEMO-SCENARIO-001',
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.validatePacket({
          packetId: 'OFF-004', reason: '校验', validation: { valid: true },
        }),
        errorCode: 'DEMO-SCENARIO-001',
      },
      {
        context: createContext(),
        run: (context: ReturnType<typeof createContext>) => context.commands.mergePacket({
          packetId: 'OFF-004', reason: '非法合并',
        }),
        errorCode: 'DEMO-SCENARIO-001',
      },
    ] as const;

    for (const item of cases) {
      const before = structuredClone(item.context.store.getState().offline);
      const result = await item.run(item.context);
      expect(result).toMatchObject({ ok: false, errorCode: item.errorCode });
      expect(item.context.commandPacket).not.toHaveBeenCalled();
      expect(item.context.store.getState().offline).toEqual(before);
      expect(c10Audits(item.context)).toHaveLength(1);
    }
  });

  it('requires successful validation before merge', async () => {
    const context = createContext();
    await context.commands.uploadPacket({ packetId: 'OFF-004', reason: '上传' });
    await context.commands.validatePacket({
      packetId: 'OFF-004',
      reason: '校验不通过',
      validation: { valid: false, issues: ['VERSION_CONFLICT'] },
    });
    const beforeMerge = structuredClone(packetOf(context, 'OFF-004'));

    await expect(context.commands.mergePacket({
      packetId: 'OFF-004', reason: '尝试合并',
    })).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });

    expect(context.commandPacket).toHaveBeenCalledTimes(2);
    expect(packetOf(context, 'OFF-004')).toEqual(beforeMerge);
    expect(c10Audits(context)).toHaveLength(3);
  });

  it('maps API, scenario identity, and transport failures without domain writes', async () => {
    const failed = createContext({ command: async () => gatewayFailure('TOS-OFF-001') });
    const wrongScenario = createContext({ command: async (id) => gatewaySuccess(id, 'SCN-02') });
    const network = createContext({ command: async () => { throw new Error('offline'); } });

    for (const [context, expected] of [
      [failed, 'TOS-OFF-001'],
      [wrongScenario, 'DEMO-SCENARIO-001'],
      [network, 'TOS-EXT-001'],
    ] as const) {
      const before = structuredClone(context.store.getState().offline);
      await expect(context.commands.retryPacket({
        packetId: 'OFF-001', reason: '恢复重试',
      })).resolves.toMatchObject({ ok: false, errorCode: expected });
      expect(context.store.getState().offline).toEqual(before);
      expect(c10Audits(context)).toHaveLength(1);
    }
  });

  it('returns DEMO-VERSION-001 after API version drift and performs no C10 transition', async () => {
    let resolveGateway: ((value: OfflinePacketGatewaySuccess) => void) | undefined;
    const context = createContext({
      command: () => new Promise((resolve) => { resolveGateway = resolve; }),
    });
    const pending = context.commands.retryPacket({
      packetId: 'OFF-001', reason: '冲突后重试',
    });
    await vi.waitFor(() => expect(context.commandPacket).toHaveBeenCalledOnce());
    context.store.replaceDomainState((candidate) => {
      candidate.offline.packets.find(({ id }) => id === 'OFF-001')!.version += 1;
    });
    if (!resolveGateway) throw new Error('API-019 resolver missing.');
    resolveGateway(gatewaySuccess('OFF-001'));

    await expect(pending).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
    });
    expect(packetOf(context, 'OFF-001')).toMatchObject({ mergeStatus: 'CONFLICT', version: 2 });
    expect(c10Audits(context)).toHaveLength(1);
  });

  it('replays a frozen commandId without repeating API, commit, workflow, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C10-REPLAY' });
    context.workflow.selectPacket('OFF-004');
    const listener = vi.fn();
    context.workflow.subscribe(listener);

    const input = { packetId: 'OFF-004', reason: '上传' };
    const first = await context.commands.uploadPacket(input);
    const version = packetOf(context, 'OFF-004').version;
    const notifications = listener.mock.calls.length;
    const second = await context.commands.uploadPacket(input);

    expect(second).toBe(first);
    expect(context.commandPacket).toHaveBeenCalledOnce();
    expect(packetOf(context, 'OFF-004').version).toBe(version);
    expect(listener).toHaveBeenCalledTimes(notifications);
    expect(c10Audits(context)).toHaveLength(1);
  });
});
