import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import type { ApiErrorEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { createDispatchBoardGateway } from '../gateway';

type FetchCall = Readonly<{
  input: RequestInfo | URL;
  init: RequestInit | undefined;
}>;

function successEnvelope(
  apiId: 'API-008' | 'API-009',
  workOrderId = 'C06-WO-PLAN-001-G001-02',
) {
  return {
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-008'
        ? 'POST_mock_work_orders_id_assign'
        : 'POST_mock_work_orders_id_dispatch',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [{ id: workOrderId }],
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

describe('DispatchBoardGateway', () => {
  it('posts the exact frozen API-008 and API-009 paths and transport bodies', async () => {
    const { fetcher, calls } = createFetcher([
      { body: successEnvelope('API-008') },
      { body: successEnvelope('API-009') },
    ]);
    const gateway = createDispatchBoardGateway(fetcher);

    await gateway.assignWorkOrder('C06-WO-PLAN-001-G001-02', {
      resourceId: 'RESOURCE-001',
      reason: '演示资源绑定',
    });
    await gateway.dispatchWorkOrder('C06-WO-PLAN-001-G001-02', {
      target: 'AREA-A',
      simulateReceipt: true,
    });

    expect(calls).toEqual([
      {
        input: '/mock/work-orders/C06-WO-PLAN-001-G001-02/assign',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ resourceId: 'RESOURCE-001', reason: '演示资源绑定' }),
        },
      },
      {
        input: '/mock/work-orders/C06-WO-PLAN-001-G001-02/dispatch',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ target: 'AREA-A', simulateReceipt: true }),
        },
      },
    ]);
  });

  it('returns strict transport identity without mutating the C03 Store', async () => {
    const session: DemoSessionSeed = {
      actorId: 'USER-DISPATCHER',
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      online: true,
      shiftId: 'SHIFT-001',
      scenarioId: 'SCN-01',
    };
    const store = createDemoStore(createFixtureSnapshot(), session);
    const before = structuredClone(store.getState());
    const success = successEnvelope('API-008');
    const { fetcher } = createFetcher([{ body: success }]);

    await expect(createDispatchBoardGateway(fetcher).assignWorkOrder(
      'C06-WO-PLAN-001-G001-02',
      { resourceId: 'RESOURCE-001' },
    )).resolves.toEqual(success);
    expect(store.getState()).toEqual(before);
  });

  it('returns a strict failure envelope unchanged', async () => {
    const failure: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'version conflict',
      details: { expected: 2, actual: 3 },
      auditLogId: 'AUD-409',
      traceId: 'TRACE-409',
    };
    const { fetcher } = createFetcher([{ body: failure, status: 409 }]);

    await expect(createDispatchBoardGateway(fetcher).dispatchWorkOrder(
      'C06-WO-PLAN-001-G001-02',
      { target: 'AREA-A', simulateReceipt: true },
    )).resolves.toEqual(failure);
  });

  it.each([
    ['wrong apiId', { ...successEnvelope('API-008'), data: { ...successEnvelope('API-008').data, apiId: 'API-009' } }],
    ['wrong operationId', {
      ...successEnvelope('API-008'),
      data: { ...successEnvelope('API-008').data, operationId: 'wrong' },
    }],
    ['wrong workOrder id', {
      ...successEnvelope('API-008'),
      data: { ...successEnvelope('API-008').data, items: [{ id: 'C06-WO-OTHER' }] },
    }],
    ['zero identities', {
      ...successEnvelope('API-008'),
      data: { ...successEnvelope('API-008').data, items: [] },
    }],
    ['extra identity field', {
      ...successEnvelope('API-008'),
      data: { ...successEnvelope('API-008').data, items: [{ id: 'C06-WO-PLAN-001-G001-02', version: 2 }] },
    }],
    ['extra success data', {
      ...successEnvelope('API-008'),
      data: { ...successEnvelope('API-008').data, resources: [] },
    }],
    ['malformed success envelope', { ok: true, data: successEnvelope('API-008').data }],
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
    await expect(createDispatchBoardGateway(fetcher).assignWorkOrder(
      'C06-WO-PLAN-001-G001-02',
      { resourceId: 'RESOURCE-001' },
    )).rejects.toBeInstanceOf(ZodError);
  });

  it('passes a network failure through to the command boundary', async () => {
    const networkFailure = new TypeError('API-009 network disconnected');
    const fetcher: typeof fetch = async () => { throw networkFailure; };

    await expect(createDispatchBoardGateway(fetcher).dispatchWorkOrder(
      'C06-WO-PLAN-001-G001-02',
      { target: 'AREA-A', simulateReceipt: true },
    )).rejects.toBe(networkFailure);
  });

  it.each([
    ['blank path id', '', { resourceId: 'RESOURCE-001' }],
    ['extra API-008 context', 'C06-WO-PLAN-001-G001-02', {
      resourceId: 'RESOURCE-001', expectedVersion: 2,
    }],
    ['invalid API-008 resource id', 'C06-WO-PLAN-001-G001-02', { resourceId: 1 }],
  ])('rejects %s before fetch', async (_label, workOrderId, input) => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope('API-008') }]);
    const gateway = createDispatchBoardGateway(fetcher);

    await expect(gateway.assignWorkOrder(
      workOrderId,
      input as unknown as Parameters<typeof gateway.assignWorkOrder>[1],
    )).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
