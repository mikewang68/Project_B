import {
  do001Schema,
  do002Schema,
  do004Schema,
  do005Schema,
  do006Schema,
  do007Schema,
  type Plan,
  type Resource,
} from '../../contracts';
import { recommendationDraftSchema } from '../recommendation';
import { TASK_DECOMPOSITION_RULE_VERSION } from './constants';
import { validateTaskGeneration, validateTaskGraph } from './ownership';
import type {
  GenerateTaskDraftInput,
  ProjectTaskGraphInput,
  TaskGeneration,
  TaskRouteExplanation,
  TaskRouteStep,
  TaskStage,
  TaskTreeNodeView,
} from './types';

const routeByCargoType: Readonly<Record<Plan['cargoType'], readonly TaskRouteStep[]>> = {
  FLY_ASH: [
    { stage: 'RECOGNITION', title: '识别与路由确认', taskType: 'INSPECT', requiredResourceType: 'TEAM' },
    { stage: 'UNLOAD', title: '卸料准备', taskType: 'UNLOAD', requiredResourceType: 'TIPPER' },
    { stage: 'TRANSFER', title: '输送转运', taskType: 'TRANSFER', requiredResourceType: 'CONVEYOR' },
    { stage: 'STORAGE', title: '筒仓入库', taskType: 'LOAD', requiredResourceType: 'SILO' },
  ],
  CEMENT: [
    { stage: 'RECOGNITION', title: '识别与路由确认', taskType: 'INSPECT', requiredResourceType: 'TEAM' },
    { stage: 'UNLOAD', title: '卸料准备', taskType: 'UNLOAD', requiredResourceType: 'TIPPER' },
    { stage: 'TRANSFER', title: '输送转运', taskType: 'TRANSFER', requiredResourceType: 'CONVEYOR' },
    { stage: 'STORAGE', title: '应急筒仓入库', taskType: 'LOAD', requiredResourceType: 'SILO' },
  ],
  STEEL: [
    { stage: 'RECOGNITION', title: '规格重量校验', taskType: 'INSPECT', requiredResourceType: 'TEAM' },
    { stage: 'UNLOAD', title: '重载吊装', taskType: 'UNLOAD', requiredResourceType: 'CRANE' },
    { stage: 'TRANSFER', title: 'AGV 转运', taskType: 'TRANSFER', requiredResourceType: 'AGV' },
    { stage: 'STORAGE', title: '货位入库', taskType: 'LOAD', requiredResourceType: 'TEAM' },
  ],
  GENERAL_CARGO: [
    { stage: 'RECOGNITION', title: '箱号包装校验', taskType: 'INSPECT', requiredResourceType: 'TEAM' },
    { stage: 'UNLOAD', title: '掏装/卸载', taskType: 'UNLOAD', requiredResourceType: 'CRANE' },
    { stage: 'TRANSFER', title: '分拣转运', taskType: 'TRANSFER', requiredResourceType: 'AGV' },
    { stage: 'STORAGE', title: '入库或发运准备', taskType: 'LOAD', requiredResourceType: 'TEAM' },
  ],
};

const stageByTaskType: Readonly<Record<TaskTreeNodeView['taskType'], TaskStage>> = {
  INSPECT: 'RECOGNITION',
  UNLOAD: 'UNLOAD',
  TRANSFER: 'TRANSFER',
  LOAD: 'STORAGE',
};

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

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
  const offsetMinutes = sign * (hours! * 60 + minutes!);
  const shifted = new Date(valueMs + offsetMinutes * 60_000).toISOString();
  const localPart = includeMilliseconds ? shifted.slice(0, 23) : shifted.slice(0, 19);
  return `${localPart}${suffix}`;
}

function planWindow(plan: Plan): Readonly<{
  arrivalText: string;
  departureText: string;
  arrivalMs: number;
  departureMs: number;
}> {
  const parts = plan.arrivalDepartureTime.split('/');
  if (parts.length !== 2) throw new Error('Plan arrival/departure interval is invalid.');
  const arrivalText = parts[0]!;
  const departureText = parts[1]!;
  const arrivalMs = parseTimestamp(arrivalText, 'plan arrival time');
  const departureMs = parseTimestamp(departureText, 'plan departure time');
  offsetSuffix(arrivalText);
  offsetSuffix(departureText);
  if (departureMs <= arrivalMs) throw new Error('Plan duration must be positive.');
  return { arrivalText, departureText, arrivalMs, departureMs };
}

