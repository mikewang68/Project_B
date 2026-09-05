import { describe, expect, it } from 'vitest';
import {
  do001Schema,
  do002Schema,
  do004Schema,
  do007Schema,
  type Plan,
} from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import {
  RECOMMENDATION_RULE_VERSION,
  recommendationDraftSchema,
  type RecommendationDraft,
} from '../../recommendation';
import { TASK_DECOMPOSITION_RULE_VERSION } from '../constants';
import {
  explainTaskRoute,
  generateTaskDraft,
  projectTaskGraph,
} from '../ruleEngine';

const demoTime = '2026-07-16T09:00:00+08:00';

function plan(cargoType: Plan['cargoType'] = 'FLY_ASH', id = 'PLAN-001'): Plan {
  const raw = createFixtureSnapshot().objects['DO-001'][0];
  return do001Schema.parse({
    ...raw,
    id,
    cargoType,
    status: 'CONFIRMED',
    version: 2,
  });
}

function confirmedRecommendation(inputPlan: Plan): RecommendationDraft {
  const candidateId = `${inputPlan.id}:TRACK-001:2026-07-16T08:01:00+08:00`;
  return recommendationDraftSchema.parse({
    planId: inputPlan.id,
    draftVersion: 2,
    status: 'CONFIRMED',
    inputPlanVersion: inputPlan.version,
    ruleVersion: RECOMMENDATION_RULE_VERSION,
    generatedAt: demoTime,
    candidates: [{
      candidateId,
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
      reasons: ['冻结测试首选'],
      sourceRefs: [`PLAN:${inputPlan.id}`, 'TRACK:TRACK-001'],
    }],
    excluded: [],
    selectedCandidateId: candidateId,
    confirmation: {
      actorId: 'USER-DISPATCHER',
      roleCode: 'DISPATCHER',
      confirmedAt: demoTime,
      commandId: 'CMD-C05-001',
      traceId: 'TRACE-C05-001',
    },
  });
}

function frozenInputs(inputPlan = plan()) {
  const snapshot = createFixtureSnapshot();
  return {
    plan: inputPlan,
    recommendation: confirmedRecommendation(inputPlan),
    waybills: do002Schema.array().parse(snapshot.objects['DO-002']),
    materials: do004Schema.array().parse(snapshot.objects['DO-004']),
    resources: do007Schema.array().parse(snapshot.objects['DO-007']),
    demoTime,
    generationVersion: 1,
  } as const;
}

