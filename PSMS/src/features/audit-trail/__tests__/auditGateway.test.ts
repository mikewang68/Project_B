import { describe, expect, it, vi } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createAuditGateway } from '../auditGateway';

const records = createFixtureSnapshot().objects['DO-013'];

function success(dataOverrides: Record<string, unknown> = {}) {
  return new Response(JSON.stringify({
    ok: true,
    data: {
      apiId: 'API-024',
      operationId: 'GET_mock_audit_logs',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: records,
      ...dataOverrides,
    },
    auditLogId: 'AUD-ENVELOPE-024',
    traceId: 'TRACE-ENVELOPE-024',
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

describe('C12 API-024 audit Gateway', () => {
  it('requests the parameterless frozen path and returns strict DO-013 plus envelope identity', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success());

    const result = await createAuditGateway(fetcher).listAuditLogs({
      expectedScenarioId: 'SCN-01',
      expectedAuditId: 'AUD-001',
    });

    expect(fetcher).toHaveBeenCalledWith('/mock/audit-logs', { headers: {} });
    expect(result).toEqual({
      ok: true,
      data: {
        apiId: 'API-024',
        operationId: 'GET_mock_audit_logs',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: records,
      },
      auditLogId: 'AUD-ENVELOPE-024',
      traceId: 'TRACE-ENVELOPE-024',
    });
    expect(result.auditLogId).not.toBe(records[0]!.id);
  });

  it('returns a complete public business error envelope unchanged', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取审计日志。',
      details: { scenarioId: 'SCN-01' },
      auditLogId: 'AUD-024-409',
      traceId: 'TRACE-024-409',
    }), { status: 409, headers: { 'content-type': 'application/json' } }));

    await expect(createAuditGateway(fetcher).listAuditLogs()).resolves.toEqual({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取审计日志。',
      details: { scenarioId: 'SCN-01' },
      auditLogId: 'AUD-024-409',
      traceId: 'TRACE-024-409',
    });
  });

  it.each([
    ['apiId', { apiId: 'API-023' }],
    ['operationId', { operationId: 'GET_mock_config' }],
    ['scenarioId', { scenarioId: 'SCN-02' }],
    ['audit identity', { items: records.map((record) => ({ ...record, id: `OTHER-${record.id}` })) }],
    ['strict DO-013 shape', { items: [{ ...records[0], auditLogId: 'INVENTED' }] }],
    ['strict DO-013 required field', { items: [{ id: 'AUD-INCOMPLETE' }] }],
  ])('rejects mismatched %s', async (_label, overrides) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success(overrides));

    await expect(createAuditGateway(fetcher).listAuditLogs({
      expectedScenarioId: 'SCN-01',
      expectedAuditId: 'AUD-001',
    })).rejects.toThrow();
  });

  it('rejects malformed JSON/envelopes and propagates network failures', async () => {
    const malformedJson = vi.fn<typeof fetch>().mockResolvedValue(
      new Response('{broken', { status: 200 }),
    );
    const malformedEnvelope = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ ok: true, data: { malformed: true } }), { status: 200 }),
    );
    const network = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('audit network down'));

    await expect(createAuditGateway(malformedJson).listAuditLogs()).rejects.toThrow();
    await expect(createAuditGateway(malformedEnvelope).listAuditLogs()).rejects.toThrow();
    await expect(createAuditGateway(network).listAuditLogs()).rejects.toThrow('audit network down');
  });

  it.each(['network', 'malformed', 'business'] as const)(
    'passes the C12 %s fault only through the internal header',
    async (fault) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success());
      await createAuditGateway(fetcher).listAuditLogs({ fault });

      expect(fetcher).toHaveBeenCalledWith('/mock/audit-logs', {
        headers: { 'x-demo-c12-fault': fault },
      });
    },
  );
});
