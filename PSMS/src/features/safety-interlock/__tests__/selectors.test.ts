import { describe, expect, it } from 'vitest';

import type { Interlock } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { parseInterlockQueryContext } from '../queryContext';
import { selectInterlockKpis, selectSafetyInterlockBoard } from '../selectors';

const session = (overrides: Partial<DemoSessionSeed> = {}): DemoSessionSeed => ({
  actorId: 'USER-SAFETY',
  roleCode: 'SAFETY',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
  ...overrides,
});

function createStore(overrides: Partial<DemoSessionSeed> = {}) {
  return createDemoStore(createFixtureSnapshot(), session(overrides));
}

describe('C09 safety interlock selectors', () => {
  it('projects strict DO-010 records and keeps C08 context display-only', () => {
    const state = structuredClone(createStore().getState()) as DemoRootState;
    state.interlock.interlocks[0] = {
      ...state.interlock.interlocks[0]!,
      exceptionId: 'EX-003',
      workOrderId: 'WO-001',
      deviceCommandId: 'DEVICE-CMD-001',
    } as Interlock;

    const board = selectSafetyInterlockBoard(state, parseInterlockQueryContext(
      '?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling',
    ));
    const first = board.items[0];

    expect(board.items).toHaveLength(4);
    expect(first?.interlock).toEqual({
      id: 'IL-001',
      interlockNo: 'IL-20260716-01',
      riskType: 'PERSON_INTRUSION',
      actionLevel: 'WARN',
      status: 'LOCKED',
      inputSnapshot: { sensor: 'SENSOR-01', value: true },
      receiptStatus: 'PENDING',
      resetRequest: { requested: false },
      approvalChain: ['USER-001'],
      version: 1,
      createdAt: '2026-07-16T08:01:00+08:00',
      updatedAt: '2026-07-16T08:01:00+08:00',
    });
    expect(first?.interlock).not.toHaveProperty('exceptionId');
    expect(first?.interlock).not.toHaveProperty('workOrderId');
    expect(first?.interlock).not.toHaveProperty('deviceCommandId');
    expect(board.sourceContextLabels).toEqual([
      '来源模块：异常处置',
      '异常上下文：EX-003',
      '场景：SCN-01',
    ]);
    expect(board.sourceDisclosure).toBe('演示来源上下文，非生产外键');
    expect(board.returnExceptionUrl).toBe(
      '/monitor/exceptions?exceptionId=EX-003&scenarioId=SCN-01&from=safety-interlock',
    );
  });

  it('applies data scope before status, action level, risk type, and receipt filters', () => {
    const filtered = selectSafetyInterlockBoard(
      createStore().getState(),
      parseInterlockQueryContext(
        '?status=APPROVED&actionLevel=FORCE_STOP&riskType=DEVICE_FAULT&receiptStatus=FAILED',
      ),
    );
    const hidden = selectSafetyInterlockBoard(
      createStore({ dataScope: ['AREA-B'] }).getState(),
      parseInterlockQueryContext('?status=APPROVED'),
    );

    expect(filtered.items.map(({ interlock }) => interlock.id)).toEqual(['IL-003']);
    expect(hidden.items).toEqual([]);
    expect(hidden.kpis).toEqual({
      locked: 0,
      pendingApproval: 0,
      approved: 0,
      restored: 0,
      forceStop: 0,
      receiptFailed: 0,
    });
  });

  it('derives progress, state flow, safety flags, reset and approval summaries, and actions', () => {
    const board = selectSafetyInterlockBoard(
      createStore().getState(),
      parseInterlockQueryContext(''),
    );
    const locked = board.items.find(({ interlock }) => interlock.id === 'IL-001');
    const resetting = board.items.find(({ interlock }) => interlock.id === 'IL-002');
    const forceStop = board.items.find(({ interlock }) => interlock.id === 'IL-003');
    const restored = board.items.find(({ interlock }) => interlock.id === 'IL-004');

    expect(locked).toEqual(expect.objectContaining({
      progressState: 'LOCKED',
      forceStop: false,
      receiptFailed: false,
      resetRequested: false,
      resetRequestSummary: ['已申请：否'],
      approvalSummary: ['审批节点 1：USER-001'],
      availableActions: {
        trigger: false,
        receipt: false,
        requestReset: true,
        approve: false,
        restore: false,
        requestOverride: true,
      },
    }));
    expect(locked?.stateFlow.find(({ status }) => status === 'LOCKED')?.current).toBe(true);
    expect(resetting).toEqual(expect.objectContaining({
      progressState: 'RESETTING',
      resetRequested: true,
      availableActions: expect.objectContaining({ approve: true }),
    }));
    expect(forceStop).toEqual(expect.objectContaining({
      progressState: 'RESETTING',
      forceStop: true,
      receiptFailed: true,
      forceStopWarning: '强制停机仅演示安全流程；演示恢复记录不代表真实设备已复位。',
      availableActions: expect.objectContaining({ restore: true }),
    }));
    expect(restored).toEqual(expect.objectContaining({
      progressState: 'RESTORED',
      availableActions: {
        trigger: false,
        receipt: false,
        requestReset: false,
        approve: false,
        restore: false,
        requestOverride: false,
      },
    }));
  });

  it('uses frozen DO-010 transitions for SI-01 and override projections', () => {
    const state = structuredClone(createStore().getState()) as DemoRootState;
    state.interlock.interlocks[0]!.status = 'TRIGGERED';
    state.interlock.interlocks[1]!.status = 'WAITING_RECEIPT';
    state.interlock.interlocks[2]!.status = 'OVERRIDE_PENDING';
    state.interlock.interlocks[3]!.status = 'OVERRIDDEN';

    const board = selectSafetyInterlockBoard(state, parseInterlockQueryContext(''));

    expect(board.items.find(({ interlock }) => interlock.id === 'IL-001')?.availableActions.trigger)
      .toBe(true);
    expect(board.items.find(({ interlock }) => interlock.id === 'IL-002')?.availableActions.receipt)
      .toBe(true);
    expect(board.items.find(({ interlock }) => interlock.id === 'IL-003')).toEqual(
      expect.objectContaining({
        progressState: 'OVERRIDE',
        availableActions: expect.objectContaining({ approve: true }),
      }),
    );
    expect(board.items.find(({ interlock }) => interlock.id === 'IL-004')?.progressState)
      .toBe('OVERRIDE');
  });

  it('derives KPI counts from visible strict records', () => {
    expect(selectInterlockKpis(createStore().getState())).toEqual({
      locked: 1,
      pendingApproval: 1,
      approved: 1,
      restored: 1,
      forceStop: 1,
      receiptFailed: 1,
    });
  });

  it('returns deeply frozen projections and disables actions without frozen permissions', () => {
    const safetyBoard = selectSafetyInterlockBoard(
      createStore().getState(),
      parseInterlockQueryContext(''),
    );
    const businessBoard = selectSafetyInterlockBoard(
      createStore({ actorId: 'USER-BUSINESS', roleCode: 'BUSINESS' }).getState(),
      parseInterlockQueryContext(''),
    );

    expect(Object.isFrozen(safetyBoard)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.interlock)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.interlock.inputSnapshot)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.interlock.resetRequest)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.interlock.approvalChain)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.stateFlow)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.resetRequestSummary)).toBe(true);
    expect(Object.isFrozen(safetyBoard.items[0]?.approvalSummary)).toBe(true);
    expect(Object.values(businessBoard.items[0]!.availableActions).every((allowed) => !allowed))
      .toBe(true);
  });
});
