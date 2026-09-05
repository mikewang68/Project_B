import { describe, expect, it } from 'vitest';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  calculateReceptionRecommendation,
  recommendationDraftSchema,
} from '../../recommendation';
import { parseExceptionQueryContext } from '../../exception-handling';
import { generateTaskDraft } from '../../task-decomposition';
import {
  selectAssignableResources,
  selectDispatchBoard,
  selectDispatchKpis,
} from '../selectors';

function createReadyStore(dataScope: string[] = ['AREA-A']) {
  const session: DemoSessionSeed = {
    actorId: 'USER-DISPATCHER',
    roleCode: 'DISPATCHER',
    dataScope,
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  };
  const store = createDemoStore(createFixtureSnapshot(), session);
  store.replaceDomainState((candidate) => {
    const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!plan) throw new Error('PLAN-001 missing.');
    plan.status = 'CONFIRMED';
    plan.version = 2;
    const calculation = calculateReceptionRecommendation({
      plan,
      tracks: candidate.resource.tracks,
      generatedAt: candidate.session.demoTime,
    });
    const selected = calculation.candidates[0];
    if (!selected) throw new Error('Expected a recommendation candidate.');
    const recommendation = recommendationDraftSchema.parse({
      planId: plan.id,
      draftVersion: 2,
      status: 'CONFIRMED',
      inputPlanVersion: plan.version,
      ruleVersion: 'C05-DEMO-RULE-1.0',
      generatedAt: candidate.session.demoTime,
      candidates: calculation.candidates,
      excluded: calculation.excluded,
      selectedCandidateId: selected.candidateId,
      confirmation: {
        actorId: candidate.session.actorId,
        roleCode: candidate.session.roleCode,
        confirmedAt: candidate.session.demoTime,
        commandId: 'CMD-C05-001',
        traceId: 'TRACE-C05-001',
      },
    });
    candidate.recommendation.drafts[plan.id] = recommendation;
    const generation = generateTaskDraft({
      plan,
      recommendation,
      waybills: candidate.plan.waybills,
      materials: candidate.resource.materials,
      resources: candidate.resource.resources,
      demoTime: candidate.session.demoTime,
      generationVersion: 1,
    });
    candidate.workOrder.workOrders.push(...generation.workOrders.map((order) => ({
      ...order,
      status: 'READY' as const,
      version: order.version + 1,
    })));
    candidate.workOrder.nodes.push(...generation.nodes);
    plan.status = 'DECOMPOSED';
    plan.version += 1;
  });
  return store;
}

