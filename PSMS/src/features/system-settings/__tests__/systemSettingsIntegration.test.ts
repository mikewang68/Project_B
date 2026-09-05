import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../../auth';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoRuntime } from '../../../runtime';

afterEach(() => {
  localStorage.clear();
});

describe('C13 shared runtime reset integration', () => {
  it('uses authorized external API-025 reset to restore DO-015 and clear workflow/replay state', async () => {
    localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
      actorId: 'USER-C13',
      roleCode: 'SYS_ADMIN',
      dataScope: ['GLOBAL'],
      online: true,
    }));
    const fixture = createFixtureSnapshot();
    const config = fixture.objects['DO-015'][0];
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const path = new URL(
        input instanceof Request ? input.url : String(input),
        'http://localhost',
      ).pathname;
      const data = path === '/mock/demo/reset'
        ? { scenarioId: 'SCN-01' }
        : {
            apiId: 'API-023',
            operationId: 'POST_mock_config_id_command',
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: 'SCN-01',
            items: [config],
          };
      return new Response(JSON.stringify({
        ok: true,
        data,
        traceId: path === '/mock/demo/reset' ? 'TRACE-C13-RESET' : 'TRACE-C13-SAVE',
        auditLogId: path === '/mock/demo/reset' ? 'AUD-C13-RESET' : 'AUD-C13-TRANSPORT',
      }), { headers: { 'content-type': 'application/json' } });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const original = structuredClone(runtime.store.getState().systemConfig.configVersions);

    await expect(runtime.systemSettings.commands.save({
      commandId: 'CMD-C13-RESET-PROBE',
      configId: 'CFG-001',
      expectedVersion: 1,
      changes: { displayName: '重置前配置' },
      reason: '验证外部 reset 集成',
    })).resolves.toMatchObject({ ok: true, auditLogId: 'AUD-C13-001' });
    await runtime.systemSettings.commands.save({
      commandId: 'CMD-C13-RESET-PROBE',
      configId: 'CFG-001',
      expectedVersion: 1,
      changes: { displayName: '重置前配置' },
      reason: '验证外部 reset 集成',
    });
    runtime.systemSettings.workflow.beginDraft(
      runtime.store.getState().systemConfig.configVersions[0],
    );
    runtime.systemSettings.workflow.updateDraftField('ruleVersion', 'RULE-RESET-PROBE');
    expect(runtime.store.getState().systemConfig.configVersions[0]).toMatchObject({
      displayName: '重置前配置', version: 2,
    });
    expect(fetcher.mock.calls.filter(([request]) =>
      new URL(request instanceof Request ? request.url : String(request), 'http://localhost')
        .pathname === '/mock/config/CFG-001/command',
    )).toHaveLength(1);

    runtime.commands.switchRole('DISPATCHER');
    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });

    expect(runtime.store.getState().systemConfig.configVersions).toEqual(original);
    expect(runtime.systemSettings.workflow.getState()).toEqual({
      selectedGroup: 'overview',
      dirtyFields: [],
      validationErrors: { fieldErrors: {}, formErrors: [] },
      pending: false,
      readState: 'idle',
    });

    runtime.commands.switchRole('SYS_ADMIN');
    await expect(runtime.systemSettings.commands.save({
      commandId: 'CMD-C13-RESET-PROBE',
      configId: 'CFG-001',
      expectedVersion: 1,
      changes: { displayName: '重置后配置' },
      reason: '验证 reset 后缓存清空',
    })).resolves.toMatchObject({ ok: true, auditLogId: 'AUD-C13-001' });
    expect(fetcher.mock.calls.filter(([request]) =>
      new URL(request instanceof Request ? request.url : String(request), 'http://localhost')
        .pathname === '/mock/config/CFG-001/command',
    )).toHaveLength(2);
  });
});
