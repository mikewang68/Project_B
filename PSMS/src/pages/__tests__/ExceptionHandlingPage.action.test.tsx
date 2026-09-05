import { act, cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderExceptionPage, waitForExceptionPage } from './exceptionHandlingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-008 exception actions', () => {
  it('acknowledges, assigns, handles, reviews, handles again, closes, and reopens', async () => {
    const user = userEvent.setup();
    const fixture = renderExceptionPage();
    await waitForExceptionPage();
    const reason = screen.getByLabelText('处置原因');

    await user.type(reason, '确认设备离线');
    await user.click(screen.getByRole('button', { name: '确认异常' }));
    expect((await screen.findAllByText('已确认', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '分派现场处理');
    await user.type(screen.getByLabelText('处理负责人'), 'TEAM-09');
    await user.click(screen.getByRole('button', { name: '分派处理' }));
    expect((await screen.findAllByText('处置中', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '提交处置证据');
    await user.type(screen.getByLabelText('证据条目'), 'EVIDENCE-HANDLE-001');
    await user.click(screen.getByRole('button', { name: '提交处理' }));
    expect((await screen.findAllByText('待复核', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '证据需补充');
    await user.click(screen.getByRole('button', { name: '复核退回' }));
    expect((await screen.findAllByText('处置中', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '补充处置证据');
    await user.clear(screen.getByLabelText('证据条目'));
    await user.type(screen.getByLabelText('证据条目'), 'EVIDENCE-HANDLE-002');
    await user.click(screen.getByRole('button', { name: '提交处理' }));
    await user.clear(reason);
    await user.type(reason, '复核通过并关闭');
    await user.click(screen.getByRole('button', { name: '关闭异常' }));
    expect((await screen.findAllByText('已关闭', { exact: true }))[0]).toBeVisible();

    await user.clear(reason);
    await user.type(reason, '现场复发');
    await user.click(screen.getByRole('button', { name: '重新打开' }));
    expect((await screen.findAllByText('已重开', { exact: true }))[0]).toBeVisible();
    expect(fixture.runtime.store.getState().exception.exceptions.find(({ id }) => id === 'EX-001'))
      .toMatchObject({ status: 'REOPENED', owner: 'TEAM-09', version: 8 });
    expect(screen.getByText('命令 CMD-C08-007', { exact: false })).toBeVisible();
  }, 60_000);

  it('shows loading, preserves drafts on API-015 failure, and retries the action', async () => {
    const user = userEvent.setup();
    const fixture = renderExceptionPage({ api015Mode: 'unresolved' });
    await waitForExceptionPage();
    await user.type(screen.getByLabelText('处置原因'), '确认异常');
    await user.click(screen.getByRole('button', { name: '确认异常' }));
    expect(await screen.findByText('正在提交异常命令')).toBeVisible();
    await act(async () => fixture.resolveApi015Success());
    expect((await screen.findAllByText('已确认', { exact: true }))[0]).toBeVisible();
    cleanup();

    const failed = renderExceptionPage({ api015Mode: 'failure' });
    await waitForExceptionPage();
    await user.type(screen.getByLabelText('处置原因'), '确认异常');
    await user.click(screen.getByRole('button', { name: '确认异常' }));
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByLabelText('处置原因')).toHaveValue('确认异常');
    failed.setApi015Mode('success');
    await user.click(screen.getByRole('button', { name: '重试原动作' }));
    await waitFor(() => expect(screen.getAllByText('已确认', { exact: true })[0]).toBeVisible());
  });
});
