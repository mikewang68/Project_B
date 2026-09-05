import { act, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../../auth';
import type { ApiSuccessEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import * as runtimeModule from '../../../runtime/DemoRuntimeContext';
import {
  createDemoRuntime,
  DemoRuntimeProvider,
  type DemoRuntime,
} from '../../../runtime/DemoRuntimeContext';
import type {
  OfflinePacketCommandService,
  OfflinePacketGateway,
  OfflinePacketWorkflowState,
  OfflinePacketWorkflowStore,
} from '..';

type OfflineSyncRuntimeProbe = {
  gateway: OfflinePacketGateway;
  workflow: OfflinePacketWorkflowStore;
  commands: OfflinePacketCommandService;
};

function offlineSyncRuntime(runtime: DemoRuntime): OfflineSyncRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { offlineSync?: OfflineSyncRuntimeProbe }).offlineSync;
}

afterEach(() => {
  window.localStorage.removeItem(DEMO_SESSION_STORAGE_KEY);
});

describe('C10 DemoRuntime integration', () => {
  it('exposes one offline runtime and API-025 resets domain, workflow, replay cache, and IDs', async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const path = new URL(
        input instanceof Request ? input.url : String(input),
        'http://localhost',
      ).pathname;
      const fixture = createFixtureSnapshot();
      const packet = fixture.objects['DO-011'].find(({ id }) => id === 'OFF-004');
      const data = path === '/mock/demo/reset'
        ? { scenarioId: 'SCN-01' }
        : {
            apiId: 'API-019',
            operationId: 'POST_mock_offline_packets_id_command',
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: 'SCN-01',
            items: packet ? [packet] : [],
          };
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data,
        traceId: 'TRACE-C10-RUNTIME',
        auditLogId: 'AUD-C10-RUNTIME',
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const c10 = offlineSyncRuntime(runtime);
    const useOfflineWorkflow = (runtimeModule as Record<string, unknown>)
      .useOfflinePacketWorkflow;

    expect(c10).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useOfflineWorkflow).toBeTypeOf('function');
    if (!c10 || typeof useOfflineWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useOfflineWorkflow as <T>(
        selector: (state: OfflinePacketWorkflowState) => T,
      ) => T)((state) => state.selectedPacketId ?? 'none');
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    act(() => {
      c10.workflow.selectPacket('OFF-004');
      c10.workflow.setReason('上传标准离线包');
      c10.workflow.setValidationDraft({ valid: true, issues: [] });
    });
    expect(observed.at(-1)).toBe('OFF-004');
    const upstreamBefore = structuredClone({
      workOrder: runtime.store.getState().workOrder,
      exception: runtime.store.getState().exception,
      interlock: runtime.store.getState().interlock,
    });

    await expect(c10.commands.uploadPacket({
      packetId: 'OFF-004', reason: '上传标准离线包',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C10-001' });
    expect(runtime.store.getState().offline.packets.find(({ id }) => id === 'OFF-004'))
      .toMatchObject({ mergeStatus: 'PENDING_UPLOAD', version: 2 });
    expect(runtime.store.getState().workOrder).toEqual(upstreamBefore.workOrder);
    expect(runtime.store.getState().exception).toEqual(upstreamBefore.exception);
    expect(runtime.store.getState().interlock).toEqual(upstreamBefore.interlock);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().offline.packets.find(({ id }) => id === 'OFF-004'))
      .toMatchObject({ mergeStatus: 'CACHED', version: 1 });
    expect(c10.workflow.getState()).toEqual({ reason: '', validationDraft: {} });

    await expect(c10.commands.uploadPacket({
      packetId: 'OFF-004', reason: '重置后上传',
    })).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C10-001',
      traceId: 'TRACE-C10-001',
      auditLogId: 'AUD-C10-001',
    });
    expect(fetcher.mock.calls.filter(([input]) =>
      new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname
        === '/mock/offline-packets/OFF-004/command',
    )).toHaveLength(2);
  });
});