function routeFor(cargoType: Plan['cargoType']): readonly TaskRouteStep[] {
  return routeByCargoType[cargoType];
}

function selectedCandidateId(
  recommendation: ReturnType<typeof recommendationDraftSchema.parse>,
  planId: string,
): string {
  if (recommendation.planId !== planId || recommendation.status !== 'CONFIRMED') {
    throw new Error(`Plan ${planId} requires a confirmed C05 recommendation.`);
  }
  const selected = recommendation.selectedCandidateId;
  if (!selected || !recommendation.candidates.some(({ candidateId }) => candidateId === selected)) {
    throw new Error(`Plan ${planId} requires a valid selected C05 candidate.`);
  }
  return selected;
}

function resourceRequirement(
  cargoType: Plan['cargoType'],
  taskType: TaskTreeNodeView['taskType'],
): Resource['resourceType'] {
  const step = routeFor(cargoType).find((candidate) => candidate.taskType === taskType);
  if (!step) throw new Error(`No resource rule exists for ${cargoType}/${taskType}.`);
  return step.requiredResourceType;
}

export function deriveTaskObjectId(workOrderId: string): string {
  let stableId = workOrderId;
  let previous = '';
  while (stableId !== previous) {
    previous = stableId;
    stableId = stableId.replace(/-(?:S\d{3}-[AB]|M\d{3})$/, '');
  }
  if (!stableId.startsWith('C06-WO-')) throw new Error(`Invalid C06 WorkOrder id: ${workOrderId}`);
  return `C06-OBJECT-${stableId.slice('C06-WO-'.length)}`;
}

export function explainTaskRoute(cargoType: Plan['cargoType']): TaskRouteExplanation {
  const limitations = [
    '货票与物料仅按相同货类和稳定编号顺序形成演示稳定映射，不代表生产外键',
    ...(cargoType === 'GENERAL_CARGO'
      ? ['冻结货类枚举无法进一步区分机电设备和生活物资']
      : []),
  ];
  return deepFreeze({
    cargoType,
    ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
    mappingMode: 'DEMO_STABLE_MAPPING' as const,
    route: structuredClone(routeFor(cargoType)),
    source: 'Plan.cargoType + C06 frozen route table',
    limitations,
  });
}

export function generateTaskDraft(input: GenerateTaskDraftInput): TaskGeneration {
  const plan = do001Schema.parse(structuredClone(input.plan));
  const recommendation = recommendationDraftSchema.parse(structuredClone(input.recommendation));
  if (plan.status !== 'CONFIRMED' || plan.missingFields.length > 0) {
    throw new Error(`Plan ${plan.id} is not eligible for task generation.`);
  }
  selectedCandidateId(recommendation, plan.id);
  parseTimestamp(input.demoTime, 'demoTime');
  offsetSuffix(input.demoTime);
  if (!Number.isInteger(input.generationVersion) || input.generationVersion <= 0) {
    throw new Error('generationVersion must be a positive integer.');
  }

  const { arrivalText, departureText, arrivalMs, departureMs } = planWindow(plan);
  const route = routeFor(plan.cargoType);
  const durationMs = departureMs - arrivalMs;
  const key = `${plan.id}-G${String(input.generationVersion).padStart(3, '0')}`;
  const workOrders = route.map((step, index) => {
    const suffix = String(index + 1).padStart(2, '0');
    const id = `C06-WO-${key}-${suffix}`;
    return do005Schema.parse({
      id,
      workOrderNo: id,
      planId: plan.id,
      parentId: index === 0 ? '' : `C06-WO-${key}-${String(index).padStart(2, '0')}`,
      type: step.taskType,
      title: step.title,
      priority: 'HIGH',
      status: 'DRAFT',
      ackStatus: 'PENDING',
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      resourceId: '',
      teamId: '',
      blockReason: '',
      version: 1,
      createdAt: input.demoTime,
      updatedAt: input.demoTime,
    });
  });
  const nodes = route.map((_step, index) => {
    const suffix = String(index + 1).padStart(2, '0');
    const startMs = index === 0
      ? arrivalMs
      : arrivalMs + Math.floor((durationMs * index) / route.length);
    const finishMs = index === route.length - 1
      ? departureMs
      : arrivalMs + Math.floor((durationMs * (index + 1)) / route.length);
    return do006Schema.parse({
      id: `C06-NODE-${key}-${suffix}`,
      nodeNo: `C06-N-${key}-${suffix}`,
      workOrderNo: `C06-WO-${key}-${suffix}`,
      sequence: index + 1,
      status: 'WAITING',
      plannedStartTime: index === 0 ? arrivalText : formatLike(startMs, arrivalText),
      plannedFinishTime: index === route.length - 1
        ? departureText
        : formatLike(finishMs, arrivalText),
      actualStartTime: '',
      actualFinishTime: '',
      version: 1,
      updatedAt: input.demoTime,
    });
  });

  return validateTaskGeneration({ workOrders, nodes }, plan.id);
}

