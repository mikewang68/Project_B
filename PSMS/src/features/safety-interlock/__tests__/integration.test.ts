import { describe, expect, it, vi } from 'vitest';

import type { ApiSuccessEnvelope, Interlock } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoRuntime } from '../../../runtime';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import {
  parseExceptionQueryContext,
  selectExceptionHandlingBoard,
} from '../../exception-handling';
import { parseInterlockQueryContext } from '../queryContext';
import { selectSafetyInterlockBoard } from '../selectors';

const session: DemoSessionSeed = {
  actorId: 'USER-DISPATCHER',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
};

describe('C08 to C09 safety-interlock integration', () => {
  it('parses the UI-008 INTERLOCK entry URL as display-only C09 source context', () => {
    const store = createDemoStore(createFixtureSnapshot(), session);
    const exceptionBoard = selectExceptionHandlingBoard(
      store.getState(),
      parseExceptionQueryContext(''),
    );
    const source = exceptionBoard.items.find(({ exception }) => exception.id === 'EX-003');

    expect(source?.exception.type).toBe('INTERLOCK');
    expect(source?.interlockEntryUrl).toBe(
      '/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling',
    );

    const context = parseInterlockQueryContext(
      new URL(source!.interlockEntryUrl!, 'http://demo.local').search,
    );
    const interlockBoard = selectSafetyInterlockBoard(store.getState(), context);

    expect(interlockBoard.sourceContext).toEqual({
      exceptionId: 'EX-003',
      scenarioId: 'SCN-01',
      from: 'exception-handling',
    });
    expect(interlockBoard.sourceDisclosure).toBe('演示来源上下文，非生产外键');
    expect(interlockBoard.returnExceptionUrl).toBe(
      '/monitor/exceptions?exceptionId=EX-003&scenarioId=SCN-01&from=safety-interlock',
    );
  });

  it('keeps exceptionId outside the strict DO-010 projection', () => {
    const store = createDemoStore(createFixtureSnapshot(), session);
    const candidate = structuredClone(store.getState()) as DemoRootState;
    candidate.interlock.interlocks[0] = {
      ...candidate.interlock.interlocks[0]!,
      exceptionId: 'EX-FABRICATED',
      workOrderId: 'WO-FABRICATED',
      deviceCommand: 'RESET-PLC',
    } as Interlock;

    const board = selectSafetyInterlockBoard(
      candidate,
      parseInterlockQueryContext('?exceptionId=EX-003&scenarioId=SCN-01'),
    );
    const projected = board.items[0]!.interlock;

    expect(projected).not.toHaveProperty('exceptionId');
    expect(projected).not.toHaveProperty('workOrderId');
    expect(projected).not.toHaveProperty('deviceCommand');
    expect(board.sourceContext.exceptionId).toBe('EX-003');
  });

  it('executes SI-02 without mutating any DispatchException record', async () => {
    const fixture = createFixtureSnapshot();
    const interlock = fixture.objects['DO-010'].find(({ id }) => id === 'IL-001');
    const fetcher = vi.fn(async () => {
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data: {
          apiId: 'API-017',
          operationId: 'POST_mock_interlocks_id_command',
          now: '2026-07-16T09:00:00+08:00',
          scenarioId: 'SCN-01',
          items: interlock ? [interlock] : [],
        },
        traceId: 'TRACE-C09-INTEGRATION',
        auditLogId: 'AUD-C09-INTEGRATION',
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const exceptionsBefore = structuredClone(runtime.store.getState().exception.exceptions);

    await expect(runtime.safetyInterlock.commands.requestReset({
      interlockId: 'IL-001',
      reason: '现场条件已核验，申请 Demo 复位',
      resetRequest: { requested: true, note: '不触发真实设备控制' },
    })).resolves.toMatchObject({ ok: true });

    expect(runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'RESET_REQUESTED', version: 2 });
    expect(runtime.store.getState().exception.exceptions).toEqual(exceptionsBefore);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
