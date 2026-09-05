import { act, cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderDispatchPage, waitForDispatchPage } from './dispatchBoardTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-005 派工动作', () => {
  it('binds, dispatches, acknowledges, starts, pauses, resumes, and completes the selected order', async () => {
    const user = userEvent.setup();
    const fixture = await renderDispatchPage();
    await waitForDispatchPage();

    await user.click(screen.getByRole('button', { name: '绑定资源' }));
    expect(await screen.findByText('已分配', { exact: true })).toBeVisible();
    expect(fixture.runtime.store.getState().workOrder.workOrders.find(
      ({ id }) => id === 'C06-WO-PLAN-001-G001-02',
    )).toMatchObject({ status: 'READY', resourceId: 'RESOURCE-001' });

    await user.click(screen.getByRole('button', { name: '下发工单' }));
    expect((await screen.findAllByText('已派工', { exact: true }))[0]).toBeVisible();
    await user.click(await screen.findByRole('button', { name: '接单' }));
    await user.click(await screen.findByRole('button', { name: '开始' }));
    expect((await screen.findAllByText('处理中', { exact: true }))[0]).toBeVisible();
    await user.click(await screen.findByRole('button', { name: '暂停' }));
    expect((await screen.findAllByText('已暂停', { exact: true }))[0]).toBeVisible();
    await user.click(await screen.findByRole('button', { name: '继续' }));
    await user.click(await screen.findByRole('button', { name: '完成' }));
    expect((await screen.findAllByText('已完成', { exact: true }))[0]).toBeVisible();
    expect(fixture.runtime.store.getState().configAudit.commandAudit.slice(-7)
      .map(({ record }) => record.action)).toEqual([
      'DB-01', 'DB-02', 'DB-03', 'DB-04', 'DB-04', 'DB-04', 'DB-04',
    ]);
    expect(screen.getByText('命令 CMD-C07-007', { exact: false })).toBeVisible();
  });

  it('shows loading, preserves selection on API-008 failure, and retries the same action', async () => {
    const user = userEvent.setup();
    const fixture = await renderDispatchPage({ api008Mode: 'unresolved' });
    await waitForDispatchPage();
    await user.click(screen.getByRole('button', { name: '绑定资源' }));
    expect(await screen.findByText('正在提交派工命令')).toBeVisible();
    await act(async () => fixture.resolveDispatchSuccess());
    expect(await screen.findByText('已分配', { exact: true })).toBeVisible();
    cleanup();

    const failed = await renderDispatchPage({ api008Mode: 'failure' });
    await waitForDispatchPage();
    await user.click(screen.getByRole('button', { name: '绑定资源' }));
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByText('RESOURCE-001', { exact: true })).toBeVisible();
    failed.setApi008Mode('success');
    await user.click(screen.getByRole('button', { name: '重试原动作' }));
    await waitFor(() => expect(screen.getByText('已分配', { exact: true })).toBeVisible());
  });
});
