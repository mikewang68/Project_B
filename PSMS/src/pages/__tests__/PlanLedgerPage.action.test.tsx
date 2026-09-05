import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot, type DemoScenario } from '../../mocks/fixtures';
import { createDemoRuntime, DemoRuntimeProvider, type DemoRuntime } from '../../runtime';
import { appTheme } from '../../styles/theme';
import PlanLedgerPage from '../dispatch/PlanLedgerPage';

type Envelope = ApiSuccessEnvelope | ApiErrorEnvelope;
type RequestRecord = Readonly<{ path: string; body: unknown; call: number }>;
type Responder = (request: RequestRecord) => Envelope | Promise<Envelope>;

function success(call: number): ApiSuccessEnvelope {
  return {
    ok: true,
    data: { status: 'ACCEPTED' },
    traceId: `MOCK-TRACE-${call}`,
    auditLogId: `MOCK-AUD-${call}`,
  };
}

function failure(call: number, errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Command failed with ${errorCode}`,
    traceId: `MOCK-TRACE-${call}`,
    auditLogId: `MOCK-AUD-${call}`,
  };
}

function createInjectedRuntime(
  scenarioId: DemoScenario['id'],
  roleCode: RoleCode = 'DISPATCHER',
  respond: Responder = ({ call }) => success(call),
) {
  const actorId = `E2E-${roleCode}`;
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({ actorId, roleCode, dataScope: ['AREA-A'], online: true }),
  );
  const requests: RequestRecord[] = [];
  const fetcher = vi.fn(
    async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
      const rawUrl = input instanceof Request ? input.url : String(input);
      const path = new URL(rawUrl, 'http://localhost').pathname;
      const method = input instanceof Request ? input.method : (init?.method ?? 'GET');
      if (path === '/mock/plans' && method === 'GET') {
        return new Response(JSON.stringify(success(0)), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      const rawBody = input instanceof Request ? await input.clone().text() : String(init?.body ?? '');
      const request = {
        path,
        body: rawBody ? (JSON.parse(rawBody) as unknown) : undefined,
        call: requests.length + 1,
      } as const;
      requests.push(request);
      const envelope = await respond(request);
      return new Response(JSON.stringify(envelope), {
        status: envelope.ok ? 200 : 409,
        headers: { 'content-type': 'application/json' },
      });
    },
  );
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  runtime.store.resetFromSnapshot(createFixtureSnapshot(), {
    actorId,
    roleCode,
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId,
  });
  runtime.commands.resetCommandState();
  return { fetcher, requests, runtime };
}

function renderLedgerRoute(
  initialEntry: string,
  fixture = createInjectedRuntime(
    initialEntry.includes('scenarioId=SCN-02')
      ? 'SCN-02'
      : initialEntry.includes('scenarioId=SCN-03')
        ? 'SCN-03'
        : 'SCN-01',
  ),
) {
  const router = createMemoryRouter(
    [{ path: '/dispatch/plans', element: <PlanLedgerPage /> }],
    { initialEntries: [initialEntry] },
  );
  render(
    <DemoRuntimeProvider runtime={fixture.runtime}>
      <ConfigProvider theme={appTheme}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </DemoRuntimeProvider>,
  );
  return { router, ...fixture };
}

async function selectedDrawer(planId: string): Promise<HTMLElement> {
  const title = await screen.findByText(new RegExp(`${planId}.*计划详情`), {
    selector: '.ant-drawer-title',
  });
  const drawer = title.closest('[role="dialog"]');
  if (!(drawer instanceof HTMLElement)) throw new Error(`Missing drawer for ${planId}`);
  return drawer;
}

async function selectFormOption(drawer: HTMLElement, name: string, label: string): Promise<void> {
  const user = userEvent.setup();
  await user.click(within(drawer).getByRole('combobox', { name }));
  await user.click(await screen.findByText(label, { selector: '.ant-select-item-option-content' }));
}

async function fillDrawerForm(drawer: HTMLElement): Promise<void> {
  const user = userEvent.setup();
  await selectFormOption(drawer, '补录股道', 'T1');
  await selectFormOption(drawer, '异人复核员', 'USER-001');
  await user.type(within(drawer).getByLabelText('有效期至'), '2026-07-16T12:30');
  await user.type(within(drawer).getByRole('textbox', { name: '补录原因' }), '补齐缺失股道');
}

function plan(runtime: DemoRuntime, planId: string) {
  const selected = runtime.store.getState().plan.plans.find(({ id }) => id === planId);
  if (!selected) throw new Error(`Missing ${planId}`);
  return selected;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-002 command actions', () => {
  it('switches the accessible current-role control through the frozen UI-002 policy roles', async () => {
    const user = userEvent.setup();
    const { runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-03',
    );

    const role = await screen.findByRole('combobox', { name: '当前角色' });
    await user.click(role);
    await user.click(await screen.findByTitle('接口运维人员'));

    await waitFor(() => expect(runtime.store.getState().session.roleCode).toBe('INTERFACE_OPS'));
    expect(JSON.parse(localStorage.getItem(DEMO_SESSION_STORAGE_KEY) ?? '{}')).toEqual({
      actorId: 'E2E-DISPATCHER',
      roleCode: 'INTERFACE_OPS',
      dataScope: ['AREA-A'],
      online: true,
    });
    expect(
      within(screen.getByLabelText('计划接口健康')).getByRole('button', {
        name: /恢复接口$/,
      }),
    ).toBeEnabled();
  });

  it('opens SCN-02 PLAN-002 from URL and disables confirm while selector validation issues remain', async () => {
    const { runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02&planId=PLAN-002',
    );
    const drawer = await selectedDrawer('PLAN-002');

    expect(within(drawer).getAllByText('股道').length).toBeGreaterThan(0);
    expect(within(drawer).getByText('TOS-EXT-002')).toBeVisible();
    expect(within(drawer).getByRole('button', { name: /确认计划$/ })).toBeDisabled();
    expect(runtime.workflow.getState().selectedPlanId).toBe('PLAN-002');
    expect(plan(runtime, 'PLAN-002')).toMatchObject({ trackNo: 'T2', status: 'BLOCKED', version: 1 });
  });

  it('confirms PLAN-001 to version 2 and exposes only the existing UI-003 recommendation link', async () => {
    const user = userEvent.setup();
    const { runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&planId=PLAN-001',
    );
    const drawer = await selectedDrawer('PLAN-001');
    const confirm = within(drawer).getByRole('button', { name: /确认计划$/ });
    expect(confirm).toBeEnabled();

    await user.click(confirm);
    await waitFor(() => expect(plan(runtime, 'PLAN-001')).toMatchObject({ status: 'CONFIRMED', version: 2 }));

    const recommendation = within(drawer).getByRole('link', { name: '开放推荐入口' });
    expect(recommendation).toHaveAttribute('href', '/dispatch/plans/PLAN-001/recommendation');
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('runs SCN-02 supplement then confirm with complete retained values, versions 1→2→3, two audits, and the UI-003 link', async () => {
    const user = userEvent.setup();
    const { requests, runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02&planId=PLAN-002',
    );
    const drawer = await selectedDrawer('PLAN-002');
    await fillDrawerForm(drawer);

    await user.click(within(drawer).getByRole('button', { name: /提交补录$/ }));
    await waitFor(() => expect(plan(runtime, 'PLAN-002')).toMatchObject({
      trackNo: 'T1',
      status: 'ADJUSTED',
      version: 2,
    }));
    const confirm = within(drawer).getByRole('button', { name: /确认计划$/ });
    expect(confirm).toBeEnabled();

    await user.click(confirm);
    await waitFor(() => expect(plan(runtime, 'PLAN-002')).toMatchObject({
      trackNo: 'T1',
      status: 'CONFIRMED',
      version: 3,
    }));
    expect(requests.map(({ body }) => body)).toEqual([
      {
        reason: '补齐缺失股道',
        supplements: {
          trackNo: 'T1',
          effectiveUntil: '2026-07-16T12:30',
          reviewerId: 'USER-001',
        },
      },
      {
        reason: '补齐缺失股道',
        supplements: {
          trackNo: 'T1',
          effectiveUntil: '2026-07-16T12:30',
          reviewerId: 'USER-001',
        },
      },
    ]);
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(2);
    expect(within(drawer).getByRole('link', { name: '开放推荐入口' })).toHaveAttribute(
      'href',
      '/dispatch/plans/PLAN-002/recommendation',
    );
  });

  it('retains every supplement field and shows the public error when the command fails without fake success', async () => {
    const user = userEvent.setup();
    const fixture = createInjectedRuntime('SCN-02', 'DISPATCHER', ({ call }) =>
      failure(call, 'TOS-EXT-001'),
    );
    const { runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02&planId=PLAN-002',
      fixture,
    );
    const drawer = await selectedDrawer('PLAN-002');
    await fillDrawerForm(drawer);
    await user.click(within(drawer).getByRole('button', { name: /提交补录$/ }));

    expect((await within(drawer).findAllByText('TOS-EXT-001')).length).toBeGreaterThanOrEqual(2);
    expect(within(drawer).getByTitle('T1')).toBeVisible();
    expect(within(drawer).getByTitle('USER-001')).toBeVisible();
    expect(within(drawer).getByLabelText('有效期至')).toHaveValue('2026-07-16T12:30');
    expect(within(drawer).getByRole('textbox', { name: '补录原因' })).toHaveValue('补齐缺失股道');
    expect(plan(runtime, 'PLAN-002')).toMatchObject({ trackNo: 'T2', status: 'BLOCKED', version: 1 });
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('uses independent loading state so an in-flight sync does not disable a valid plan confirm', async () => {
    let release: ((envelope: Envelope) => void) | undefined;
    const fixture = createInjectedRuntime('SCN-01', 'DISPATCHER', ({ call }) =>
      new Promise<Envelope>((resolve) => {
        release = (envelope) => resolve(envelope);
        if (call !== 1) resolve(success(call));
      }),
    );
    const user = userEvent.setup();
    renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01&planId=PLAN-001',
      fixture,
    );
    const drawer = await selectedDrawer('PLAN-001');
    const sync = within(screen.getByLabelText('计划接口健康')).getByRole('button', {
      name: /同步计划$/,
    });
    await user.click(sync);

    expect(sync).toBeDisabled();
    expect(within(drawer).getByRole('button', { name: /确认计划$/ })).toBeEnabled();
    act(() => release?.(success(1)));
    await waitFor(() => expect(sync).toBeEnabled());
  });

  it('does not expose the SCN-03 recovery action to INTERFACE_OPS while SCN-02 is degraded', async () => {
    const fixture = createInjectedRuntime('SCN-02', 'INTERFACE_OPS');
    const { requests } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02',
      fixture,
    );
    await screen.findByRole('heading');
    const actions = document.querySelector('.plan-interface-actions');
    expect(actions).toBeInstanceOf(HTMLElement);
    expect(within(actions as HTMLElement).getAllByRole('button')).toHaveLength(1);
    expect(requests).toHaveLength(0);
  });

  it('opens the circuit after three failures, then exact INTERFACE_OPS recovery resets to SCN-01 and permits sync', async () => {
    const fixture = createInjectedRuntime('SCN-03', 'DISPATCHER', ({ path, call }) =>
      path === '/mock/plans/sync' && fixture.runtime.store.getState().scenario.activeScenarioId === 'SCN-03'
        ? failure(call, 'TOS-EXT-001')
        : success(call),
    );
    const user = userEvent.setup();
    const { router, runtime } = renderLedgerRoute(
      '/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-03',
      fixture,
    );
    const health = await screen.findByLabelText('计划接口健康');
    const sync = within(health).getByRole('button', { name: /同步计划$/ });

    for (const count of [1, 2, 3]) {
      await user.click(sync);
      await waitFor(() => expect(runtime.workflow.getState().retryCount).toBe(count));
    }
    expect(runtime.workflow.getState().circuitOpen).toBe(true);
    expect(sync).toBeDisabled();
    expect(within(health).queryByRole('button', { name: /恢复接口$/ })).not.toBeInTheDocument();

    act(() => runtime.commands.switchRole('INTERFACE_OPS'));
    const recover = await within(health).findByRole('button', { name: /恢复接口$/ });
    expect(recover).toBeEnabled();
    await user.click(recover);

    await waitFor(() => expect(runtime.store.getState().scenario.activeScenarioId).toBe('SCN-01'));
    expect(runtime.workflow.getState()).toEqual({ retryCount: 0, circuitOpen: false, resolvedFields: {} });
    expect(router.state.location.search).toContain('scenarioId=SCN-01');
    expect(sync).toBeEnabled();
    await user.click(sync);
    await waitFor(() => expect(runtime.workflow.getState().lastSuccessAt).toBe('2026-07-16T09:00:00+08:00'));
  });
});
