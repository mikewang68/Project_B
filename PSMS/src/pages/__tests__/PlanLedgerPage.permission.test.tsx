import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import { createFixtureSnapshot, type DemoScenario } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

function renderForRole(
  roleCode: RoleCode,
  scenarioId: DemoScenario['id'] = 'SCN-01',
  planId = 'PLAN-001',
) {
  const actorId = `E2E-${roleCode}`;
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({ actorId, roleCode, dataScope: ['AREA-A'], online: true }),
  );
  const fetcher = vi.fn(async () =>
    new Response(
      JSON.stringify({
        ok: true,
        data: {},
        traceId: 'TRACE-PERMISSION-READ',
        auditLogId: 'AUD-PERMISSION-READ',
      }),
      { headers: { 'content-type': 'application/json' } },
    ),
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
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [
      `/dispatch/plans?date=2026-07-16&workArea=AREA-A&scenarioId=${scenarioId}&planId=${planId}`,
    ],
  });
  render(<App router={router} runtime={runtime} />);
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-002 路由和动作权限', () => {
  it.each(['DISPATCHER', 'INTERFACE_OPS'] as const)('允许 %s 读取台账', async (roleCode) => {
    renderForRole(roleCode);

    expect(
      await screen.findByRole('heading', { name: '外部到发信息台账' }, LAZY_ROUTE_TIMEOUT),
    ).toBeVisible();
    expect(await screen.findByLabelText('计划台账')).toBeVisible();
    const drawerTitle = await screen.findByText('PLAN-001 计划详情', {
      selector: '.ant-drawer-title',
    });
    expect(drawerTitle.closest('[role="dialog"]')).toBeVisible();
    expect(screen.queryByText('403 无权访问')).not.toBeInTheDocument();
  });

  it('BUSINESS 在页面模块和业务数据渲染前进入既有安全 403', async () => {
    renderForRole('BUSINESS');

    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：plan:view')).toBeVisible();
    expect(screen.queryByRole('heading', { name: '外部到发信息台账' })).not.toBeInTheDocument();
    expect(screen.queryByText('PLAN-001')).not.toBeInTheDocument();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
  });

  it('INTERFACE_OPS 可读详情但不渲染补录工作区', async () => {
    renderForRole('INTERFACE_OPS', 'SCN-02', 'PLAN-002');

    const drawerTitle = await screen.findByText('PLAN-002 计划详情', {
      selector: '.ant-drawer-title',
    });
    const drawer = drawerTitle.closest<HTMLElement>('[role="dialog"]');
    expect(drawer).not.toBeNull();
    expect(within(drawer!).getByText('TOS-EXT-002')).toBeVisible();
    expect(within(drawer!).queryByLabelText('计划字段补录')).not.toBeInTheDocument();
  });

  it('恢复入口仅对精确 INTERFACE_OPS 显示并继续经过 PermissionGate', async () => {
    renderForRole('DISPATCHER', 'SCN-03');
    await screen.findByRole('heading', { name: '外部到发信息台账' }, LAZY_ROUTE_TIMEOUT);
    expect(
      within(screen.getByLabelText('计划接口健康')).queryByRole('button', { name: /恢复接口$/ }),
    ).not.toBeInTheDocument();
    cleanup();
    localStorage.clear();

    renderForRole('INTERFACE_OPS', 'SCN-03');
    await screen.findByRole('heading', { name: '外部到发信息台账' }, LAZY_ROUTE_TIMEOUT);
    expect(
      await within(screen.getByLabelText('计划接口健康')).findByRole('button', {
        name: /恢复接口$/,
      }),
    ).toBeEnabled();
  });

  it('hides recovery for INTERFACE_OPS in SCN-02 while retaining the exact SCN-03 entry', async () => {
    renderForRole('INTERFACE_OPS', 'SCN-02', 'PLAN-002');
    await screen.findByRole('heading', { level: 2 }, LAZY_ROUTE_TIMEOUT);
    const degradedActions = document.querySelector('.plan-interface-actions');
    expect(degradedActions).toBeInstanceOf(HTMLElement);
    expect(within(degradedActions as HTMLElement).getAllByRole('button')).toHaveLength(1);
    cleanup();
    localStorage.clear();

    renderForRole('INTERFACE_OPS', 'SCN-03');
    await screen.findByRole('heading', { level: 2 }, LAZY_ROUTE_TIMEOUT);
    const recoveryActions = document.querySelector('.plan-interface-actions');
    expect(recoveryActions).toBeInstanceOf(HTMLElement);
    expect(within(recoveryActions as HTMLElement).getAllByRole('button')).toHaveLength(2);
  });
});
