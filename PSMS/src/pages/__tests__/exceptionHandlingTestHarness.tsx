import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type ExceptionApiMode = 'success' | 'failure' | 'business-error' | 'malformed' | 'unresolved';

export type ExceptionPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  dataScope?: string[];
  preparation?: 'normal' | 'empty';
  api014Mode?: ExceptionApiMode;
  api015Mode?: ExceptionApiMode;
  scenarioId?: 'SCN-01' | 'SCN-05';
  search?: string;
  autoplay?: boolean;
}>;

function failure(mode: ExceptionApiMode): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: mode === 'business-error' ? 'DEMO-SCENARIO-001' : 'TOS-EXT-001',
    message: mode === 'business-error' ? '异常状态不允许当前操作' : '异常接口暂不可用',
    traceId: 'TRACE-PAGE-C08-ERROR',
    auditLogId: 'AUD-PAGE-C08-ERROR',
  };
}

export function createExceptionPageFixture(options: ExceptionPageFixtureOptions = {}) {
  const roleCode = options.roleCode ?? 'DISPATCHER';
  const dataScope = options.dataScope ?? ['AREA-A'];
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: `USER-${roleCode}`,
    roleCode,
    dataScope,
    online: true,
  }));

  let api014Mode = options.api014Mode ?? 'success';
  let api015Mode = options.api015Mode ?? 'success';
  let resolveApi014: ((response: Response) => void) | undefined;
  let resolveApi015: ((response: Response) => void) | undefined;
  let call = 0;
  const fixture = createFixtureSnapshot();
  const scenarioId = options.scenarioId ?? 'SCN-01';

  const success = (path: string): ApiSuccessEnvelope => {
    call += 1;
    const match = path.match(/^\/mock\/exceptions\/([^/]+)\/command$/);
    const apiId = match ? 'API-015' : 'API-014';
    const items = match
      ? fixture.objects['DO-009'].filter(({ id }) => id === decodeURIComponent(match[1]!))
      : fixture.objects['DO-009'];
    return {
      ok: true,
      data: {
        apiId,
        operationId: apiId === 'API-014'
          ? 'GET_mock_exceptions'
          : 'POST_mock_exceptions_id_command',
        now: options.scenarioId === 'SCN-05'
          ? '2026-07-16T13:00:00+08:00'
          : '2026-07-16T09:00:00+08:00',
        scenarioId,
        items,
      },
      traceId: `TRACE-PAGE-C08-${call}`,
      auditLogId: `AUD-PAGE-C08-${call}`,
    };
  };

  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    const isCommand = /\/mock\/exceptions\/[^/]+\/command$/.test(path);
    const mode = isCommand ? api015Mode : api014Mode;
    if (mode === 'failure' || mode === 'business-error') return response(failure(mode), 409);
    if (mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (mode === 'unresolved') {
      return new Promise<Response>((resolve) => {
        if (isCommand) resolveApi015 = resolve;
        else resolveApi014 = resolve;
      });
    }
    return response(success(path));
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch, { initialScenarioId: scenarioId });
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.exception.exceptions = [];
    });
  }

  return {
    runtime,
    fetcher,
    setApi014Mode: (mode: ExceptionApiMode) => { api014Mode = mode; },
    setApi015Mode: (mode: ExceptionApiMode) => { api015Mode = mode; },
    resolveApi014Success: () => {
      if (!resolveApi014) throw new Error('No unresolved API-014 request.');
      resolveApi014(response(success('/mock/exceptions')));
    },
    resolveApi015Success: (id = 'EX-001') => {
      if (!resolveApi015) throw new Error('No unresolved API-015 request.');
      resolveApi015(response(success(`/mock/exceptions/${id}/command`)));
    },
  };
}

export function renderExceptionPage(options: ExceptionPageFixtureOptions = {}) {
  const fixture = createExceptionPageFixture(options);
  const search = options.search
    ?? '?workOrderId=C06-WO-PLAN-001-G001-02&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board';
  const normalizedSearch = search.startsWith('?') ? search : `?${search}`;
  const entry = `/monitor/exceptions${normalizedSearch}${options.autoplay ? '&autoplay=1' : ''}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForExceptionPage(): Promise<void> {
  await screen.findByRole('heading', { name: '异常处置' }, { timeout: 5_000 });
  await screen.findByLabelText('异常台账', {}, { timeout: 5_000 });
}
