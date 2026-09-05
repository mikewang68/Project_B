import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderSystemSettingsPage, waitForSystemSettingsPage } from './systemSettingsTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

function upstreamSnapshot(state: ReturnType<ReturnType<typeof renderSystemSettingsPage>['runtime']['store']['getState']>) {
  return structuredClone({
    plan: state.plan,
    recommendation: state.recommendation,
    workOrder: state.workOrder,
    resource: state.resource,
    vehicle: state.vehicle,
    exception: state.exception,
    interlock: state.interlock,
    offline: state.offline,
    report: state.report,
    userRoles: state.configAudit.userRoles,
    audit: state.configAudit.audit,
  });
}

describe('UI-012 system settings actions', () => {
  it('edits a draft and updates the fixed summary in whitelist order', async () => {
    const user = userEvent.setup();
    renderSystemSettingsPage();
    await waitForSystemSettingsPage();
    await user.click(screen.getByRole('button', { name: '编辑配置' }));
    await user.clear(screen.getByLabelText('配置名称'));
    await user.type(screen.getByLabelText('配置名称'), '新的演示名称');
    await user.selectOptions(screen.getByLabelText('默认场景'), 'SCN-02');

    const summary = screen.getByLabelText('配置变更摘要');
    expect(within(summary).getByText('配置名称')).toBeVisible();
    expect(within(summary).getByText('新的演示名称')).toBeVisible();
    expect(within(summary).getByText('默认场景')).toBeVisible();
    expect(within(summary).getByText('SCN-02')).toBeVisible();
    expect(screen.getByRole('button', { name: '保存配置' })).toBeEnabled();
  });

  it('blocks invalid fields and blank reason without calling API-023 or changing Store', async () => {
    const user = userEvent.setup();
    const fixture = renderSystemSettingsPage();
    await waitForSystemSettingsPage();
    const before = structuredClone(fixture.runtime.store.getState().systemConfig.configVersions);
    await user.click(screen.getByRole('button', { name: '编辑配置' }));
    await user.clear(screen.getByLabelText('配置名称'));
    await user.click(screen.getByRole('button', { name: '保存配置' }));

    const summary = screen.getByLabelText('配置变更摘要');
    expect(await within(summary).findByText('请填写变更说明。')).toBeVisible();
    expect(within(summary).getByText('配置名称不能为空，且最多 64 个字符。')).toBeVisible();
    expect(fixture.runtime.store.getState().systemConfig.configVersions).toEqual(before);
    expect(fixture.fetcher.mock.calls.map(([request]) => String(request)).join('\n'))
      .not.toContain('/mock/config/CFG-001/command');
  });

  it('discards a draft and restores current Store values', async () => {
    const user = userEvent.setup();
    renderSystemSettingsPage();
    await waitForSystemSettingsPage();
    await user.click(screen.getByRole('button', { name: '编辑配置' }));
    await user.clear(screen.getByLabelText('配置名称'));
    await user.type(screen.getByLabelText('配置名称'), '不保存的名称');
    await user.click(screen.getByRole('button', { name: '放弃修改' }));

    expect(screen.queryByDisplayValue('不保存的名称')).not.toBeInTheDocument();
    expect(screen.getByText('B项目生产调度演示')).toBeVisible();
    expect(screen.getByLabelText('配置变更摘要')).toHaveTextContent('暂无待提交变更');
  });

  it('saves strict changes, shows two audit identities, and changes only DO-015 plus command audit', async () => {
    const user = userEvent.setup();
    const fixture = renderSystemSettingsPage();
    await waitForSystemSettingsPage();
    const upstream = upstreamSnapshot(fixture.runtime.store.getState());
    await user.click(screen.getByRole('button', { name: '编辑配置' }));
    await user.clear(screen.getByLabelText('配置名称'));
    await user.type(screen.getByLabelText('配置名称'), '新的演示名称');
    await user.type(screen.getByLabelText('变更说明'), '演示系统配置变更');
    await user.click(screen.getByRole('button', { name: '保存配置' }));

    expect(await screen.findByText('系统配置已保存')).toBeVisible();
    const feedback = screen.getByLabelText('C13 命令反馈');
    expect(feedback).toHaveTextContent('v2');
    expect(feedback).toHaveTextContent('TRACE-PAGE-C13-2');
    expect(feedback).toHaveTextContent('接口回执审计号：AUD-PAGE-C13-2');
    expect(feedback).toHaveTextContent('领域变更审计号：AUD-C13-001');
    expect(fixture.runtime.store.getState().systemConfig.configVersions[0]).toMatchObject({
      displayName: '新的演示名称', version: 2, updatedBy: 'USER-SYS_ADMIN',
    });
    expect(fixture.runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(upstreamSnapshot(fixture.runtime.store.getState())).toEqual(upstream);

    const commandCall = fixture.fetcher.mock.calls.find(([request]) =>
      String(request).includes('/mock/config/CFG-001/command'),
    );
    expect(commandCall).toBeDefined();
    expect(JSON.parse(String(commandCall?.[1]?.body))).toEqual({
      command: 'edit',
      expectedVersion: 1,
      changes: { displayName: '新的演示名称' },
      reason: '演示系统配置变更',
    });
    expect(fixture.fetcher.mock.calls.map(([request]) => String(request)).join('\n'))
      .not.toContain('/mock/demo/reset');
  });

  it.each(['network', 'malformed', 'business', 'version-conflict'] as const)(
    'retains the draft and config for %s save failure',
    async (api023Mode) => {
      const user = userEvent.setup();
      const fixture = renderSystemSettingsPage({ api023Mode });
      await waitForSystemSettingsPage();
      const before = structuredClone(fixture.runtime.store.getState().systemConfig.configVersions);
      await user.click(screen.getByRole('button', { name: '编辑配置' }));
      await user.clear(screen.getByLabelText('配置名称'));
      await user.type(screen.getByLabelText('配置名称'), '失败草稿');
      await user.type(screen.getByLabelText('变更说明'), '保留失败草稿');
      await user.click(screen.getByRole('button', { name: '保存配置' }));

      await waitFor(() => {
        expect(screen.getByLabelText('C13 命令反馈')).toHaveTextContent('保存失败');
      });
      expect(screen.getByDisplayValue('失败草稿')).toBeVisible();
      expect(fixture.runtime.store.getState().systemConfig.configVersions).toEqual(before);
    },
  );
});
