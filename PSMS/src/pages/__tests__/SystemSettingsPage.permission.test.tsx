import { cleanup, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderSystemSettingsPage, waitForSystemSettingsPage } from './systemSettingsTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-012 system settings permissions', () => {
  it.each(['SYS_ADMIN', 'INTERFACE_OPS', 'SAFETY'] as const)(
    '%s can view and enter edit mode while reset stays truthfully disabled',
    async (roleCode) => {
      renderSystemSettingsPage({ roleCode });
      await waitForSystemSettingsPage();
      expect(screen.getByRole('button', { name: '编辑配置' })).toBeEnabled();
      expect(screen.getByRole('button', { name: '重置到冻结场景' })).toBeDisabled();
      expect(screen.getByText('当前角色缺少场景重置权限，页面不提供场景重置')).toBeVisible();
    },
  );

  it('renders route forbidden before API-022 for a disallowed role', async () => {
    const fixture = renderSystemSettingsPage({ roleCode: 'DISPATCHER' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：settings:view')).toBeVisible();
    expect(fixture.fetcher).not.toHaveBeenCalled();
  });

  it('allows offline reading but disables edit with the existing online reason', async () => {
    renderSystemSettingsPage({ roleCode: 'SYS_ADMIN', online: false });
    await waitForSystemSettingsPage();
    expect(screen.getByText('B项目生产调度演示')).toBeVisible();
    expect(screen.getByRole('button', { name: '编辑配置' })).toBeDisabled();
    expect(screen.getByText('当前角色或在线状态不允许编辑配置。')).toBeVisible();
  });
});
