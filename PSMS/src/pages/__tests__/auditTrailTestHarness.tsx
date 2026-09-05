import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type AuditApiMode = 'success' | 'network' | 'malformed' | 'business' | 'unresolved';

export type AuditTrailPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  preparation?: 'normal' | 'empty';
  api024Mode?: AuditApiMode;
  search?: string;
}>;

export function createAuditTrailPageFixture(options: AuditTrailPageFixtureOptions = {}) {
  const roleCode = options.roleCode ?? 'AUDITOR';
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: options.actorId ?? `USER-${roleCode}`,
    roleCode,
    dataScope: options.dataScope ?? ['GLOBAL'],
    online: true,
  }));

  let api024Mode = options.api024Mode ?? 'success';
  let resolveApi024: ((response: Response) => void) | undefined;
  let call = 0;
  const fixture = createFixtureSnapshot();
  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
  const auditSuccess = (): ApiSuccessEnvelope => ({
    ok: true,
    data: {
      apiId: 'API-024',
      operationId: 'GET_mock_audit_logs',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: fixture.objects['DO-013'],
    },
    traceId: `TRACE-PAGE-C12-${++call}`,
    auditLogId: `AUD-PAGE-C12-${call}`,
  });
  const businessFailure: ApiErrorEnvelope = {
    ok: false,
    errorCode: 'DEMO-SCENARIO-001',
    message: '当前场景不允许读取审计日志。',
    traceId: 'TRACE-PAGE-C12-ERROR',
    auditLogId: 'AUD-PAGE-C12-ERROR',
  };
  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    if (path === '/mock/demo/reset') {
      return response({
        ok: true,
        data: { scenarioId: 'SCN-01' },
        traceId: 'TRACE-PAGE-C12-RESET',
        auditLogId: 'AUD-PAGE-C12-RESET',
      });
    }
    if (path !== '/mock/audit-logs') throw new Error(`Unexpected C12 request: ${path}`);
    if (api024Mode === 'network') throw new TypeError('audit network down');
    if (api024Mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (api024Mode === 'business') return response(businessFailure, 409);
    if (api024Mode === 'unresolved') {
      return new Promise<Response>((resolve) => { resolveApi024 = resolve; });
    }
    return response(auditSuccess());
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch);
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.configAudit.audit = [];
      candidate.configAudit.commandAudit = [];
    });
  }

  return {
    runtime,
    fetcher,
    setApi024Mode: (mode: AuditApiMode) => { api024Mode = mode; },
    resolveApi024Success: () => {
      if (!resolveApi024) throw new Error('No unresolved API-024 request.');
      resolveApi024(response(auditSuccess()));
    },
  };
}

export function renderAuditTrailPage(options: AuditTrailPageFixtureOptions = {}) {
  const fixture = createAuditTrailPageFixture(options);
  const search = options.search ?? '?scenarioId=SCN-01&from=reports';
  const entry = `/governance/audit${search.startsWith('?') ? search : `?${search}`}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForAuditTrailPage(): Promise<void> {
  await screen.findByRole('heading', { name: '审计日志' }, { timeout: 5_000 });
  await screen.findByLabelText('审计台账', {}, { timeout: 5_000 });
  await screen.findByText('API-024 核验成功', {}, { timeout: 5_000 });
}
