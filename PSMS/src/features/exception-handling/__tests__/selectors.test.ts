import { describe, expect, it } from 'vitest';

import type { DispatchException } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { parseInterlockQueryContext } from '../../safety-interlock';
import { parseExceptionQueryContext } from '../queryContext';
import { selectExceptionHandlingBoard, selectExceptionKpis } from '../selectors';

const session = (overrides: Partial<DemoSessionSeed> = {}): DemoSessionSeed => ({
  actorId: 'USER-DISPATCHER',
  roleCode: 'DISPATCHER',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
  ...overrides,
});

function createStore(overrides: Partial<DemoSessionSeed> = {}) {
  return createDemoStore(createFixtureSnapshot(), session(overrides));
}

describe('C08 exception handling selectors', () => {
  it('projects strict DO-009 records and never carries a fabricated foreign key', () => {
    const state = structuredClone(createStore().getState()) as DemoRootState;
    state.exception.exceptions[0] = {
      ...state.exception.exceptions[0]!,
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      planId: 'PLAN-001',
      resourceId: 'RESOURCE-001',
    } as DispatchException;

    const board = selectExceptionHandlingBoard(state, parseExceptionQueryContext(
      '?workOrderId=C06-WO-PLAN-001-G001-02&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board',
    ));
    const first = board.items[0];

    expect(board.items).toHaveLength(5);
    expect(first?.exception).toEqual({
      id: 'EX-001',
      exceptionNo: 'EX-20260716-01',
      type: 'DEVICE_OFFLINE',
      level: 'INFO',
      status: 'OPEN',
      owner: 'TEAM-01',
      dueAt: '2026-07-16T12:01:00+08:00',
      evidence: ['EVIDENCE-001'],
      version: 1,
      createdAt: '2026-07-16T08:01:00+08:00',
      updatedAt: '2026-07-16T08:01:00+08:00',
    });
    expect(first?.exception).not.toHaveProperty('workOrderId');
    expect(first?.exception).not.toHaveProperty('planId');
    expect(first?.exception).not.toHaveProperty('resourceId');
    expect(board.sourceContextLabels).toEqual([
      '来源模块：dispatch-board',
      '工单上下文：C06-WO-PLAN-001-G001-02',
      '计划上下文：PLAN-001',
      '场景：SCN-01',
    ]);
    expect(board.sourceDisclosure).toBe('演示来源上下文，非生产外键');
  });

  it('applies data scope before status, type, level, and owner filters', () => {
    const filtered = selectExceptionHandlingBoard(
      createStore().getState(),
      parseExceptionQueryContext('?status=PENDING_REVIEW&type=TIMEOUT&level=CRITICAL&owner=TEAM-01'),
    );
    const hidden = selectExceptionHandlingBoard(
      createStore({ dataScope: ['AREA-B'] }).getState(),
      parseExceptionQueryContext('?status=PENDING_REVIEW'),
    );

    expect(filtered.items.map(({ exception }) => exception.id)).toEqual(['EX-004']);
    expect(hidden.items).toEqual([]);
    expect(hidden.kpis).toEqual({
      unacknowledged: 0,
      handling: 0,
      pendingReview: 0,
      closed: 0,
      overdue: 0,
      interlock: 0,
    });
  });

  it('derives due state, evidence counts, progress, state flow, and the UI-009 link', () => {
    const store = createStore();
    store.replaceDomainState((candidate) => {
      const first = candidate.exception.exceptions[0];
      if (!first) throw new Error('EX-001 missing.');
      first.dueAt = '2026-07-16T08:59:59+08:00';
    });

    const board = selectExceptionHandlingBoard(store.getState(), parseExceptionQueryContext(''));
    const open = board.items.find(({ exception }) => exception.id === 'EX-001');
    const interlock = board.items.find(({ exception }) => exception.id === 'EX-003');
    const closed = board.items.find(({ exception }) => exception.id === 'EX-005');

    expect(open).toEqual(expect.objectContaining({
      evidenceCount: 1,
      dueState: 'OVERDUE',
      progressState: 'OPEN_QUEUE',
      availableActions: {
        ack: true,
        assign: false,
        handle: false,
        review: false,
        close: false,
        reopen: false,
      },
    }));
    expect(open?.stateFlow.find(({ status }) => status === 'OPEN')?.current).toBe(true);
    expect(interlock).toEqual(expect.objectContaining({
      progressState: 'HANDLING',
      interlockEntryUrl: '/safety/interlocks?exceptionId=EX-003&scenarioId=SCN-01&from=exception-handling',
      availableActions: {
        ack: false,
        assign: false,
        handle: false,
        review: false,
        close: false,
        reopen: false,
      },
    }));
    expect(parseInterlockQueryContext(new URL(
      interlock!.interlockEntryUrl!,
      'http://demo.local',
    ).search)).toEqual({
      exceptionId: 'EX-003',
      scenarioId: 'SCN-01',
      from: 'exception-handling',
    });
    expect(closed).toEqual(expect.objectContaining({
      dueState: 'CLOSED',
      progressState: 'CLOSED',
      availableActions: {
        ack: false,
        assign: false,
        handle: false,
        review: false,
        close: false,
        reopen: true,
      },
    }));
  });

  it('derives KPI counts from visible strict records and injected demo time', () => {
    const store = createStore();
    store.replaceDomainState((candidate) => {
      candidate.exception.exceptions[0]!.dueAt = '2026-07-16T08:59:59+08:00';
      candidate.exception.exceptions[1]!.status = 'REOPENED';
    });

    expect(selectExceptionKpis(store.getState())).toEqual({
      unacknowledged: 2,
      handling: 1,
      pendingReview: 1,
      closed: 1,
      overdue: 1,
      interlock: 1,
    });
  });

  it('returns deeply frozen projections and disables actions without frozen permissions', () => {
    const dispatcherBoard = selectExceptionHandlingBoard(
      createStore().getState(),
      parseExceptionQueryContext(''),
    );
    const businessBoard = selectExceptionHandlingBoard(
      createStore({ actorId: 'USER-BUSINESS', roleCode: 'BUSINESS' }).getState(),
      parseExceptionQueryContext(''),
    );

    expect(Object.isFrozen(dispatcherBoard)).toBe(true);
    expect(Object.isFrozen(dispatcherBoard.items)).toBe(true);
    expect(Object.isFrozen(dispatcherBoard.items[0]?.exception)).toBe(true);
    expect(Object.isFrozen(dispatcherBoard.items[0]?.exception.evidence)).toBe(true);
    expect(Object.isFrozen(dispatcherBoard.items[0]?.stateFlow)).toBe(true);
    expect(Object.values(businessBoard.items[0]!.availableActions).every((allowed) => !allowed)).toBe(true);
  });
});
