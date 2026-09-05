import { cleanup, render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiSuccessEnvelope } from '../../contracts';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

const LAZY_ROUTE_TIMEOUT = { timeout: 5_000 } as const;

function renderForSession(roleCode: RoleCode, dataScope: string[] = ['AREA-A']) {
  const actorId = `E2E-${roleCode}`;
  localStorage.setItem(
    DEMO_SESSION_STORAGE_KEY,
    JSON.stringify({ actorId, roleCode, dataScope, online: true }),
  );
  const plan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  const fetcher = vi.fn(async () => {
    const envelope: ApiSuccessEnvelope = {
      ok: true,
      data: {
        apiId: 'API-005',
        operationId: 'GET_mock_plans_id_recommendation',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: plan ? [plan] : [],
      },
      traceId: 'TRACE-PERMISSION-C05',
      auditLogId: 'AUD-PERMISSION-C05',
    };
    return new Response(JSON.stringify(envelope), {
      headers: { 'content-type': 'application/json' },
    });
  });
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  runtime.store.replaceDomainState((candidate) => {
    const selected = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!selected) throw new Error('PLAN-001 missing.');
    selected.status = 'CONFIRMED';
    selected.version = 2;
    candidate.session.dataScope = [...dataScope];
  });
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/dispatch/plans/PLAN-001/recommendation'],
  });
  render(<App router={router} runtime={runtime} />);
  return { fetcher, runtime };
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('UI-003 路由与数据范围权限', () => {
  it('仅 DISPATCHER 进入推荐页并触发受控 API-005', async () => {
    const { fetcher } = renderForSession('DISPATCHER');

    expect(
      await screen.findByRole('heading', { name: '生产准备建议' }, LAZY_ROUTE_TIMEOUT),
    ).toBeVisible();
    expect(await screen.findByLabelText('候选股道', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it.each(['BUSINESS', 'SHIFT_LEADER', 'INTERFACE_OPS'] as const)(
    '%s 在懒加载页面和 API-005 前进入既有 403',
    async (roleCode) => {
      const { fetcher } = renderForSession(roleCode);

      expect(await screen.findByRole('heading', { name: '403 无权访问' })).toBeVisible();
      expect(screen.getByText('所需权限：plan:recommend')).toBeVisible();
      expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /选择候选/ })).not.toBeInTheDocument();
      expect(fetcher).not.toHaveBeenCalled();
    },
  );

  it('AREA-B 会话进入不泄露计划和候选的 not-found', async () => {
    const { fetcher } = renderForSession('DISPATCHER', ['AREA-B']);

    expect(await screen.findByText('对象已变化或不存在', {}, LAZY_ROUTE_TIMEOUT)).toBeVisible();
    expect(screen.queryByText('PB-20260716-01')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /选择候选/ })).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
});
