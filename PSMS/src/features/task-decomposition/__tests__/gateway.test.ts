import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import { do001Schema, type ApiErrorEnvelope, type Plan } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { TASK_DECOMPOSITION_RULE_VERSION } from '../constants';
import { createTaskDecompositionGateway } from '../gateway';

type FetchCall = Readonly<{
  input: RequestInfo | URL;
  init: RequestInit | undefined;
}>;

function fixturePlan(planId = 'PLAN-001'): Plan {
  const raw = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === planId);
  if (!raw) throw new Error(`Missing fixture Plan ${planId}.`);
  return do001Schema.parse(raw);
}

function successEnvelope(plan: Plan = fixturePlan()) {
  return {
    ok: true,
    data: {
      apiId: 'API-007',
      operationId: 'POST_mock_plans_id_decompose',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [plan],
    },
    auditLogId: 'AUD-0001',
    traceId: 'TRACE-0001',
  };
}

function createFetcher(payloads: Array<{ body: unknown; status?: number }>) {
  const calls: FetchCall[] = [];
  let index = 0;
  const fetcher: typeof fetch = async (input, init) => {
    calls.push({ input, init });
    const payload = payloads[index++];
    if (!payload) throw new Error('Missing fetch payload.');
    return new Response(JSON.stringify(payload.body), {
      status: payload.status ?? 200,
      headers: { 'content-type': 'application/json' },
    });
  };
  return { fetcher, calls };
}

describe('TaskDecompositionGateway', () => {
  it('posts only the exact frozen API-007 ruleVersion and mode body', async () => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope() }]);

    await createTaskDecompositionGateway(fetcher).decomposePlan('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'AUTO',
    });

    expect(calls).toEqual([{
      input: '/mock/plans/PLAN-001/decompose',
      init: {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
          mode: 'AUTO',
        }),
      },
    }]);
  });

  it('returns a strict API failure envelope without writing or reshaping it', async () => {
    const failure: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'version conflict',
      details: { expected: 1, actual: 2 },
      auditLogId: 'AUD-409',
      traceId: 'TRACE-409',
    };
    const { fetcher } = createFetcher([{ body: failure, status: 409 }]);

    await expect(createTaskDecompositionGateway(fetcher).decomposePlan('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'CONFIRM',
    })).resolves.toEqual(failure);
  });

  it.each([
    ['wrong apiId', { ...successEnvelope(), data: { ...successEnvelope().data, apiId: 'API-006' } }],
    ['wrong operationId', { ...successEnvelope(), data: { ...successEnvelope().data, operationId: 'wrong' } }],
    ['wrong Plan id', { ...successEnvelope(), data: { ...successEnvelope().data, items: [fixturePlan('PLAN-002')] } }],
    ['zero Plans', { ...successEnvelope(), data: { ...successEnvelope().data, items: [] } }],
    ['two Plans', { ...successEnvelope(), data: { ...successEnvelope().data, items: [fixturePlan(), fixturePlan()] } }],
    ['extra success data', { ...successEnvelope(), data: { ...successEnvelope().data, workOrders: [] } }],
    ['malformed success envelope', { ok: true, data: successEnvelope().data }],
    ['malformed error envelope', {
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: 'bad',
      auditLogId: 'AUD-X',
      traceId: 'TRACE-X',
      extra: true,
    }],
  ])('rejects %s', async (_label, body) => {
    const { fetcher } = createFetcher([{ body }]);
    await expect(createTaskDecompositionGateway(fetcher).decomposePlan('PLAN-001', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'AUTO',
    })).rejects.toBeInstanceOf(ZodError);
  });

  it.each([
    ['extra command context', {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: 'SPLIT',
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      reason: 'not transport data',
    }],
    ['numeric ruleVersion', { ruleVersion: 1, mode: 'AUTO' }],
    ['invalid mode', { ruleVersion: TASK_DECOMPOSITION_RULE_VERSION, mode: 'EDIT' }],
  ])('rejects %s before fetch', async (_label, input) => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope() }]);
    const gateway = createTaskDecompositionGateway(fetcher);

    await expect(gateway.decomposePlan(
      'PLAN-001',
      input as unknown as Parameters<typeof gateway.decomposePlan>[1],
    )).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
