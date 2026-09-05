import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime, DemoRuntimeProvider } from '../../runtime';
import { appTheme } from '../../styles/theme';
import ReceptionRecommendationPage from '../dispatch/ReceptionRecommendationPage';

type Api005Mode = 'success' | 'failure' | 'malformed';

function apiError(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Recommendation failed with ${errorCode}`,
    traceId: 'TRACE-C05-ERROR',
    auditLogId: 'AUD-C05-ERROR',
  };
}

function createPageFixture(options: Readonly<{
  actorId?: string;
  dataScope?: string[];
  confirmed?: boolean;
  api005Mode?: Api005Mode;
}> = {}) {
  const actorId = options.actorId ?? 'USER-001';
  const dataScope = options.dataScope ?? ['AREA-A'];
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({ actorId, roleCode: 'DISPATCHER', dataScope, online: true }),
  );
  const snapshot = createFixtureSnapshot();
  const responsePlan = snapshot.objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    if (path.endsWith('/recommendation') && options.api005Mode === 'failure') {
      return new Response(JSON.stringify(apiError('TOS-EXT-001')), {
        status: 409,
        headers: { 'content-type': 'application/json' },
      });
    }
    if (path.endsWith('/recommendation') && options.api005Mode === 'malformed') {
      return new Response(
        JSON.stringify({ ok: true, data: {}, traceId: 'TRACE-BAD', auditLogId: 'AUD-BAD', extra: true }),
        { headers: { 'content-type': 'application/json' } },
      );
    }
    const apiId = path.endsWith('/recommendation/confirm') ? 'API-006' : 'API-005';
    const envelope: ApiSuccessEnvelope = {
      ok: true,
      data: {
        apiId,
        operationId:
          apiId === 'API-005'
            ? 'GET_mock_plans_id_recommendation'
            : 'POST_mock_plans_id_recommendation_confirm',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: responsePlan ? [responsePlan] : [],
      },
      traceId: `TRACE-${apiId}`,
      auditLogId: `AUD-${apiId}`,
    };
    return new Response(JSON.stringify(envelope), {
      headers: { 'content-type': 'application/json' },
    });
  });
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  runtime.store.replaceDomainState((candidate) => {
    candidate.session.actorId = actorId;
    candidate.session.dataScope = [...dataScope];
    const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!plan) throw new Error('PLAN-001 missing.');
    if (options.confirmed !== false) {
      plan.status = 'CONFIRMED';
      plan.version = 2;
    }
  });
  return { fetcher, runtime };
}

function renderPage(
  initialEntry = '/dispatch/plans/PLAN-001/recommendation',
  fixture = createPageFixture(),
) {
  const router = createMemoryRouter(
    [{ path: '/dispatch/plans/:planId/recommendation', element: <ReceptionRecommendationPage /> }],
    { initialEntries: [initialEntry] },
  );
  render(
    <DemoRuntimeProvider runtime={fixture.runtime}>
      <ConfigProvider theme={appTheme}>
        <RouterProvider router={router} />
      </ConfigProvider>
    </DemoRuntimeProvider>,
  );
  return { router, ...fixture };
}

async function openAlternativeDrawer(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: '选择候选 T3' }));
  await user.click(screen.getByRole('button', { name: '调整并确认' }));
  const drawer = await screen.findByRole('dialog');
  return drawer;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-003 推荐动作', () => {
  it('自动计算、默认选择 T1、展示规则说明并确认后开放 UI-004 链接', async () => {
    const user = userEvent.setup();
    const { runtime } = renderPage();

    const t1 = await screen.findByRole('button', { name: '选择候选 T1' });
    await waitFor(() => expect(runtime.recommendation.workflow.getState().selectedCandidateId).toContain('TRACK-001'));
    expect(t1).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: '查看规则说明' }));
    expect(await screen.findByText('仅供演示解释，不构成生产调度承诺')).toBeVisible();
    await user.click(screen.getByRole('button', { name: '关闭规则说明' }));

    await user.click(screen.getByRole('button', { name: '确认推荐' }));
    const drawer = await screen.findByRole('dialog');
    await user.click(within(drawer).getByRole('button', { name: '提交确认' }));

    expect(await screen.findByRole('link', { name: '进入任务拆解' })).toHaveAttribute(
      'href',
      '/dispatch/plans/PLAN-001/tasks',
    );
    expect(runtime.store.getState().configAudit.commandAudit.at(-1)?.record.action).toBe('RC-04');
  });

  it('T3 高风险调整拒绝本人复核、保留表单，再接受 USER-001', async () => {
    const user = userEvent.setup();
    const fixture = createPageFixture({ actorId: 'USER-002' });
    const { runtime } = renderPage(undefined, fixture);
    const drawer = await openAlternativeDrawer(user);

    await user.click(within(drawer).getByRole('button', { name: '提交确认' }));
    expect(await within(drawer).findByText('请输入调整原因')).toBeInTheDocument();
    expect(within(drawer).getByText('请选择异人复核员')).toBeInTheDocument();
    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '错峰释放 T1，采用 T3');
    await user.click(within(drawer).getByRole('combobox', { name: '异人复核员' }));
    await user.click(
      await screen.findByText(/USER-002.*本人，不能复核/, {
        selector: '.ant-select-item-option-content',
      }),
    );
    await user.click(within(drawer).getByRole('button', { name: '提交确认' }));

    expect(await within(drawer).findByText('TOS-AUTH-001', { exact: false })).toBeVisible();
    expect(within(drawer).getByRole('textbox', { name: '调整原因' })).toHaveValue(
      '错峰释放 T1，采用 T3',
    );
    await user.click(within(drawer).getByRole('combobox', { name: '异人复核员' }));
    await user.click(
      await screen.findByText(/^USER-001/, { selector: '.ant-select-item-option-content' }),
    );
    await waitFor(() =>
      expect(within(drawer).getByRole('button', { name: /提交确认/ })).toBeEnabled(),
    );
    await user.click(within(drawer).getByRole('button', { name: /提交确认/ }));

    expect(await screen.findByRole('link', { name: '进入任务拆解' })).toBeVisible();
    expect(
      screen.getByText('调整溯源：T1 → T3 · 原因：错峰释放 T1，采用 T3 · 复核员：USER-001'),
    ).toBeVisible();
    expect(runtime.store.getState().recommendation.drafts['PLAN-001']).toMatchObject({
      status: 'CONFIRMED',
      adjustment: { reviewerId: 'USER-001', reason: '错峰释放 T1，采用 T3' },
    });
  });

  it('版本变化显示 DEMO-VERSION-001，保留选择与表单并提供重新计算', async () => {
    const user = userEvent.setup();
    const { runtime } = renderPage(undefined, createPageFixture({ actorId: 'USER-002' }));
    const drawer = await openAlternativeDrawer(user);
    await user.type(within(drawer).getByRole('textbox', { name: '调整原因' }), '保留版本冲突输入');
    await user.click(within(drawer).getByRole('combobox', { name: '异人复核员' }));
    await user.click(
      await screen.findByText(/^USER-001/, { selector: '.ant-select-item-option-content' }),
    );
    act(() => {
      runtime.store.replaceDomainState((candidate) => {
        const track = candidate.resource.tracks.find(({ trackNo }) => trackNo === 'T3');
        if (!track) throw new Error('T3 missing.');
        track.version += 1;
      });
    });
    await user.click(within(drawer).getByRole('button', { name: '提交确认' }));

    expect(await within(drawer).findByText('DEMO-VERSION-001', { exact: false })).toBeVisible();
    expect(within(drawer).getByRole('textbox', { name: '调整原因' })).toHaveValue(
      '保留版本冲突输入',
    );
    expect(runtime.recommendation.workflow.getState().selectedCandidateId).toContain('TRACK-003');
    expect(within(drawer).getByRole('button', { name: '重新计算' })).toBeVisible();
  });

  it.each(['failure', 'malformed'] as const)('API-005 %s 进入 network-error', async (api005Mode) => {
    renderPage(undefined, createPageFixture({ api005Mode }));

    expect(await screen.findByText('计划接口暂不可用')).toBeVisible();
    expect(screen.getByRole('button', { name: /重新计算/ })).toBeVisible();
  });

  it('未确认计划进入 business-error，未知计划进入 not-found', async () => {
    renderPage(undefined, createPageFixture({ confirmed: false }));
    expect(await screen.findByText('业务处理失败')).toBeVisible();
    cleanup();

    renderPage('/dispatch/plans/PLAN-UNKNOWN/recommendation');
    expect(await screen.findByText('对象已变化或不存在')).toBeVisible();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
  });

  it('空候选显示全部硬排除原因而不是空白页', async () => {
    const fixture = createPageFixture();
    act(() => {
      fixture.runtime.store.replaceDomainState((candidate) => {
        for (const track of candidate.resource.tracks) track.occupyStatus = 'BLOCKED';
      });
    });
    renderPage(undefined, fixture);

    expect(await screen.findByText('暂无可推荐股道')).toBeVisible();
    expect(screen.getAllByText('股道处于封锁状态')).toHaveLength(4);
    expect(screen.getByRole('button', { name: /重新计算/ })).toBeVisible();
  });
});
