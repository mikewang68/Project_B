import type { Resource, WorkOrder } from '../../contracts';
import type { DemoRootState } from '../../stores';
import { selectTaskTree } from '../task-decomposition';
import { DISPATCH_BOARD_RULE_VERSION } from './constants';
import {
  hasAreaAVisibility,
  isDispatchBoardWorkNode,
  isDispatchBoardWorkOrder,
  isDispatchResourceVisible,
} from './ownership';
import type {
  DispatchBoardView,
  DispatchKpis,
  DispatchProgressState,
  DispatchResourceCandidate,
  DispatchWorkOrderView,
} from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function progressState(order: WorkOrder): DispatchProgressState {
  if (order.status === 'READY') {
    return order.resourceId !== '' && order.teamId !== '' ? 'ASSIGNED' : 'READY_QUEUE';
  }
  if (order.status === 'DISPATCHED') return 'DISPATCHED';
  return 'EXECUTING';
}

function projectKpis(orders: readonly DispatchWorkOrderView[]): DispatchKpis {
  return {
    ready: orders.filter(({ workOrder }) => workOrder.status === 'READY').length,
    assigned: orders.filter(({ workOrder }) =>
      workOrder.resourceId !== '' && workOrder.teamId !== '',
    ).length,
    dispatched: orders.filter(({ workOrder }) => workOrder.status === 'DISPATCHED').length,
    executing: orders.filter(({ workOrder }) =>
      workOrder.status === 'ACKNOWLEDGED'
      || workOrder.status === 'IN_PROGRESS'
      || workOrder.status === 'PAUSED',
    ).length,
    completed: orders.filter(({ workOrder }) => workOrder.status === 'COMPLETED').length,
    exceptionEntry: orders.filter(({ workOrder }) =>
      workOrder.status === 'BLOCKED'
      || workOrder.status === 'FAILED'
      || workOrder.blockReason !== '',
    ).length,
  };
}

export function selectAssignableResources(
  state: DemoRootState,
  requiredResourceType: Resource['resourceType'],
): readonly DispatchResourceCandidate[] {
  if (!hasAreaAVisibility(state.session.dataScope)) return deepFreeze([]);
  const candidates = state.resource.resources
    .filter((resource) => isDispatchResourceVisible(resource, state.session.dataScope))
    .filter(({ resourceType }) => resourceType === requiredResourceType)
    .sort((left, right) => left.id.localeCompare(right.id))
    .map((resource): DispatchResourceCandidate => {
      const assignable = resource.status === 'AVAILABLE';
      return {
        ...structuredClone(resource),
        assignable,
        unavailableReason: assignable
          ? undefined
          : `资源状态 ${resource.status}，不可分配`,
      };
    });
  return deepFreeze(candidates);
}

export function selectDispatchBoard(
  state: DemoRootState,
  planId: string,
): DispatchBoardView | undefined {
  if (!hasAreaAVisibility(state.session.dataScope)) return undefined;
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || plan.status !== 'DECOMPOSED') return undefined;

  const taskTree = selectTaskTree(state, planId);
  const taskByOrderId = new Map(taskTree.map((item) => [item.workOrderId, item]));
  const ownedOrders = state.workOrder.workOrders
    .filter((order) => isDispatchBoardWorkOrder(order, planId));
  const ownedNumbers = new Set(ownedOrders.map(({ workOrderNo }) => workOrderNo));
  const nodeByOrderNo = new Map(
    state.workOrder.nodes
      .filter((node) => isDispatchBoardWorkNode(node, ownedNumbers))
      .map((node) => [node.workOrderNo, node]),
  );

  const orders = ownedOrders
    .map((workOrder): DispatchWorkOrderView => {
      const workNode = nodeByOrderNo.get(workOrder.workOrderNo);
      const task = taskByOrderId.get(workOrder.id);
      if (!workNode || !task) {
        throw new Error(`Missing C06 task projection for ${workOrder.id}.`);
      }
      const resources = selectAssignableResources(state, task.requiredResourceType);
      return {
        workOrder: structuredClone(workOrder),
        workNode: structuredClone(workNode),
        stage: task.stage,
        dependencyIds: [...task.dependencyIds],
        upstreamWorkOrderId: task.dependencyIds[0],
        downstreamWorkOrderIds: ownedOrders
          .filter(({ parentId }) => parentId === workOrder.id)
          .map(({ id }) => id)
          .sort((left, right) => left.localeCompare(right)),
        requiredResourceType: task.requiredResourceType,
        resources,
        assignableResourceIds: resources
          .filter(({ assignable }) => assignable)
          .map(({ id }) => id),
        unavailableReasons: resources
          .filter((resource): resource is DispatchResourceCandidate & { unavailableReason: string } =>
            resource.unavailableReason !== undefined,
          )
          .map(({ id, unavailableReason }) => ({ resourceId: id, reason: unavailableReason })),
        progressState: progressState(workOrder),
        exceptionEntryUrl: `/monitor/exceptions?workOrderId=${encodeURIComponent(workOrder.id)}`
          + `&planId=${encodeURIComponent(plan.id)}`
          + `&scenarioId=${encodeURIComponent(state.scenario.activeScenarioId)}`
          + '&from=dispatch-board',
      };
    })
    .sort((left, right) =>
      left.workNode.sequence - right.workNode.sequence
      || left.workOrder.id.localeCompare(right.workOrder.id),
    );

  const resourcePool = Array.from(
    new Map(orders.flatMap(({ resources }) => resources).map((item) => [item.id, item])).values(),
  ).sort((left, right) => left.id.localeCompare(right.id));

  return deepFreeze({
    plan: {
      id: plan.id,
      planBatchNo: plan.planBatchNo,
      trainNo: plan.trainNo,
      cargoType: plan.cargoType,
      status: plan.status,
      version: plan.version,
    },
    ruleVersion: DISPATCH_BOARD_RULE_VERSION,
    sourceUi: 'UI-004' as const,
    orders,
    resourcePool,
    kpis: projectKpis(orders),
  });
}

export function selectDispatchKpis(state: DemoRootState, planId: string): DispatchKpis {
  return selectDispatchBoard(state, planId)?.kpis ?? deepFreeze({
    ready: 0,
    assigned: 0,
    dispatched: 0,
    executing: 0,
    completed: 0,
    exceptionEntry: 0,
  });
}
