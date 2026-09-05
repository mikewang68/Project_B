import { cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderDispatchPage, waitForDispatchPage } from './dispatchBoardTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-005 状态与权限', () => {
  it.each(['DISPATCHER', 'SHIFT_LEADER'] as const)(
    '%s sees the frozen dispatch:view workspace',
    async (roleCode) => {
      await renderDispatchPage({ roleCode });
      await waitForDispatchPage();
      expect(screen.getByText('UI-005')).toBeVisible();
      expect(screen.getByRole('button', { name: '绑定资源' })).toBeEnabled();
    },
  );

  it('renders route forbidden before page data or API calls for BUSINESS', async () => {
    const fixture = await renderDispatchPage({ roleCode: 'BUSINESS', preparation: 'none' });

    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：dispatch:view')).toBeVisible();
    expect(screen.queryByLabelText('C06 工单队列')).not.toBeInTheDocument();
    expect(fixture.fetcher).not.toHaveBeenCalled();
  });

  it('uses safe not-found for AREA-B and unknown Plan', async () => {
    await renderDispatchPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
    cleanup();

    await renderDispatchPage({ planId: 'PLAN-UNKNOWN' });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('C06-WO-PLAN-001-G001-02')).not.toBeInTheDocument();
  });

  it('renders empty and business-error states without inventing work orders', async () => {
    await renderDispatchPage({ preparation: 'empty' });
    expect(await screen.findByText('当前作业区暂无可见计划')).toBeVisible();
    expect(screen.queryByText('WO-001', { exact: true })).not.toBeInTheDocument();
    cleanup();

    await renderDispatchPage({ preparation: 'business-error' });
    expect(await screen.findByText('业务处理失败')).toBeVisible();
    expect(screen.getByText('DEMO-SCENARIO-001')).toBeVisible();
    expect(screen.getByRole('link', { name: '返回 UI-004 任务拆解' })).toHaveAttribute(
      'href',
      '/dispatch/plans/PLAN-001/tasks?scenarioId=SCN-01&from=dispatch-board',
    );
  });

  it('disables unavailable resources and all writes under SCN-05 while exposing UI-009', async () => {
    const user = userEvent.setup();
    await renderDispatchPage();
    await waitForDispatchPage();
    expect(screen.getByRole('radio', { name: /RESOURCE-007/ })).toBeDisabled();
    cleanup();

    await renderDispatchPage({ scenarioId: 'SCN-05' });
    await waitForDispatchPage();
    expect(screen.getByText('TOS-IL-001', { exact: false })).toBeVisible();
    expect(screen.getByRole('button', { name: '绑定资源' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '下发工单' })).toBeDisabled();
    expect(screen.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
      'href',
      '/safety/interlocks?scenarioId=SCN-05&planId=PLAN-001&from=dispatch-board',
    );
    expect(user).toBeDefined();
  });
});
