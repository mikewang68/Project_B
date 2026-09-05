import { describe, expect, it } from 'vitest';

import type { OfflinePacket } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { selectOfflinePacketBoard, selectOfflinePacketKpis } from '../offlinePacketProjection';
import { parseOfflinePacketQuery } from '../offlinePacketQueries';

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

describe('C10 offline packet projection', () => {
  it('projects only strict DO-011 fields and keeps query context display-only', () => {
    const state = structuredClone(createStore().getState()) as DemoRootState;
    state.offline.packets[0] = {
      ...state.offline.packets[0]!,
      workOrderId: 'WO-INVENTED',
      exceptionId: 'EX-INVENTED',
    } as OfflinePacket;

    const board = selectOfflinePacketBoard(state, parseOfflinePacketQuery(
      '?packetId=OFF-001&terminalId=PDA-01&workOrderNo=WO-006'
      + '&mergeStatus=CONFLICT&scenarioId=SCN-06&from=monitor',
    ));

    expect(board.items).toHaveLength(1);
    expect(board.items[0]?.packet).toEqual({
      id: 'OFF-001',
      offlinePackageNo: 'OFF-PKG-001',
      terminalId: 'PDA-01',
      workOrderNo: 'WO-006',
      packageVersion: 2,
      serverVersion: 1,
      validation: { valid: false, issues: ['VERSION_CONFLICT'] },
      mergeStatus: 'CONFLICT',
      version: 1,
      createdAt: '2026-07-16T08:01:00+08:00',
      updatedAt: '2026-07-16T08:01:00+08:00',
    });
    expect(board.items[0]?.packet).not.toHaveProperty('workOrderId');
    expect(board.items[0]?.packet).not.toHaveProperty('exceptionId');
    expect(board.sourceContextLabels).toEqual([
      '来源模块：monitor',
      '场景：SCN-06',
      '离线包：OFF-001',
      '终端：PDA-01',
      '作业单文本：WO-006',
      '状态：CONFLICT',
    ]);
    expect(board.sourceDisclosure).toBe('演示文本上下文，非生产外键');
    expect(board.recoveryGuidance).toContain('重置到 SCN-01');
  });

  it('derives version, validation, state flow, conflict, and action availability', () => {
    const board = selectOfflinePacketBoard(createStore().getState(), parseOfflinePacketQuery(''));
    const conflict = board.items.find(({ packet }) => packet.id === 'OFF-001');
    const rejected = board.items.find(({ packet }) => packet.id === 'OFF-002');
    const retry = board.items.find(({ packet }) => packet.id === 'OFF-003');
    const cached = board.items.find(({ packet }) => packet.id === 'OFF-004');

    expect(conflict).toEqual(expect.objectContaining({
      progressState: 'CONFLICT',
      versionDelta: 1,
      validationValid: false,
      validationIssues: ['VERSION_CONFLICT'],
      conflict: true,
      availableActions: {
        upload: false, validate: false, merge: false, reject: true, retry: true,
      },
    }));
    expect(conflict?.stateFlow.find(({ status }) => status === 'CONFLICT')?.current).toBe(true);
    expect(rejected?.availableActions).toEqual({
      upload: false, validate: false, merge: false, reject: false, retry: true,
    });
    expect(retry?.availableActions).toEqual({
      upload: true, validate: false, merge: false, reject: false, retry: false,
    });
    expect(cached?.availableActions).toEqual({
      upload: true, validate: false, merge: false, reject: false, retry: false,
    });
  });

  it('filters by packet, terminal, work-order text, and merge status after data scope', () => {
    const filtered = selectOfflinePacketBoard(
      createStore().getState(),
      parseOfflinePacketQuery(
        '?packetId=OFF-004&terminalId=PDA-04&workOrderNo=WO-009&mergeStatus=CACHED',
      ),
    );
    const hidden = selectOfflinePacketBoard(
      createStore({ dataScope: ['AREA-B'] }).getState(),
      parseOfflinePacketQuery('?packetId=OFF-004'),
    );

    expect(filtered.items.map(({ packet }) => packet.id)).toEqual(['OFF-004']);
    expect(hidden.items).toEqual([]);
    expect(hidden.kpis).toEqual({
      cached: 0,
      pendingUpload: 0,
      validating: 0,
      merged: 0,
      conflict: 0,
      rejected: 0,
      retry: 0,
    });
  });

  it('derives all seven KPI counts from visible packets', () => {
    expect(selectOfflinePacketKpis(createStore().getState())).toEqual({
      cached: 1,
      pendingUpload: 0,
      validating: 0,
      merged: 0,
      conflict: 1,
      rejected: 1,
      retry: 1,
    });
  });

  it('deep-freezes projections and disables actions without permissions', () => {
    const board = selectOfflinePacketBoard(createStore().getState(), parseOfflinePacketQuery(''));
    const businessBoard = selectOfflinePacketBoard(
      createStore({ actorId: 'USER-BUSINESS', roleCode: 'BUSINESS' }).getState(),
      parseOfflinePacketQuery(''),
    );

    expect(Object.isFrozen(board)).toBe(true);
    expect(Object.isFrozen(board.items)).toBe(true);
    expect(Object.isFrozen(board.items[0]?.packet)).toBe(true);
    expect(Object.isFrozen(board.items[0]?.packet.validation)).toBe(true);
    expect(Object.isFrozen(board.items[0]?.stateFlow)).toBe(true);
    expect(Object.isFrozen(board.items[0]?.validationIssues)).toBe(true);
    expect(Object.values(businessBoard.items[0]!.availableActions).every((value) => !value))
      .toBe(true);
  });
});
