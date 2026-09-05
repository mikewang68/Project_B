import { describe, expect, it } from 'vitest';
import { do005Schema, do006Schema, type WorkNode, type WorkOrder } from '../../../contracts';
import { TASK_DECOMPOSITION_RULE_VERSION } from '../constants';
import {
  isC06WorkNode,
  isC06WorkOrder,
  parseGenerationVersion,
  validateTaskGeneration,
} from '../ownership';
import type { TaskGeneration } from '../types';

function order(index: number, parentId = ''): WorkOrder {
  const suffix = String(index).padStart(2, '0');
  return do005Schema.parse({
    id: `C06-WO-PLAN-001-G001-${suffix}`,
    workOrderNo: `C06-WO-PLAN-001-G001-${suffix}`,
    planId: 'PLAN-001',
    parentId,
    type: index === 1 ? 'INSPECT' : 'UNLOAD',
    title: index === 1 ? '识别与路由确认' : '卸料准备',
    priority: 'HIGH',
    status: 'DRAFT',
    ackStatus: 'PENDING',
    ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
    resourceId: '',
    teamId: '',
    blockReason: '',
    version: 1,
    createdAt: '2026-07-16T09:00:00+08:00',
    updatedAt: '2026-07-16T09:00:00+08:00',
  });
}

function node(index: number, workOrderNo: string): WorkNode {
  const suffix = String(index).padStart(2, '0');
  return do006Schema.parse({
    id: `C06-NODE-PLAN-001-G001-${suffix}`,
    nodeNo: `C06-N-PLAN-001-G001-${suffix}`,
    workOrderNo,
    sequence: index,
    status: 'WAITING',
    plannedStartTime: `2026-07-16T0${7 + index}:01:00+08:00`,
    plannedFinishTime: `2026-07-16T0${8 + index}:01:00+08:00`,
    actualStartTime: '',
    actualFinishTime: '',
    version: 1,
    updatedAt: '2026-07-16T09:00:00+08:00',
  });
}

function validGeneration(): TaskGeneration {
  const first = order(1);
  const second = order(2, first.id);
  return {
    workOrders: [first, second],
    nodes: [node(1, first.workOrderNo), node(2, second.workOrderNo)],
  };
}

describe('C06 task ownership', () => {
  it('requires plan, both WorkOrder prefixes, and the frozen rule version together', () => {
    const base = order(1);

    expect(isC06WorkOrder(base, 'PLAN-001')).toBe(true);
    expect(isC06WorkOrder({ ...base, id: 'WO-001' }, 'PLAN-001')).toBe(false);
    expect(isC06WorkOrder({ ...base, workOrderNo: 'WO-001' }, 'PLAN-001')).toBe(false);
    expect(isC06WorkOrder({ ...base, planId: 'PLAN-002' }, 'PLAN-001')).toBe(false);
    expect(isC06WorkOrder({ ...base, ruleVersion: 'C06-DEMO-RULE-1.1' }, 'PLAN-001')).toBe(false);
  });

  it('requires both WorkNode prefixes and rejects legacy near-matches', () => {
    const base = node(1, order(1).workOrderNo);

    expect(isC06WorkNode(base)).toBe(true);
    expect(isC06WorkNode({ ...base, id: 'NODE-001' })).toBe(false);
    expect(isC06WorkNode({ ...base, nodeNo: 'NODE-001' })).toBe(false);
  });

  it.each([
    ['C06-WO-PLAN-001-G001-01', 1],
    ['C06-NODE-PLAN-001-G002-04', 2],
    ['C06-WO-PLAN-001-G019-02-S001-A', 19],
    ['C06-WO-PLAN-001-01', undefined],
    ['C06-WO-PLAN-001-G000-01', undefined],
  ])('parses the persisted generation from %s', (id, expected) => {
    expect(parseGenerationVersion(id)).toBe(expected);
  });
});

describe('C06 task generation invariants', () => {
  it('strictly parses, clone-freezes, and returns a valid one-to-one graph', () => {
    const input = validGeneration();
    const result = validateTaskGeneration(input, 'PLAN-001');

    expect(result).toEqual(input);
    expect(result).not.toBe(input);
    expect(Object.isFrozen(result)).toBe(true);
    expect(Object.isFrozen(result.workOrders)).toBe(true);
    expect(Object.isFrozen(result.workOrders[0])).toBe(true);
    expect(Object.isFrozen(result.nodes)).toBe(true);
    expect(Object.isFrozen(result.nodes[0])).toBe(true);
  });

  it.each([
    ['duplicate WorkOrder id', (value: TaskGeneration) => {
      value.workOrders[1]!.id = value.workOrders[0]!.id;
    }],
    ['duplicate WorkOrder number', (value: TaskGeneration) => {
      value.workOrders[1]!.workOrderNo = value.workOrders[0]!.workOrderNo;
    }],
    ['duplicate WorkNode id', (value: TaskGeneration) => {
      value.nodes[1]!.id = value.nodes[0]!.id;
    }],
    ['duplicate WorkNode number', (value: TaskGeneration) => {
      value.nodes[1]!.nodeNo = value.nodes[0]!.nodeNo;
    }],
    ['noncontiguous sequence', (value: TaskGeneration) => {
      value.nodes[1]!.sequence = 3;
    }],
    ['orphan parent', (value: TaskGeneration) => {
      value.workOrders[1]!.parentId = 'C06-WO-MISSING';
    }],
    ['cycle', (value: TaskGeneration) => {
      value.workOrders[0]!.parentId = value.workOrders[1]!.id;
    }],
    ['mismatched pair', (value: TaskGeneration) => {
      value.nodes[1]!.workOrderNo = 'C06-WO-MISSING';
    }],
    ['non-draft WorkOrder', (value: TaskGeneration) => {
      value.workOrders[1]!.status = 'READY';
    }],
    ['non-waiting WorkNode', (value: TaskGeneration) => {
      value.nodes[1]!.status = 'READY';
    }],
  ] as const)('rejects %s', (_label, mutate) => {
    const candidate = structuredClone(validGeneration());
    mutate(candidate);
    expect(() => validateTaskGeneration(candidate, 'PLAN-001')).toThrow();
  });

  it('rejects a strict DO-005 extra field instead of stripping it', () => {
    const candidate = structuredClone(validGeneration()) as TaskGeneration & {
      workOrders: Array<WorkOrder & { unexpected?: string }>;
    };
    candidate.workOrders[0]!.unexpected = 'not-allowed';

    expect(() => validateTaskGeneration(candidate, 'PLAN-001')).toThrow();
  });

  it('rejects plan and ownership near-matches before accepting the graph', () => {
    const candidate = structuredClone(validGeneration());
    candidate.workOrders[0]!.ruleVersion = 'C06-DEMO-RULE-2.0';

    expect(() => validateTaskGeneration(candidate, 'PLAN-001')).toThrow();
    expect(() => validateTaskGeneration(validGeneration(), 'PLAN-002')).toThrow();
  });
});
