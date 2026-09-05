import { cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { renderMonitoringPage } from './monitoringTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe('UI-006 公路预约与叫号', () => {
  it('展示预约台账、候车队列、场区位置和中文状态', async () => {
    renderMonitoringPage('appointments');
    expect(screen.getByText('UI-006')).toBeVisible();
    expect(screen.getByRole('heading', { name: '公路预约与叫号' })).toBeVisible();
    expect(screen.getByText('当前路由：/yard/appointments')).toBeVisible();
    expect(screen.getByLabelText('预约台账')).toBeVisible();
    expect(screen.getByLabelText('候车叫号队列')).toBeVisible();
    expect(screen.getByLabelText('车辆位置列表')).toBeVisible();
    expect(await screen.findByText('预约接口已校验')).toBeVisible();
    expect(screen.queryByText(/QUEUED|CALLED|OPERATING/)).not.toBeInTheDocument();
  });

  it('调用状态动作并把排队车辆更新为已叫号', async () => {
    const user = userEvent.setup();
    renderMonitoringPage('appointments');
    const detail = screen.getByLabelText('当前预约详情');
    expect(within(detail).getByText('排队中')).toBeVisible();
    await user.click(within(detail).getByRole('button', { name: '叫号入场' }));
    expect(await within(detail).findByText('已叫号')).toBeVisible();
    expect(screen.getByText(/已更新为“已叫号”/)).toBeVisible();
  });

  it('离线时禁止执行在线放行操作', async () => {
    const user = userEvent.setup();
    renderMonitoringPage('appointments', { online: false });
    const row = within(screen.getByLabelText('预约台账')).getByText('Q04').closest('tr');
    expect(row).not.toBeNull();
    await user.click(within(row as HTMLElement).getByRole('button', { name: /查\s*看/ }));
    expect(screen.getByRole('button', { name: /确\s*认\s*放\s*行/ })).toBeDisabled();
    expect(screen.getByText('当前角色或在线状态不能执行下一步操作。')).toBeVisible();
  });
});
