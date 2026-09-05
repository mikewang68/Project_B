import { act, cleanup, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderAuditTrailPage, waitForAuditTrailPage } from './auditTrailTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

function domainSnapshot(state: ReturnType<ReturnType<typeof renderAuditTrailPage>['runtime']['store']['getState']>) {
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
    configAudit: state.configAudit,
  });
}

describe('UI-013 audit trail actions', () => {
  it('filters the ledger, opens detail, opens trace, closes both, and never writes Store facts', async () => {
    const fixture = renderAuditTrailPage();
    await waitForAuditTrailPage();
    const before = domainSnapshot(fixture.runtime.store.getState());

    fireEvent.change(screen.getByLabelText('来源模块'), { target: { value: 'BASELINE' } });
    fireEvent.change(screen.getByLabelText('操作人编号'), { target: { value: 'USER-001' } });
    const ledger = screen.getByLabelText('审计台账');
    expect(within(ledger).getByText('AUD-001')).toBeVisible();
    expect(within(ledger).queryByText('AUD-002')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '查看 AUD-001 详情' }));
    expect(await screen.findByRole('dialog', { name: '审计详情 AUD-001' })).toBeVisible();
    expect(screen.getByText('已打开审计详情。')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '打开 TRACE-001 链路' }));
    expect(screen.getByLabelText('审计链路 TRACE-001')).toHaveTextContent('AUD-001');
    fireEvent.click(screen.getByRole('button', { name: '关闭链路' }));
    expect(screen.queryByLabelText('审计链路 TRACE-001')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '关闭审计详情' }));
    expect(screen.queryByRole('dialog', { name: '审计详情 AUD-001' })).not.toBeInTheDocument();
    expect(domainSnapshot(fixture.runtime.store.getState())).toEqual(before);
  });

  it('retries API-024, keeps controls disabled while pending, and reports stale traces truthfully', async () => {
    const user = userEvent.setup();
    const fixture = renderAuditTrailPage({ api024Mode: 'network' });
    await screen.findByText('审计日志读取暂不可用');
    const before = domainSnapshot(fixture.runtime.store.getState());
    fixture.setApi024Mode('unresolved');

    await user.click(screen.getByRole('button', { name: '重新加载' }));
    expect(await screen.findByLabelText('审计日志加载中')).toBeVisible();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeDisabled();
    await act(async () => fixture.resolveApi024Success());
    await waitForAuditTrailPage();

    act(() => fixture.runtime.auditTrail.workflow.setExpandedTrace('TRACE-STALE'));
    expect(await screen.findByText('原链路已失效。')).toBeVisible();
    expect(domainSnapshot(fixture.runtime.store.getState())).toEqual(before);
  });

  it('does not offer export, print, download, archive, or forged idempotent actions', async () => {
    renderAuditTrailPage();
    await waitForAuditTrailPage();

    for (const label of ['导出', '打印', '下载', '归档', '验签', '幂等重放']) {
      expect(screen.queryByText(label, { exact: true })).not.toBeInTheDocument();
    }
  });
});
