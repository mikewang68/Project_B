import { describe, expect, it } from 'vitest';
import { do005Schema, do006Schema, do007Schema } from '../../../contracts';
import {
  isDispatchBoardWorkNode,
  isDispatchBoardWorkOrder,
  isDispatchResourceVisible,
} from '../ownership';

const readyOrder = do005Schema.parse({
  id: 'C06-WO-PLAN-001-G001-01',
  workOrderNo: 'C06-WO-PLAN-001-G001-01',
  planId: 'PLAN-001',
  parentId: '',
  type: 'INSPECT',
  title: '识别与路由确认',
  priority: 'HIGH',
  status: 'READY',
  ackStatus: 'PENDING',
  ruleVersion: 'C06-DEMO-RULE-1.0',
  resourceId: '',
  teamId: '',
  blockReason: '',
  version: 2,
  createdAt: '2026-07-16T08:01:00+08:00',
  updatedAt: '2026-07-16T09:01:00+08:00',
});

const waitingNode = do006Schema.parse({
  id: 'C06-NODE-PLAN-001-G001-01',
  nodeNo: 'C06-N-PLAN-001-G001-01',
  workOrderNo: readyOrder.workOrderNo,
  sequence: 1,
  status: 'WAITING',
  plannedStartTime: '2026-07-16T08:01:00+08:00',
  plannedFinishTime: '2026-07-16T08:31:00+08:00',
  actualStartTime: '',
  actualFinishTime: '',
  version: 1,
  updatedAt: '2026-07-16T09:01:00+08:00',
});

const areaAResource = do007Schema.parse({
  id: 'RESOURCE-001',
  deviceId: 'DEVICE-001',
  personId: '',
  resourceType: 'TIPPER',
  capabilityTags: ['FLY_ASH'],
  status: 'AVAILABLE',
  workArea: 'AREA-A',
  location: 'AREA-A-BERTH',
  version: 1,
  updatedAt: '2026-07-16T09:01:00+08:00',
});

describe('C07 dispatch-board ownership', () => {
  it('accepts only C06-owned READY or later work orders for the selected plan', () => {
    expect(isDispatchBoardWorkOrder(readyOrder, 'PLAN-001')).toBe(true);
    expect(isDispatchBoardWorkOrder({ ...readyOrder, status: 'DRAFT' }, 'PLAN-001')).toBe(false);
    expect(isDispatchBoardWorkOrder({ ...readyOrder, id: 'WO-001' }, 'PLAN-001')).toBe(false);
    expect(isDispatchBoardWorkOrder({ ...readyOrder, workOrderNo: 'WO-001' }, 'PLAN-001')).toBe(false);
    expect(isDispatchBoardWorkOrder({ ...readyOrder, planId: 'PLAN-002' }, 'PLAN-001')).toBe(false);
    expect(isDispatchBoardWorkOrder({ ...readyOrder, ruleVersion: 'C06-DEMO-RULE-2.0' }, 'PLAN-001')).toBe(false);
  });

  it('requires C06 node prefixes and a matching owned work-order number', () => {
    const ownedNumbers = new Set([readyOrder.workOrderNo]);

    expect(isDispatchBoardWorkNode(waitingNode, ownedNumbers)).toBe(true);
    expect(isDispatchBoardWorkNode({ ...waitingNode, id: 'NODE-001' }, ownedNumbers)).toBe(false);
    expect(isDispatchBoardWorkNode({ ...waitingNode, nodeNo: 'NODE-001' }, ownedNumbers)).toBe(false);
    expect(isDispatchBoardWorkNode({ ...waitingNode, workOrderNo: 'C06-WO-OTHER' }, ownedNumbers)).toBe(false);
  });

  it('exposes only AREA-A/GLOBAL/* resources to an AREA-A-visible session', () => {
    expect(isDispatchResourceVisible(areaAResource, ['AREA-A'])).toBe(true);
    expect(isDispatchResourceVisible({ ...areaAResource, workArea: 'GLOBAL' }, ['AREA-A'])).toBe(true);
    expect(isDispatchResourceVisible({ ...areaAResource, workArea: '*' }, ['AREA-A'])).toBe(true);
    expect(isDispatchResourceVisible({ ...areaAResource, workArea: 'AREA-B' }, ['AREA-A'])).toBe(false);
    expect(isDispatchResourceVisible(areaAResource, ['AREA-B'])).toBe(false);
    expect(isDispatchResourceVisible(areaAResource, ['GLOBAL'])).toBe(true);
    expect(isDispatchResourceVisible(areaAResource, ['*'])).toBe(true);
  });
});
