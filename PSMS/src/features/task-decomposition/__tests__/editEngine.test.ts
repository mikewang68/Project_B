import { describe, expect, it } from 'vitest';
import {
  do001Schema,
  do002Schema,
  do004Schema,
  do007Schema,
  type Plan,
  type WorkNode,
  type WorkOrder,
} from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import {
  RECOMMENDATION_RULE_VERSION,
  recommendationDraftSchema,
} from '../../recommendation';
import { mergeTaskDraft, splitTaskDraft } from '../editEngine';
import { generateTaskDraft } from '../ruleEngine';
import type { TaskGeneration } from '../types';

const demoTime = '2026-07-16T09:30:00+08:00';

function generation(): TaskGeneration {
  const snapshot = createFixtureSnapshot();
  const rawPlan = snapshot.objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  if (!rawPlan) throw new Error('PLAN-001 missing.');
  const plan = do001Schema.parse({ ...rawPlan, status: 'CONFIRMED', version: 2 });
  const candidateId = 'PLAN-001:TRACK-001:2026-07-16T08:01:00+08:00';
  const recommendation = recommendationDraftSchema.parse({
    planId: plan.id,
    draftVersion: 2,
    status: 'CONFIRMED',
    inputPlanVersion: plan.version,
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
      sourceRefs: ['PLAN:PLAN-001', 'TRACK:TRACK-001'],
    }],
    excluded: [],
    selectedCandidateId: candidateId,
    confirmation: {
      actorId: 'USER-001',
      roleCode: 'DISPATCHER',
      confirmedAt: demoTime,
      commandId: 'CMD-C05-001',
      traceId: 'TRACE-C05-001',
    },
  });
  return generateTaskDraft({
    plan: plan as Plan,
    recommendation,
    waybills: do002Schema.array().parse(snapshot.objects['DO-002']),
    materials: do004Schema.array().parse(snapshot.objects['DO-004']),
    resources: do007Schema.array().parse(snapshot.objects['DO-007']),
    demoTime,
    generationVersion: 1,
  });
}

function mutable(input: TaskGeneration): { workOrders: WorkOrder[]; nodes: WorkNode[] } {
  return {
    workOrders: [...structuredClone(input.workOrders)],
    nodes: [...structuredClone(input.nodes)],
  };
}

describe('splitTaskDraft', () => {
  it('replaces stage 02 with deterministic A/B pairs and reconnects the direct chain', () => {
    const input = generation();
    const before = structuredClone(input);

    const result = splitTaskDraft({
      ...input,
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      demoTime,
    });

    expect(input).toEqual(before);
    expect(result.workOrders.map(({ id, parentId, title }) => [id, parentId, title])).toEqual([
      ['C06-WO-PLAN-001-G001-01', '', '识别与路由确认'],
      ['C06-WO-PLAN-001-G001-02-S001-A', 'C06-WO-PLAN-001-G001-01', '卸料准备 A段'],
      ['C06-WO-PLAN-001-G001-02-S001-B', 'C06-WO-PLAN-001-G001-02-S001-A', '卸料准备 B段'],
      ['C06-WO-PLAN-001-G001-03', 'C06-WO-PLAN-001-G001-02-S001-B', '输送转运'],
      ['C06-WO-PLAN-001-G001-04', 'C06-WO-PLAN-001-G001-03', '筒仓入库'],
    ]);
    expect(result.nodes.map(({ id, sequence, plannedStartTime, plannedFinishTime }) => [
      id,
      sequence,
      plannedStartTime,
      plannedFinishTime,
    ])).toEqual([
      ['C06-NODE-PLAN-001-G001-01', 1, '2026-07-16T08:01:00+08:00', '2026-07-16T08:31:00+08:00'],
      ['C06-NODE-PLAN-001-G001-02-S001-A', 2, '2026-07-16T08:31:00+08:00', '2026-07-16T08:46:00+08:00'],
      ['C06-NODE-PLAN-001-G001-02-S001-B', 3, '2026-07-16T08:46:00+08:00', '2026-07-16T09:01:00+08:00'],
      ['C06-NODE-PLAN-001-G001-03', 4, '2026-07-16T09:01:00+08:00', '2026-07-16T09:31:00+08:00'],
      ['C06-NODE-PLAN-001-G001-04', 5, '2026-07-16T09:31:00+08:00', '2026-07-16T10:01:00+08:00'],
    ]);
    expect(result.workOrders.some(({ id }) => id === 'C06-WO-PLAN-001-G001-02')).toBe(false);
    expect(result.nodes.some(({ id }) => id === 'C06-NODE-PLAN-001-G001-02')).toBe(false);
    expect(Object.isFrozen(result)).toBe(true);
    expect(Object.isFrozen(result.workOrders)).toBe(true);
    expect(Object.isFrozen(result.nodes[0])).toBe(true);
  });

  it('increments the persisted split suffix instead of relying on a page counter', () => {
    const first = splitTaskDraft({
      ...generation(),
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      demoTime,
    });

    const second = splitTaskDraft({
      ...first,
      targetNodeId: 'C06-NODE-PLAN-001-G001-02-S001-B',
      demoTime,
    });

    expect(second.workOrders.map(({ id }) => id)).toContain(
      'C06-WO-PLAN-001-G001-02-S001-B-S002-A',
    );
    expect(second.workOrders.map(({ id }) => id)).toContain(
      'C06-WO-PLAN-001-G001-02-S001-B-S002-B',
    );
  });

  it.each([
    ['INSPECT', (_value: ReturnType<typeof mutable>): string => 'C06-NODE-PLAN-001-G001-01'],
    ['unknown node', (value: ReturnType<typeof mutable>): string => `${value.nodes[1]!.id}-UNKNOWN`],
    ['READY order', (value: ReturnType<typeof mutable>): string => {
      value.workOrders[1]!.status = 'READY';
      return value.nodes[1]!.id;
    }],
    ['executed node', (value: ReturnType<typeof mutable>): string => {
      value.nodes[1]!.actualStartTime = '2026-07-16T08:31:00+08:00';
      return value.nodes[1]!.id;
    }],
    ['zero duration', (value: ReturnType<typeof mutable>): string => {
      value.nodes[1]!.plannedFinishTime = value.nodes[1]!.plannedStartTime;
      return value.nodes[1]!.id;
    }],
    ['orphan graph', (value: ReturnType<typeof mutable>): string => {
      value.workOrders[2]!.parentId = 'C06-WO-MISSING';
      return value.nodes[1]!.id;
    }],
  ] as const)('rejects %s', (_label, arrange) => {
    const input = mutable(generation());
    const targetNodeId = arrange(input);

    expect(() => splitTaskDraft({ ...input, targetNodeId, demoTime })).toThrow();
  });
});

