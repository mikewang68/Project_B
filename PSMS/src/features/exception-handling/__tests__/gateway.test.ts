import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';

import type { ApiErrorEnvelope, DispatchException } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { createExceptionHandlingGateway } from '../gateway';

type FetchCall = Readonly<{ input: RequestInfo | URL; init: RequestInit | undefined }>;

const exception = (id = 'EX-001'): DispatchException => ({
  id,
  exceptionNo: id === 'EX-001' ? 'EX-20260716-01' : 'EX-20260716-02',
  type: id === 'EX-001' ? 'DEVICE_OFFLINE' : 'DATA_CONFLICT',
  level: id === 'EX-001' ? 'INFO' : 'MINOR',
  status: id === 'EX-001' ? 'OPEN' : 'ACKNOWLEDGED',
  owner: id === 'EX-001' ? 'TEAM-01' : 'TEAM-02',
  dueAt: '2026-07-16T12:01:00+08:00',
  evidence: ['EVIDENCE-001'],
  version: 1,
  createdAt: '2026-07-16T08:01:00+08:00',
  updatedAt: '2026-07-16T08:01:00+08:00',
});

function successEnvelope(apiId: 'API-014' | 'API-015', items: DispatchException[]) {
  return {
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-014'
        ? 'GET_mock_exceptions'
        : 'POST_mock_exceptions_id_command',
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

describe('ExceptionHandlingGateway', () => {
  it('uses exact API-014/API-015 paths, operation identities, and the frozen C08 command body', async () => {
    const { fetcher, calls } = createFetcher([
      { body: successEnvelope('API-014', [exception(), exception('EX-002')]) },
      { body: successEnvelope('API-015', [exception()]) },
    ]);
    const gateway = createExceptionHandlingGateway(fetcher);

    await gateway.listExceptions();
    await gateway.commandException('EX-001', {
      action: 'ACK',
      reason: '确认设备离线异常',
      owner: 'TEAM-01',
      evidence: ['EVIDENCE-001'],
    });

    expect(calls).toEqual([
      { input: '/mock/exceptions', init: undefined },
      {
        input: '/mock/exceptions/EX-001/command',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            action: 'ACK',
            reason: '确认设备离线异常',
            owner: 'TEAM-01',
            evidence: ['EVIDENCE-001'],
          }),
        },
      },
    ]);
  });

  it('returns strict transport facts without writing the C03 Store', async () => {
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
    const list = successEnvelope('API-014', [exception(), exception('EX-002')]);
    const command = successEnvelope('API-015', [exception()]);
    const { fetcher } = createFetcher([{ body: list }, { body: command }]);
    const gateway = createExceptionHandlingGateway(fetcher);

    await expect(gateway.listExceptions()).resolves.toEqual(list);
    await expect(gateway.commandException('EX-001', {
      action: 'ACK', reason: '确认异常',
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

    await expect(createExceptionHandlingGateway(fetcher).commandException(
      'EX-001', { action: 'ACK', reason: '确认异常' },
    )).resolves.toEqual(failure);
  });

  it.each([
    ['wrong api id', { ...successEnvelope('API-015', [exception()]), data: { ...successEnvelope('API-015', [exception()]).data, apiId: 'API-014' } }],
    ['wrong operation id', { ...successEnvelope('API-015', [exception()]), data: { ...successEnvelope('API-015', [exception()]).data, operationId: 'wrong' } }],
    ['path id mismatch', successEnvelope('API-015', [exception('EX-002')])],
    ['missing command identity', successEnvelope('API-015', [])],
    ['extra command identity', successEnvelope('API-015', [exception(), exception('EX-002')])],
    ['malformed item', { ...successEnvelope('API-014', [exception()]), data: { ...successEnvelope('API-014', [exception()]).data, items: [{ id: 'EX-001' }] } }],
    ['malformed success envelope', { ok: true, data: successEnvelope('API-015', [exception()]).data }],
    ['malformed error envelope', { ok: false, errorCode: 'DEMO-SCENARIO-001', message: 'bad', auditLogId: 'AUD-X', traceId: 'TRACE-X', extra: true }],
  ])('rejects %s', async (_label, body) => {
    const { fetcher } = createFetcher([{ body }]);
    const gateway = createExceptionHandlingGateway(fetcher);
    const request = (body as { data?: { apiId?: string } }).data?.apiId === 'API-014'
      ? gateway.listExceptions()
      : gateway.commandException('EX-001', { action: 'ACK', reason: '确认异常' });

    await expect(request).rejects.toBeInstanceOf(ZodError);
  });

  it('passes network failure through to the page/command boundary', async () => {
    const failure = new TypeError('API-014 network disconnected');
    const fetcher: typeof fetch = async () => { throw failure; };

    await expect(createExceptionHandlingGateway(fetcher).listExceptions()).rejects.toBe(failure);
  });

  it.each([
    ['blank id', '', { action: 'ACK', reason: '确认异常' }],
    ['unknown action', 'EX-001', { action: 'ESCALATE', reason: '禁止新增动作' }],
    ['blank reason', 'EX-001', { action: 'ACK', reason: '   ' }],
    ['non-array evidence', 'EX-001', { action: 'HANDLE', reason: '提交处理', evidence: {} }],
    ['extra field', 'EX-001', { action: 'ACK', reason: '确认异常', workOrderId: 'WO-001' }],
  ])('rejects %s before fetch', async (_label, id, input) => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope('API-015', [exception()]) }]);
    const gateway = createExceptionHandlingGateway(fetcher);

    await expect(gateway.commandException(
      id,
      input as Parameters<typeof gateway.commandException>[1],
    )).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
