import { cleanup, render, screen, within } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

function renderForRole(roleCode: RoleCode, initialEntry = '/dispatch/overview') {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({
      actorId: `E2E-${roleCode}`,
      roleCode,
      dataScope: ['AREA-A'],
      online: true,
    }),
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
    actorId: `E2E-${roleCode}`,
    roleCode,
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  });
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] });
  render(<App router={router} runtime={runtime} />);
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-001 权限与数据域', () => {
  it.each([
    ['BUSINESS', '业主管理人员'],
    ['SHIFT_LEADER', '现场班组长'],
    ['DISPATCHER', '货场调度员'],
  ] as const)(
    '允许 %s 查看总览投影',
    async (roleCode, roleLabel) => {
      renderForRole(roleCode);

      expect(
        await screen.findByRole('heading', { name: '调度总览' }, LAZY_ROUTE_TIMEOUT),
      ).toBeVisible();
      expect(screen.getByLabelText('演示上下文')).toHaveTextContent(roleLabel);
      expect(await screen.findByLabelText('调度指标')).toBeVisible();
      expect(await screen.findByLabelText('重点计划')).toBeVisible();
      expect(screen.queryByText('403')).not.toBeInTheDocument();
    },
  );

  it('AREA-B 在计数前被过滤且不泄露 AREA-A 计划对象', async () => {
    renderForRole(
      'DISPATCHER',
      '/dispatch/overview?date=2026-07-16&workArea=AREA-B&scenarioId=SCN-01',
    );
    await screen.findByRole('heading', { name: '调度总览' }, LAZY_ROUTE_TIMEOUT);

    const kpis = await screen.findByLabelText('调度指标');
    for (const label of [
      '当日计划值',
      '待确认计划值',
      '已完成工单值',
      '车辆等待值',
      '设备可用率值',
      '未闭环异常值',
    ]) {
      expect(within(kpis).getByLabelText(label)).toHaveTextContent('0');
    }
    expect(screen.getByText('当前作业区暂无可见计划')).toBeVisible();
    expect(screen.queryByRole('link', { name: 'PLAN-001' })).not.toBeInTheDocument();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
    expect(screen.queryByText('75001')).not.toBeInTheDocument();
  });
});
