import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderAuditTrailPage, waitForAuditTrailPage } from './auditTrailTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-013 audit trail render', () => {
  it('shows frozen identity, disclosure, truthful KPIs, API observation, and nine strict rows', async () => {
    renderAuditTrailPage();
    await waitForAuditTrailPage();

    expect(screen.getByText('UI-013')).toBeVisible();
    expect(screen.getByText('当前路由：/governance/audit')).toBeVisible();
    expect(screen.getByText('API-024 · 查询')).toBeVisible();
    expect(screen.getByText('演示审计投影，非真实生产日志')).toBeVisible();
    expect(screen.getByText(
      '当前冻结场景未产生独立幂等审计记录；重放复用首次结果且不新增 DO-013。',
    )).toBeVisible();

    const kpis = screen.getByLabelText('审计指标');
    for (const label of [
      '领域审计记录', '显式成功', '权限拒绝', '版本冲突', '幂等命中', '业务错误', '最近链路编号',
    ]) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    expect(within(kpis).getByText('9', { exact: true })).toBeVisible();
    expect(within(kpis).getAllByText('0', { exact: true })).toHaveLength(5);
    expect(within(kpis).getByText('TRACE-PAGE-C12-1', { exact: true })).toBeVisible();

    const ledger = screen.getByLabelText('审计台账');
    for (let index = 1; index <= 9; index += 1) {
      expect(within(ledger).getByText(`AUD-${String(index).padStart(3, '0')}`)).toBeVisible();
    }
    expect(screen.getByLabelText('API-024 读取状态')).toHaveTextContent('AUD-PAGE-C12-1');
    expect(screen.getByLabelText('API-024 读取状态')).toHaveTextContent('TRACE-PAGE-C12-1');
  });

  it('opens a strict DO-013 detail and a truthful one-record trace from query context', async () => {
    renderAuditTrailPage({ search: '?auditId=AUD-001&traceId=TRACE-001&scenarioId=SCN-01&from=reports' });
    await waitForAuditTrailPage();

    expect(await screen.findByRole('dialog', { name: '审计详情 AUD-001' })).toBeVisible();
    const detail = screen.getByRole('dialog', { name: '审计详情 AUD-001' });
    for (const field of [
      '审计编号', '操作人编号', '操作终端', '业务动作', '对象类型', '对象编号',
      '变更前', '变更后', '操作原因', '链路编号', '发生时间',
    ]) {
      expect(within(detail).getByText(field, { exact: true })).toBeVisible();
    }
    expect(detail).toHaveTextContent('未提供');
    expect(screen.getByLabelText('审计链路 TRACE-001')).toHaveTextContent('当前链路共 1 条');
  });

  it('renders loading, Store empty, filter empty, not-found, network, malformed, and business states', async () => {
    const loading = renderAuditTrailPage({ api024Mode: 'unresolved' });
    expect(await screen.findByLabelText('审计日志加载中')).toBeVisible();
    await act(async () => loading.resolveApi024Success());
    await waitForAuditTrailPage();
    cleanup();

    renderAuditTrailPage({ preparation: 'empty' });
    expect(await screen.findByText('当前场景暂无领域审计记录')).toBeVisible();
    cleanup();

    renderAuditTrailPage({ search: '?actorId=USER-NOT-FOUND&scenarioId=SCN-01' });
    expect(await screen.findByText('当前筛选条件下暂无审计记录')).toBeVisible();
    cleanup();

    renderAuditTrailPage({ search: '?auditId=AUD-NOT-FOUND&scenarioId=SCN-01' });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    cleanup();

    renderAuditTrailPage({ api024Mode: 'network' });
    expect(await screen.findByText('审计日志读取暂不可用')).toBeVisible();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect(screen.getByLabelText('审计台账')).toBeVisible();
    cleanup();

    renderAuditTrailPage({ api024Mode: 'malformed' });
    expect(await screen.findByText('审计响应契约不完整')).toBeVisible();
    cleanup();

    renderAuditTrailPage({ api024Mode: 'business' });
    expect(await screen.findByText('当前场景不允许读取审计日志。')).toBeVisible();
    expect(screen.getAllByText('TRACE-PAGE-C12-ERROR').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('AUD-PAGE-C12-ERROR')).toBeVisible();
  });
});
