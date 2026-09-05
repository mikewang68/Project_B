import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderReportingPage, waitForReportingPage } from './reportingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

function upstreamSnapshot(state: ReturnType<ReturnType<typeof renderReportingPage>['runtime']['store']['getState']>) {
  return structuredClone({
    plan: state.plan,
    recommendation: state.recommendation,
    workOrder: state.workOrder,
    resource: state.resource,
    vehicle: state.vehicle,
    exception: state.exception,
    interlock: state.interlock,
    offline: state.offline,
  });
}

describe('UI-011 reporting dashboard actions', () => {
  it('filters the ledger, selects a report, and opens the metric drawer', async () => {
    const user = userEvent.setup();
    renderReportingPage();
    await waitForReportingPage();

    await user.selectOptions(screen.getByLabelText('报表类型'), 'DAILY');
    await user.type(screen.getByLabelText('统计周期'), '2026-07-17');
    await user.selectOptions(screen.getByLabelText('生成状态'), 'FAILED');
    const ledger = screen.getByLabelText('报表台账');
    expect(within(ledger).getByText('RP-002')).toBeVisible();
    expect(within(ledger).queryByText('RP-001')).not.toBeInTheDocument();
    expect(screen.getByLabelText('报表详情')).toHaveTextContent('RP-002');

    await user.click(screen.getByRole('button', { name: '查看指标口径' }));
    expect(await screen.findByRole('dialog', { name: '指标口径与公式' })).toBeVisible();
  });

  it('refreshes RP-002, shows identifiers, and changes only DO-012 plus C11 audit/workflow', async () => {
    const user = userEvent.setup();
    const fixture = renderReportingPage({ search: '?reportId=RP-002&scenarioId=SCN-01' });
    await waitForReportingPage();
    const upstreamBefore = upstreamSnapshot(fixture.runtime.store.getState());
    const otherReportsBefore = structuredClone(
      fixture.runtime.store.getState().report.reports.filter(({ id }) => id !== 'RP-002'),
    );

    await user.clear(screen.getByLabelText('生成理由'));
    await user.type(screen.getByLabelText('生成理由'), '刷新日报快照');
    await user.click(screen.getByRole('button', { name: '生成或刷新快照' }));

    expect(await screen.findByText('报表快照已刷新')).toBeVisible();
    const feedback = screen.getByLabelText('C11 命令反馈');
    expect(feedback).toHaveTextContent('CMD-C11-001');
    expect(feedback).toHaveTextContent('TRACE-C11-001');
    expect(feedback).toHaveTextContent('AUD-C11-001');
    expect(fixture.runtime.store.getState().report.reports.find(({ id }) => id === 'RP-002'))
      .toMatchObject({
        generateStatus: 'SUCCESS',
        generatedAt: fixture.runtime.store.getState().session.demoTime,
      });
    expect(fixture.runtime.store.getState().report.reports.filter(({ id }) => id !== 'RP-002'))
      .toEqual(otherReportsBefore);
    expect(upstreamSnapshot(fixture.runtime.store.getState())).toEqual(upstreamBefore);
    expect(fixture.runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(fixture.fetcher.mock.calls.map(([input]) => String(input)).join('\n'))
      .not.toContain('/mock/reports/export');
  });

  it('restores the frozen report and C11 local state through reset', async () => {
    const user = userEvent.setup();
    const fixture = renderReportingPage({ search: '?reportId=RP-003&scenarioId=SCN-01' });
    await waitForReportingPage();
    const frozenReport = structuredClone(
      fixture.runtime.store.getState().report.reports.find(({ id }) => id === 'RP-003'),
    );
    await user.click(screen.getByRole('button', { name: '生成或刷新快照' }));
    await screen.findByText('报表快照已刷新');

    await user.click(screen.getByRole('button', { name: '重置到 SCN-01' }));
    await waitFor(() => {
      expect(fixture.runtime.store.getState().report.reports.find(({ id }) => id === 'RP-003'))
        .toEqual(frozenReport);
    });
    expect(fixture.runtime.reporting.workflow.getState()).toEqual({
      filters: {},
      metricsDrawerOpen: false,
      snapshotSequence: 0,
    });
  });
});
