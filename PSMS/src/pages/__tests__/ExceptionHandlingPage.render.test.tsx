import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderExceptionPage, waitForExceptionPage } from './exceptionHandlingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-008 exception handling render', () => {
  it('shows exact identity, C07 display-only context, KPI, ledger, detail, evidence, audit, and UI-009 entry', async () => {
    await renderExceptionPage();
    await waitForExceptionPage();

    expect(screen.getByText('UI-008')).toBeVisible();
    expect(screen.getByText('当前路由：/monitor/exceptions')).toBeVisible();
    expect(screen.getByText('演示来源上下文，非生产外键')).toBeVisible();
    expect(screen.getByText('C06-WO-PLAN-001-G001-02')).toBeVisible();
    expect(screen.getAllByText('PLAN-001')[0]).toBeVisible();
    const kpis = screen.getByLabelText('异常处置指标');
    for (const label of ['未确认', '处理中', '待复核', '已关闭', '超期', '联锁类']) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    const ledger = screen.getByLabelText('异常台账');
    expect(within(ledger).getByText('EX-20260716-01')).toBeVisible();
    expect(within(ledger).getAllByText('TEAM-01')[0]).toBeVisible();
    expect(within(ledger).getByText('EX-20260716-03')).toBeVisible();
    expect(screen.getByLabelText('异常证据')).toHaveTextContent('EVIDENCE-001');
    expect(screen.getByLabelText('异常处置面板')).toBeVisible();
    expect(screen.getByLabelText('C08 审计摘要')).toBeVisible();
    expect(screen.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
      'href',
      '/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling',
    );
    expect(screen.getByLabelText('异常识别到闭环演示')).toBeVisible();
    expect(screen.getByLabelText('异常闭环演示阶段')).toBeVisible();
  });

  it('reactively renders OPEN_QUEUE, HANDLING, REVIEW, and CLOSED progress', async () => {
    const fixture = renderExceptionPage();
    await waitForExceptionPage();

    const setStatus = (status: 'OPEN' | 'HANDLING' | 'PENDING_REVIEW' | 'CLOSED') => {
      act(() => fixture.runtime.store.replaceDomainState((candidate) => {
        candidate.exception.exceptions.find(({ id }) => id === 'EX-001')!.status = status;
      }));
    };

    expect(screen.getAllByText('待确认', { exact: true })[0]).toBeVisible();
    setStatus('HANDLING');
    expect(screen.getAllByText('处置中', { exact: true })[0]).toBeVisible();
    setStatus('PENDING_REVIEW');
    expect(screen.getAllByText('待复核', { exact: true })[0]).toBeVisible();
    setStatus('CLOSED');
    expect(screen.getAllByText('已关闭', { exact: true })[0]).toBeVisible();
    expect(screen.getByLabelText('UI-008 异常处置页面')).toHaveClass('exception-handling-page');
  });

  it('filters by frozen query values without claiming a production relation', async () => {
    await renderExceptionPage({
      search: '?workOrderId=WO-NOT-FOUND&planId=PLAN-NOT-FOUND&status=PENDING_REVIEW&type=TIMEOUT&level=CRITICAL&owner=TEAM-01',
    });
    await waitForExceptionPage();

    const ledger = screen.getByLabelText('异常台账');
    expect(within(ledger).getByText('EX-20260716-04')).toBeVisible();
    expect(within(ledger).queryByText('EX-20260716-01')).not.toBeInTheDocument();
    expect(screen.getByText('WO-NOT-FOUND')).toBeVisible();
    expect(screen.getByText('演示来源上下文，非生产外键')).toBeVisible();
  });
});
