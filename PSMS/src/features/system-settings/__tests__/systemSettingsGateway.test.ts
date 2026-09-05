import { describe, expect, it, vi } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createSystemSettingsGateway } from '../index';

const config = createFixtureSnapshot().objects['DO-015'][0];
const now = '2026-07-16T09:00:00+08:00';

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function listSuccess(dataOverrides: Record<string, unknown> = {}): Response {
  return response({
    ok: true,
    data: {
      apiId: 'API-022',
      operationId: 'GET_mock_config',
      now,
      scenarioId: 'SCN-01',
      items: [config],
      ...dataOverrides,
    },
    auditLogId: 'AUD-TRANSPORT-022',
    traceId: 'TRACE-TRANSPORT-022',
  });
}

function editSuccess(dataOverrides: Record<string, unknown> = {}): Response {
  return response({
    ok: true,
    data: {
      apiId: 'API-023',
      operationId: 'POST_mock_config_id_command',
      now,
      scenarioId: 'SCN-01',
      items: [config],
      ...dataOverrides,
    },
    auditLogId: 'AUD-TRANSPORT-023',
    traceId: 'TRACE-TRANSPORT-023',
  });
}

describe('C13 API-022/023 system settings Gateway', () => {
  it('reads one strict target as a deeply frozen transport observation', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(listSuccess());
    const result = await createSystemSettingsGateway(fetcher).listConfig({
      expectedScenarioId: 'SCN-01',
      expectedNow: now,
      expectedConfigId: 'CFG-001',
    });

    expect(fetcher).toHaveBeenCalledWith('/mock/config', { headers: {} });
    expect(result).toEqual({
      ok: true,
      data: {
        apiId: 'API-022',
        operationId: 'GET_mock_config',
        now,
        scenarioId: 'SCN-01',
        items: [config],
      },
      auditLogId: 'AUD-TRANSPORT-022',
      traceId: 'TRACE-TRANSPORT-022',
    });
    expect(Object.isFrozen(result)).toBe(true);
    expect(result.ok).toBe(true);
    if (!result.ok) throw new Error('Expected API-022 success.');
    expect(Object.isFrozen(result.data)).toBe(true);
    expect(Object.isFrozen(result.data.items)).toBe(true);
    expect(Object.isFrozen(result.data.items[0])).toBe(true);
  });

  it('sends only API-023 edit with strict allowlisted changes', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(editSuccess());
    const result = await createSystemSettingsGateway(fetcher).editConfig({
      configId: 'CFG-001',
      expectedScenarioId: 'SCN-01',
      expectedNow: now,
      expectedVersion: 1,
      changes: { displayName: '新的 Demo 名称' },
      reason: '演示配置变更',
    });

    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0]!;
    expect(url).toBe('/mock/config/CFG-001/command');
    expect(init).toMatchObject({
      method: 'POST',
      headers: { 'content-type': 'application/json' },
    });
    expect(JSON.parse(String(init?.body))).toEqual({
      command: 'edit',
      expectedVersion: 1,
      changes: { displayName: '新的 Demo 名称' },
      reason: '演示配置变更',
    });
    expect(result).toMatchObject({
      ok: true,
      data: { apiId: 'API-023', items: [{ id: 'CFG-001' }] },
      auditLogId: 'AUD-TRANSPORT-023',
      traceId: 'TRACE-TRANSPORT-023',
    });
  });

  it('returns a complete public error envelope unchanged', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(response({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许修改配置。',
      details: { scenarioId: 'SCN-01' },
      auditLogId: 'AUD-TRANSPORT-409',
      traceId: 'TRACE-TRANSPORT-409',
    }, 409));

    await expect(createSystemSettingsGateway(fetcher).editConfig({
      configId: 'CFG-001',
      expectedScenarioId: 'SCN-01',
      expectedNow: now,
      expectedVersion: 1,
      changes: { displayName: '新的 Demo 名称' },
      reason: '演示配置变更',
    })).resolves.toEqual({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许修改配置。',
      details: { scenarioId: 'SCN-01' },
      auditLogId: 'AUD-TRANSPORT-409',
      traceId: 'TRACE-TRANSPORT-409',
    });
  });

  it.each([
    ['read api id', 'read', { apiId: 'API-023' }],
    ['read operation id', 'read', { operationId: 'POST_mock_config_id_command' }],
    ['read scenario', 'read', { scenarioId: 'SCN-02' }],
    ['read demo time', 'read', { now: '2026-07-16T09:01:00+08:00' }],
    ['read missing target', 'read', { items: [] }],
    ['read duplicate target', 'read', { items: [config, config] }],
    ['edit api id', 'edit', { apiId: 'API-022' }],
    ['edit operation id', 'edit', { operationId: 'GET_mock_config' }],
    ['edit scenario', 'edit', { scenarioId: 'SCN-02' }],
    ['edit demo time', 'edit', { now: '2026-07-16T09:01:00+08:00' }],
    ['edit target', 'edit', { items: [{ ...config, id: 'CFG-404' }] }],
  ] as const)('rejects mismatched %s', async (_label, method, overrides) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(
      method === 'read' ? listSuccess(overrides) : editSuccess(overrides),
    );
    const gateway = createSystemSettingsGateway(fetcher);

    const operation = method === 'read'
      ? gateway.listConfig({
        expectedScenarioId: 'SCN-01', expectedNow: now, expectedConfigId: 'CFG-001',
      })
      : gateway.editConfig({
        configId: 'CFG-001', expectedScenarioId: 'SCN-01', expectedNow: now,
        expectedVersion: 1, changes: { displayName: '新的 Demo 名称' }, reason: '原因',
      });
    await expect(operation).rejects.toThrow();
  });

  it('rejects malformed envelopes and propagates network failures', async () => {
    const malformed = vi.fn<typeof fetch>().mockResolvedValue(
      response({ ok: true, data: { malformed: true } }),
    );
    const network = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('config network down'));
    const query = { expectedScenarioId: 'SCN-01', expectedNow: now, expectedConfigId: 'CFG-001' };

    await expect(createSystemSettingsGateway(malformed).listConfig(query)).rejects.toThrow();
    await expect(createSystemSettingsGateway(network).listConfig(query))
      .rejects.toThrow('config network down');
  });

  it.each(['network', 'malformed', 'business'] as const)(
    'passes the C13 %s fault only through the internal header',
    async (fault) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(listSuccess());
      await createSystemSettingsGateway(fetcher).listConfig({
        expectedScenarioId: 'SCN-01', expectedNow: now, expectedConfigId: 'CFG-001', fault,
      });
      expect(fetcher).toHaveBeenCalledWith('/mock/config', {
        headers: { 'x-demo-c13-fault': fault },
      });
    },
  );
});
