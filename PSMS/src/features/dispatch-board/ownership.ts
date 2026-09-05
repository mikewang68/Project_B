import type { Resource, WorkNode, WorkOrder } from '../../contracts';
import { DISPATCH_BOARD_RULE_VERSION } from './constants';

const dispatchStatuses = new Set<WorkOrder['status']>([
  'READY',
  'DISPATCHED',
  'ACKNOWLEDGED',
  'IN_PROGRESS',
  'COMPLETED',
  'PAUSED',
  'BLOCKED',
  'FAILED',
  'CANCELLED',
]);

export function hasAreaAVisibility(dataScope: readonly string[]): boolean {
  return dataScope.includes('*')
    || dataScope.includes('GLOBAL')
    || dataScope.includes('AREA-A');
}

export function isDispatchBoardWorkOrder(order: WorkOrder, planId: string): boolean {
  return order.planId === planId
    && order.id.startsWith('C06-WO-')
    && order.workOrderNo.startsWith('C06-WO-')
    && order.ruleVersion === DISPATCH_BOARD_RULE_VERSION
    && dispatchStatuses.has(order.status);
}

export function isDispatchBoardWorkNode(
  node: WorkNode,
  ownedWorkOrderNumbers: ReadonlySet<string>,
): boolean {
  return node.id.startsWith('C06-NODE-')
    && node.nodeNo.startsWith('C06-N-')
    && ownedWorkOrderNumbers.has(node.workOrderNo);
}

export function isDispatchResourceVisible(
  resource: Resource,
  dataScope: readonly string[],
): boolean {
  return hasAreaAVisibility(dataScope)
    && (resource.workArea === 'AREA-A'
      || resource.workArea === 'GLOBAL'
      || resource.workArea === '*');
}
