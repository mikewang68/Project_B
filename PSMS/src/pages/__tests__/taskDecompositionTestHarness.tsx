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

export type Api007Mode = 'success' | 'failure' | 'malformed' | 'unresolved';

export type TaskPageFixtureOptions = Readonly<{
  roleCode?: RoleCode;
  dataScope?: string[];
  preparation?: 'confirmed-recommendation' | 'plan-only' | 'none';
  api007Mode?: Api007Mode;
  scenarioId?: 'SCN-01' | 'SCN-05';
  planId?: string;
  autoplay?: boolean;
}>;

function failureEnvelope(): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'TOS-EXT-001',
    message: 'API-007 network failure',
    traceId: 'TRACE-PAGE-C06-ERROR',
    auditLogId: 'AUD-PAGE-C06-ERROR',
  };
}

export async function createTaskPageFixture(options: TaskPageFixtureOptions = {}) {
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
  let api007Mode = options.api007Mode ?? 'success';
  let resolveApi007: ((response: Response) => void) | undefined;
  const responsePlan = createFixtureSnapshot().objects['DO-001']
    .find(({ id }) => id === 'PLAN-001');
  const successFor = (path: string): ApiSuccessEnvelope => {
    call += 1;
    const apiId = path.endsWith('/recommendation/confirm')
      ? 'API-006'
      : path.endsWith('/recommendation')
        ? 'API-005'
        : path.endsWith('/decompose')
          ? 'API-007'
          : undefined;
    const operationId = apiId === 'API-005'
      ? 'GET_mock_plans_id_recommendation'
      : apiId === 'API-006'
        ? 'POST_mock_plans_id_recommendation_confirm'
        : apiId === 'API-007'
          ? 'POST_mock_plans_id_decompose'
          : undefined;
    return {
      ok: true,
      data: apiId && operationId
        ? {
            apiId,
            operationId,
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: options.scenarioId ?? 'SCN-01',
            items: responsePlan ? [responsePlan] : [],
          }
        : { status: 'ACCEPTED' },
      traceId: `TRACE-PAGE-C06-${call}`,
      auditLogId: `AUD-PAGE-C06-${call}`,
    };
  };
  const fetcher = vi.fn(async (input: string | URL | Request) => {
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    if (path.endsWith('/decompose')) {
      if (api007Mode === 'failure') {
        return new Response(JSON.stringify(failureEnvelope()), {
          status: 503,
          headers: { 'content-type': 'application/json' },
        });
      }
      if (api007Mode === 'malformed') {
        return new Response(JSON.stringify({
          ok: true,
          data: {},
          traceId: 'TRACE-PAGE-C06-BAD',
          auditLogId: 'AUD-PAGE-C06-BAD',
          extra: true,
        }), { headers: { 'content-type': 'application/json' } });
      }
      if (api007Mode === 'unresolved') {
        return new Promise<Response>((resolve) => {
          resolveApi007 = resolve;
        });
      }
    }
    return new Response(JSON.stringify(successFor(path)), {
      headers: { 'content-type': 'application/json' },
    });
  });
  const runtime = createDemoRuntime(fetcher as typeof fetch);
  const preparation = options.preparation ?? 'confirmed-recommendation';
  if (preparation !== 'none') {
    await runtime.commands.confirmPlan('PLAN-001');
  }
  if (preparation === 'confirmed-recommendation') {
    await runtime.recommendation.commands.calculateRecommendation('PLAN-001');
    const draft = selectReceptionRecommendations(runtime.store.getState(), 'PLAN-001');
    const selected = draft?.candidates.find(({ recommended }) => recommended);
    if (!selected) throw new Error('C05 recommended candidate missing.');
    await runtime.recommendation.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: selected.candidateId,
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
    setApi007Mode: (mode: Api007Mode) => {
      api007Mode = mode;
    },
    resolveApi007Success: () => {
      if (!resolveApi007) throw new Error('No unresolved API-007 request.');
      resolveApi007(new Response(JSON.stringify(successFor('/mock/plans/PLAN-001/decompose')), {
        headers: { 'content-type': 'application/json' },
      }));
    },
  };
}

export async function renderTaskPage(options: TaskPageFixtureOptions = {}) {
  const fixture = await createTaskPageFixture(options);
  const planId = options.planId ?? 'PLAN-001';
  const entry = `/dispatch/plans/${planId}/tasks?scenarioId=${options.scenarioId ?? 'SCN-01'}${options.autoplay ? '&autoplay=1' : ''}`;
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] });
  render(<App router={router} runtime={fixture.runtime} />);
  return { ...fixture, router, entry };
}

export async function waitForTaskPage(): Promise<void> {
  await screen.findByRole('heading', { name: '任务拆解' }, { timeout: 5_000 });
}
