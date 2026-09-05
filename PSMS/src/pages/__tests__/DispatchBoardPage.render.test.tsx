import { act, cleanup, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { renderDispatchPage, waitForDispatchPage } from './dispatchBoardTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-005 派工看板渲染', () => {
  it('shows exact identity, C06 context, KPI, queue, detail, resource pool, and Demo disclosure', async () => {
    await renderDispatchPage();
    await waitForDispatchPage();

    expect(screen.getByText('UI-005')).toBeVisible();
    expect(screen.getByText('/dispatch/work-orders')).toBeVisible();
    expect(screen.getByText('来源 UI-004', { exact: false })).toBeVisible();
    expect(screen.getByText('派工规则 1.0')).toBeVisible();
    expect(screen.getByText('PB-20260716-01')).toBeVisible();
    const kpis = screen.getByLabelText('派工指标');
    for (const label of ['就绪', '已绑定', '已下发', '执行中', '已完成', '异常入口']) {
      expect(within(kpis).getByText(label, { exact: true })).toBeVisible();
    }
    const queue = screen.getByLabelText('C06 工单队列');
    expect(within(queue).getByText('C06-WO-PLAN-001-G001-02')).toBeVisible();
    expect(within(queue).queryByText('WO-001', { exact: true })).not.toBeInTheDocument();
    expect(screen.getByLabelText('工单详情')).toHaveTextContent('上游');
    expect(screen.getByLabelText('一作业区资源池')).toHaveTextContent('RESOURCE-001');
    expect(screen.getByText('本地演示执行反馈，不代表现场系统回执')).toBeVisible();
    expect(within(queue).getAllByText('待派工', { exact: true })[0]).toBeVisible();
    expect(within(queue).queryByText('READY_QUEUE', { exact: true })).not.toBeInTheDocument();
    expect(screen.getByLabelText('资源智能匹配与派工演示')).toBeVisible();
    expect(screen.getByLabelText('资源匹配与派工演示阶段')).toBeVisible();
  });

  it('reactively renders ASSIGNED, DISPATCHED, and EXECUTING progress without horizontal-only content', async () => {
    const fixture = await renderDispatchPage();
    await waitForDispatchPage();
    const mutate = (status: 'READY' | 'DISPATCHED' | 'IN_PROGRESS') => {
      act(() => {
        fixture.runtime.store.replaceDomainState((candidate) => {
          const order = candidate.workOrder.workOrders.find(
            ({ id }) => id === 'C06-WO-PLAN-001-G001-02',
          )!;
          const node = candidate.workOrder.nodes.find(
            ({ id }) => id === 'C06-NODE-PLAN-001-G001-02',
          )!;
          Object.assign(order, {
            status,
            resourceId: 'RESOURCE-001',
            teamId: 'DEVICE-001',
            version: order.version + 1,
          });
          if (status === 'DISPATCHED') node.status = 'READY';
          if (status === 'IN_PROGRESS') node.status = 'IN_PROGRESS';
        });
      });
    };

    mutate('READY');
    expect(screen.getAllByText('已分配', { exact: true })[0]).toBeVisible();
    mutate('DISPATCHED');
    expect(screen.getAllByText('已派工', { exact: true })[0]).toBeVisible();
    mutate('IN_PROGRESS');
    expect(screen.getAllByText('执行中', { exact: true })[0]).toBeVisible();
    expect(screen.getByLabelText('UI-005 派工看板页面')).toHaveClass('dispatch-board-page');
    expect(screen.getByLabelText('一作业区资源池')).toBeVisible();
  });
});
