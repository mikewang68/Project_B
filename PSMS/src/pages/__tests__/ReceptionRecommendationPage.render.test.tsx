import { cleanup, render, screen, within } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

function successEnvelope(path: string, call: number): ApiSuccessEnvelope {
  const plan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  const apiId = path.endsWith('/recommendation/confirm') ? 'API-006' : 'API-005';
  return {
    ok: true,
    data: path.includes('/recommendation')
      ? {
          apiId,
          operationId:
            apiId === 'API-005'
              ? 'GET_mock_plans_id_recommendation'
              : 'POST_mock_plans_id_recommendation_confirm',
          now: '2026-07-16T09:00:00+08:00',
          scenarioId: 'SCN-01',
          items: plan ? [plan] : [],
        }
      : { status: 'ACCEPTED' },
    traceId: `TRACE-PAGE-${call}`,
    auditLogId: `AUD-PAGE-${call}`,
  };
}

async function renderReadyRecommendation() {
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({
      actorId: 'USER-001',
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
    }),
  );
  let call = 0;
  const fetcher = vi.fn(async (input: string | URL | Request) => {
    call += 1;
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    return new Response(JSON.stringify(successEnvelope(path, call)), {
      headers: { 'content-type': 'application/json' },
    });
  });
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  await runtime.commands.confirmPlan('PLAN-001');
  await runtime.recommendation.commands.calculateRecommendation('PLAN-001');
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/dispatch/plans/PLAN-001/recommendation'],
  });
  render(<App router={router} runtime={runtime} />);
  await screen.findByRole('heading', { name: '生产准备建议' }, LAZY_ROUTE_TIMEOUT);
  return { fetcher, router, runtime };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-003 生产准备建议内容', () => {
  it('呈现精确页面标记、计划事实和明确的数据未提供项', async () => {
    await renderReadyRecommendation();

    expect(screen.getByText('UI-003')).toBeVisible();
    expect(screen.getByText('当前路由：/dispatch/plans/PLAN-001/recommendation')).toBeVisible();
    for (const label of [
      '计划摘要',
      '候选股道',
      '排除选项',
      '接车时间轴',
      '综合评分',
      '系统推荐',
      '规则版本',
      '权威来源',
      '计划优先级：数据未提供',
      '关联箱量：数据未提供',
    ]) {
      expect(screen.getByText(label, { exact: false })).toBeVisible();
    }

    const summary = screen.getByLabelText('计划摘要');
    for (const fact of [
      'PB-20260716-01',
      '75001',
      '粉煤灰',
      '2026-07-16T08:01:00+08:00',
      '2026-07-16T10:01:00+08:00',
      'T1',
      '已确认',
      '2',
      '铁路到发计划系统',
      '2026-07-16T07:01:00+08:00',
      '无',
    ]) {
      expect(within(summary).getAllByText(fact, { exact: false }).length).toBeGreaterThan(0);
    }
    expect(within(summary).queryByText(/推断|箱量 8|优先级 LOW/)).not.toBeInTheDocument();
  });

  it('按 T1/T3/T2 展示候选、完整分项，并单独解释 T4 排除', async () => {
    await renderReadyRecommendation();

    const candidates = screen.getByLabelText('候选股道');
    expect(
      within(candidates)
        .getAllByRole('button', { name: /选择候选/ })
        .map((button) => button.getAttribute('aria-label')),
    ).toEqual(['选择候选 T1', '选择候选 T3', '选择候选 T2']);
    expect(within(candidates).getByText('100')).toBeVisible();
    for (const label of ['可用性 45', '时序 30', '连续性 15', '权威性 10']) {
      expect(within(candidates).getAllByText(label, { exact: false }).length).toBeGreaterThan(0);
    }
    const excluded = screen.getByLabelText('排除选项');
    expect(within(excluded).getByText('T4', { exact: false })).toBeVisible();
    expect(within(excluded).getByText('股道处于封锁状态')).toBeVisible();
    expect(screen.queryByText('API-007')).not.toBeInTheDocument();
    expect(screen.queryByText('任务拆解工作区')).not.toBeInTheDocument();
  });
});
