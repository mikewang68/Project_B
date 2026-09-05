import { render, screen } from '@testing-library/react';
import { createMemoryRouter } from 'react-router-dom';
import { vi } from 'vitest';

import App from '../../app/App';
import { appRoutes } from '../../app/router';
import { DEMO_SESSION_STORAGE_KEY, type RoleCode } from '../../auth';
import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../contracts';
import { selectReceptionRecommendations } from '../../features/recommendation';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { createDemoRuntime } from '../../runtime';

export type DispatchApiMode = 'success' | 'failure' | 'malformed' | 'unresolved';

export type DispatchPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  dataScope?: string[];
  preparation?: 'ready' | 'empty' | 'business-error' | 'none';
  api008Mode?: DispatchApiMode;
  api009Mode?: DispatchApiMode;
  scenarioId?: 'SCN-01' | 'SCN-05';
  planId?: string;
  autoplay?: boolean;
}>;

function networkFailure(apiId: 'API-008' | 'API-009'): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'TOS-EXT-001',
    message: `${apiId} network failure`,
    traceId: `TRACE-PAGE-${apiId}-ERROR`,
    auditLogId: `AUD-PAGE-${apiId}-ERROR`,
  };
}

export async function createDispatchPageFixture(options: DispatchPageFixtureOptions = {}) {
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
  let activeScenarioId = 'SCN-01';
  let api008Mode = options.api008Mode ?? 'success';
  let api009Mode = options.api009Mode ?? 'success';
  let resolveDispatch: ((response: Response) => void) | undefined;
  const responsePlan = createFixtureSnapshot().objects['DO-001']
    .find(({ id }) => id === 'PLAN-001');

  const successFor = (path: string): ApiSuccessEnvelope => {
    call += 1;
    const workOrderMatch = path.match(/\/mock\/work-orders\/([^/]+)\/(assign|dispatch)$/);
    const apiId = path.endsWith('/recommendation/confirm')
      ? 'API-006'
      : path.endsWith('/recommendation')
        ? 'API-005'
        : path.endsWith('/decompose')
          ? 'API-007'
          : path.endsWith('/assign')
            ? 'API-008'
            : path.endsWith('/dispatch')
              ? 'API-009'
              : undefined;
    const operationId = apiId === 'API-005'
      ? 'GET_mock_plans_id_recommendation'
      : apiId === 'API-006'
        ? 'POST_mock_plans_id_recommendation_confirm'
        : apiId === 'API-007'
          ? 'POST_mock_plans_id_decompose'
          : apiId === 'API-008'
            ? 'POST_mock_work_orders_id_assign'
            : apiId === 'API-009'
              ? 'POST_mock_work_orders_id_dispatch'
              : undefined;
    return {
      ok: true,
      data: apiId && operationId
        ? {
            apiId,
            operationId,
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: activeScenarioId,
            items: workOrderMatch
              ? [{ id: decodeURIComponent(workOrderMatch[1]!) }]
              : responsePlan ? [responsePlan] : [],
          }
        : { status: 'ACCEPTED' },
      traceId: `TRACE-PAGE-C07-${call}`,
      auditLogId: `AUD-PAGE-C07-${call}`,
    };
  };

  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    const apiId = path.endsWith('/assign') ? 'API-008' : path.endsWith('/dispatch') ? 'API-009' : undefined;
    const mode = apiId === 'API-008' ? api008Mode : apiId === 'API-009' ? api009Mode : 'success';
    if (apiId && mode === 'failure') {
      return new Response(JSON.stringify(networkFailure(apiId)), {
        status: 503,
        headers: { 'content-type': 'application/json' },
      });
    }
    if (apiId && mode === 'malformed') {
      return new Response(JSON.stringify({
        ok: true,
        data: {},
        traceId: `TRACE-PAGE-${apiId}-BAD`,
        auditLogId: `AUD-PAGE-${apiId}-BAD`,
        extra: true,
      }), { headers: { 'content-type': 'application/json' } });
    }
    if (apiId && mode === 'unresolved') {
      return new Promise<Response>((resolve) => { resolveDispatch = resolve; });
    }
    return new Response(JSON.stringify(successFor(path)), {
      headers: { 'content-type': 'application/json' },
    });
  });

  const runtime = createDemoRuntime(fetcher as typeof fetch);
  const preparation = options.preparation ?? 'ready';
  if (preparation !== 'none') {
    await runtime.commands.confirmPlan('PLAN-001');
    await runtime.recommendation.commands.calculateRecommendation('PLAN-001');
    const draft = selectReceptionRecommendations(runtime.store.getState(), 'PLAN-001');
    const selected = draft?.candidates.find(({ recommended }) => recommended);
    if (!selected) throw new Error('C05 recommended candidate missing.');
    await runtime.recommendation.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: selected.candidateId,
    });
  }
  if (preparation === 'ready' || preparation === 'empty') {
    await runtime.taskDecomposition.commands.generateTasks('PLAN-001');
    await runtime.taskDecomposition.commands.confirmTasks('PLAN-001');
  }
  if (preparation === 'empty') {
    runtime.store.replaceDomainState((candidate) => {
      candidate.workOrder.workOrders = candidate.workOrder.workOrders.filter(
        ({ id }) => !id.startsWith('C06-WO-'),
      );
      candidate.workOrder.nodes = candidate.workOrder.nodes.filter(
        ({ id }) => !id.startsWith('C06-NODE-'),
      );
    });
  }
  if (options.roleCode && options.roleCode !== 'DISPATCHER') {
    runtime.commands.switchRole(options.roleCode);
  }
  if (options.dataScope) {
    runtime.store.replaceDomainState((candidate) => {
      candidate.session.dataScope = [...options.dataScope!];
    });
  }
  if (options.scenarioId === 'SCN-05') {
    activeScenarioId = 'SCN-05';
    runtime.store.replaceDomainState((candidate) => {
      const scenario = candidate.scenario.scenarios.find(({ id }) => id === 'SCN-05');
      if (!scenario) throw new Error('SCN-05 missing.');
      candidate.session.scenarioId = scenario.id;
      candidate.session.demoTime = scenario.clock;
      candidate.scenario.activeScenarioId = scenario.id;
      candidate.scenario.activeFault = structuredClone(scenario.fault);
      candidate.scenario.resetPoint = scenario.resetPoint;
    });
  }

  return {
    fetcher,
    runtime,
    setApi008Mode: (mode: DispatchApiMode) => { api008Mode = mode; },
    setApi009Mode: (mode: DispatchApiMode) => { api009Mode = mode; },
    resolveDispatchSuccess: (path = `/mock/work-orders/C06-WO-PLAN-001-G001-02/assign`) => {
      if (!resolveDispatch) throw new Error('No unresolved dispatch request.');
      resolveDispatch(new Response(JSON.stringify(successFor(path)), {
        headers: { 'content-type': 'application/json' },
      }));
    },
  };
}

export async function renderDispatchPage(options: DispatchPageFixtureOptions = {}) {
  const fixture = await createDispatchPageFixture(options);
  const planId = options.planId ?? 'PLAN-001';
  const scenarioId = options.scenarioId ?? 'SCN-01';
  const entry = `/dispatch/work-orders?planId=${planId}&scenarioId=${scenarioId}&from=task-decomposition${options.autoplay ? '&autoplay=1' : ''}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForDispatchPage(): Promise<void> {
  await screen.findByRole('heading', { name: '派工看板' }, { timeout: 5_000 });
}
