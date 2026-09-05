import { act, cleanup, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { renderTaskPage, waitForTaskPage } from './taskDecompositionTestHarness';

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-004 状态与权限', () => {
  it.each(['DISPATCHER', 'SHIFT_LEADER'] as const)(
    '%s uses frozen task:view policy and sees the workspace',
    async (roleCode) => {
      await renderTaskPage({ roleCode });
      await waitForTaskPage();
      expect(screen.getByText('UI-004')).toBeVisible();
      expect(screen.getByRole('button', { name: '自动拆解' })).toBeEnabled();
    },
  );

  it('blocks BUSINESS before lazy page behavior or API calls', async () => {
    const fixture = await renderTaskPage({ roleCode: 'BUSINESS', preparation: 'none' });

    expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
    expect(screen.getByText('所需权限：task:view')).toBeVisible();
    expect(screen.queryByText('计划与推荐摘要')).not.toBeInTheDocument();
    expect(fixture.fetcher).not.toHaveBeenCalled();
  });

  it('returns safe not-found for AREA-B and unknown Plan without leaking task facts', async () => {
    await renderTaskPage({ dataScope: ['AREA-B'] });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
    expect(screen.queryByText('识别与路由确认')).not.toBeInTheDocument();
    cleanup();

    await renderTaskPage({ planId: 'PLAN-UNKNOWN' });
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('PLAN-001')).not.toBeInTheDocument();
  });

  it('shows business-error when the confirmed C05 prerequisite is missing', async () => {
    await renderTaskPage({ preparation: 'plan-only' });
    await waitForTaskPage();

    expect(screen.getByText('业务处理失败')).toBeVisible();
    expect(screen.getByText('DEMO-SCENARIO-001')).toBeVisible();
    expect(screen.getByRole('link', { name: '返回生产准备建议' })).toHaveAttribute(
      'href',
      '/dispatch/plans/PLAN-001/recommendation',
    );
  });

  it('shows loading for unresolved API-007 and network-error with retry for failed/malformed envelopes', async () => {
    const user = userEvent.setup();
    const unresolved = await renderTaskPage({ api007Mode: 'unresolved' });
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    expect(await screen.findByLabelText('页面加载中')).toBeVisible();
    await act(async () => unresolved.resolveApi007Success());
    expect((await screen.findAllByText('识别与路由确认', { exact: false }))[0]).toBeVisible();
    cleanup();

    for (const api007Mode of ['failure', 'malformed'] as const) {
      await renderTaskPage({ api007Mode });
      await waitForTaskPage();
      await user.click(screen.getByRole('button', { name: '自动拆解' }));
      expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
      expect(screen.getByRole('button', { name: '重试原动作' })).toBeVisible();
      cleanup();
    }
  });

  it('maps force-stop and missing resource type to public business errors without partial confirmation', async () => {
    const user = userEvent.setup();
    await renderTaskPage({ scenarioId: 'SCN-05' });
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    expect(await screen.findByText('TOS-IL-001', { exact: false })).toBeVisible();
    expect(screen.getByRole('link', { name: '前往 UI-009 安全联锁' })).toHaveAttribute(
      'href',
      '/safety/interlocks?scenarioId=SCN-05&planId=PLAN-001&from=task-decomposition',
    );
    cleanup();

    const fixture = await renderTaskPage();
    await waitForTaskPage();
    await user.click(screen.getByRole('button', { name: '自动拆解' }));
    act(() => {
      fixture.runtime.store.replaceDomainState((candidate) => {
        candidate.resource.resources = candidate.resource.resources.filter(
          ({ resourceType }) => resourceType !== 'TEAM',
        );
      });
    });
    await user.click(screen.getByRole('button', { name: '确认工单草稿' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: '确认任务定义' }));
    expect(await screen.findByText('DEMO-SCENARIO-001', { exact: false })).toBeVisible();
    expect(fixture.runtime.store.getState().plan.plans.find(({ id }) => id === 'PLAN-001')?.status)
      .toBe('CONFIRMED');
  });
});
