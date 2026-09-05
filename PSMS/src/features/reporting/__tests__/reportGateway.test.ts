import { describe, expect, it, vi } from 'vitest';

import { createReportGateway } from '../reportGateway';

const report = {
  id: 'RP-002',
  reportType: 'DAILY',
  period: '2026-07-17',
  generateStatus: 'FAILED',
  metrics: { completedWorkOrders: 24, exceptions: 2 },
  generatedAt: '2026-07-16T18:02:00+08:00',
} as const;

function success(dataOverrides: Record<string, unknown> = {}) {
  return new Response(JSON.stringify({
    ok: true,
    data: {
      apiId: 'API-020',
      operationId: 'GET_mock_reports',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [report],
      ...dataOverrides,
    },
    auditLogId: 'AUD-020',
    traceId: 'TRACE-020',
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

describe('C11 API-020 report Gateway', () => {
  it('maps feature filters to the frozen API-020 query and returns strict reports', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success());
    const gateway = createReportGateway(fetcher);

    const result = await gateway.listReports({
      reportType: 'DAILY',
      period: '2026-07-17',
      dimensions: ['workArea'],
      expectedScenarioId: 'SCN-01',
      expectedReportId: 'RP-002',
    });

    expect(fetcher).toHaveBeenCalledWith(
      '/mock/reports?type=DAILY&period=2026-07-17&dimensions=workArea',
      { headers: {} },
    );
    expect(result).toEqual({
      ok: true,
      data: {
        apiId: 'API-020',
        operationId: 'GET_mock_reports',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: [report],
      },
      auditLogId: 'AUD-020',
      traceId: 'TRACE-020',
    });
    expect(JSON.stringify(fetcher.mock.calls)).not.toContain('/mock/reports/export');
    expect(JSON.stringify(fetcher.mock.calls)).not.toContain('API-021');
  });

  it('sends only frozen query parameters and encodes repeated dimensions', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success());
    const gateway = createReportGateway(fetcher);

    await gateway.listReports({
      reportType: 'CUSTOM',
      period: '2026-07/班次 A',
      dimensions: ['workArea', 'team'],
      expectedScenarioId: 'SCN-01',
    });

    expect(fetcher.mock.calls[0]?.[0]).toBe(
      '/mock/reports?type=CUSTOM&period=2026-07%2F%E7%8F%AD%E6%AC%A1+A&dimensions=workArea&dimensions=team',
    );
  });

  it('parses complete public error envelopes without turning them into success', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取。',
      details: { scenarioId: 'SCN-01' },
      auditLogId: 'AUD-020-400',
      traceId: 'TRACE-020-400',
    }), { status: 400, headers: { 'content-type': 'application/json' } }));

    await expect(createReportGateway(fetcher).listReports({ expectedScenarioId: 'SCN-01' }))
      .resolves.toEqual({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: '当前场景不允许读取。',
        details: { scenarioId: 'SCN-01' },
        auditLogId: 'AUD-020-400',
        traceId: 'TRACE-020-400',
      });
  });

  it.each([
    ['apiId', { apiId: 'API-021' }],
    ['operationId', { operationId: 'POST_mock_reports_export' }],
    ['scenarioId', { scenarioId: 'SCN-02' }],
    ['report identity', { items: [{ ...report, id: 'RP-OTHER' }] }],
    ['strict DO-012 shape', { items: [{ ...report, version: 1 }] }],
  ])('rejects mismatched %s', async (_label, overrides) => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success(overrides));
    const gateway = createReportGateway(fetcher);

    await expect(gateway.listReports({
      expectedScenarioId: 'SCN-01',
      expectedReportId: 'RP-002',
    })).rejects.toThrow();
  });

  it('rejects malformed JSON/envelopes and propagates network failures', async () => {
    const malformedJson = vi.fn<typeof fetch>().mockResolvedValue(
      new Response('{broken', { status: 200 }),
    );
    const malformedEnvelope = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ ok: true, data: { malformed: true } }), { status: 200 }),
    );
    const network = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('network down'));

    await expect(createReportGateway(malformedJson).listReports()).rejects.toThrow();
    await expect(createReportGateway(malformedEnvelope).listReports()).rejects.toThrow();
    await expect(createReportGateway(network).listReports()).rejects.toThrow('network down');
  });

  it.each(['network', 'malformed'] as const)(
    'passes the C11 %s fault only through the internal header',
    async (fault) => {
      const fetcher = vi.fn<typeof fetch>().mockResolvedValue(success());
      await createReportGateway(fetcher).listReports({ fault });

      expect(fetcher).toHaveBeenCalledWith('/mock/reports', {
        headers: { 'x-demo-c11-fault': fault },
      });
    },
  );
});
