import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { ApiSuccessEnvelope } from '../../contracts';
import { createDemoRuntime, DemoRuntimeProvider } from '../../runtime';
import { appTheme } from '../../styles/theme';
import OverviewPage from '../dispatch/OverviewPage';

function successEnvelope(call: number): ApiSuccessEnvelope {
  return {
    ok: true,
    data: { status: 'ACCEPTED' },
    traceId: `MOCK-TRACE-${call}`,
    auditLogId: `MOCK-AUD-${call}`,
  };
}

function renderOverviewRoute(
  initialEntry = '/dispatch/overview?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01',
) {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({
      actorId: 'E2E-DISPATCHER',
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
    }),
  );
  let call = 0;
  const resetRequests: string[] = [];
  const fetcher = vi.fn(async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    call += 1;
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    const method = input instanceof Request ? input.method : (init?.method ?? 'GET');
    if (path === '/mock/demo/reset' && method === 'POST') resetRequests.push(path);
    return new Response(JSON.stringify(successEnvelope(call)), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  });
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  const router = createMemoryRouter(
    [
      { path: '/dispatch/overview', element: <OverviewPage /> },
      { path: '/dispatch/plans', element: <output aria-label="计划台账地址">计划台账</output> },
    ],
    { initialEntries: [initialEntry] },
  );
  render(
    <DemoRuntimeProvider runtime={runtime}>
      <ConfigProvider theme={appTheme}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </DemoRuntimeProvider>,
  );
  return { fetcher, resetRequests, router, runtime };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-001 cross-page and command controls', () => {
  it('opens UI-002 with the complete query context from the pending plan', async () => {
    const user = userEvent.setup();
    const { router } = renderOverviewRoute();

    const pendingLink = await screen.findByRole('link', { name: '待确认计划' });
    expect(pendingLink).toHaveAttribute(
      'href',
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&status=PENDING_CONFIRM&planId=PLAN-001&from=overview',
    );
    await user.click(pendingLink);

    await screen.findByLabelText('计划台账地址');
    await waitFor(() => {
      expect(`${router.state.location.pathname}${router.state.location.search}`).toBe(
        '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&status=PENDING_CONFIRM&planId=PLAN-001&from=overview',
      );
    });
  });

  it('routes scenario selection and reset through the stable runtime command service', async () => {
    const user = userEvent.setup();
    const { fetcher, resetRequests, router, runtime } = renderOverviewRoute();
    await screen.findByRole('heading', { name: '调度总览' });

    const scenarioSelect = screen.getByRole('combobox', { name: '演示场景' });
    expect(scenarioSelect).toBeEnabled();
    await user.click(screen.getByTitle(/SCN-01/));
    await user.click(await screen.findByTitle(/SCN-03/));

    await waitFor(() => expect(runtime.store.getState().scenario.activeScenarioId).toBe('SCN-03'));
    expect(router.state.location.search).toContain('scenarioId=SCN-03');
    expect(resetRequests).toHaveLength(1);
    expect(fetcher).toHaveBeenCalledTimes(3);

    const reset = screen.getByRole('button', { name: /重置演示$/ });
    expect(reset).toBeEnabled();
    await user.click(reset);
    await waitFor(() => expect(resetRequests).toHaveLength(2));
    expect(fetcher).toHaveBeenCalledTimes(5);
    expect(runtime.store.getState().scenario.activeScenarioId).toBe('SCN-03');
  });

  it('switches only catalog-authorized UI-001 roles through the service and persists a valid C03 session record', async () => {
    const user = userEvent.setup();
    const { runtime } = renderOverviewRoute();
    await screen.findByRole('heading', { name: '调度总览' });

    const roleSelect = screen.getByRole('combobox', { name: '当前角色' });
    expect(roleSelect).toBeEnabled();
    await user.click(screen.getByTitle('货场调度员'));
    expect(screen.queryByTitle('审计人员')).not.toBeInTheDocument();
    await user.click(await screen.findByTitle('业主管理人员'));

    await waitFor(() => expect(runtime.store.getState().session.roleCode).toBe('BUSINESS'));
    expect(JSON.parse(localStorage.getItem(DEMO_SESSION_STORAGE_KEY) ?? '{}')).toEqual({
      actorId: 'E2E-DISPATCHER',
      roleCode: 'BUSINESS',
      dataScope: ['AREA-A'],
      online: true,
    });
  });
});
