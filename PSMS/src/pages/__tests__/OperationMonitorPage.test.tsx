import { cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { renderMonitoringPage } from './monitoringTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});

describe('UI-007 全流程监控', () => {
  it('展示甘特图、工序链、资源位置和实时事件', async () => {
    renderMonitoringPage('operations');
    expect(screen.getByText('UI-007')).toBeVisible();
    expect(screen.getByRole('heading', { name: '全流程监控' })).toBeVisible();
    expect(screen.getByText('当前路由：/monitor/operations')).toBeVisible();
    expect(screen.getByRole('img', { name: '工序计划甘特图' })).toBeVisible();
    expect(screen.getByLabelText('工序进度链')).toBeVisible();
    expect(screen.getByLabelText('场区资源位置图')).toBeVisible();
    expect(screen.getByLabelText('资源运行状态')).toBeVisible();
    expect(screen.getByLabelText('实时事件台账')).toBeVisible();
    expect(await screen.findByText('监控接口已校验')).toBeVisible();
  });

  it('可启动并暂停确定性流程推演', async () => {
    const user = userEvent.setup();
    renderMonitoringPage('operations');
    await user.click(screen.getByRole('button', { name: '播放流程' }));
    expect(await screen.findByText('正在推演')).toBeVisible();
    expect(screen.getByRole('button', { name: '暂停推演' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: '暂停推演' }));
    expect(screen.getByText('监控就绪')).toBeVisible();
  });
});
