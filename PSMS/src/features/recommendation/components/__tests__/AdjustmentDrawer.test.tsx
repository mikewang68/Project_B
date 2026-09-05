import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createFixtureSnapshot } from '../../../../mocks/fixtures';
import { createDemoStore } from '../../../../stores';
import { calculateReceptionRecommendation } from '../../ruleEngine';
import AdjustmentDrawer from '../AdjustmentDrawer';

afterEach(cleanup);

describe('AdjustmentDrawer', () => {
  it('对高风险替代方案校验原因和复核员，并展示受影响工单与本人标记', async () => {
    const user = userEvent.setup();
    const store = createDemoStore(createFixtureSnapshot(), {
      actorId: 'USER-002',
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
      shiftId: 'SHIFT-001',
      scenarioId: 'SCN-01',
    });
    const state = store.getState();
    const plan = state.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!plan) throw new Error('PLAN-001 missing.');
    const candidate = calculateReceptionRecommendation({
      plan,
      tracks: state.resource.tracks,
      generatedAt: state.session.demoTime,
    }).candidates.find(({ trackNo }) => trackNo === 'T3');
    if (!candidate) throw new Error('T3 candidate missing.');
    const affectedWorkOrders = state.workOrder.workOrders.filter(
      ({ planId }) => planId === 'PLAN-001',
    );
    const onSubmit = vi.fn();

    render(
      <AdjustmentDrawer
        open
        candidate={candidate}
        affectedWorkOrders={affectedWorkOrders}
        reviewers={state.configAudit.userRoles}
        actorId="USER-002"
        loading={false}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    );

    const drawer = screen.getByRole('dialog');
    expect(within(drawer).getByText('WO-004')).toBeVisible();
    expect(within(drawer).getByText('已确认')).toBeVisible();
    expect(within(drawer).getByText('WO-007')).toBeVisible();
    expect(within(drawer).getByText('已暂停')).toBeVisible();
    await user.click(within(drawer).getByRole('button', { name: '提交确认' }));
    expect(await within(drawer).findByText('请输入调整原因')).toBeInTheDocument();
    expect(within(drawer).getByText('请选择异人复核员')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();

    await user.click(within(drawer).getByRole('combobox', { name: '异人复核员' }));
    expect(
      await screen.findByText(/USER-002.*本人，不能复核/, {
        selector: '.ant-select-item-option-content',
      }),
    ).toBeInTheDocument();
  });
});