export function projectTaskGraph(input: ProjectTaskGraphInput): readonly TaskTreeNodeView[] {
  const plan = do001Schema.parse(structuredClone(input.plan));
  const recommendation = recommendationDraftSchema.parse(structuredClone(input.recommendation));
  const selectedId = selectedCandidateId(recommendation, plan.id);
  const waybills = do002Schema.array().parse(structuredClone(input.waybills));
  const materials = do004Schema.array().parse(structuredClone(input.materials));
  const resources = do007Schema.array().parse(structuredClone(input.resources));
  const graph = validateTaskGraph({
    workOrders: input.workOrders,
    nodes: input.nodes,
  }, plan.id);
  const ordersByNumber = new Map(graph.workOrders.map((item) => [item.workOrderNo, item]));
  const sourceRefs = [
    `PLAN:${plan.id}`,
    `RECOMMENDATION:${selectedId}`,
    ...waybills
      .filter(({ cargoType }) => cargoType === plan.cargoType)
      .sort((left, right) => left.id.localeCompare(right.id))
      .map(({ id }) => `WAYBILL:${id}`),
    ...materials
      .filter(({ cargoType, locationCode }) => cargoType === plan.cargoType && locationCode === 'AREA-A')
      .sort((left, right) => left.id.localeCompare(right.id))
      .map(({ id }) => `MATERIAL:${id}`),
    `RULE:${TASK_DECOMPOSITION_RULE_VERSION}`,
    'MAPPING:DEMO_STABLE_MAPPING',
  ];

  const result = [...graph.nodes]
    .sort((left, right) => left.sequence - right.sequence || left.id.localeCompare(right.id))
    .map((node): TaskTreeNodeView => {
      const order = ordersByNumber.get(node.workOrderNo);
      if (!order) throw new Error(`Missing WorkOrder for ${node.id}.`);
      const requiredResourceType = resourceRequirement(plan.cargoType, order.type);
      const resourceCandidateIds = resources
        .filter(({ resourceType, workArea }) =>
          resourceType === requiredResourceType && workArea === 'AREA-A',
        )
        .map(({ id }) => id)
        .sort((left, right) => left.localeCompare(right));
      return {
        workOrderId: order.id,
        workOrderNo: order.workOrderNo,
        nodeId: node.id,
        nodeNo: node.nodeNo,
        sequence: node.sequence,
        stage: stageByTaskType[order.type],
        taskType: order.type,
        title: order.title,
        objectId: deriveTaskObjectId(order.id),
        dependencyIds: order.parentId === '' ? [] : [order.parentId],
        ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
        status: order.status,
        nodeStatus: node.status,
        requiredResourceType,
        resourceCandidateIds,
        sourceRefs: [...sourceRefs],
        workOrderVersion: order.version,
        nodeVersion: node.version,
      };
    });
  return deepFreeze(result);
}
