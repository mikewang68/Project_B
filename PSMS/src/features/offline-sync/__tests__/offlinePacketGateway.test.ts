import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';

import type { ApiErrorEnvelope, OfflinePacket } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { createOfflinePacketGateway } from '../offlinePacketGateway';

type FetchCall = Readonly<{ input: RequestInfo | URL; init: RequestInit | undefined }>;

const packet = (id = 'OFF-001'): OfflinePacket => ({
  id,
  offlinePackageNo: id === 'OFF-001' ? 'OFF-PKG-001' : 'OFF-PKG-004',
  terminalId: id === 'OFF-001' ? 'PDA-01' : 'PDA-04',
  workOrderNo: id === 'OFF-001' ? 'WO-006' : 'WO-009',
  packageVersion: id === 'OFF-001' ? 2 : 5,
  serverVersion: id === 'OFF-001' ? 1 : 4,
  validation: id === 'OFF-001'
    ? { valid: false, issues: ['VERSION_CONFLICT'] }
    : { valid: true, issues: [] },
  mergeStatus: id === 'OFF-001' ? 'CONFLICT' : 'CACHED',
  version: 1,
  createdAt: '2026-07-16T08:01:00+08:00',
  updatedAt: '2026-07-16T08:01:00+08:00',
});

function successEnvelope(apiId: 'API-018' | 'API-019', items: OfflinePacket[]) {
  return {
    ok: true,
    data: {
      apiId,
      operationId: apiId === 'API-018'
        ? 'GET_mock_offline_packets'
        : 'POST_mock_offline_packets_id_command',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items,
    },
    auditLogId: 'AUD-018-019',
    traceId: 'TRACE-018-019',
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

describe('OfflinePacketGateway', () => {
  it('uses exact API-018/API-019 paths and the strict C10 command body', async () => {
    const { fetcher, calls } = createFetcher([
      { body: successEnvelope('API-018', [packet(), packet('OFF-004')]) },
      { body: successEnvelope('API-019', [packet('OFF-004')]) },
    ]);
    const gateway = createOfflinePacketGateway(fetcher);

    await gateway.listPackets();
    await gateway.commandPacket('OFF-004', {
      action: 'VALIDATE',
      reason: '重新校验离线包',
      validation: { valid: true, issues: [] },
    });

    expect(calls).toEqual([
      { input: '/mock/offline-packets', init: undefined },
      {
        input: '/mock/offline-packets/OFF-004/command',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({
            action: 'VALIDATE',
            reason: '重新校验离线包',
            validation: { valid: true, issues: [] },
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
    const list = successEnvelope('API-018', [packet(), packet('OFF-004')]);
    const command = successEnvelope('API-019', [packet('OFF-004')]);
    const { fetcher } = createFetcher([{ body: list }, { body: command }]);
    const gateway = createOfflinePacketGateway(fetcher);

    await expect(gateway.listPackets()).resolves.toEqual(list);
    await expect(gateway.commandPacket('OFF-004', {
      action: 'UPLOAD', reason: '上传离线包',
    })).resolves.toEqual(command);
    expect(store.getState()).toEqual(before);
  });

  it('returns a strict error envelope unchanged', async () => {
    const failure: ApiErrorEnvelope = {
      ok: false,
      errorCode: 'TOS-OFF-001',
      message: '离线包版本冲突',
      details: { scenarioId: 'SCN-06', seedRefs: ['OFF-001'] },
      auditLogId: 'AUD-409',
      traceId: 'TRACE-409',
    };
    const { fetcher } = createFetcher([{ body: failure, status: 409 }]);

    await expect(createOfflinePacketGateway(fetcher).commandPacket(
      'OFF-001', { action: 'RETRY', reason: '冲突包重试' },
    )).resolves.toEqual(failure);
  });

  it.each([
    ['wrong api id', {
      ...successEnvelope('API-019', [packet()]),
      data: { ...successEnvelope('API-019', [packet()]).data, apiId: 'API-018' },
    }, 'command'],
    ['wrong operation id', {
      ...successEnvelope('API-019', [packet()]),
      data: { ...successEnvelope('API-019', [packet()]).data, operationId: 'wrong' },
    }, 'command'],
    ['path id mismatch', successEnvelope('API-019', [packet('OFF-004')]), 'command'],
    ['missing command identity', successEnvelope('API-019', []), 'command'],
    ['extra command identity', successEnvelope('API-019', [packet(), packet('OFF-004')]), 'command'],
    ['malformed packet', {
      ...successEnvelope('API-018', [packet()]),
      data: { ...successEnvelope('API-018', [packet()]).data, items: [{ id: 'OFF-001' }] },
    }, 'list'],
    ['missing scenario identity', {
      ...successEnvelope('API-018', [packet()]),
      data: { ...successEnvelope('API-018', [packet()]).data, scenarioId: undefined },
    }, 'list'],
    ['malformed success envelope', {
      ok: true,
      data: successEnvelope('API-019', [packet()]).data,
    }, 'command'],
    ['malformed error envelope', {
      ok: false,
      errorCode: 'TOS-OFF-001',
      message: 'bad',
      auditLogId: 'AUD-X',
      traceId: 'TRACE-X',
      extra: true,
    }, 'command'],
  ])('rejects %s', async (_label, body, method) => {
    const { fetcher } = createFetcher([{ body }]);
    const gateway = createOfflinePacketGateway(fetcher);
    const request = method === 'list'
      ? gateway.listPackets()
      : gateway.commandPacket('OFF-001', { action: 'RETRY', reason: '冲突包重试' });

    await expect(request).rejects.toBeInstanceOf(ZodError);
  });

  it.each(['list', 'command'])('passes %s network failure through to its caller', async (method) => {
    const failure = new TypeError(`API-01${method === 'list' ? '8' : '9'} disconnected`);
    const fetcher: typeof fetch = async () => { throw failure; };
    const gateway = createOfflinePacketGateway(fetcher);
    const request = method === 'list'
      ? gateway.listPackets()
      : gateway.commandPacket('OFF-001', { action: 'RETRY', reason: '冲突包重试' });

    await expect(request).rejects.toBe(failure);
  });

  it.each([
    ['blank id', '', { action: 'RETRY', reason: '重试' }],
    ['unknown action', 'OFF-001', { action: 'AUTO_MERGE', reason: '禁止新增动作' }],
    ['blank reason', 'OFF-001', { action: 'RETRY', reason: '   ' }],
    ['non-object validation', 'OFF-004', {
      action: 'VALIDATE', reason: '校验', validation: [],
    }],
    ['extra field', 'OFF-001', {
      action: 'RETRY', reason: '重试', workOrderId: 'WO-006',
    }],
  ])('rejects %s before fetch', async (_label, id, input) => {
    const { fetcher, calls } = createFetcher([{ body: successEnvelope('API-019', [packet()]) }]);
    const gateway = createOfflinePacketGateway(fetcher);

    await expect(gateway.commandPacket(
      id,
      input as Parameters<typeof gateway.commandPacket>[1],
    )).rejects.toBeInstanceOf(ZodError);
    expect(calls).toEqual([]);
  });
});