describe('mergeTaskDraft', () => {
  function splitGeneration(): TaskGeneration {
    return splitTaskDraft({
      ...generation(),
      targetNodeId: 'C06-NODE-PLAN-001-G001-02',
      demoTime,
    });
  }

  const pair = [
    'C06-NODE-PLAN-001-G001-02-S001-A',
    'C06-NODE-PLAN-001-G001-02-S001-B',
  ] as const;

  it('merges only the direct A/B chain, restores its interval, and reconnects the child', () => {
    const input = splitGeneration();
    const before = structuredClone(input);

    const result = mergeTaskDraft({ ...input, nodeIds: pair, demoTime });

    expect(input).toEqual(before);
    expect(result.workOrders.map(({ id, parentId, title }) => [id, parentId, title])).toEqual([
      ['C06-WO-PLAN-001-G001-01', '', '识别与路由确认'],
      ['C06-WO-PLAN-001-G001-02-M001', 'C06-WO-PLAN-001-G001-01', '卸料准备'],
      ['C06-WO-PLAN-001-G001-03', 'C06-WO-PLAN-001-G001-02-M001', '输送转运'],
      ['C06-WO-PLAN-001-G001-04', 'C06-WO-PLAN-001-G001-03', '筒仓入库'],
    ]);
    expect(result.nodes.map(({ id, sequence, plannedStartTime, plannedFinishTime }) => [
      id,
      sequence,
      plannedStartTime,
      plannedFinishTime,
    ])).toEqual([
      ['C06-NODE-PLAN-001-G001-01', 1, '2026-07-16T08:01:00+08:00', '2026-07-16T08:31:00+08:00'],
      ['C06-NODE-PLAN-001-G001-02-M001', 2, '2026-07-16T08:31:00+08:00', '2026-07-16T09:01:00+08:00'],
      ['C06-NODE-PLAN-001-G001-03', 3, '2026-07-16T09:01:00+08:00', '2026-07-16T09:31:00+08:00'],
      ['C06-NODE-PLAN-001-G001-04', 4, '2026-07-16T09:31:00+08:00', '2026-07-16T10:01:00+08:00'],
    ]);
  });

  it.each([
    ['reversed nodes', (value: ReturnType<typeof mutable>) => [pair[1], pair[0]] as const],
    ['nonadjacent nodes', (value: ReturnType<typeof mutable>) => [pair[0], value.nodes[3]!.id] as const],
    ['different type', (value: ReturnType<typeof mutable>) => {
      value.workOrders[2]!.type = 'TRANSFER';
      return pair;
    }],
    ['different rule', (value: ReturnType<typeof mutable>) => {
      value.workOrders[2]!.ruleVersion = 'C06-DEMO-RULE-2.0';
      return pair;
    }],
    ['not direct chain', (value: ReturnType<typeof mutable>) => {
      value.workOrders[2]!.parentId = value.workOrders[0]!.id;
      return pair;
    }],
    ['READY order', (value: ReturnType<typeof mutable>) => {
      value.workOrders[2]!.status = 'READY';
      return pair;
    }],
    ['executed node', (value: ReturnType<typeof mutable>) => {
      value.nodes[2]!.actualFinishTime = '2026-07-16T09:01:00+08:00';
      return pair;
    }],
  ] as const)('rejects %s', (_label, arrange) => {
    const input = mutable(splitGeneration());
    const nodeIds = arrange(input);

    expect(() => mergeTaskDraft({ ...input, nodeIds, demoTime })).toThrow();
  });
});
