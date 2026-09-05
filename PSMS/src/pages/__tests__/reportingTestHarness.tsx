import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type ReportApiMode = 'success' | 'network' | 'malformed' | 'business' | 'unresolved';

export type ReportingPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  preparation?: 'normal' | 'empty';
  api020Mode?: ReportApiMode;
  search?: string;
}>;

export function createReportingPageFixture(options: ReportingPageFixtureOptions = {}) {
  const roleCode = options.roleCode ?? 'BUSINESS';
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: options.actorId ?? `USER-${roleCode}`,
    roleCode,
    dataScope: options.dataScope ?? ['AREA-A'],
    online: true,
  }));

  let api020Mode = options.api020Mode ?? 'success';
  let resolveApi020: ((response: Response) => void) | undefined;
  let call = 0;
  const fixture = createFixtureSnapshot();
  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
  const reportSuccess = (): ApiSuccessEnvelope => ({
    ok: true,
    data: {
      apiId: 'API-020',
      operationId: 'GET_mock_reports',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: fixture.objects['DO-012'],
    },
    traceId: `TRACE-PAGE-C11-${++call}`,
    auditLogId: `AUD-PAGE-C11-${call}`,
  });
  const businessFailure: ApiErrorEnvelope = {
    ok: false,
    errorCode: 'DEMO-SCENARIO-001',
    message: '当前场景不允许读取统计报表',
    traceId: 'TRACE-PAGE-C11-ERROR',
    auditLogId: 'AUD-PAGE-C11-ERROR',
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
        traceId: 'TRACE-PAGE-C11-RESET',
        auditLogId: 'AUD-PAGE-C11-RESET',
      });
    }
    if (path !== '/mock/reports') throw new Error(`Unexpected C11 request: ${path}`);
    if (api020Mode === 'network') throw new TypeError('report network down');
    if (api020Mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (api020Mode === 'business') return response(businessFailure, 409);
    if (api020Mode === 'unresolved') {
      return new Promise<Response>((resolve) => { resolveApi020 = resolve; });
    }
    return response(reportSuccess());
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch);
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => { candidate.report.reports = []; });
  }

  return {
    runtime,
    fetcher,
    setApi020Mode: (mode: ReportApiMode) => { api020Mode = mode; },
    resolveApi020Success: () => {
      if (!resolveApi020) throw new Error('No unresolved API-020 request.');
      resolveApi020(response(reportSuccess()));
    },
  };
}

export function renderReportingPage(options: ReportingPageFixtureOptions = {}) {
  const fixture = createReportingPageFixture(options);
  const search = options.search ?? '?scenarioId=SCN-01&from=dispatch-overview';
  const entry = `/reports/operations${search.startsWith('?') ? search : `?${search}`}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForReportingPage(): Promise<void> {
  await screen.findByRole('heading', { name: '统计报表' }, { timeout: 5_000 });
  await screen.findByLabelText('报表台账', {}, { timeout: 5_000 });
}
