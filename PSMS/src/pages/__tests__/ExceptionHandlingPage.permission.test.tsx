import { act, cleanup, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderExceptionPage, waitForExceptionPage } from './exceptionHandlingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-008 states and permissions', () => {
  it.each(['DISPATCHER', 'SHIFT_LEADER'] as const)('%s sees monitor:view and EX actions', async (roleCode) => {
    await renderExceptionPage({ roleCode });
    await waitForExceptionPage();
    expect(screen.getByText('UI-008')).toBeVisible();
    expect(screen.getByRole('button', { name: '确认异常' })).toBeDisabled();
  });

  it('keeps BUSINESS read-only and renders route forbidden before API for SAFETY', async () => {
    await renderExceptionPage({ roleCode: 'BUSINESS' });
    await waitForExceptionPage();
    expect(screen.getByRole('button', { name: '确认异常' })).toBeDisabled();
    cleanup();

    const forbidden = renderExceptionPage({ roleCode: 'SAFETY' });
    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：monitor:view')).toBeVisible();
    expect(forbidden.fetcher).not.toHaveBeenCalled();
  });

  it('renders loading, empty, network-error, business-error, and safe not-found states', async () => {
    const loading = renderExceptionPage({ api014Mode: 'unresolved' });
    expect(await screen.findByLabelText('页面加载中')).toBeVisible();
    await act(async () => loading.resolveApi014Success());
    await waitForExceptionPage();
    cleanup();

    renderExceptionPage({ preparation: 'empty' });
    expect(await screen.findByText('当前作业区暂无可见计划')).toBeVisible();
    cleanup();

    renderExceptionPage({ api014Mode: 'malformed' });
    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    cleanup();

    const business = renderExceptionPage({ api015Mode: 'business-error' });
    await waitForExceptionPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('处置原因'), '确认异常');
    await user.click(screen.getByRole('button', { name: '确认异常' }));
    expect(await screen.findByText('业务处理失败')).toBeVisible();
    expect(business.runtime.store.getState().exception.exceptions[0]).toMatchObject({ status: 'OPEN' });
    cleanup();

    renderExceptionPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('EX-20260716-01')).not.toBeInTheDocument();
  });

  it('disables C08 writes for INTERLOCK and exposes only the UI-009 boundary', async () => {
    const fixture = renderExceptionPage();
    await waitForExceptionPage();
    const interlockBefore = structuredClone(fixture.runtime.store.getState().interlock);
    await userEvent.setup().click(screen.getByRole('button', { name: /EX-20260716-03/ }));

    expect(screen.getAllByText('TOS-IL-001', { exact: false })[0]).toBeVisible();
    for (const label of ['确认异常', '分派处理', '提交处理', '复核退回', '关闭异常', '重新打开']) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled();
    }
    expect(screen.getByRole('link', { name: '前往 UI-009 安全联锁' })).toBeVisible();
    expect(fixture.runtime.store.getState().interlock).toEqual(interlockBefore);
  });
});
