import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import {
  calculateReceptionRecommendation,
  recommendationDraftSchema,
} from '../../recommendation';
import { generateTaskDraft } from '../ruleEngine';
import {
  selectCargoSummary,
  selectResourcePreview,
  selectRuleExplanation,
  selectTaskTree,
} from '../selectors';

function createStore(dataScope: string[] = ['AREA-A']) {
  const session: DemoSessionSeed = {
    actorId: 'USER-DISPATCHER',
    roleCode: 'DISPATCHER',
    dataScope,
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  };
  return createDemoStore(createFixtureSnapshot(), session);
}

function createGeneratedStore(dataScope: string[] = ['AREA-A']) {
  const store = createStore(dataScope);
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
    candidate.workOrder.workOrders.push(...generation.workOrders);
    candidate.workOrder.nodes.push(...generation.nodes);
  });
  return store;
}

describe('C06 task selectors', () => {
  it('projects frozen cargo facts, stable mapping, rules, task tree, and real resource states', () => {
    const state = createGeneratedStore().getState();
    const cargo = selectCargoSummary(state, 'PLAN-001');
    const rule = selectRuleExplanation(state, 'PLAN-001');
    const tree = selectTaskTree(state, 'PLAN-001');
    const resources = selectResourcePreview(state, 'PLAN-001');

    expect(cargo).toEqual(expect.objectContaining({
      planId: 'PLAN-001',
      cargoType: 'FLY_ASH',
      mappingMode: 'DEMO_STABLE_MAPPING',
      waybillIds: ['WAYBILL-001', 'WAYBILL-005'],
      materialIds: ['MATERIAL-001'],
      actualContainerCount: '数据未提供',
      acceptanceBenchmark: {
        trains: 2,
        cars: 80,
        containers: 160,
        disclosure: '高峰验收基准，不是本计划实际箱量',
      },
    }));
    expect(rule?.route.map(({ title }) => title)).toEqual([
      '识别与路由确认',
      '卸料准备',
      '输送转运',
      '筒仓入库',
    ]);
    expect(tree).toHaveLength(4);
    expect(tree.map(({ workOrderId }) => workOrderId)).toEqual([
      'C06-WO-PLAN-001-G001-01',
      'C06-WO-PLAN-001-G001-02',
      'C06-WO-PLAN-001-G001-03',
      'C06-WO-PLAN-001-G001-04',
    ]);
    expect(tree.some(({ workOrderId }) => workOrderId === 'WO-001')).toBe(false);
    expect(resources.find(({ requiredResourceType }) => requiredResourceType === 'TIPPER')?.candidates).toEqual([
      { id: 'RESOURCE-001', workArea: 'AREA-A', status: 'AVAILABLE' },
      { id: 'RESOURCE-007', workArea: 'AREA-A', status: 'BUSY' },
    ]);
    expect(resources.find(({ requiredResourceType }) => requiredResourceType === 'SILO')?.candidates).toEqual([
      { id: 'RESOURCE-004', workArea: 'AREA-A', status: 'MAINTENANCE' },
      { id: 'RESOURCE-010', workArea: 'AREA-A', status: 'LOCKED' },
    ]);
    expect(resources.every(({ allocationState }) => allocationState === 'PREVIEW_ONLY')).toBe(true);
    expect(Object.isFrozen(cargo)).toBe(true);
    expect(Object.isFrozen(cargo?.waybillIds)).toBe(true);
    expect(Object.isFrozen(rule)).toBe(true);
    expect(Object.isFrozen(tree)).toBe(true);
    expect(Object.isFrozen(tree[0])).toBe(true);
    expect(Object.isFrozen(resources)).toBe(true);
    expect(Object.isFrozen(resources[0]?.candidates)).toBe(true);
  });

  it('keeps the C06 READY read model visible after Plan becomes DECOMPOSED', () => {
    const store = createGeneratedStore();
    store.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
      if (!plan) throw new Error('PLAN-001 missing.');
      plan.status = 'DECOMPOSED';
      for (const order of candidate.workOrder.workOrders) {
        if (order.id.startsWith('C06-WO-PLAN-001-')) order.status = 'READY';
      }
    });

    expect(selectCargoSummary(store.getState(), 'PLAN-001')?.planStatus).toBe('DECOMPOSED');
    expect(selectTaskTree(store.getState(), 'PLAN-001').map(({ status }) => status)).toEqual([
      'READY', 'READY', 'READY', 'READY',
    ]);
  });

  it('strictly parses the C05 draft in scope but filters AREA-A before parsing or counting', () => {
    const inScope = createStore();
    inScope.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
      if (!plan) throw new Error('PLAN-001 missing.');
      plan.status = 'CONFIRMED';
      candidate.recommendation.drafts[plan.id] = { malformed: true };
    });
    expect(() => selectCargoSummary(inScope.getState(), 'PLAN-001')).toThrow(ZodError);

    const outOfScope = createStore(['AREA-B']);
    outOfScope.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
      if (!plan) throw new Error('PLAN-001 missing.');
      plan.status = 'CONFIRMED';
      candidate.recommendation.drafts[plan.id] = { malformed: true };
    });
    const state = outOfScope.getState();

    expect(selectCargoSummary(state, 'PLAN-001')).toBeUndefined();
    expect(selectRuleExplanation(state, 'PLAN-001')).toBeUndefined();
    expect(selectTaskTree(state, 'PLAN-001')).toEqual([]);
    expect(selectResourcePreview(state, 'PLAN-001')).toEqual([]);
  });

  it('returns empty task projections before AUTO and throws for an owned orphan graph', () => {
    const store = createGeneratedStore();
    store.replaceDomainState((candidate) => {
      candidate.workOrder.workOrders = candidate.workOrder.workOrders.filter(
        ({ id }) => !id.startsWith('C06-WO-'),
      );
      candidate.workOrder.nodes = candidate.workOrder.nodes.filter(
        ({ id }) => !id.startsWith('C06-NODE-'),
      );
    });
    expect(selectTaskTree(store.getState(), 'PLAN-001')).toEqual([]);

    const invalid = createGeneratedStore();
    invalid.replaceDomainState((candidate) => {
      const order = candidate.workOrder.workOrders.find(({ id }) => id === 'C06-WO-PLAN-001-G001-02');
      if (!order) throw new Error('C06 order missing.');
      order.parentId = 'C06-WO-MISSING';
    });
    expect(() => selectTaskTree(invalid.getState(), 'PLAN-001')).toThrow(/orphan/i);
  });
});