describe('C07 dispatch-board selectors', () => {
  it('projects only C06-owned READY-or-later pairs with plan and resource requirements', () => {
    const board = selectDispatchBoard(createReadyStore().getState(), 'PLAN-001');

    expect(board?.plan).toEqual(expect.objectContaining({
      id: 'PLAN-001',
      planBatchNo: 'PB-20260716-01',
      cargoType: 'FLY_ASH',
      status: 'DECOMPOSED',
    }));
    expect(board?.ruleVersion).toBe('C06-DEMO-RULE-1.0');
    expect(board?.orders).toHaveLength(4);
    expect(board?.orders.map(({ workOrder }) => workOrder.id)).toEqual([
      'C06-WO-PLAN-001-G001-01',
      'C06-WO-PLAN-001-G001-02',
      'C06-WO-PLAN-001-G001-03',
      'C06-WO-PLAN-001-G001-04',
    ]);
    expect(board?.orders.some(({ workOrder }) => workOrder.id === 'WO-001')).toBe(false);
    expect(board?.orders[1]).toEqual(expect.objectContaining({
      workNode: expect.objectContaining({ sequence: 2, status: 'WAITING' }),
      requiredResourceType: 'TIPPER',
      assignableResourceIds: ['RESOURCE-001'],
      progressState: 'READY_QUEUE',
      exceptionEntryUrl: '/monitor/exceptions?workOrderId=C06-WO-PLAN-001-G001-02&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board',
    }));
    expect(Object.isFrozen(board)).toBe(true);
    expect(Object.isFrozen(board?.orders)).toBe(true);
    expect(Object.isFrozen(board?.orders[0]?.workOrder)).toBe(true);
  });

  it('projects DB-05 as a read-only, scoped exception entry', () => {
    const store = createReadyStore();
    const before = structuredClone(store.getState());

    const entry = selectDispatchBoard(store.getState(), 'PLAN-001')?.orders[1]?.exceptionEntryUrl;

    expect(entry).toBe(
      '/monitor/exceptions?workOrderId=C06-WO-PLAN-001-G001-02&planId=PLAN-001&scenarioId=SCN-01&from=dispatch-board',
    );
    expect(parseExceptionQueryContext(new URL(entry!, 'http://localhost').search)).toEqual({
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      planId: 'PLAN-001',
      scenarioId: 'SCN-01',
      from: 'dispatch-board',
    });
    expect(store.getState()).toEqual(before);
  });

  it('excludes C06 drafts and historical WO-001..012/NODE-001..012 records', () => {
    const store = createReadyStore();
    store.replaceDomainState((candidate) => {
      const draft = candidate.workOrder.workOrders.find(
        ({ id }) => id === 'C06-WO-PLAN-001-G001-01',
      );
      if (!draft) throw new Error('C06 order missing.');
      draft.status = 'DRAFT';
    });

    const board = selectDispatchBoard(store.getState(), 'PLAN-001');

    expect(board?.orders).toHaveLength(3);
    expect(board?.orders.every(({ workOrder, workNode }) =>
      workOrder.id.startsWith('C06-WO-') && workNode.id.startsWith('C06-NODE-'),
    )).toBe(true);
  });

  it('filters visibility before candidate counts and keeps unavailable matching resources with reasons', () => {
    const store = createReadyStore();
    store.replaceDomainState((candidate) => {
      const tipper = candidate.resource.resources.find(({ id }) => id === 'RESOURCE-001');
      if (!tipper) throw new Error('RESOURCE-001 missing.');
      candidate.resource.resources.push(
        { ...tipper, id: 'RESOURCE-AREA-B', workArea: 'AREA-B', status: 'AVAILABLE' },
        { ...tipper, id: 'RESOURCE-GLOBAL', workArea: 'GLOBAL', status: 'AVAILABLE' },
      );
    });

    const resources = selectAssignableResources(store.getState(), 'TIPPER');

    expect(resources.map(({ id }) => id)).toEqual([
      'RESOURCE-001',
      'RESOURCE-007',
      'RESOURCE-GLOBAL',
    ]);
    expect(resources.find(({ id }) => id === 'RESOURCE-001')).toEqual(expect.objectContaining({
      assignable: true,
      unavailableReason: undefined,
    }));
    expect(resources.find(({ id }) => id === 'RESOURCE-007')).toEqual(expect.objectContaining({
      status: 'BUSY',
      assignable: false,
      unavailableReason: '资源状态 BUSY，不可分配',
    }));
    expect(resources.some(({ id }) => id === 'RESOURCE-AREA-B')).toBe(false);
    expect(selectAssignableResources(createReadyStore(['AREA-B']).getState(), 'TIPPER')).toEqual([]);
  });

  it('derives the four dispatch progress states and KPI counts from owned records only', () => {
    const store = createReadyStore();
    store.replaceDomainState((candidate) => {
      const owned = candidate.workOrder.workOrders.filter(({ id }) => id.startsWith('C06-WO-'));
      Object.assign(owned[1]!, { resourceId: 'RESOURCE-001', teamId: 'RESOURCE-001' });
      Object.assign(owned[2]!, {
        status: 'DISPATCHED', resourceId: 'RESOURCE-002', teamId: 'RESOURCE-002',
      });
      Object.assign(owned[3]!, {
        status: 'IN_PROGRESS', resourceId: 'RESOURCE-003', teamId: 'RESOURCE-003',
      });
    });

    const board = selectDispatchBoard(store.getState(), 'PLAN-001');
    const kpis = selectDispatchKpis(store.getState(), 'PLAN-001');

    expect(board?.orders.map(({ progressState }) => progressState)).toEqual([
      'READY_QUEUE', 'ASSIGNED', 'DISPATCHED', 'EXECUTING',
    ]);
    expect(kpis).toEqual({
      ready: 2,
      assigned: 3,
      dispatched: 1,
      executing: 1,
      completed: 0,
      exceptionEntry: 0,
    });
    expect(board?.kpis).toEqual(kpis);
  });
});
