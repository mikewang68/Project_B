import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type InterlockApiMode = 'success' | 'failure' | 'business-error' | 'malformed' | 'unresolved';

export type SafetyInterlockPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  preparation?: 'normal' | 'empty';
  api016Mode?: InterlockApiMode;
  api017Mode?: InterlockApiMode;
  scenarioId?: 'SCN-01' | 'SCN-05';
  search?: string;
  autoplay?: boolean;
}>;

function failure(mode: InterlockApiMode): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: mode === 'business-error' ? 'DEMO-SCENARIO-001' : 'TOS-EXT-001',
    message: mode === 'business-error' ? '联锁状态不允许当前操作' : '联锁接口暂不可用',
    traceId: 'TRACE-PAGE-C09-ERROR',
    auditLogId: 'AUD-PAGE-C09-ERROR',
  };
}

export function createSafetyInterlockPageFixture(
  options: SafetyInterlockPageFixtureOptions = {},
) {
  const roleCode = options.roleCode ?? 'SAFETY';
  const dataScope = options.dataScope ?? ['AREA-A'];
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: options.actorId ?? (roleCode === 'SAFETY' ? 'USER-008' : `USER-${roleCode}`),
    roleCode,
    dataScope,
    online: true,
  }));

  let api016Mode = options.api016Mode ?? 'success';
  let api017Mode = options.api017Mode ?? 'success';
  let resolveApi016: ((response: Response) => void) | undefined;
  let resolveApi017: ((response: Response) => void) | undefined;
  let call = 0;
  const fixture = createFixtureSnapshot();
  const scenarioId = options.scenarioId ?? 'SCN-01';

  const success = (path: string): ApiSuccessEnvelope => {
    call += 1;
    const match = path.match(/^\/mock\/interlocks\/([^/]+)\/command$/);
    const apiId = match ? 'API-017' : 'API-016';
    const items = match
      ? fixture.objects['DO-010'].filter(({ id }) => id === decodeURIComponent(match[1]!))
      : fixture.objects['DO-010'];
    return {
      ok: true,
      data: {
        apiId,
        operationId: apiId === 'API-016'
          ? 'GET_mock_interlocks'
          : 'POST_mock_interlocks_id_command',
        now: scenarioId === 'SCN-05'
          ? '2026-07-16T13:00:00+08:00'
          : '2026-07-16T09:00:00+08:00',
        scenarioId,
        items,
      },
      traceId: `TRACE-PAGE-C09-${call}`,
      auditLogId: `AUD-PAGE-C09-${call}`,
    };
  };

  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    const isCommand = /\/mock\/interlocks\/[^/]+\/command$/.test(path);
    const mode = isCommand ? api017Mode : api016Mode;
    if (mode === 'failure' || mode === 'business-error') return response(failure(mode), 409);
    if (mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (mode === 'unresolved') {
      return new Promise<Response>((resolve) => {
        if (isCommand) resolveApi017 = resolve;
        else resolveApi016 = resolve;
      });
    }
    return response(success(path));
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch, { initialScenarioId: scenarioId });
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.interlock.interlocks = [];
    });
  }

  return {
    runtime,
    fetcher,
    setApi016Mode: (mode: InterlockApiMode) => { api016Mode = mode; },
    setApi017Mode: (mode: InterlockApiMode) => { api017Mode = mode; },
    resolveApi016Success: () => {
      if (!resolveApi016) throw new Error('No unresolved API-016 request.');
      resolveApi016(response(success('/mock/interlocks')));
    },
    resolveApi017Success: (id = 'IL-001') => {
      if (!resolveApi017) throw new Error('No unresolved API-017 request.');
      resolveApi017(response(success(`/mock/interlocks/${id}/command`)));
    },
  };
}

export function renderSafetyInterlockPage(options: SafetyInterlockPageFixtureOptions = {}) {
  const fixture = createSafetyInterlockPageFixture(options);
  const search = options.search
    ?? '?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling';
  const normalizedSearch = search.startsWith('?') ? search : `?${search}`;
  const entry = `/safety/interlocks${normalizedSearch}${options.autoplay ? '&autoplay=1' : ''}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForSafetyInterlockPage(): Promise<void> {
  await screen.findByRole('heading', { name: '安全联锁' }, { timeout: 5_000 });
  await screen.findByLabelText('联锁台账', {}, { timeout: 5_000 });
}
