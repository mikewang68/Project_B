import { describe, expect, it, vi } from 'vitest';

import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoRuntime } from '../../../runtime';

function upstreamSnapshot(state: ReturnType<ReturnType<typeof createDemoRuntime>['store']['getState']>) {
  return structuredClone({
    plan: state.plan,
    recommendation: state.recommendation,
    workOrder: state.workOrder,
    resource: state.resource,
    vehicle: state.vehicle,
    exception: state.exception,
    interlock: state.interlock,
    offline: state.offline,
  });
}

function createIntegrationFetcher() {
  const fixture = createFixtureSnapshot();
  return vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    if (path === '/mock/demo/reset') {
      return new Response(JSON.stringify({
        ok: true,
        data: { scenarioId: 'SCN-01' },
        traceId: 'TRACE-C11-INTEGRATION-RESET',
        auditLogId: 'AUD-C11-INTEGRATION-RESET',
      }), { headers: { 'content-type': 'application/json' } });
    }
    if (path !== '/mock/reports') throw new Error(`Unexpected reporting request: ${path}`);
    const headers = new Headers(init?.headers);
    if (headers.get('x-demo-c11-fault') === 'network') throw new TypeError('network down');
    if (headers.get('x-demo-c11-fault') === 'malformed') {
      return new Response(JSON.stringify({ ok: true, data: { malformed: true } }), {
        headers: { 'content-type': 'application/json' },
      });
    }
    const success: ApiSuccessEnvelope = {
      ok: true,
      data: {
        apiId: 'API-020',
        operationId: 'GET_mock_reports',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: fixture.objects['DO-012'],
      },
      traceId: 'TRACE-C11-INTEGRATION',
      auditLogId: 'AUD-C11-INTEGRATION',
    };
    return new Response(JSON.stringify(success), {
      headers: { 'content-type': 'application/json' },
    });
  });
}

describe('C11 reporting integration boundaries', () => {
  it('keeps API-020 read-only, refreshes once, rejects the stale token, and resets deterministically', async () => {
    const fetcher = createIntegrationFetcher();
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const fixtureReports = structuredClone(runtime.store.getState().report.reports);
    const upstreamBefore = upstreamSnapshot(runtime.store.getState());

    await expect(runtime.reporting.gateway.listReports({
      expectedScenarioId: 'SCN-01',
      expectedReportId: 'RP-003',
    })).resolves.toMatchObject({
      ok: true,
      data: { apiId: 'API-020', operationId: 'GET_mock_reports' },
    });
    expect(runtime.store.getState().report.reports).toEqual(fixtureReports);

    const oldToken = runtime.store.getState().report.reports
      .find(({ id }) => id === 'RP-003')!.generatedAt;
    await expect(runtime.reporting.commands.refreshReport({
      commandId: 'CMD-C11-001',
      reportId: 'RP-003',
      expectedGeneratedAt: oldToken,
      reason: '集成刷新',
    })).resolves.toEqual({
      ok: true,
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });
    const refreshed = structuredClone(
      runtime.store.getState().report.reports.find(({ id }) => id === 'RP-003'),
    );
    expect(refreshed).toMatchObject({
      generateStatus: 'SUCCESS',
      generatedAt: runtime.store.getState().session.demoTime,
    });
    expect(upstreamSnapshot(runtime.store.getState())).toEqual(upstreamBefore);
    expect(runtime.reporting.workflow.getState().snapshotSequence).toBe(1);

    await expect(runtime.reporting.commands.refreshReport({
      commandId: 'CMD-C11-002',
      reportId: 'RP-003',
      expectedGeneratedAt: oldToken,
      reason: '旧令牌重试',
    })).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(runtime.store.getState().report.reports.find(({ id }) => id === 'RP-003'))
      .toEqual(refreshed);
    expect(runtime.reporting.workflow.getState().snapshotSequence).toBe(1);
    expect(upstreamSnapshot(runtime.store.getState())).toEqual(upstreamBefore);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().report.reports).toEqual(fixtureReports);
    expect(runtime.reporting.workflow.getState()).toEqual({
      filters: {},
      metricsDrawerOpen: false,
      snapshotSequence: 0,
    });
    const resetReport = runtime.store.getState().report.reports.find(({ id }) => id === 'RP-003')!;
    await expect(runtime.reporting.commands.refreshReport({
      reportId: 'RP-003',
      expectedGeneratedAt: resetReport.generatedAt,
      reason: '重置后刷新',
    })).resolves.toMatchObject({
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });
  });

  it('preserves all Store facts for API-020 network, malformed, and public business errors', async () => {
    const fetcher = createIntegrationFetcher();
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const before = structuredClone(runtime.store.getState());

    await expect(runtime.reporting.gateway.listReports({ fault: 'network' })).rejects.toThrow();
    expect(runtime.store.getState()).toEqual(before);
    await expect(runtime.reporting.gateway.listReports({ fault: 'malformed' })).rejects.toThrow();
    expect(runtime.store.getState()).toEqual(before);

    const business: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: '报表读取业务失败',
      traceId: 'TRACE-C11-BUSINESS',
      auditLogId: 'AUD-C11-BUSINESS',
    };
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify(business), {
      status: 409,
      headers: { 'content-type': 'application/json' },
    }));
    await expect(runtime.reporting.gateway.listReports()).resolves.toEqual(business);
    expect(runtime.store.getState()).toEqual(before);
    expect(fetcher.mock.calls.map(([input]) => String(input)).join('\n'))
      .not.toContain('/mock/reports/export');
  });
});
