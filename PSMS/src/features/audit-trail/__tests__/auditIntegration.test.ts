import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoRuntime } from '../../../runtime';
import { projectAuditTrail } from '../auditProjection';

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function createIntegrationFetcher() {
  const fixture = createFixtureSnapshot();
  return vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    if (path === '/mock/plans/PLAN-001/confirm') {
      const success: ApiSuccessEnvelope = {
        ok: true,
        data: { status: 'ACCEPTED' },
        traceId: 'TRACE-API004-INTEGRATION',
        auditLogId: 'AUD-API004-INTEGRATION',
      };
      return response(success);
    }
    if (path === '/mock/demo/reset') {
      return response({
        ok: true,
        data: { scenarioId: 'SCN-01' },
        traceId: 'TRACE-C12-INTEGRATION-RESET',
        auditLogId: 'AUD-C12-INTEGRATION-RESET',
      });
    }
    if (path !== '/mock/audit-logs') throw new Error(`Unexpected C12 integration request: ${path}`);

    const fault = new Headers(init?.headers).get('x-demo-c12-fault');
    if (fault === 'network') throw new TypeError('audit network down');
    if (fault === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (fault === 'business') {
      const business: ApiErrorEnvelope = {
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: '当前场景不允许读取审计日志。',
        traceId: 'TRACE-C12-INTEGRATION-ERROR',
        auditLogId: 'AUD-C12-INTEGRATION-ERROR',
      };
      return response(business, 409);
    }
    return response({
      ok: true,
      data: {
        apiId: 'API-024',
        operationId: 'GET_mock_audit_logs',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: fixture.objects['DO-013'],
      },
      traceId: 'TRACE-C12-INTEGRATION',
      auditLogId: 'AUD-C12-INTEGRATION',
    });
  });
}

beforeEach(() => {
  localStorage.clear();
});

describe('C12 audit trail integration boundaries', () => {
  it('projects real command success and denial, then reset keeps only its real audit with zero idempotent hits', async () => {
    const fetcher = createIntegrationFetcher();
    const runtime = createDemoRuntime(fetcher as typeof fetch);

    await expect(runtime.commands.confirmPlan('PLAN-001')).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C04-001',
      auditLogId: 'AUD-C04-001',
    });
    runtime.commands.switchRole('BUSINESS');
    await expect(runtime.commands.confirmPlan('PLAN-001')).resolves.toMatchObject({
      ok: false,
      errorCode: 'TOS-AUTH-001',
      commandId: 'CMD-C04-002',
      auditLogId: 'AUD-C04-002',
    });

    const projection = projectAuditTrail(runtime.store.getState());
    expect(projection.items).toHaveLength(11);
    expect(projection.items.find(({ record }) => record.id === 'AUD-C04-001'))
      .toMatchObject({ sourceModule: 'C04', resultCategory: 'SUCCESS' });
    expect(projection.items.find(({ record }) => record.id === 'AUD-C04-002'))
      .toMatchObject({ sourceModule: 'C04', resultCategory: 'DENIED' });
    expect(runtime.store.getState().configAudit.commandAudit.map(({ metadata }) => metadata.result))
      .toEqual(['SUCCESS', 'DENIED']);
    expect(fetcher.mock.calls.filter(([input]) => String(input).includes('/confirm'))).toHaveLength(1);

    runtime.commands.switchRole('DISPATCHER');
    runtime.auditTrail.workflow.setFilters({ module: 'C04' });
    runtime.auditTrail.workflow.openDetail('AUD-C04-001');
    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });

    expect(runtime.store.getState().configAudit.audit).toHaveLength(9);
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(runtime.store.getState().configAudit.commandAudit[0]).toMatchObject({
      record: { id: 'AUD-C04-003', action: 'execute', objectId: 'SCN-01' },
      metadata: { result: 'SUCCESS' },
    });
    expect(projectAuditTrail(runtime.store.getState()).kpis.idempotentHit).toBe(0);
    expect(runtime.auditTrail.workflow.getState()).toEqual({
      filters: {},
      detailDrawerOpen: false,
      pending: false,
      readState: { kind: 'idle' },
    });
  });

  it('keeps the complete Store byte-equivalent across API-024 outcomes and every C12 workflow action', async () => {
    const fetcher = createIntegrationFetcher();
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const before = structuredClone(runtime.store.getState());

    await expect(runtime.auditTrail.gateway.listAuditLogs({
      expectedScenarioId: 'SCN-01',
      expectedAuditId: 'AUD-001',
    })).resolves.toMatchObject({
      ok: true,
      data: { apiId: 'API-024', operationId: 'GET_mock_audit_logs' },
    });
    expect(runtime.store.getState()).toEqual(before);

    await expect(runtime.auditTrail.gateway.listAuditLogs({ fault: 'network' })).rejects.toThrow();
    expect(runtime.store.getState()).toEqual(before);
    await expect(runtime.auditTrail.gateway.listAuditLogs({ fault: 'malformed' })).rejects.toThrow();
    expect(runtime.store.getState()).toEqual(before);
    await expect(runtime.auditTrail.gateway.listAuditLogs({ fault: 'business' })).resolves.toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
    });
    expect(runtime.store.getState()).toEqual(before);

    runtime.auditTrail.workflow.setFilters({ actorId: 'USER-001', traceId: 'TRACE-001' });
    runtime.auditTrail.workflow.openDetail('AUD-001');
    runtime.auditTrail.workflow.setExpandedTrace('TRACE-001');
    runtime.auditTrail.workflow.beginRead();
    runtime.auditTrail.workflow.recordReadSuccess({
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      traceId: 'TRACE-C12-INTEGRATION',
      auditLogId: 'AUD-C12-INTEGRATION',
    });
    runtime.auditTrail.workflow.recordFeedback({ kind: 'success', message: '只读操作完成。' });
    runtime.auditTrail.workflow.closeDetail();
    runtime.auditTrail.workflow.setExpandedTrace(undefined);

    expect(runtime.store.getState()).toEqual(before);
    expect(runtime.store.getState().configAudit.commandAudit).toEqual([]);
  });
});
