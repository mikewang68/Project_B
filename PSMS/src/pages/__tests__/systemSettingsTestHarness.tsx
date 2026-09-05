import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type SystemSettingsApiMode =
  | 'success'
  | 'network'
  | 'malformed'
  | 'business'
  | 'unresolved'
  | 'version-conflict';

export type SystemSettingsPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  actorId?: string;
  dataScope?: string[];
  online?: boolean;
  preparation?: 'normal' | 'empty' | 'submitted';
  api022Mode?: SystemSettingsApiMode;
  api023Mode?: SystemSettingsApiMode;
  search?: string;
}>;

export function createSystemSettingsPageFixture(
  options: SystemSettingsPageFixtureOptions = {},
) {
  const roleCode = options.roleCode ?? 'SYS_ADMIN';
  localStorage.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify({
    actorId: options.actorId ?? `USER-${roleCode}`,
    roleCode,
    dataScope: options.dataScope ?? ['GLOBAL'],
    online: options.online ?? true,
  }));

  const fixture = createFixtureSnapshot();
  let api022Mode = options.api022Mode ?? 'success';
  let api023Mode = options.api023Mode ?? 'success';
  let resolveApi022: ((response: Response) => void) | undefined;
  let sequence = 0;
  const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
  const success = (apiId: 'API-022' | 'API-023') => ({
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-022'
        ? 'GET_mock_config'
        : 'POST_mock_config_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: fixture.objects['DO-015'],
    },
    traceId: `TRACE-PAGE-C13-${++sequence}`,
    auditLogId: `AUD-PAGE-C13-${sequence}`,
  });
  const error = (code: 'DEMO-SCENARIO-001' | 'DEMO-VERSION-001') => ({
    ok: false,
    errorCode: code,
    message: code === 'DEMO-VERSION-001'
      ? '配置版本已变化，请保留草稿并刷新。'
      : '当前场景不允许读取或修改系统配置。',
    details: { scenarioId: 'SCN-01' },
    traceId: `TRACE-PAGE-C13-${code}`,
    auditLogId: `AUD-PAGE-C13-${code}`,
  });

  const fetcher = vi.fn(async (
    input: string | URL | Request,
    _init?: RequestInit,
  ): Promise<Response> => {
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    if (path === '/mock/demo/reset') {
      return response({
        ok: true,
        data: { scenarioId: 'SCN-01' },
        traceId: 'TRACE-PAGE-C13-RESET',
        auditLogId: 'AUD-PAGE-C13-RESET',
      });
    }
    const apiId = path === '/mock/config'
      ? 'API-022' as const
      : path === '/mock/config/CFG-001/command'
        ? 'API-023' as const
        : undefined;
    if (!apiId) throw new Error(`Unexpected C13 request: ${path}`);
    const mode = apiId === 'API-022' ? api022Mode : api023Mode;
    if (mode === 'network') throw new TypeError(`${apiId} network down`);
    if (mode === 'malformed') return response({ ok: true, data: { malformed: true } });
    if (mode === 'business') return response(error('DEMO-SCENARIO-001'), 409);
    if (mode === 'version-conflict') return response(error('DEMO-VERSION-001'), 409);
    if (mode === 'unresolved' && apiId === 'API-022') {
      return new Promise<Response>((resolve) => { resolveApi022 = resolve; });
    }
    return response(success(apiId));
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch);
  if (options.preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions = [];
    });
  }
  if (options.preparation === 'submitted') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.systemConfig.configVersions[0].status = 'SUBMITTED';
    });
  }

  return {
    runtime,
    fetcher,
    setApi022Mode: (mode: SystemSettingsApiMode) => { api022Mode = mode; },
    setApi023Mode: (mode: SystemSettingsApiMode) => { api023Mode = mode; },
    resolveApi022Success: () => {
      if (!resolveApi022) throw new Error('No unresolved API-022 request.');
      resolveApi022(response(success('API-022')));
    },
  };
}

export function renderSystemSettingsPage(options: SystemSettingsPageFixtureOptions = {}) {
  const fixture = createSystemSettingsPageFixture(options);
  const search = options.search
    ?? '?group=overview&configId=CFG-001&scenarioId=SCN-01&from=dispatch-overview';
  const entry = `/settings/system${search.startsWith('?') ? search : `?${search}`}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForSystemSettingsPage(): Promise<void> {
  await screen.findByRole('heading', { name: '系统配置' }, { timeout: 5_000 });
  await screen.findByLabelText('系统配置详情', {}, { timeout: 5_000 });
  await screen.findByText('API-022 已校验', {}, { timeout: 5_000 });
}
