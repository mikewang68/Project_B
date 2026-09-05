import { act, cleanup, screen } from '@testing-library/react';
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

describe('UI-009 states and permissions', () => {
  it.each(['SAFETY', 'MAINTAINER', 'DISPATCHER'] as const)(
    '%s sees interlock:view and state-gated SI actions',
    async (roleCode) => {
      await renderSafetyInterlockPage({ roleCode });
      await waitForSafetyInterlockPage();
      expect(screen.getByText('UI-009')).toBeVisible();
      expect(screen.getByRole('button', { name: '申请复位' })).toBeDisabled();
    },
  );

  it('renders route forbidden before API for BUSINESS', async () => {
    const forbidden = renderSafetyInterlockPage({ roleCode: 'BUSINESS' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：interlock:view')).toBeVisible();
    expect(forbidden.fetcher).not.toHaveBeenCalled();
  });

  it('renders loading, empty, network-error, business-error, and safe not-found states', async () => {
    const loading = renderSafetyInterlockPage({ api016Mode: 'unresolved' });
    expect(await screen.findByLabelText('页面加载中')).toBeVisible();
    await act(async () => loading.resolveApi016Success());
    await waitForSafetyInterlockPage();
    cleanup();

    renderSafetyInterlockPage({ preparation: 'empty' });
    expect(await screen.findByText('当前作业区暂无可见计划')).toBeVisible();
    cleanup();

    renderSafetyInterlockPage({ api016Mode: 'malformed' });
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    cleanup();

    const business = renderSafetyInterlockPage({ api017Mode: 'business-error' });
    await waitForSafetyInterlockPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('处置原因'), '申请复位');
    await user.type(screen.getByLabelText('复位申请'), '现场清场');
    await user.click(screen.getByRole('button', { name: '申请复位' }));
    expect(await screen.findByText('业务处理失败')).toBeVisible();
    expect(business.runtime.store.getState().interlock.interlocks[0])
      .toMatchObject({ status: 'LOCKED' });
    cleanup();

    renderSafetyInterlockPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('IL-20260716-01')).not.toBeInTheDocument();
  });

  it('disables actions until reason, reset request, approval user, and state allow them', async () => {
    const user = userEvent.setup();
    await renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();

    const requestReset = screen.getByRole('button', { name: '申请复位' });
    expect(requestReset).toBeDisabled();
    await user.type(screen.getByLabelText('处置原因'), '申请复位');
    expect(requestReset).toBeDisabled();
    await user.type(screen.getByLabelText('复位申请'), '现场清场');
    expect(requestReset).toBeEnabled();
    expect(screen.getByRole('button', { name: '审批复位' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '登记恢复' })).toBeDisabled();
  });

  it('shows the FORCE_STOP safety boundary while allowing only a Demo restoration record', async () => {
    const user = userEvent.setup();
    const fixture = renderSafetyInterlockPage({ scenarioId: 'SCN-05' });
    await waitForSafetyInterlockPage();
    const exceptionsBefore = structuredClone(fixture.runtime.store.getState().exception);
    await user.click(screen.getByRole('button', { name: /IL-20260716-03/ }));

    expect(screen.getByText(
      '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。',
    )).toBeVisible();
    expect(screen.getByRole('button', { name: '登记恢复' })).toBeDisabled();
    await user.type(screen.getByLabelText('处置原因'), '仅登记 Demo 恢复记录');
    expect(screen.getByRole('button', { name: '登记恢复' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '真实设备复位' })).not.toBeInTheDocument();
    expect(fixture.runtime.store.getState().exception).toEqual(exceptionsBefore);
  });
});
