import { act, cleanup, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import {
  renderOfflineSyncPage,
  waitForOfflineSyncPage,
} from './offlineSyncTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-010 offline sync actions', () => {
  it('completes CACHED to MERGED and shows command, trace, audit, and version feedback', async () => {
    const user = userEvent.setup();
    const fixture = renderOfflineSyncPage();
    await waitForOfflineSyncPage();
    await user.click(screen.getByRole('button', { name: /OFF-PKG-004/ }));
    await user.type(screen.getByLabelText('处理原因'), '标准离线包处理');

    expect(screen.getByRole('button', { name: '上传' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '校验' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '合并' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '驳回' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '重试' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '上传' }));
    expect((await screen.findAllByText('上传中', { exact: true }))[0]).toBeVisible();
    await user.click(screen.getByRole('checkbox', { name: '校验通过' }));
    expect(screen.getByRole('button', { name: '校验' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '校验' }));
    expect((await screen.findAllByText('校验中', { exact: true }))[0]).toBeVisible();
    expect(screen.getByRole('button', { name: '合并' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '合并' }));
    expect((await screen.findAllByText('已合并', { exact: true }))[0]).toBeVisible();

    expect(fixture.runtime.store.getState().offline.packets.find(({ id }) => id === 'OFF-004'))
      .toMatchObject({ mergeStatus: 'MERGED', serverVersion: 5, version: 4 });
    expect(screen.getByLabelText('C10 命令反馈')).toHaveTextContent('CMD-C10-003');
    expect(screen.getByLabelText('C10 命令反馈')).toHaveTextContent('TRACE-C10-003');
    expect(screen.getByLabelText('C10 命令反馈')).toHaveTextContent('AUD-C10-003');
    expect(screen.getByLabelText('C10 命令反馈')).toHaveTextContent('OS-03');

    const commandBodies = fixture.fetcher.mock.calls
      .filter(([input]) => new URL(
        input instanceof Request ? input.url : String(input),
        'http://localhost',
      ).pathname.includes('/mock/offline-packets/OFF-004/command'))
      .map(([, init]) => JSON.parse(String(init?.body)));
    expect(commandBodies).toEqual([
      { action: 'UPLOAD', reason: '标准离线包处理' },
      {
        action: 'VALIDATE',
        reason: '标准离线包处理',
        validation: { valid: true, issues: [] },
      },
      { action: 'MERGE', reason: '标准离线包处理' },
    ]);
  });

  it('shows pending state, preserves drafts on API-019 failure, and retries the original action', async () => {
    const user = userEvent.setup();
    const fixture = renderOfflineSyncPage({ api019Mode: 'unresolved' });
    await waitForOfflineSyncPage();
    await user.click(screen.getByRole('button', { name: /OFF-PKG-004/ }));
    await user.type(screen.getByLabelText('处理原因'), '上传离线包');
    await user.click(screen.getByRole('button', { name: '上传' }));
    expect(await screen.findByText('正在执行上传')).toBeVisible();
    expect(screen.getByRole('button', { name: '上传' })).toBeDisabled();
    await act(async () => fixture.resolveApi019Success());
    expect((await screen.findAllByText('上传中', { exact: true }))[0]).toBeVisible();
    cleanup();

    const failed = renderOfflineSyncPage({ api019Mode: 'failure' });
    await waitForOfflineSyncPage();
    await user.click(screen.getByRole('button', { name: /OFF-PKG-004/ }));
    await user.type(screen.getByLabelText('处理原因'), '上传离线包');
    await user.click(screen.getByRole('button', { name: '上传' }));
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByLabelText('处理原因')).toHaveValue('上传离线包');
    failed.setApi019Mode('success');
    await user.click(screen.getByRole('button', { name: '重试原动作' }));
    await waitFor(() => expect(screen.getAllByText('上传中', { exact: true })[0]).toBeVisible());
  });
});
