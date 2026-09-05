import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { calculateReceptionRecommendation } from '../ruleEngine';
import { recommendationDraftSchema } from '../schemas';
import {
  selectAffectedWorkOrders,
  selectConfirmedPlan,
  selectReceptionRecommendations,
  selectTrackConflicts,
} from '../selectors';

function createStore(dataScope: string[] = ['AREA-A']) {
  const session: DemoSessionSeed = {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
    dataScope,
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  };
  return createDemoStore(createFixtureSnapshot(), session);
}

function createConfirmedState() {
  const store = createStore();
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
    candidate.recommendation.drafts[plan.id] = recommendationDraftSchema.parse({
      planId: plan.id,
      draftVersion: 1,
      status: 'CALCULATED',
      inputPlanVersion: plan.version,
      ruleVersion: 'C05-DEMO-RULE-1.0',
      generatedAt: candidate.session.demoTime,
      candidates: calculation.candidates,
      excluded: [
        {
          trackId: 'TRACK-009',
          trackNo: 'T9',
          exclusionCode: 'CARGO_INCOMPATIBLE',
          reason: '股道不兼容计划货类',
          sourceRefs: ['PLAN:PLAN-001', 'TRACK:TRACK-009'],
        },
        ...calculation.excluded,
      ],
    });
  });
  return store.getState();
}

describe('C05 recommendation selectors', () => {
  it('returns PLAN-001 only after confirmation and returns immutable copies', () => {
    const pendingState = createStore().getState();
    expect(selectConfirmedPlan(pendingState, 'PLAN-001')).toBeUndefined();

    const state = createConfirmedState();
    const rawPlan = state.plan.plans.find(({ id }) => id === 'PLAN-001');
    const plan = selectConfirmedPlan(state, 'PLAN-001');
    const draft = selectReceptionRecommendations(state, 'PLAN-001');

    expect(plan).toMatchObject({ id: 'PLAN-001', status: 'CONFIRMED', version: 2 });
    expect(plan).not.toBe(rawPlan);
    expect(Object.isFrozen(plan)).toBe(true);
    expect(draft).toMatchObject({ planId: 'PLAN-001', status: 'CALCULATED' });
    expect(Object.isFrozen(draft)).toBe(true);
    expect(Object.isFrozen(draft?.candidates)).toBe(true);
  });

  it('parses drafts strictly and filters AREA-A before lookup, parsing, or association', () => {
    const inScopeStore = createStore();
    inScopeStore.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
      if (!plan) throw new Error('PLAN-001 missing.');
      plan.status = 'CONFIRMED';
      candidate.recommendation.drafts['PLAN-001'] = { malformed: true };
    });
    expect(() => selectReceptionRecommendations(inScopeStore.getState(), 'PLAN-001')).toThrow(
      ZodError,
    );

    const outOfScopeStore = createStore(['AREA-B']);
    outOfScopeStore.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
      if (!plan) throw new Error('PLAN-001 missing.');
      plan.status = 'CONFIRMED';
      candidate.recommendation.drafts['PLAN-001'] = { malformed: true };
    });
    const state = outOfScopeStore.getState();

    expect(selectConfirmedPlan(state, 'PLAN-001')).toBeUndefined();
    expect(selectReceptionRecommendations(state, 'PLAN-001')).toBeUndefined();
    expect(selectTrackConflicts(state, 'PLAN-001')).toEqual([]);
    expect(selectAffectedWorkOrders(state, 'PLAN-001')).toEqual([]);
  });

  it('maps exclusions and affected work orders in deterministic order', () => {
    const state = createConfirmedState();
    const conflicts = selectTrackConflicts(state, 'PLAN-001');
    const workOrders = selectAffectedWorkOrders(state, 'PLAN-001');

    expect(conflicts.map(({ trackNo, exclusionCode }) => [trackNo, exclusionCode])).toEqual([
      ['T4', 'TRACK_BLOCKED'],
      ['T9', 'CARGO_INCOMPATIBLE'],
    ]);
    expect(workOrders.map(({ id }) => id)).toEqual(['WO-001', 'WO-004', 'WO-007', 'WO-010']);
    expect(Object.isFrozen(conflicts)).toBe(true);
    expect(Object.isFrozen(conflicts[0])).toBe(true);
    expect(Object.isFrozen(conflicts[0]?.sourceRefs)).toBe(true);
    expect(Object.isFrozen(workOrders)).toBe(true);
    expect(Object.isFrozen(workOrders[0])).toBe(true);
  });
});