describe('C06 deterministic task rule engine', () => {
  it('generates the exact PLAN-001 G001 FLY_ASH four-stage chain and strict defaults', () => {
    const result = generateTaskDraft(frozenInputs());

    expect(result.workOrders.map(({ id, title, type, parentId }) => [id, title, type, parentId])).toEqual([
      ['C06-WO-PLAN-001-G001-01', '识别与路由确认', 'INSPECT', ''],
      ['C06-WO-PLAN-001-G001-02', '卸料准备', 'UNLOAD', 'C06-WO-PLAN-001-G001-01'],
      ['C06-WO-PLAN-001-G001-03', '输送转运', 'TRANSFER', 'C06-WO-PLAN-001-G001-02'],
      ['C06-WO-PLAN-001-G001-04', '筒仓入库', 'LOAD', 'C06-WO-PLAN-001-G001-03'],
    ]);
    expect(result.workOrders).toEqual(result.workOrders.map((item) => expect.objectContaining({
      workOrderNo: item.id,
      planId: 'PLAN-001',
      priority: 'HIGH',
      status: 'DRAFT',
      ackStatus: 'PENDING',
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      resourceId: '',
      teamId: '',
      blockReason: '',
      version: 1,
      createdAt: demoTime,
      updatedAt: demoTime,
    })));
    expect(result.nodes.map(({ id, nodeNo, sequence, status, plannedStartTime, plannedFinishTime }) => ({
      id,
      nodeNo,
      sequence,
      status,
      plannedStartTime,
      plannedFinishTime,
    }))).toEqual([
      {
        id: 'C06-NODE-PLAN-001-G001-01',
        nodeNo: 'C06-N-PLAN-001-G001-01',
        sequence: 1,
        status: 'WAITING',
        plannedStartTime: '2026-07-16T08:01:00+08:00',
        plannedFinishTime: '2026-07-16T08:31:00+08:00',
      },
      {
        id: 'C06-NODE-PLAN-001-G001-02',
        nodeNo: 'C06-N-PLAN-001-G001-02',
        sequence: 2,
        status: 'WAITING',
        plannedStartTime: '2026-07-16T08:31:00+08:00',
        plannedFinishTime: '2026-07-16T09:01:00+08:00',
      },
      {
        id: 'C06-NODE-PLAN-001-G001-03',
        nodeNo: 'C06-N-PLAN-001-G001-03',
        sequence: 3,
        status: 'WAITING',
        plannedStartTime: '2026-07-16T09:01:00+08:00',
        plannedFinishTime: '2026-07-16T09:31:00+08:00',
      },
      {
        id: 'C06-NODE-PLAN-001-G001-04',
        nodeNo: 'C06-N-PLAN-001-G001-04',
        sequence: 4,
        status: 'WAITING',
        plannedStartTime: '2026-07-16T09:31:00+08:00',
        plannedFinishTime: '2026-07-16T10:01:00+08:00',
      },
    ]);
    expect(result.nodes.every(({ actualStartTime, actualFinishTime, version, updatedAt }) =>
      actualStartTime === '' && actualFinishTime === '' && version === 1 && updatedAt === demoTime,
    )).toBe(true);
  });

  it('projects resource requirements, real AREA-A candidates, and stable traceability without inventing links', () => {
    const input = frozenInputs();
    const generation = generateTaskDraft(input);
    const tree = projectTaskGraph({ ...input, ...generation });

    expect(tree.map(({ stage, requiredResourceType, resourceCandidateIds }) => [
      stage,
      requiredResourceType,
      resourceCandidateIds,
    ])).toEqual([
      ['RECOGNITION', 'TEAM', []],
      ['UNLOAD', 'TIPPER', ['RESOURCE-001', 'RESOURCE-007']],
      ['TRANSFER', 'CONVEYOR', []],
      ['STORAGE', 'SILO', ['RESOURCE-004', 'RESOURCE-010']],
    ]);
    expect(tree[0]?.sourceRefs).toEqual([
      'PLAN:PLAN-001',
      'RECOMMENDATION:PLAN-001:TRACK-001:2026-07-16T08:01:00+08:00',
      'WAYBILL:WAYBILL-001',
      'WAYBILL:WAYBILL-005',
      'MATERIAL:MATERIAL-001',
      `RULE:${TASK_DECOMPOSITION_RULE_VERSION}`,
      'MAPPING:DEMO_STABLE_MAPPING',
    ]);
    expect(tree.map(({ objectId }) => objectId)).toEqual([
      'C06-OBJECT-PLAN-001-G001-01',
      'C06-OBJECT-PLAN-001-G001-02',
      'C06-OBJECT-PLAN-001-G001-03',
      'C06-OBJECT-PLAN-001-G001-04',
    ]);
    expect(tree[0]?.sourceRefs.some((ref) => ref.includes('quantity'))).toBe(false);
  });

  it.each([
    ['FLY_ASH', ['识别与路由确认', '卸料准备', '输送转运', '筒仓入库']],
    ['CEMENT', ['识别与路由确认', '卸料准备', '输送转运', '应急筒仓入库']],
    ['STEEL', ['规格重量校验', '重载吊装', 'AGV 转运', '货位入库']],
    ['GENERAL_CARGO', ['箱号包装校验', '掏装/卸载', '分拣转运', '入库或发运准备']],
  ] as const)('uses the frozen %s four-stage route', (cargoType, titles) => {
    const inputPlan = plan(cargoType, `PLAN-${cargoType}`);
    const result = generateTaskDraft(frozenInputs(inputPlan));

    expect(result.workOrders.map(({ title }) => title)).toEqual(titles);
    expect(result.workOrders.map(({ type }) => type)).toEqual(['INSPECT', 'UNLOAD', 'TRANSFER', 'LOAD']);
    expect(explainTaskRoute(cargoType).mappingMode).toBe('DEMO_STABLE_MAPPING');
  });

  it('documents the frozen GENERAL_CARGO limitation explicitly', () => {
    const explanation = explainTaskRoute('GENERAL_CARGO');

    expect(explanation.limitations).toContain('冻结货类枚举无法进一步区分机电设备和生活物资');
    expect(Object.isFrozen(explanation)).toBe(true);
    expect(Object.isFrozen(explanation.route)).toBe(true);
    expect(Object.isFrozen(explanation.limitations)).toBe(true);
  });

  it('does not mutate inputs and deep-freezes generated records and projections', () => {
    const input = frozenInputs();
    const before = structuredClone(input);
    const generation = generateTaskDraft(input);
    const tree = projectTaskGraph({ ...input, ...generation });

    expect(input).toEqual(before);
    expect(Object.isFrozen(generation)).toBe(true);
    expect(Object.isFrozen(generation.workOrders)).toBe(true);
    expect(Object.isFrozen(generation.workOrders[0])).toBe(true);
    expect(Object.isFrozen(generation.nodes)).toBe(true);
    expect(Object.isFrozen(tree)).toBe(true);
    expect(Object.isFrozen(tree[0])).toBe(true);
    expect(Object.isFrozen(tree[0]?.dependencyIds)).toBe(true);
    expect(Object.isFrozen(tree[0]?.resourceCandidateIds)).toBe(true);
    expect(Object.isFrozen(tree[0]?.sourceRefs)).toBe(true);
  });

  it.each([
    ['invalid interval', { arrivalDepartureTime: 'invalid' }],
    ['non-positive interval', { arrivalDepartureTime: '2026-07-16T10:01:00+08:00/2026-07-16T08:01:00+08:00' }],
    ['invalid demo time', { demoTime: 'invalid' }],
    ['invalid generation', { generationVersion: 0 }],
  ] as const)('rejects %s', (_label, patch) => {
    const input = frozenInputs();
    const candidate = {
      ...input,
      ...patch,
      plan: 'arrivalDepartureTime' in patch
        ? do001Schema.parse({ ...input.plan, arrivalDepartureTime: patch.arrivalDepartureTime })
        : input.plan,
    };

    expect(() => generateTaskDraft(candidate)).toThrow();
  });

  it('rejects an unconfirmed or mismatched C05 recommendation', () => {
    const input = frozenInputs();
    const calculated = recommendationDraftSchema.parse({
      ...input.recommendation,
      status: 'CALCULATED',
      confirmation: undefined,
    });
    const otherPlan = plan('FLY_ASH', 'PLAN-OTHER');

    expect(() => generateTaskDraft({ ...input, recommendation: calculated })).toThrow();
    expect(() => generateTaskDraft({ ...input, recommendation: confirmedRecommendation(otherPlan) })).toThrow();
  });
});
