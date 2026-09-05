import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderReportingPage, waitForReportingPage } from './reportingTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-011 reporting dashboard render', () => {
  it('shows identity, deterministic disclosure, metrics, distributions, ledger, and strict detail', async () => {
    renderReportingPage();
    await waitForReportingPage();

    expect(screen.getByText('UI-011')).toBeVisible();
    expect(screen.getByText('当前路由：/reports/operations')).toBeVisible();
    expect(screen.getByText('演示用确定性统计口径')).toBeVisible();
    const kpis = screen.getByLabelText('运行结果指标');
    for (const label of [
      '计划总数', '已确认计划数', '推荐已应用数', '已生成任务数',
      '已派工任务数', '异常数量', '安全联锁数量', '离线包已合并数量',
    ]) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    const efficiency = screen.getByLabelText('作业效率指标');
    for (const label of [
      '计划确认率', '任务拆解完成率', '派工完成率', '异常关闭率', '离线同步合并率',
    ]) {
      expect(within(efficiency).getByText(label, { exact: true })).toBeVisible();
    }
    for (const label of ['作业状态分布', '异常分布', '联锁动作等级分布', '离线包状态分布']) {
      expect(screen.getByLabelText(label)).toBeVisible();
    }
    const ledger = screen.getByLabelText('报表台账');
    for (const reportId of ['RP-001', 'RP-002', 'RP-003']) {
      expect(within(ledger).getByText(reportId)).toBeVisible();
    }
    const detail = screen.getByLabelText('报表详情');
    for (const field of ['报表编号', '报表类型', '统计周期', '生成状态', '指标快照', '生成时间']) {
      expect(within(detail).getByText(field, { exact: true })).toBeVisible();
    }
    expect(screen.queryByText('API-021')).not.toBeInTheDocument();
    expect(screen.queryByText('导出报表')).not.toBeInTheDocument();
  });

  it('hydrates query labels, selects RP-002, and exposes metric sources and formulas', async () => {
    renderReportingPage({
      search: '?reportId=RP-002&reportType=DAILY&period=2026-07-17'
        + '&generateStatus=FAILED&scenarioId=SCN-01&from=exception-handling',
    });
    await waitForReportingPage();

    for (const label of [
      '来源模块：异常处置', '场景：SCN-01', '报表：RP-002',
      '类型：日报', '周期：2026-07-17', '状态：生成失败',
    ]) {
      expect(screen.getByText(label)).toBeVisible();
    }
    expect(screen.getByLabelText('报表详情')).toHaveTextContent('RP-002');
    expect(screen.getByLabelText('报表详情')).toHaveTextContent('日报');
    expect(screen.getByLabelText('报表详情')).toHaveTextContent('失败');
    await act(async () => screen.getByRole('button', { name: '查看指标口径' }).click());
    expect(await screen.findByRole('dialog', { name: '指标口径与公式' })).toBeVisible();
    expect(screen.getAllByText('DO-011 离线包合并状态').length).toBeGreaterThan(0);
    expect(screen.getByText('已合并离线包数量 / 离线包总数；分母为 0 时取 0%')).toBeVisible();
  });

  it('renders loading, Store empty, filter empty, not-found, network, malformed, and business states', async () => {
    const loading = renderReportingPage({ api020Mode: 'unresolved' });
    expect(await screen.findByLabelText('报表加载中')).toBeVisible();
    await act(async () => loading.resolveApi020Success());
    await waitForReportingPage();
    cleanup();

    renderReportingPage({ preparation: 'empty' });
    expect(await screen.findByText('当前作业区暂无可见报表')).toBeVisible();
    cleanup();

    renderReportingPage({ search: '?reportType=MONTHLY&period=2099-01&scenarioId=SCN-01' });
    expect(await screen.findByText('当前筛选条件下暂无报表')).toBeVisible();
    cleanup();

    renderReportingPage({ search: '?reportId=RP-NOT-FOUND&scenarioId=SCN-01' });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    cleanup();

    renderReportingPage({ api020Mode: 'network' });
    expect(await screen.findByText('报表读取暂不可用')).toBeVisible();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect(screen.getByLabelText('运行结果指标')).toBeVisible();
    cleanup();

    renderReportingPage({ api020Mode: 'malformed' });
    expect(await screen.findByText('报表响应契约不完整')).toBeVisible();
    cleanup();

    renderReportingPage({ api020Mode: 'business' });
    expect(await screen.findByText('当前场景不允许读取统计报表')).toBeVisible();
    expect(screen.getByText('TRACE-PAGE-C11-ERROR')).toBeVisible();
    expect(screen.getByText('AUD-PAGE-C11-ERROR')).toBeVisible();
  });
});
