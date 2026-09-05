import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import {
  api006RequestSchema,
  do001Schema,
  type ApiErrorEnvelope,
  type Plan,
} from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createRecommendationGateway } from '../gateway';

type FetchCall = {
  input: RequestInfo | URL;
  init: RequestInit | undefined;
};

function frozenPlan(planId = 'PLAN-001'): Plan {
  const plan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === planId);
  if (!plan) throw new Error(`Missing fixture plan ${planId}`);
  return do001Schema.parse(plan);
}

function successEnvelope(apiId: 'API-005' | 'API-006', plan = frozenPlan()) {
  return {
    ok: true,
    data: {
      apiId,
      operationId:
        apiId === 'API-005'
          ? 'GET_mock_plans_id_recommendation'
          : 'POST_mock_plans_id_recommendation_confirm',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [plan],
    },
    auditLogId: 'AUD-0001',
    traceId: 'TRACE-0001',
  };
}

function createFetcher(payloads: Array<{ body: unknown; status?: number }>): {
  fetcher: typeof fetch;
  calls: FetchCall[];
} {
  const calls: FetchCall[] = [];
  let responseIndex = 0;
  const fetcher: typeof fetch = async (input, init) => {
    calls.push({ input, init });
    const payload = payloads[responseIndex++];
    if (!payload) throw new Error('Missing fetch payload.');
    return new Response(JSON.stringify(payload.body), {
      status: payload.status ?? 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  return { fetcher, calls };
}

describe('RecommendationGateway', () => {
  it('sends the exact API-005 query and strict API-006 body', async () => {
    const { fetcher, calls } = createFetcher([
      { body: successEnvelope('API-005') },
      { body: successEnvelope('API-006') },
    ]);
    const gateway = createRecommendationGateway(fetcher);
    const confirmation = {
      trackNo: 'T1',
      window: '2026-07-16T08:01:00+08:00/2026-07-16T10:01:00+08:00',
    };

    await gateway.getRecommendation('PLAN-001', 2);
    await gateway.confirmRecommendation('PLAN-001', confirmation);

    expect(calls).toEqual([
      {
        input: '/mock/plans/PLAN-001/recommendation?inputVersion=2',
        init: undefined,
      },
      {
        input: '/mock/plans/PLAN-001/recommendation/confirm',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(confirmation),
        },
      },
    ]);
  });

  it('returns a strict failure envelope unchanged', async () => {
    const failure: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'version conflict',
      details: { expected: 1, actual: 2 },
      auditLogId: 'AUD-409',
      traceId: 'TRACE-409',
    };
    const { fetcher } = createFetcher([{ body: failure, status: 409 }]);

    await expect(createRecommendationGateway(fetcher).getRecommendation('PLAN-001', 2)).resolves.toEqual(
      failure,
    );
  });

  it.each([
    [
      'wrong API id',
      { ...successEnvelope('API-005'), data: { ...successEnvelope('API-005').data, apiId: 'API-006' } },
    ],
    [
      'wrong operation id',
      {
        ...successEnvelope('API-005'),
        data: { ...successEnvelope('API-005').data, operationId: 'wrong_operation' },
      },
    ],
    [
      'absent matching plan',
      { ...successEnvelope('API-005'), data: { ...successEnvelope('API-005').data, items: [frozenPlan('PLAN-002')] } },
    ],
    [
      'extra data property',
      { ...successEnvelope('API-005'), data: { ...successEnvelope('API-005').data, extra: true } },
    ],
    ['malformed success envelope', { ok: true, data: successEnvelope('API-005').data }],
    [
      'malformed failure envelope',
      {
        ok: false,
        errorCode: 'DEMO-VERSION-001',
        message: 'bad',
        auditLogId: 'AUD-X',
        traceId: 'TRACE-X',
        extra: true,
      },
    ],
  ])('rejects %s', async (_label, body) => {
    const { fetcher } = createFetcher([{ body }]);
    await expect(createRecommendationGateway(fetcher).getRecommendation('PLAN-001', 2)).rejects.toBeInstanceOf(
      ZodError,
    );
  });

  it('rejects reviewer and version context before API-006 transport', async () => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope('API-006') }]);
    const gateway = createRecommendationGateway(fetcher);
    const invalid = {
      trackNo: 'T1',
      window: '2026-07-16T08:01:00+08:00/2026-07-16T10:01:00+08:00',
      reviewerId: 'USER-001',
      inputPlanVersion: 2,
    } as unknown as Parameters<typeof gateway.confirmRecommendation>[1];

    expect(api006RequestSchema.safeParse(invalid).success).toBe(false);
    await expect(gateway.confirmRecommendation('PLAN-001', invalid)).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
