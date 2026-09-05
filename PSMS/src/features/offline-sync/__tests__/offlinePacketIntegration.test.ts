import { setupServer } from 'msw/node';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { createMockHandlers } from '../../../mocks/handlers';
import { createMockRuntime } from '../../../mocks/scenarios';
import { createDemoRuntime } from '../../../runtime';

const mockRuntime = createMockRuntime();
const server = setupServer(...createMockHandlers(mockRuntime));

const fetcher: typeof fetch = async (input, init) => {
  const target = input instanceof Request ? input.url : String(input);
  return await fetch(new URL(target, window.location.origin), init);
};

function upstreamOf(runtime: ReturnType<typeof createDemoRuntime>) {
  const state = runtime.store.getState();
  return structuredClone({
    workOrder: state.workOrder,
    exception: state.exception,
    interlock: state.interlock,
    resource: state.resource,
    plan: state.plan,
  });
}

function packetOf(runtime: ReturnType<typeof createDemoRuntime>, id: string) {
  return runtime.store.getState().offline.packets.find((packet) => packet.id === id)!;
}

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());

describe('C10 SCN-06 and SCN-01 integration boundary', () => {
  it('keeps SCN-06 as conflict guidance, then resets into the standard SCN-01 merge loop', async () => {
    mockRuntime.reset('SCN-06');
    const runtime = createDemoRuntime(fetcher, { initialScenarioId: 'SCN-06' });
    const upstreamBefore = upstreamOf(runtime);
    const packetsBefore = structuredClone(runtime.store.getState().offline.packets);
    const conflictBefore = structuredClone(packetOf(runtime, 'OFF-001'));
    runtime.offlineSync.workflow.selectPacket('OFF-001');
    runtime.offlineSync.workflow.setReason('识别离线版本冲突');
    runtime.offlineSync.workflow.setValidationDraft({ valid: true, issues: [] });

    await expect(runtime.offlineSync.commands.retryPacket({
      packetId: 'OFF-001',
      reason: '识别离线版本冲突',
    })).resolves.toMatchObject({
      ok: false,
      commandId: 'CMD-C10-001',
      traceId: 'TRACE-C10-001',
      auditLogId: 'AUD-C10-001',
      errorCode: 'TOS-OFF-001',
    });
    expect(packetOf(runtime, 'OFF-001')).toEqual(conflictBefore);
    expect(runtime.store.getState().offline.packets).toEqual(packetsBefore);
    expect(upstreamOf(runtime)).toEqual(upstreamBefore);
    expect(runtime.offlineSync.workflow.getState().lastFeedback).toMatchObject({
      ok: false,
      errorCode: 'TOS-OFF-001',
      idempotent: false,
    });

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().scenario.activeScenarioId).toBe('SCN-01');
    expect(runtime.store.getState().offline.packets).toEqual(packetsBefore);
    expect(runtime.offlineSync.workflow.getState()).toEqual({
      reason: '',
      validationDraft: {},
    });
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(runtime.store.getState().configAudit.commandAudit[0]!.record.action).toBe('execute');
    expect(upstreamOf(runtime)).toEqual(upstreamBefore);

    const results = [];
    results.push(await runtime.offlineSync.commands.retryPacket({
      packetId: 'OFF-001', reason: '冲突包进入重试',
    }));
    expect(packetOf(runtime, 'OFF-001')).toMatchObject({ mergeStatus: 'RETRY', version: 2 });
    results.push(await runtime.offlineSync.commands.uploadPacket({
      packetId: 'OFF-001', reason: '上传重试离线包',
    }));
    expect(packetOf(runtime, 'OFF-001')).toMatchObject({ mergeStatus: 'PENDING_UPLOAD', version: 3 });
    results.push(await runtime.offlineSync.commands.validatePacket({
      packetId: 'OFF-001',
      reason: '校验重试离线包',
      validation: { valid: true, issues: [] },
    }));
    expect(packetOf(runtime, 'OFF-001')).toMatchObject({
      mergeStatus: 'VALIDATING',
      validation: { valid: true, issues: [] },
      version: 4,
    });
    results.push(await runtime.offlineSync.commands.mergePacket({
      packetId: 'OFF-001', reason: '合并有效离线包',
    }));

    expect(results).toEqual([
      expect.objectContaining({
        ok: true, commandId: 'CMD-C10-001', traceId: 'TRACE-C10-001', auditLogId: 'AUD-C10-001',
      }),
      expect.objectContaining({
        ok: true, commandId: 'CMD-C10-002', traceId: 'TRACE-C10-002', auditLogId: 'AUD-C10-002',
      }),
      expect.objectContaining({
        ok: true, commandId: 'CMD-C10-003', traceId: 'TRACE-C10-003', auditLogId: 'AUD-C10-003',
      }),
      expect.objectContaining({
        ok: true, commandId: 'CMD-C10-004', traceId: 'TRACE-C10-004', auditLogId: 'AUD-C10-004',
      }),
    ]);
    expect(packetOf(runtime, 'OFF-001')).toMatchObject({
      mergeStatus: 'MERGED',
      packageVersion: 2,
      serverVersion: 2,
      validation: { valid: true, issues: [] },
      version: 5,
    });
    expect(runtime.store.getState().configAudit.commandAudit.map(({ record }) => record.action))
      .toEqual(['execute', 'OS-05', 'OS-01', 'OS-02', 'OS-03']);
    expect(upstreamOf(runtime)).toEqual(upstreamBefore);
  });
});
