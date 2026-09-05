import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  renderSafetyInterlockPage,
  waitForSafetyInterlockPage,
} from './safetyInterlockTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('UI-009 safety interlock render', () => {
  it('autoplays the stored LOCKED interlock through reset approval and restoration', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
      matches: query === '(prefers-reduced-motion: reduce)',
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent: () => false,
    }));

    const fixture = renderSafetyInterlockPage({ autoplay: true, roleCode: 'DISPATCHER' });

    await waitFor(() => {
      expect(screen.getByLabelText('安全联锁闭环演示')).toHaveTextContent('演示完成');
    }, { timeout: 15_000 });
    expect(fixture.runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'RESTORED' });
    expect(fixture.fetcher.mock.calls.filter(([input]) =>
      new URL(String(input), 'http://localhost').pathname.endsWith('/command'),
    )).toHaveLength(3);
  });

  it('shows identity, C08 display-only context, KPI, ledger, detail, snapshot, action, audit, and return entry', async () => {
    await renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();

    expect(screen.getByText('UI-009')).toBeVisible();
    expect(screen.getByText('当前路由：/safety/interlocks')).toBeVisible();
    expect(screen.getByText('演示来源上下文，非生产外键')).toBeVisible();
    expect(screen.getAllByText('EX-003')[0]).toBeVisible();
    expect(screen.getAllByText('SCN-01')[0]).toBeVisible();
    const kpis = screen.getByLabelText('安全联锁指标');
    for (const label of ['锁定中', '待审批', '已批准', '已恢复', '强制停机', '回执失败']) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    const ledger = screen.getByLabelText('联锁台账');
    expect(within(ledger).getByText('IL-20260716-01')).toBeVisible();
    expect(within(ledger).getByText('人员侵入')).toBeVisible();
    expect(within(ledger).getByText('IL-20260716-03')).toBeVisible();
    expect(screen.getByLabelText('联锁详情')).toBeVisible();
    expect(screen.getByLabelText('输入快照')).toHaveTextContent('SENSOR-01');
    expect(screen.getByLabelText('联锁处置面板')).toBeVisible();
    expect(screen.getByLabelText('C09 审计摘要')).toBeVisible();
    expect(screen.getByRole('link', { name: '返回 UI-008 异常处置' })).toHaveAttribute(
      'href',
      '/monitor/exceptions?exceptionId=EX-003&scenarioId=SCN-01&from=safety-interlock',
    );
    expect(screen.getByLabelText('安全联锁闭环演示')).toBeVisible();
    expect(screen.getByLabelText('安全联锁演示阶段')).toBeVisible();
  });

  it('reactively renders LOCKED, RESETTING, RESTORED, and OVERRIDE progress', async () => {
    const fixture = renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();

    const setStatus = (
      status: 'LOCKED' | 'RESET_REQUESTED' | 'RESTORED' | 'OVERRIDE_PENDING',
    ) => {
      act(() => fixture.runtime.store.replaceDomainState((candidate) => {
        candidate.interlock.interlocks.find(({ id }) => id === 'IL-001')!.status = status;
      }));
    };

    expect(screen.getAllByText('联锁锁定', { exact: true })[0]).toBeVisible();
    setStatus('RESET_REQUESTED');
    expect(screen.getAllByText('恢复申请中', { exact: true })[0]).toBeVisible();
    setStatus('RESTORED');
    expect(screen.getAllByText('已恢复', { exact: true })[0]).toBeVisible();
    setStatus('OVERRIDE_PENDING');
    expect(screen.getAllByText('解锁审批中', { exact: true })[0]).toBeVisible();
    expect(screen.getByLabelText('UI-009 安全联锁页面')).toHaveClass('safety-interlock-page');
  });

  it('shows FORCE_STOP and receipt-failure boundaries without claiming real reset control', async () => {
    await renderSafetyInterlockPage();
    await waitForSafetyInterlockPage();
    await act(async () => {
      screen.getByRole('button', { name: /IL-20260716-03/ }).click();
    });

    expect(screen.getAllByText('强制停机', { exact: true })[0]).toBeVisible();
    expect(screen.getByText(
      '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。',
    )).toBeVisible();
    expect(screen.getByText('回执失败')).toBeVisible();
    expect(screen.queryByText('真实设备复位成功')).not.toBeInTheDocument();
  });

  it('filters strict DO-010 values while preserving source context as display text', async () => {
    await renderSafetyInterlockPage({
      search: '?exceptionId=EX-NOT-FOUND&scenarioId=SCN-01&from=exception-handling'
        + '&status=APPROVED&actionLevel=FORCE_STOP&riskType=DEVICE_FAULT&receiptStatus=FAILED',
    });
    await waitForSafetyInterlockPage();

    const ledger = screen.getByLabelText('联锁台账');
    expect(within(ledger).getByText('IL-20260716-03')).toBeVisible();
    expect(within(ledger).queryByText('IL-20260716-01')).not.toBeInTheDocument();
    expect(screen.getAllByText('EX-NOT-FOUND')).not.toHaveLength(0);
    expect(screen.getByText('演示来源上下文，非生产外键')).toBeVisible();
  });
});
