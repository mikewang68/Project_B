import { do005Schema, do006Schema, type WorkNode, type WorkOrder } from '../../contracts';
import { TASK_DECOMPOSITION_RULE_VERSION } from './constants';
import type { TaskGeneration } from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function requireUnique(values: readonly string[], label: string): void {
  if (new Set(values).size !== values.length) throw new Error(`${label} must be unique.`);
}

export function isC06WorkOrder(order: WorkOrder, planId: string): boolean {
  return order.planId === planId
    && order.id.startsWith('C06-WO-')
    && order.workOrderNo.startsWith('C06-WO-')
    && order.ruleVersion === TASK_DECOMPOSITION_RULE_VERSION;
}

export function isC06WorkNode(node: WorkNode): boolean {
  return node.id.startsWith('C06-NODE-') && node.nodeNo.startsWith('C06-N-');
}

export function parseGenerationVersion(id: string): number | undefined {
  const match = id.match(/-G(\d{3})(?:-|$)/);
  if (!match) return undefined;
  const value = Number(match[1]);
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

export function validateTaskGraph(
  generation: TaskGeneration,
  planId: string,
): TaskGeneration {
  const workOrders = do005Schema.array().parse(structuredClone(generation.workOrders));
  const nodes = do006Schema.array().parse(structuredClone(generation.nodes));

  if (workOrders.length === 0 || workOrders.length !== nodes.length) {
    throw new Error('A task graph requires one WorkNode per WorkOrder.');
  }
  if (workOrders.some((item) => !isC06WorkOrder(item, planId))) {
    throw new Error(`Task graph contains a WorkOrder outside C06 ownership for ${planId}.`);
  }
  if (nodes.some((item) => !isC06WorkNode(item))) {
    throw new Error('Task graph contains a WorkNode outside C06 ownership.');
  }

  requireUnique(workOrders.map(({ id }) => id), 'WorkOrder IDs');
  requireUnique(workOrders.map(({ workOrderNo }) => workOrderNo), 'WorkOrder numbers');
  requireUnique(nodes.map(({ id }) => id), 'WorkNode IDs');
  requireUnique(nodes.map(({ nodeNo }) => nodeNo), 'WorkNode numbers');
  requireUnique(nodes.map(({ sequence }) => String(sequence)), 'WorkNode sequences');

  const expectedSequences = Array.from({ length: nodes.length }, (_, index) => index + 1);
  const actualSequences = nodes.map(({ sequence }) => sequence).sort((left, right) => left - right);
  if (JSON.stringify(actualSequences) !== JSON.stringify(expectedSequences)) {
    throw new Error('WorkNode sequences must be contiguous and start at one.');
  }

  const orderNumbers = new Set(workOrders.map(({ workOrderNo }) => workOrderNo));
  const nodeOrderNumbers = nodes.map(({ workOrderNo }) => workOrderNo);
  requireUnique(nodeOrderNumbers, 'WorkNode-to-WorkOrder pairs');
  if (nodeOrderNumbers.some((workOrderNo) => !orderNumbers.has(workOrderNo))) {
    throw new Error('Task graph contains an orphan WorkNode.');
  }
  if (workOrders.some(({ workOrderNo }) => !nodeOrderNumbers.includes(workOrderNo))) {
    throw new Error('Task graph contains a WorkOrder without a WorkNode.');
  }

  const ordersById = new Map(workOrders.map((item) => [item.id, item]));
  const roots = workOrders.filter(({ parentId }) => parentId === '');
  if (roots.length !== 1) throw new Error('Task graph requires exactly one root WorkOrder.');

  for (const order of workOrders) {
    if (order.parentId !== '' && !ordersById.has(order.parentId)) {
      throw new Error(`Task graph contains orphan parent ${order.parentId}.`);
    }
    const visited = new Set<string>();
    let current: WorkOrder | undefined = order;
    while (current) {
      if (visited.has(current.id)) throw new Error(`Task graph contains a cycle at ${current.id}.`);
      visited.add(current.id);
      current = current.parentId === '' ? undefined : ordersById.get(current.parentId);
    }
  }

  return deepFreeze({ workOrders, nodes });
}

export function validateTaskGeneration(
  generation: TaskGeneration,
  planId: string,
): TaskGeneration {
  const parsed = validateTaskGraph(generation, planId);
  if (parsed.workOrders.some(({ status }) => status !== 'DRAFT')) {
    throw new Error('Generated C06 WorkOrders must be DRAFT.');
  }
  if (parsed.nodes.some(({ status }) => status !== 'WAITING')) {
    throw new Error('Generated C06 WorkNodes must be WAITING.');
  }
  return parsed;
}
