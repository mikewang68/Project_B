import { act, cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import {
  renderSafetyInterlockPage,
  waitForSafetyInterlockPage,
} from './safetyInterlockTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-009 safety interlock actions', () => {
  it('requests reset, approves, and records Demo restoration', async () => {
    const user = userEvent.setup();
    const fixture = renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();
    const reason = screen.getByLabelText('处置原因');

    await user.type(reason, '现场清场并申请复位');
    await user.type(screen.getByLabelText('复位申请'), '现场清场；传感器复核');
    await user.click(screen.getByRole('button', { name: '申请复位' }));
    expect((await screen.findAllByText('恢复申请中', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '复位审批通过');
    await user.type(screen.getByLabelText('审批人'), 'USER-004');
    await user.click(screen.getByRole('button', { name: '审批复位' }));
    expect((await screen.findAllByText('已批准', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '仅登记 Demo 恢复记录');
    await user.click(screen.getByRole('button', { name: '登记恢复' }));
    expect((await screen.findAllByText('已恢复', { exact: true }))[0]).toBeVisible();
    expect(fixture.runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'RESTORED', version: 4 });
    expect(screen.getByText('命令 CMD-C09-003', { exact: false })).toBeVisible();
    expect(screen.getByText('演示恢复记录，不代表真实设备已复位', { exact: false }))
      .toBeVisible();
  });

  it('requests and approves an override as an independent safety path', async () => {
    const user = userEvent.setup();
    const fixture = renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();

    await user.type(screen.getByLabelText('处置原因'), '申请受控旁路演示');
    await user.click(screen.getByRole('button', { name: '申请旁路' }));
    expect((await screen.findAllByText('解锁审批中', { exact: true }))[0]).toBeVisible();
    await user.clear(screen.getByLabelText('处置原因'));
    await user.type(screen.getByLabelText('处置原因'), '旁路审批通过');
    await user.type(screen.getByLabelText('审批人'), 'USER-004');
    await user.click(screen.getByRole('button', { name: '审批旁路' }));

    expect((await screen.findAllByText('已授权解锁', { exact: true }))[0]).toBeVisible();
    expect(fixture.runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'OVERRIDDEN', version: 3 });
  });

  it('shows loading, preserves drafts on API-017 failure, and retries the action', async () => {
    const user = userEvent.setup();
    const fixture = renderSafetyInterlockPage({ api017Mode: 'unresolved' });
    await waitForSafetyInterlockPage();
    await user.type(screen.getByLabelText('处置原因'), '申请复位');
    await user.type(screen.getByLabelText('复位申请'), '现场清场');
    await user.click(screen.getByRole('button', { name: '申请复位' }));
    expect(await screen.findByText('正在提交联锁命令')).toBeVisible();
    await act(async () => fixture.resolveApi017Success());
    expect((await screen.findAllByText('恢复申请中', { exact: true }))[0]).toBeVisible();
    cleanup();

    const failed = renderSafetyInterlockPage({ api017Mode: 'failure' });
    await waitForSafetyInterlockPage();
    await user.type(screen.getByLabelText('处置原因'), '申请复位');
    await user.type(screen.getByLabelText('复位申请'), '现场清场');
    await user.click(screen.getByRole('button', { name: '申请复位' }));
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByLabelText('处置原因')).toHaveValue('申请复位');
    expect(screen.getByLabelText('复位申请')).toHaveValue('现场清场');
    failed.setApi017Mode('success');
    await user.click(screen.getByRole('button', { name: '重试原动作' }));
    await waitFor(() => expect(screen.getAllByText('恢复申请中', { exact: true })[0]).toBeVisible());
  });
});
