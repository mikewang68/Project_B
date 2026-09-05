import { act, cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderSystemSettingsPage, waitForSystemSettingsPage } from './systemSettingsTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-012 system settings render', () => {
  it('shows identity, Demo boundary, five groups, metadata, and an in-page summary', async () => {
    renderSystemSettingsPage();
    await waitForSystemSettingsPage();

    expect(screen.getByText('UI-012')).toBeVisible();
    expect(screen.getByText('当前路由：/settings/system')).toBeVisible();
    expect(screen.getByText('API-022 / API-023')).toBeVisible();
    expect(screen.getByText('演示系统配置视图，非生产配置中心')).toBeVisible();
    const navigation = screen.getByLabelText('系统配置分组');
    for (const label of ['基础与版本', '调度规则', '接口与离线', '报表与审计', '权限与上下文']) {
      expect(within(navigation).getByRole('button', { name: label })).toBeVisible();
    }
    const detail = screen.getByLabelText('系统配置详情');
    for (const value of ['CFG-001', 'CFG-1.0', '草稿', 'USER-011']) {
      expect(within(detail).getByText(value)).toBeVisible();
    }
    expect(screen.getByLabelText('配置变更摘要')).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('switches among strict field groups without inventing external-system config', async () => {
    const user = userEvent.setup();
    renderSystemSettingsPage();
    await waitForSystemSettingsPage();

    await user.click(screen.getByRole('button', { name: '调度规则' }));
    for (const label of ['规则版本', '调度策略', '启用智能推荐']) {
      expect(screen.getByText(label)).toBeVisible();
    }

    await user.click(screen.getByRole('button', { name: '接口与离线' }));
    expect(screen.getByText('启用离线同步')).toBeVisible();
    expect(screen.getByText('未纳入 DO-015，不提供生产连接参数')).toBeVisible();
    expect(screen.queryByText('TOS 地址')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '报表与审计' }));
    expect(screen.getByText('默认报表周期')).toBeVisible();
    expect(screen.getByText('审计保留天数')).toBeVisible();
    expect(screen.getByText('演示审计记录不等同于合规归档')).toBeVisible();

    await user.click(screen.getByRole('button', { name: '权限与上下文' }));
    for (const value of ['系统管理员', '全局数据', 'SHIFT-001', 'Asia/Shanghai', 'SCN-01']) {
      expect(screen.getByLabelText('系统配置详情')).toHaveTextContent(value);
    }
  });

  it('renders loading, Store empty, not-found, network, malformed, and business read states', async () => {
    const loading = renderSystemSettingsPage({ api022Mode: 'unresolved' });
    expect(await screen.findByLabelText('系统配置加载中')).toBeVisible();
    await act(async () => loading.resolveApi022Success());
    await waitForSystemSettingsPage();
    cleanup();

    renderSystemSettingsPage({ preparation: 'empty' });
    expect(await screen.findByText('当前状态快照中没有可见的 DO-015 配置')).toBeVisible();
    cleanup();

    renderSystemSettingsPage({ search: '?group=overview&configId=CFG-404&scenarioId=SCN-01' });
    expect(await screen.findByText('配置对象已变化或不存在')).toBeVisible();
    cleanup();

    renderSystemSettingsPage({ api022Mode: 'network' });
    expect(await screen.findByText('系统配置读取暂不可用')).toBeVisible();
    expect(screen.getByText('B项目生产调度演示')).toBeVisible();
    cleanup();

    renderSystemSettingsPage({ api022Mode: 'malformed' });
    expect(await screen.findByText('系统配置响应契约不完整')).toBeVisible();
    cleanup();

    renderSystemSettingsPage({ api022Mode: 'business' });
    expect(await screen.findByText('当前场景不允许读取或修改系统配置。')).toBeVisible();
    expect(screen.getByText('TRACE-PAGE-C13-DEMO-SCENARIO-001')).toBeVisible();
    expect(screen.getByText('AUD-PAGE-C13-DEMO-SCENARIO-001')).toBeVisible();
  });
});
