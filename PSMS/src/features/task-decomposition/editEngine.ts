import { do005Schema, do006Schema, type WorkNode, type WorkOrder } from '../../contracts';
import { validateTaskGeneration } from './ownership';
import { deriveTaskObjectId } from './ruleEngine';
import type { TaskGeneration } from './types';

type TaskPair = Readonly<{ order: WorkOrder; node: WorkNode }>;

function parseTimestamp(value: string, label: string): number {
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid ${label}: ${value}`);
  return parsed;
}

function offsetSuffix(value: string): string {
  const match = value.match(/(Z|[+-]\d{2}:\d{2})$/);
  if (!match) throw new Error(`Timestamp must include an ISO offset: ${value}`);
  return match[1]!;
}

function formatLike(valueMs: number, template: string): string {
  const suffix = offsetSuffix(template);
  const includeMilliseconds = /\.\d{3}(?:Z|[+-]\d{2}:\d{2})$/.test(template);
  if (suffix === 'Z') {
    const iso = new Date(valueMs).toISOString();
    return includeMilliseconds ? iso : `${iso.slice(0, 19)}Z`;
  }
  const sign = suffix.startsWith('-') ? -1 : 1;
  const [hours, minutes] = suffix.slice(1).split(':').map(Number);
  const shifted = new Date(valueMs + sign * (hours! * 60 + minutes!) * 60_000).toISOString();
  const localPart = includeMilliseconds ? shifted.slice(0, 23) : shifted.slice(0, 19);
  return `${localPart}${suffix}`;
}

function stableBaseId(id: string): string {
  let result = id;
  let previous = '';
  while (result !== previous) {
    previous = result;
    result = result.replace(/-(?:S\d{3}-[AB]|M\d{3})$/, '');
  }
  return result;
}

function nextEditNumber(
  generation: TaskGeneration,
  kind: 'S' | 'M',
): number {
  const pattern = kind === 'S' ? /-S(\d{3})-/g : /-M(\d{3})(?:-|$)/g;
  const values: number[] = [];
  for (const id of [
    ...generation.workOrders.map(({ id }) => id),
    ...generation.nodes.map(({ id }) => id),
  ]) {
    for (const match of id.matchAll(pattern)) values.push(Number(match[1]));
  }
  return Math.max(0, ...values) + 1;
}

function pairsFrom(generation: TaskGeneration): TaskPair[] {
  const ordersByNumber = new Map(generation.workOrders.map((item) => [item.workOrderNo, item]));
  return [...generation.nodes]
    .sort((left, right) => left.sequence - right.sequence || left.id.localeCompare(right.id))
    .map((node) => {
      const order = ordersByNumber.get(node.workOrderNo);
      if (!order) throw new Error(`Missing WorkOrder for ${node.id}.`);
      return { order, node };
    });
}

function assertEditable(pair: TaskPair): void {
  if (pair.order.status !== 'DRAFT') throw new Error('Only a DRAFT C06 WorkOrder may be edited.');
  if (pair.node.status !== 'WAITING') throw new Error('Only a WAITING C06 WorkNode may be edited.');
  if (pair.node.actualStartTime !== '' || pair.node.actualFinishTime !== '') {
    throw new Error('An executed C06 WorkNode cannot be edited.');
  }
}

function normalizedGeneration(
  pairs: readonly TaskPair[],
  planId: string,
  demoTime: string,
): TaskGeneration {
  const workOrders: WorkOrder[] = [];
  const nodes: WorkNode[] = [];
  pairs.forEach((pair, index) => {
    const sequence = index + 1;
    const node = do006Schema.parse({
      ...pair.node,
      sequence,
      ...(pair.node.sequence === sequence
        ? {}
        : { version: pair.node.version + 1, updatedAt: demoTime }),
    });
    workOrders.push(do005Schema.parse(pair.order));
    nodes.push(node);
  });
  return validateTaskGeneration({ workOrders, nodes }, planId);
}

export function splitTaskDraft(input: Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  targetNodeId: string;
  demoTime: string;
}>): TaskGeneration {
  parseTimestamp(input.demoTime, 'demoTime');
  offsetSuffix(input.demoTime);
  const planId = input.workOrders[0]?.planId;
  if (!planId) throw new Error('A C06 task graph is required.');
  const generation = validateTaskGeneration({
    workOrders: input.workOrders,
    nodes: input.nodes,
  }, planId);
  const pairs = pairsFrom(generation);
  const targetIndex = pairs.findIndex(({ node }) => node.id === input.targetNodeId);
  if (targetIndex < 0) throw new Error(`Unknown C06 WorkNode: ${input.targetNodeId}`);
  const target = pairs[targetIndex]!;
  assertEditable(target);
  if (target.order.type === 'INSPECT') throw new Error('INSPECT is not splittable.');
  const startMs = parseTimestamp(target.node.plannedStartTime, 'plannedStartTime');
  const finishMs = parseTimestamp(target.node.plannedFinishTime, 'plannedFinishTime');
  if (finishMs <= startMs) throw new Error('The target task interval must be positive.');

  const directChildren = generation.workOrders.filter(({ parentId }) => parentId === target.order.id);
  if (directChildren.length > 1) throw new Error('Split supports one direct downstream child only.');
  const splitNumber = nextEditNumber(generation, 'S');
  const suffix = `S${String(splitNumber).padStart(3, '0')}`;
  const firstOrderId = `${target.order.id}-${suffix}-A`;
  const secondOrderId = `${target.order.id}-${suffix}-B`;
  const firstNodeId = `${target.node.id}-${suffix}-A`;
  const secondNodeId = `${target.node.id}-${suffix}-B`;
  const midpoint = startMs + Math.floor((finishMs - startMs) / 2);

  const first: TaskPair = {
    order: do005Schema.parse({
      ...target.order,
      id: firstOrderId,
      workOrderNo: firstOrderId,
      parentId: target.order.parentId,
      title: `${target.order.title} A段`,
      version: 1,
      createdAt: input.demoTime,
      updatedAt: input.demoTime,
    }),
    node: do006Schema.parse({
      ...target.node,
      id: firstNodeId,
      nodeNo: `${target.node.nodeNo}-${suffix}-A`,
      workOrderNo: firstOrderId,
      plannedFinishTime: formatLike(midpoint, target.node.plannedStartTime),
      version: 1,
      updatedAt: input.demoTime,
    }),
  };
  const second: TaskPair = {
    order: do005Schema.parse({
      ...target.order,
      id: secondOrderId,
      workOrderNo: secondOrderId,
      parentId: firstOrderId,
      title: `${target.order.title} B段`,
      version: 1,
      createdAt: input.demoTime,
      updatedAt: input.demoTime,
    }),
    node: do006Schema.parse({
      ...target.node,
      id: secondNodeId,
      nodeNo: `${target.node.nodeNo}-${suffix}-B`,
      workOrderNo: secondOrderId,
      plannedStartTime: formatLike(midpoint, target.node.plannedStartTime),
      version: 1,
      updatedAt: input.demoTime,
    }),
  };

  const editedPairs = pairs.flatMap((pair, index): TaskPair[] => {
    if (index === targetIndex) return [first, second];
    if (pair.order.parentId !== target.order.id) return [pair];
    return [{
      order: do005Schema.parse({
        ...pair.order,
        parentId: secondOrderId,
        version: pair.order.version + 1,
        updatedAt: input.demoTime,
      }),
      node: pair.node,
    }];
  });
  return normalizedGeneration(editedPairs, planId, input.demoTime);
}

export function mergeTaskDraft(input: Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  nodeIds: readonly [string, string];
  demoTime: string;
}>): TaskGeneration {
  parseTimestamp(input.demoTime, 'demoTime');
  offsetSuffix(input.demoTime);
  const planId = input.workOrders[0]?.planId;
  if (!planId) throw new Error('A C06 task graph is required.');
  const generation = validateTaskGeneration({
    workOrders: input.workOrders,
    nodes: input.nodes,
  }, planId);
  const pairs = pairsFrom(generation);
  const firstIndex = pairs.findIndex(({ node }) => node.id === input.nodeIds[0]);
  const secondIndex = pairs.findIndex(({ node }) => node.id === input.nodeIds[1]);
  if (firstIndex < 0 || secondIndex < 0 || firstIndex === secondIndex) {
    throw new Error('Merge requires two known distinct C06 WorkNodes.');
  }
  const first = pairs[firstIndex]!;
  const second = pairs[secondIndex]!;
  assertEditable(first);
  assertEditable(second);
  if (secondIndex !== firstIndex + 1 || second.order.parentId !== first.order.id) {
    throw new Error('Merge requires an ordered adjacent direct chain.');
  }
  if (first.order.type !== second.order.type) throw new Error('Merge requires identical task types.');
  if (first.order.ruleVersion !== second.order.ruleVersion) {
    throw new Error('Merge requires identical rule versions.');
  }
  if (deriveTaskObjectId(first.order.id) !== deriveTaskObjectId(second.order.id)) {
    throw new Error('Merge requires the same derived task object.');
  }
  const firstChildren = generation.workOrders.filter(({ parentId }) => parentId === first.order.id);
  if (firstChildren.length !== 1 || firstChildren[0]?.id !== second.order.id) {
    throw new Error('The first merge node has an ambiguous downstream branch.');
  }
  const secondChildren = generation.workOrders.filter(({ parentId }) => parentId === second.order.id);
  if (secondChildren.length > 1) {
    throw new Error('The second merge node has ambiguous downstream branches.');
  }

  const firstBase = stableBaseId(first.order.id);
  const secondBase = stableBaseId(second.order.id);
  if (firstBase !== secondBase) throw new Error('Merge nodes do not share a stable edit base.');
  const mergeNumber = nextEditNumber(generation, 'M');
  const suffix = `M${String(mergeNumber).padStart(3, '0')}`;
  const mergedOrderId = `${firstBase}-${suffix}`;
  const firstNodeBase = stableBaseId(first.node.id);
  const secondNodeBase = stableBaseId(second.node.id);
  const firstNodeNoBase = stableBaseId(first.node.nodeNo);
  if (firstNodeBase !== secondNodeBase) throw new Error('Merge WorkNodes do not share a stable edit base.');
  const merged: TaskPair = {
    order: do005Schema.parse({
      ...first.order,
      id: mergedOrderId,
      workOrderNo: mergedOrderId,
      title: first.order.title.replace(/ [AB]段$/, ''),
      version: 1,
      createdAt: input.demoTime,
      updatedAt: input.demoTime,
    }),
    node: do006Schema.parse({
      ...first.node,
      id: `${firstNodeBase}-${suffix}`,
      nodeNo: `${firstNodeNoBase}-${suffix}`,
      workOrderNo: mergedOrderId,
      plannedFinishTime: second.node.plannedFinishTime,
      version: 1,
      updatedAt: input.demoTime,
    }),
  };

  const editedPairs: TaskPair[] = [];
  for (let index = 0; index < pairs.length; index += 1) {
    const pair = pairs[index]!;
    if (index === firstIndex) {
      editedPairs.push(merged);
      continue;
    }
    if (index === secondIndex) continue;
    if (pair.order.parentId === second.order.id) {
      editedPairs.push({
        order: do005Schema.parse({
          ...pair.order,
          parentId: mergedOrderId,
          version: pair.order.version + 1,
          updatedAt: input.demoTime,
        }),
        node: pair.node,
      });
      continue;
    }
    editedPairs.push(pair);
  }
  return normalizedGeneration(editedPairs, planId, input.demoTime);
}
