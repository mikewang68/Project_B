import { describe, expect, it } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  selectInterfaceHealth,
  selectOpenRisks,
  selectOverviewKpis,
  selectPlanDetails,
  selectPlanLedger,
  selectValidationIssues,
  selectVisibleYardObjects,
} from '../selectors';
import type { PlanEntryQuery, PlanEntryWorkflowState } from '../types';

const query: PlanEntryQuery = {
  date: '2026-07-16',
  workArea: 'AREA-A',
  scenarioId: 'SCN-02',
  planBatchNo: '',
  trainNo: '',
  statuses: [],
  exceptionTypes: [],
  page: 1,
  pageSize: 20,
  sort: 'updatedAt:desc',
};

const unresolvedWorkflow: PlanEntryWorkflowState = {
  retryCount: 0,
  circuitOpen: false,
  resolvedFields: {},
};

function createState(
  scenarioId: 'SCN-01' | 'SCN-02' | 'SCN-03' = 'SCN-02',
  session: Pick<DemoSessionSeed, 'actorId' | 'roleCode'> = {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
  },
) {
  return createDemoStore(createFixtureSnapshot(), {
    actorId: session.actorId,
    roleCode: session.roleCode,
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId,
  }).getState();
}

describe('plan entry selectors', () => {
  it('projects all seven immutable page view-models from the strict Store', () => {
    const state = createState();
    const kpis = selectOverviewKpis(state, query, unresolvedWorkflow);
    const yard = selectVisibleYardObjects(state, query);
    const risks = selectOpenRisks(state, query);
    const ledger = selectPlanLedger(state, query, unresolvedWorkflow);
    const health = selectInterfaceHealth(state, unresolvedWorkflow);
    const issues = selectValidationIssues(state, query, unresolvedWorkflow);
    const details = selectPlanDetails(state, query, 'PLAN-002', unresolvedWorkflow);

    expect(kpis).toMatchObject({ totalPlans: 3, pendingConfirmPlans: 1, blockedPlans: 1 });
    expect(yard.tracks).toHaveLength(4);
    expect(yard.resourceMarkers.map(({ id }) => id)).toEqual([
      'RESOURCE-001',
      'RESOURCE-004',
      'RESOURCE-007',
      'RESOURCE-010',
    ]);
    expect(yard.appointmentMarkers.map(({ vehicleNo }) => vehicleNo)).toEqual([
      '川A·DEMO01',
      '川A·DEMO02',
      '川A·DEMO03',
      '川A·DEMO04',
      '川A·DEMO05',
      '川A·DEMO06',
    ]);
    expect(risks.some(({ errorCode }) => errorCode === 'TOS-EXT-002')).toBe(true);
    expect(ledger).toMatchObject({ page: 1, pageSize: 20, total: 3 });
    expect(health).toMatchObject({ status: 'DEGRADED', retryCount: 0, circuitOpen: false });
    expect(issues).toContainEqual({
      planId: 'PLAN-002',
      field: 'trackNo',
      errorCode: 'TOS-EXT-002',
    });
    expect(details).toMatchObject({
      id: 'PLAN-002',
      trackNo: undefined,
      validationStatus: 'MISSING_FIELD',
    });

    for (const projection of [kpis, yard, risks, ledger, health, issues, details]) {
      expect(Object.isFrozen(projection)).toBe(true);
    }
    expect(Object.isFrozen(ledger.items)).toBe(true);
    expect(Object.isFrozen(ledger.items[0])).toBe(true);
    expect(Object.isFrozen(yard.resourceMarkers)).toBe(true);
    expect(Object.isFrozen(yard.appointmentMarkers[0])).toBe(true);
  });

  it('projects authoritative plan progress and risk presentation instead of leaving UI mappings', () => {
    const state = createState();
    const ledger = selectPlanLedger(state, query, unresolvedWorkflow);

    expect(ledger.items.find(({ id }) => id === 'PLAN-001')).toMatchObject({
      progress: { percent: 75, label: '等待确认', status: 'normal' },
      risk: { level: 'NONE', label: '正常' },
    });
    expect(ledger.items.find(({ id }) => id === 'PLAN-002')).toMatchObject({
      progress: { percent: 50, label: '流程阻断', status: 'exception' },
      risk: { level: 'CRITICAL', label: '缺少字段：trackNo' },
    });
    expect(Object.isFrozen(ledger.items[0].progress)).toBe(true);
    expect(Object.isFrozen(ledger.items[0].risk)).toBe(true);
  });

  it('projects track occupancy and every yard marker without inventing a plan-to-track relation', () => {
    const state = createState('SCN-01');
    const yard = selectVisibleYardObjects(state, { ...query, scenarioId: 'SCN-01' });

    expect(yard.tracks.find(({ trackNo }) => trackNo === 'T1')).toMatchObject({
      occupyStatus: 'FREE',
      occupyStatusLabel: '空闲',
      compatibleCargoSummary: '粉煤灰 / 钢材',
      occupancyMarker: undefined,
    });
    expect(yard.tracks.find(({ trackNo }) => trackNo === 'T2')).toMatchObject({
      occupyStatus: 'OCCUPIED',
      occupyStatusLabel: '占用',
      occupancyMarker: { label: '当前占用' },
    });
    expect(yard.resourceMarkers).toHaveLength(4);
    expect(yard.resourceMarkers.map(({ laneOrder, positionPercent }) => [
      laneOrder,
      positionPercent,
    ])).toEqual([
      [1, 8],
      [2, 36],
      [3, 64],
      [4, 92],
    ]);
    expect(yard.appointmentMarkers).toHaveLength(6);
    expect(yard.appointmentMarkers.map(({ queueNo, laneOrder }) => [
      queueNo,
      laneOrder,
    ])).toEqual([
      ['Q01', 1],
      ['Q02', 2],
      ['Q03', 3],
      ['Q04', 4],
      ['Q05', 5],
      ['Q06', 6],
    ]);
    expect(yard.appointmentMarkers.every((marker) => !('positionPercent' in marker))).toBe(true);
    expect(JSON.stringify(yard)).not.toContain('PLAN-001');
    expect(JSON.stringify(yard)).not.toContain('75001');
  });

  it('projects only mutation-declared missing fields and reveals the strict value after resolution', () => {
    const state = createState();
    const strictPlan = state.plan.plans.find(({ id }) => id === 'PLAN-002');

    expect(strictPlan?.trackNo).toBe('T2');
    expect(selectValidationIssues(state, query, unresolvedWorkflow)).toContainEqual({
      planId: 'PLAN-002',
      field: 'trackNo',
      errorCode: 'TOS-EXT-002',
    });
    expect(
      selectPlanLedger(state, query, unresolvedWorkflow).items.find(({ id }) => id === 'PLAN-002'),
    ).toMatchObject({ trackNo: undefined, validationStatus: 'MISSING_FIELD' });
    expect(state.plan.plans.find(({ id }) => id === 'PLAN-002')?.trackNo).toBe('T2');

    const resolvedWorkflow: PlanEntryWorkflowState = {
      ...unresolvedWorkflow,
      resolvedFields: { 'PLAN-002': ['trackNo'] },
    };
    expect(
      selectPlanLedger(state, query, resolvedWorkflow).items.find(({ id }) => id === 'PLAN-002'),
    ).toMatchObject({ trackNo: 'T2', validationStatus: 'VALID' });
    expect(selectValidationIssues(state, query, resolvedWorkflow)).not.toContainEqual(
      expect.objectContaining({ planId: 'PLAN-002', field: 'trackNo' }),
    );
  });

  it('projects immutable drawer presentation without leaking the strict SCN-02 track', () => {
    const state = createState();
    const details = selectPlanDetails(state, query, 'PLAN-002', unresolvedWorkflow);

    expect(details).toMatchObject({
      workflowStepIndex: 2,
      summary: [
        { label: '计划编号', value: 'PLAN-002' },
        { label: '计划批次', value: 'PB-20260716-02' },
        { label: '车次', value: '75002' },
        { label: '股道', value: '待补录' },
        { label: '货类', value: 'STEEL' },
        { label: '状态', value: 'BLOCKED' },
      ],
      retryHistory: [],
    });
    expect(details?.maskedRawSummary).toContain('"trackNo": "***"');
    expect(details?.maskedRawSummary).not.toContain('"trackNo": "T2"');
    expect(Object.isFrozen(details?.summary)).toBe(true);
    expect(Object.isFrozen(details?.summary[0])).toBe(true);
    expect(Object.isFrozen(details?.retryHistory)).toBe(true);
  });

  it('projects immutable UI-ready supplement policy and reviewer options without raw user roles', () => {
    const details = selectPlanDetails(
      createState('SCN-02', { actorId: 'E2E-DISPATCHER', roleCode: 'DISPATCHER' }),
      query,
      'PLAN-002',
      unresolvedWorkflow,
    );

    expect(details?.supplement).toEqual({
      visible: true,
      actorId: 'E2E-DISPATCHER',
      reviewerOptions: [{ value: 'USER-001', label: 'USER-001' }],
    });
    expect(Object.isFrozen(details?.supplement)).toBe(true);
    expect(Object.isFrozen(details?.supplement.reviewerOptions)).toBe(true);
    expect(Object.isFrozen(details?.supplement.reviewerOptions[0])).toBe(true);
    expect(details?.supplement.reviewerOptions[0]).not.toHaveProperty('roleCode');
    expect(details?.supplement.reviewerOptions[0]).not.toHaveProperty('status');
    expect(details?.supplement.reviewerOptions[0]).not.toHaveProperty('dataScope');

    expect(
      selectPlanDetails(createState(), query, 'PLAN-002', unresolvedWorkflow)?.supplement,
    ).toEqual({ visible: true, actorId: 'USER-001', reviewerOptions: [] });
    expect(
      selectPlanDetails(
        createState('SCN-02', { actorId: 'USER-012', roleCode: 'INTERFACE_OPS' }),
        query,
        'PLAN-002',
        unresolvedWorkflow,
      )?.supplement,
    ).toEqual({ visible: false, actorId: 'USER-012', reviewerOptions: [] });
  });

  it('projects exact interface recovery visibility from the current session and health', () => {
    expect(
      selectInterfaceHealth(
        createState('SCN-03', { actorId: 'USER-012', roleCode: 'INTERFACE_OPS' }),
        unresolvedWorkflow,
      ).recoveryVisible,
    ).toBe(true);
    expect(
      selectInterfaceHealth(
        createState('SCN-03', { actorId: 'USER-001', roleCode: 'DISPATCHER' }),
        unresolvedWorkflow,
      ).recoveryVisible,
    ).toBe(false);
    expect(
      selectInterfaceHealth(
        createState('SCN-02', { actorId: 'USER-012', roleCode: 'INTERFACE_OPS' }),
        unresolvedWorkflow,
      ).recoveryVisible,
    ).toBe(false);
    expect(
      selectInterfaceHealth(
        createState('SCN-01', { actorId: 'USER-012', roleCode: 'INTERFACE_OPS' }),
        unresolvedWorkflow,
      ).recoveryVisible,
    ).toBe(false);
  });

  it('projects retry history and workflow step index from authoritative workflow/status values', () => {
    const state = createState('SCN-03');
    const workflow: PlanEntryWorkflowState = {
      retryCount: 3,
      circuitOpen: true,
      lastSuccessAt: '2026-07-16T09:30:00+08:00',
      resolvedFields: {},
    };

    expect(selectPlanDetails(state, { ...query, scenarioId: 'SCN-03' }, 'PLAN-001', workflow)).toMatchObject({
      workflowStepIndex: 3,
      retryHistory: [
        { attempt: 1, status: 'FAILED', errorCode: 'TOS-EXT-001' },
        { attempt: 2, status: 'FAILED', errorCode: 'TOS-EXT-001' },
        { attempt: 3, status: 'FAILED', errorCode: 'TOS-EXT-001' },
      ],
    });
    expect(selectPlanDetails(state, { ...query, scenarioId: 'SCN-03' }, 'PLAN-003', workflow)).toMatchObject({
      workflowStepIndex: 0,
    });
  });

  it('tests AREA-A scope before reading or counting frozen plans', () => {
    const state = createState();
    const outOfScopeQuery = { ...query, workArea: 'AREA-B' };

    const ledger = selectPlanLedger(state, outOfScopeQuery, unresolvedWorkflow);
    const kpis = selectOverviewKpis(state, outOfScopeQuery, unresolvedWorkflow);

    expect(ledger.items).toEqual([]);
    expect(ledger.total).toBe(0);
    expect(kpis).toEqual({
      totalPlans: 0,
      pendingConfirmPlans: 0,
      confirmedPlans: 0,
      blockedPlans: 0,
      completedWorkOrders: 0,
      waitingVehicles: 0,
      availableResourceRate: 0,
      openExceptions: 0,
    });
    expect(selectValidationIssues(state, outOfScopeQuery, unresolvedWorkflow)).toEqual([]);
    expect(selectVisibleYardObjects(state, outOfScopeQuery)).toEqual({
      tracks: [],
      resourceMarkers: [],
      appointmentMarkers: [],
    });
  });

  it('gates plan details by both query workArea and session scope before reading related objects', () => {
    const state = createState();
    expect(selectPlanDetails(state, query, 'PLAN-002', unresolvedWorkflow)).toMatchObject({
      id: 'PLAN-002',
    });
    expect(
      selectPlanDetails(
        state,
        { ...query, workArea: 'AREA-B' },
        'PLAN-002',
        unresolvedWorkflow,
      ),
    ).toBeUndefined();
  });

  it('projects immutable row exception types and matches SCN-02 missing-field, SCN-03 timeout, and existing data conflicts through one path', () => {
    const scn02Query = { ...query, exceptionTypes: ['VALIDATION_MISSING_FIELD'] };
    const scn02Ledger = selectPlanLedger(createState('SCN-02'), scn02Query, unresolvedWorkflow);
    expect(scn02Ledger.items).toHaveLength(1);
    expect(scn02Ledger.items[0]).toMatchObject({
      id: 'PLAN-002',
      exceptionTypes: ['VALIDATION_MISSING_FIELD'],
    });
    expect(Object.isFrozen(scn02Ledger.items[0].exceptionTypes)).toBe(true);

    const scn03Query = {
      ...query,
      scenarioId: 'SCN-03' as const,
      exceptionTypes: ['INTERFACE_TIMEOUT'],
    };
    const scn03Ledger = selectPlanLedger(createState('SCN-03'), scn03Query, unresolvedWorkflow);
    expect(scn03Ledger.items).toHaveLength(1);
    expect(scn03Ledger.items[0]).toMatchObject({
      id: 'PLAN-003',
      exceptionTypes: ['INTERFACE_TIMEOUT'],
    });

    const conflictState = structuredClone(createState('SCN-01'));
    const conflictPlan = conflictState.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!conflictPlan) throw new Error('Missing conflict test plan.');
    conflictPlan.conflicts = [{ field: 'trackNo', left: 'T1', right: 'T2' }];
    const conflictLedger = selectPlanLedger(
      conflictState,
      { ...query, scenarioId: 'SCN-01', exceptionTypes: ['DATA_CONFLICT'] },
      unresolvedWorkflow,
    );
    expect(conflictLedger.items).toHaveLength(1);
    expect(conflictLedger.items[0]).toMatchObject({
      id: 'PLAN-001',
      exceptionTypes: ['DATA_CONFLICT'],
    });
  });

  it('filters, sorts, and paginates the ledger deterministically without mutating Store order', () => {
    const state = createState('SCN-01');
    const originalOrder = state.plan.plans.map(({ id }) => id);
    const filteredQuery: PlanEntryQuery = {
      ...query,
      scenarioId: 'SCN-01',
      statuses: ['BLOCKED', 'RECEIVED'],
      sort: 'status:asc',
    };

    const ledger = selectPlanLedger(state, filteredQuery, unresolvedWorkflow);

    expect(ledger.items.map(({ id }) => id)).toEqual(['PLAN-002', 'PLAN-003']);
    expect(state.plan.plans.map(({ id }) => id)).toEqual(originalOrder);
  });

  it('reports timeout workflow health independently of domain state', () => {
    const state = createState('SCN-03');
    const workflow: PlanEntryWorkflowState = {
      retryCount: 3,
      circuitOpen: true,
      lastSuccessAt: '2026-07-16T09:30:00+08:00',
      resolvedFields: {},
    };

    expect(selectInterfaceHealth(state, workflow)).toEqual({
      status: 'CIRCUIT_OPEN',
      errorCode: 'TOS-EXT-001',
      retryCount: 3,
      circuitOpen: true,
      lastSuccessAt: '2026-07-16T09:30:00+08:00',
      recoveryVisible: false,
    });
  });
});
