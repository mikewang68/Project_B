import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';

import type { ApiErrorEnvelope, Interlock } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { createSafetyInterlockGateway } from '../gateway';

type FetchCall = Readonly<{ input: RequestInfo | URL; init: RequestInit | undefined }>;

const interlock = (id = 'IL-001'): Interlock => ({
  id,
  interlockNo: id === 'IL-001' ? 'IL-20260716-01' : 'IL-20260716-02',
  riskType: id === 'IL-001' ? 'PERSON_INTRUSION' : 'OVERLOAD',
  actionLevel: id === 'IL-001' ? 'WARN' : 'PAUSE',
  status: id === 'IL-001' ? 'LOCKED' : 'RESET_REQUESTED',
  inputSnapshot: { sensor: id === 'IL-001' ? 'SENSOR-01' : 'SENSOR-02', value: true },
  receiptStatus: id === 'IL-001' ? 'PENDING' : 'RECEIVED',
  resetRequest: { requested: id !== 'IL-001' },
  approvalChain: [id === 'IL-001' ? 'USER-001' : 'USER-002'],
  version: 1,
  createdAt: '2026-07-16T08:01:00+08:00',
  updatedAt: '2026-07-16T08:01:00+08:00',
});

function successEnvelope(apiId: 'API-016' | 'API-017', items: Interlock[]) {
  return {
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-016'
        ? 'GET_mock_interlocks'
        : 'POST_mock_interlocks_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items,
    },
    auditLogId: 'AUD-0001',
    traceId: 'TRACE-0001',
  } as const;
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

describe('SafetyInterlockGateway', () => {
  it('uses exact API-016/API-017 paths, identities, and the frozen C09 command body', async () => {
    const { fetcher, calls } = createFetcher([
      { body: successEnvelope('API-016', [interlock(), interlock('IL-002')]) },
      { body: successEnvelope('API-017', [interlock()]) },
    ]);
    const gateway = createSafetyInterlockGateway(fetcher);

    await gateway.listInterlocks();
    await gateway.commandInterlock('IL-001', {
      action: 'REQUEST_RESET',
      reason: '现场条件已核验，申请复位',
      approvalUserId: 'USER-002',
      resetRequest: { requested: true, checklist: ['现场清场', '传感器复核'] },
    });

    expect(calls).toEqual([
      { input: '/mock/interlocks', init: undefined },
      {
        input: '/mock/interlocks/IL-001/command',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            action: 'REQUEST_RESET',
            reason: '现场条件已核验，申请复位',
            approvalUserId: 'USER-002',
            resetRequest: { requested: true, checklist: ['现场清场', '传感器复核'] },
          }),
        },
      },
    ]);
  });

  it('returns strict transport facts without writing the C03 Store', async () => {
    const session: DemoSessionSeed = {
      actorId: 'USER-SAFETY',
      roleCode: 'SAFETY',
      dataScope: ['AREA-A'],
      online: true,
      shiftId: 'SHIFT-001',
      scenarioId: 'SCN-01',
    };
    const store = createDemoStore(createFixtureSnapshot(), session);
    const before = structuredClone(store.getState());
    const list = successEnvelope('API-016', [interlock(), interlock('IL-002')]);
    const command = successEnvelope('API-017', [interlock()]);
    const { fetcher } = createFetcher([{ body: list }, { body: command }]);
    const gateway = createSafetyInterlockGateway(fetcher);

    await expect(gateway.listInterlocks()).resolves.toEqual(list);
    await expect(gateway.commandInterlock('IL-001', {
      action: 'REQUEST_RESET', reason: '申请复位',
    })).resolves.toEqual(command);
    expect(store.getState()).toEqual(before);
  });

  it('returns a strict error envelope unchanged', async () => {
    const failure: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'version conflict',
      details: { expected: 1, actual: 2 },
      auditLogId: 'AUD-409',
      traceId: 'TRACE-409',
    };
    const { fetcher } = createFetcher([{ body: failure, status: 409 }]);

    await expect(createSafetyInterlockGateway(fetcher).commandInterlock(
      'IL-001', { action: 'REQUEST_RESET', reason: '申请复位' },
    )).resolves.toEqual(failure);
  });

  it.each([
    ['wrong api id', {
      ...successEnvelope('API-017', [interlock()]),
      data: { ...successEnvelope('API-017', [interlock()]).data, apiId: 'API-016' },
    }, 'command'],
    ['wrong operation id', {
      ...successEnvelope('API-017', [interlock()]),
      data: { ...successEnvelope('API-017', [interlock()]).data, operationId: 'wrong' },
    }, 'command'],
    ['path id mismatch', successEnvelope('API-017', [interlock('IL-002')]), 'command'],
    ['missing command identity', successEnvelope('API-017', []), 'command'],
    ['extra command identity', successEnvelope('API-017', [interlock(), interlock('IL-002')]), 'command'],
    ['malformed item', {
      ...successEnvelope('API-016', [interlock()]),
      data: { ...successEnvelope('API-016', [interlock()]).data, items: [{ id: 'IL-001' }] },
    }, 'list'],
    ['malformed success envelope', {
      ok: true,
      data: successEnvelope('API-017', [interlock()]).data,
    }, 'command'],
    ['malformed error envelope', {
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: 'bad',
      auditLogId: 'AUD-X',
      traceId: 'TRACE-X',
      extra: true,
    }, 'command'],
  ])('rejects %s', async (_label, body, method) => {
    const { fetcher } = createFetcher([{ body }]);
    const gateway = createSafetyInterlockGateway(fetcher);
    const request = method === 'list'
      ? gateway.listInterlocks()
      : gateway.commandInterlock('IL-001', { action: 'REQUEST_RESET', reason: '申请复位' });

    await expect(request).rejects.toBeInstanceOf(ZodError);
  });

  it.each(['list', 'command'])('passes %s network failure through to its caller', async (method) => {
    const failure = new TypeError(`API-01${method === 'list' ? '6' : '7'} network disconnected`);
    const fetcher: typeof fetch = async () => { throw failure; };
    const gateway = createSafetyInterlockGateway(fetcher);
    const request = method === 'list'
      ? gateway.listInterlocks()
      : gateway.commandInterlock('IL-001', { action: 'REQUEST_RESET', reason: '申请复位' });

    await expect(request).rejects.toBe(failure);
  });

  it.each([
    ['blank id', '', { action: 'REQUEST_RESET', reason: '申请复位' }],
    ['unknown action', 'IL-001', { action: 'FORCE_RESTORE', reason: '禁止新增动作' }],
    ['blank reason', 'IL-001', { action: 'REQUEST_RESET', reason: '   ' }],
    ['blank approval user', 'IL-001', {
      action: 'APPROVE', reason: '审批', approvalUserId: '  ',
    }],
    ['non-object reset request', 'IL-001', {
      action: 'REQUEST_RESET', reason: '申请复位', resetRequest: [],
    }],
    ['extra field', 'IL-001', {
      action: 'REQUEST_RESET', reason: '申请复位', exceptionId: 'EX-003',
    }],
  ])('rejects %s before fetch', async (_label, id, input) => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope('API-017', [interlock()]) }]);
    const gateway = createSafetyInterlockGateway(fetcher);

    await expect(gateway.commandInterlock(
      id,
      input as Parameters<typeof gateway.commandInterlock>[1],
    )).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
