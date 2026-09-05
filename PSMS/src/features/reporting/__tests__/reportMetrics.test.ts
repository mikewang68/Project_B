import { describe, expect, it } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { RECOMMENDATION_RULE_VERSION } from '../../recommendation/schemas';
import { deriveReportMetrics } from '../reportMetrics';

const session: DemoSessionSeed = {
  actorId: 'USER-BUSINESS',
  roleCode: 'BUSINESS',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
};

function createStore() {
  return createDemoStore(createFixtureSnapshot(), session);
}

describe('C11 deterministic report metrics', () => {
  it('derives the eight KPI and five rates from fresh SCN-01 Store facts', () => {
    const store = createStore();
    const before = structuredClone(store.getState());
    const metrics = deriveReportMetrics(store.getState());

    expect(metrics.kpis).toEqual({
      planTotal: 3,
      confirmedPlanCount: 0,
      appliedRecommendationCount: 0,
      generatedWorkOrderCount: 12,
      dispatchedWorkOrderCount: 5,
      exceptionCount: 5,
      interlockCount: 4,
      mergedOfflinePacketCount: 0,
    });
    expect(metrics.rates.map(({ key, value, numerator, denominator }) => [
      key, value, numerator, denominator,
    ])).toEqual([
      ['planConfirmationRate', 0, 0, 3],
      ['taskDecompositionRate', 100, 3, 3],
      ['dispatchRate', 42, 5, 12],
      ['exceptionClosureRate', 20, 1, 5],
      ['offlineMergeRate', 0, 0, 4],
    ]);
    expect(store.getState()).toEqual(before);
  });

  it('counts only schema-valid confirmed C05 drafts as applied recommendations', () => {
    const store = createStore();
    store.replaceDomainState((candidate) => {
      candidate.recommendation.drafts['PLAN-001'] = {
        planId: 'PLAN-001',
        draftVersion: 2,
        status: 'CONFIRMED',
        inputPlanVersion: 1,
        ruleVersion: RECOMMENDATION_RULE_VERSION,
        generatedAt: candidate.session.demoTime,
        candidates: [{
          candidateId: 'REC-CANDIDATE-001',
          trackId: 'TRACK-001',
          trackNo: 'T1',
          trackVersion: 1,
          occupyStatus: 'FREE',
          windowStart: '2026-07-16T08:01:00+08:00',
          windowEnd: '2026-07-16T10:01:00+08:00',
          score: 100,
          rank: 1,
          recommended: true,
          scoreBreakdown: { availability: 45, timing: 30, continuity: 15, authority: 10 },
          reasons: ['确定性推荐'],
          sourceRefs: ['PLAN:PLAN-001', 'TRACK:TRACK-001'],
        }],
        excluded: [],
        selectedCandidateId: 'REC-CANDIDATE-001',
        confirmation: {
          actorId: candidate.session.actorId,
          roleCode: candidate.session.roleCode,
          confirmedAt: candidate.session.demoTime,
          commandId: 'CMD-C05-001',
          traceId: 'TRACE-C05-001',
        },
      };
      candidate.recommendation.drafts.INVALID = { status: 'CONFIRMED' };
    });

    expect(deriveReportMetrics(store.getState()).kpis.appliedRecommendationCount).toBe(1);
  });

  it('returns zero-safe rates and preserves frozen enum order including zero values', () => {
    const store = createStore();
    store.replaceDomainState((candidate) => {
      candidate.plan.plans = [];
      candidate.workOrder.workOrders = [];
      candidate.exception.exceptions = [];
      candidate.interlock.interlocks = [];
      candidate.offline.packets = [];
    });

    const metrics = deriveReportMetrics(store.getState());
    expect(metrics.rates.every(({ value }) => value === 0)).toBe(true);
    expect(metrics.distributions.planStatus.items.map(({ key }) => key)).toEqual([
      'RECEIVED', 'VALIDATING', 'PENDING_CONFIRM', 'CONFIRMED',
      'DECOMPOSED', 'BLOCKED', 'CANCELLED', 'ADJUSTED',
    ]);
    expect(metrics.distributions.workOrderStatus.items.every(({ value }) => value === 0)).toBe(true);
    expect(metrics.distributions.exceptionLevel.items.every(({ value }) => value === 0)).toBe(true);
    expect(metrics.distributions.interlockAction.items.every(({ value }) => value === 0)).toBe(true);
    expect(metrics.distributions.offlineStatus.items.every(({ value }) => value === 0)).toBe(true);
  });

  it('publishes deterministic formula/source disclosure and a flat DO-012 snapshot', () => {
    const metrics = deriveReportMetrics(createStore().getState());

    expect(metrics.disclosure).toBe('演示用确定性统计口径');
    expect(metrics.asOf).toBe('2026-07-16T09:00:00+08:00');
    expect(metrics.definitions.find(({ key }) => key === 'offlineMergeRate')).toEqual({
      key: 'offlineMergeRate',
      label: '离线同步合并率',
      source: 'DO-011 离线包合并状态',
      formula: '已合并离线包数量 / 离线包总数；分母为 0 时取 0%',
    });
    expect(metrics.flatMetrics).toEqual({
      planTotal: 3,
      confirmedPlanCount: 0,
      appliedRecommendationCount: 0,
      generatedWorkOrderCount: 12,
      dispatchedWorkOrderCount: 5,
      exceptionCount: 5,
      interlockCount: 4,
      mergedOfflinePacketCount: 0,
      planConfirmationRate: 0,
      taskDecompositionRate: 100,
      dispatchRate: 42,
      exceptionClosureRate: 20,
      offlineMergeRate: 0,
    });
    expect(Object.isFrozen(metrics)).toBe(true);
    expect(Object.isFrozen(metrics.flatMetrics)).toBe(true);
    expect(Object.isFrozen(metrics.definitions)).toBe(true);
  });
});
