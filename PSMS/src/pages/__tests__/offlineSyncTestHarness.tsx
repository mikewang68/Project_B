import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type OfflineApiMode =
  | 'success'
  | 'failure'
  | 'business-error'
  | 'offline-conflict'
  | 'malformed'
  | 'unresolved';

export type OfflineSyncPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  preparation?: 'normal' | 'empty';
  api018Mode?: OfflineApiMode;
  api019Mode?: OfflineApiMode;
  scenarioId?: 'SCN-01' | 'SCN-06';
  search?: string;
  autoplay?: boolean;
}>;

function failure(mode: OfflineApiMode): ApiErrorEnvelope {
  const offlineConflict = mode === 'offline-conflict';
  const business = mode === 'business-error';
  return {
    ok: false,
    errorCode: offlineConflict
      ? 'TOS-OFF-001'
      : business ? 'DEMO-SCENARIO-001' : 'TOS-EXT-001',
    message: offlineConflict
      ? '离线包版本与服务端版本冲突'
      : business ? '离线包状态不允许当前操作' : '离线同步接口暂不可用',
    traceId: 'TRACE-PAGE-C10-ERROR',
    auditLogId: 'AUD-PAGE-C10-ERROR',
  };
}

export function createOfflineSyncPageFixture(
  options: OfflineSyncPageFixtureOptions = {},
) {
  const roleCode = options.roleCode ?? 'DISPATCHER';
  const dataScope = options.dataScope ?? ['AREA-A'];
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: options.actorId ?? (roleCode === 'DISPATCHER' ? 'USER-001' : `USER-${roleCode}`),
    roleCode,
    dataScope,
    online: true,
  }));

  let api018Mode = options.api018Mode ?? 'success';
  let api019Mode = options.api019Mode ?? 'success';
  let activeScenarioId = options.scenarioId ?? 'SCN-01';
  let resolveApi018: ((response: Response) => void) | undefined;
  let resolveApi019: ((response: Response) => void) | undefined;
  let call = 0;
  const fixture = createFixtureSnapshot();

  const success = (path: string): ApiSuccessEnvelope => {
    call += 1;
    if (path === '/mock/demo/reset') {
      activeScenarioId = 'SCN-01';
      return {
        ok: true,
        data: { scenarioId: 'SCN-01' },
        traceId: `TRACE-PAGE-C10-${call}`,
        auditLogId: `AUD-PAGE-C10-${call}`,
      };
    }
    const match = path.match(/^\/mock\/offline-packets\/([^/]+)\/command$/);
    const apiId = match ? 'API-019' : 'API-018';
    const items = match
      ? fixture.objects['DO-011'].filter(({ id }) => id === decodeURIComponent(match[1]!))
      : fixture.objects['DO-011'];
    return {
      ok: true,
      data: {
        apiId,
        operationId: apiId === 'API-018'
          ? 'GET_mock_offline_packets'
          : 'POST_mock_offline_packets_id_command',
        now: activeScenarioId === 'SCN-06'
          ? '2026-07-16T14:00:00+08:00'
          : '2026-07-16T09:00:00+08:00',
        scenarioId: activeScenarioId,
        items,
      },
      traceId: `TRACE-PAGE-C10-${call}`,
      auditLogId: `AUD-PAGE-C10-${call}`,
    };
  };

  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });

  const fetcher = vi.fn(async (input: string | URL | Request, _init?: RequestInit) => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    if (path === '/mock/demo/reset') return response(success(path));
    const isCommand = /\/mock\/offline-packets\/[^/]+\/command$/.test(path);
    const mode = isCommand ? api019Mode : api018Mode;
    if (mode === 'failure' || mode === 'business-error' || mode === 'offline-conflict') {
      return response(failure(mode), mode === 'failure' ? 400 : 409);
    }
    if (mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (mode === 'unresolved') {
      return new Promise<Response>((resolve) => {
        if (isCommand) resolveApi019 = resolve;
        else resolveApi018 = resolve;
      });
    }
    return response(success(path));
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch, {
    initialScenarioId: activeScenarioId,
  });
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.offline.packets = [];
    });
  }

  return {
    runtime,
    fetcher,
    setApi018Mode: (mode: OfflineApiMode) => { api018Mode = mode; },
    setApi019Mode: (mode: OfflineApiMode) => { api019Mode = mode; },
    resolveApi018Success: () => {
      if (!resolveApi018) throw new Error('No unresolved API-018 request.');
      resolveApi018(response(success('/mock/offline-packets')));
    },
    resolveApi019Success: (id = 'OFF-004') => {
      if (!resolveApi019) throw new Error('No unresolved API-019 request.');
      resolveApi019(response(success(`/mock/offline-packets/${id}/command`)));
    },
  };
}

export function renderOfflineSyncPage(options: OfflineSyncPageFixtureOptions = {}) {
  const fixture = createOfflineSyncPageFixture(options);
  const search = options.search ?? `?scenarioId=${options.scenarioId ?? 'SCN-01'}&from=monitor`;
  const normalizedSearch = search.startsWith('?') ? search : `?${search}`;
  const entry = `/operations/offline-sync${normalizedSearch}${options.autoplay ? '&autoplay=1' : ''}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForOfflineSyncPage(): Promise<void> {
  await screen.findByRole('heading', { name: '离线同步' }, { timeout: 5_000 });
  await screen.findByLabelText('离线包台账', {}, { timeout: 5_000 });
}
