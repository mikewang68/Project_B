import { act, cleanup, screen } from '@testing-library/react';
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

describe('UI-010 states and permissions', () => {
  it.each(['DISPATCHER', 'SHIFT_LEADER', 'INTERFACE_OPS'] as const)(
    '%s sees offline:view and state-gated OS actions',
    async (roleCode) => {
      renderOfflineSyncPage({ roleCode });
      await waitForOfflineSyncPage();
      expect(screen.getByText('UI-010')).toBeVisible();
      expect(screen.getByRole('button', { name: '合并' })).toBeDisabled();
    },
  );

  it('renders route forbidden before API for BUSINESS', async () => {
    const forbidden = renderOfflineSyncPage({ roleCode: 'BUSINESS' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：offline:view')).toBeVisible();
    expect(forbidden.fetcher).not.toHaveBeenCalled();
  });

  it('renders loading, empty, network, malformed, business, and safe not-found states', async () => {
    const loading = renderOfflineSyncPage({ api018Mode: 'unresolved' });
    expect(await screen.findByLabelText('页面加载中')).toBeVisible();
    await act(async () => loading.resolveApi018Success());
    await waitForOfflineSyncPage();
    cleanup();

    renderOfflineSyncPage({ preparation: 'empty' });
    expect(await screen.findByText('当前作业区暂无可见计划')).toBeVisible();
    cleanup();

    renderOfflineSyncPage({ api018Mode: 'failure' });
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect(screen.getByRole('button', { name: '重置到 SCN-01' })).toBeVisible();
    cleanup();

    renderOfflineSyncPage({ api018Mode: 'malformed' });
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    cleanup();

    renderOfflineSyncPage({ api018Mode: 'business-error' });
    expect(await screen.findByText('业务处理失败')).toBeVisible();
    cleanup();

    renderOfflineSyncPage({ search: '?packetId=OFF-NOT-FOUND&scenarioId=SCN-01' });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    cleanup();

    const scoped = renderOfflineSyncPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(scoped.fetcher).not.toHaveBeenCalled();
  });

  it('shows SCN-06 TOS-OFF-001 as conflict recognition and recoverable guidance only', async () => {
    const user = userEvent.setup();
    const fixture = renderOfflineSyncPage({
      scenarioId: 'SCN-06',
      api019Mode: 'offline-conflict',
      search: '?packetId=OFF-001&scenarioId=SCN-06&from=monitor',
    });
    await waitForOfflineSyncPage();
    const before = structuredClone(
      fixture.runtime.store.getState().offline.packets.find(({ id }) => id === 'OFF-001'),
    );
    await user.type(screen.getByLabelText('处理原因'), '识别离线版本冲突');
    await user.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('TOS-OFF-001')).toBeVisible();
    expect(screen.getByText(
      'SCN-06 仅用于冲突识别与恢复引导；请重置到 SCN-01 后继续标准离线包处理闭环。',
    )).toBeVisible();
    expect(screen.getByRole('button', { name: '重置到 SCN-01' })).toBeEnabled();
    expect(fixture.runtime.store.getState().offline.packets.find(({ id }) => id === 'OFF-001'))
      .toEqual(before);
  });
});
