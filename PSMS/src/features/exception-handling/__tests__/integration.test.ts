import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ApiSuccessEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoRuntime } from '../../../runtime';
import {
  EXCEPTION_SOURCE_DISCLOSURE,
  parseExceptionQueryContext,
  selectExceptionHandlingBoard,
} from '..';

function createExceptionFetcher() {
  let call = 0;
  return vi.fn(async (input: string | URL | Request) => {
    call += 1;
    const path = new URL(
      input instanceof Request ? input.url : String(input),
      'http://localhost',
    ).pathname;
    const id = decodeURIComponent(path.match(/^\/mock\/exceptions\/([^/]+)\/command$/)?.[1] ?? '');
    const exception = createFixtureSnapshot().objects['DO-009'].find((item) => item.id === id);
    const envelope: ApiSuccessEnvelope = {
      ok: true,
      data: {
        apiId: 'API-015',
        operationId: 'POST_mock_exceptions_id_command',
        now: '2026-07-16T09:00:00+08:00',
        scenarioId: 'SCN-01',
        items: exception ? [exception] : [],
      },
      traceId: `TRACE-C08-INTEGRATION-${call}`,
      auditLogId: `AUD-C08-INTEGRATION-${call}`,
    };
    return new Response(JSON.stringify(envelope), {
      headers: { 'content-type': 'application/json' },
    });
  });
}

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('C07 to C08 integration boundary', () => {
  it('accepts DB-05 query fields as display-only context without inventing DO-009 relations', () => {
    const runtime = createDemoRuntime();
    const before = structuredClone(runtime.store.getState());
    const context = parseExceptionQueryContext(
      '?workOrderId=C06-WO-PLAN-001-G001-02'
      + '&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board',
    );

    const board = selectExceptionHandlingBoard(runtime.store.getState(), context);

    expect(board.sourceContext).toEqual({
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      planId: 'PLAN-001',
      scenarioId: 'SCN-01',
      from: 'dispatch-board',
    });
    expect(board.sourceDisclosure).toBe(EXCEPTION_SOURCE_DISCLOSURE);
    expect(board.items).toHaveLength(before.exception.exceptions.length);
    for (const { exception } of board.items) {
      expect(exception).not.toHaveProperty('workOrderId');
      expect(exception).not.toHaveProperty('workNodeId');
      expect(exception).not.toHaveProperty('planId');
    }
    expect(runtime.store.getState()).toEqual(before);
  });

  it('keeps WorkOrder, WorkNode, and Interlock stores unchanged across EX-01 and EX-02', async () => {
    const fetcher = createExceptionFetcher();
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const workOrderBefore = structuredClone(runtime.store.getState().workOrder);
    const interlockBefore = structuredClone(runtime.store.getState().interlock);

    await expect(runtime.exceptionHandling.commands.ackException({
      exceptionId: 'EX-001',
      reason: '确认设备离线',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-001' });
    await expect(runtime.exceptionHandling.commands.assignException({
      exceptionId: 'EX-001',
      owner: 'TEAM-09',
      reason: '分派现场处理',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-002' });

    expect(runtime.store.getState().workOrder).toEqual(workOrderBefore);
    expect(runtime.store.getState().interlock).toEqual(interlockBefore);
    expect(runtime.store.getState().exception.exceptions.find(({ id }) => id === 'EX-001'))
      .toMatchObject({ status: 'HANDLING', owner: 'TEAM-09', version: 3 });
    expect(runtime.store.getState().configAudit.commandAudit.slice(-2).map(({ record }) =>
      record.action,
    )).toEqual(['EX-01', 'EX-02']);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
