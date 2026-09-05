import type { DemoRootState } from '../../stores';
import { recommendationDraftSchema, type RecommendationDraft } from '../recommendation';
import { isC06WorkNode, isC06WorkOrder } from './ownership';
import { explainTaskRoute, projectTaskGraph } from './ruleEngine';
import type {
  CargoSummaryView,
  ResourcePreviewItem,
  TaskRouteExplanation,
  TaskTreeNodeView,
} from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

export function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*')
    || state.session.dataScope.includes('GLOBAL')
    || state.session.dataScope.includes('AREA-A');
}

function selectContext(
  state: DemoRootState,
  planId: string,
): Readonly<{
  plan: DemoRootState['plan']['plans'][number];
  recommendation: RecommendationDraft;
}> | undefined {
  if (!canReadAreaA(state)) return undefined;
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || (plan.status !== 'CONFIRMED' && plan.status !== 'DECOMPOSED')) return undefined;
  const rawRecommendation = state.recommendation.drafts[planId];
  if (rawRecommendation === undefined) return undefined;
  const recommendation = recommendationDraftSchema.parse(structuredClone(rawRecommendation));
  if (recommendation.status !== 'CONFIRMED' || recommendation.planId !== planId) return undefined;
  return { plan, recommendation };
}

export function selectCargoSummary(
  state: DemoRootState,
  planId: string,
): CargoSummaryView | undefined {
  const context = selectContext(state, planId);
  if (!context) return undefined;
  const waybillIds = state.plan.waybills
    .filter(({ cargoType }) => cargoType === context.plan.cargoType)
    .map(({ id }) => id)
    .sort((left, right) => left.localeCompare(right));
  const materialIds = state.resource.materials
    .filter(({ cargoType, locationCode }) =>
      cargoType === context.plan.cargoType && locationCode === 'AREA-A',
    )
    .map(({ id }) => id)
    .sort((left, right) => left.localeCompare(right));
  return deepFreeze({
    planId: context.plan.id,
    planStatus: context.plan.status,
    cargoType: context.plan.cargoType,
    mappingMode: 'DEMO_STABLE_MAPPING' as const,
    waybillIds,
    materialIds,
    sourceRefs: [
      `PLAN:${context.plan.id}`,
      ...waybillIds.map((id) => `WAYBILL:${id}`),
      ...materialIds.map((id) => `MATERIAL:${id}`),
    ],
    missingData: ['计划实际箱量', 'Plan 到 Waybill/Material 的生产外键'],
    actualContainerCount: '数据未提供' as const,
    acceptanceBenchmark: {
      trains: 2 as const,
      cars: 80 as const,
      containers: 160 as const,
      disclosure: '高峰验收基准，不是本计划实际箱量' as const,
    },
  });
}

export function selectRuleExplanation(
  state: DemoRootState,
  planId: string,
): TaskRouteExplanation | undefined {
  const context = selectContext(state, planId);
  return context ? explainTaskRoute(context.plan.cargoType) : undefined;
}

export function selectTaskTree(
  state: DemoRootState,
  planId: string,
): readonly TaskTreeNodeView[] {
  const context = selectContext(state, planId);
  if (!context) return deepFreeze([]);
  const workOrderPrefix = `C06-WO-${planId}-`;
  const workOrders = state.workOrder.workOrders.filter((item) => isC06WorkOrder(item, planId));
  const nodes = state.workOrder.nodes.filter((item) =>
    isC06WorkNode(item) && item.workOrderNo.startsWith(workOrderPrefix),
  );
  if (workOrders.length === 0 && nodes.length === 0) return deepFreeze([]);
  return projectTaskGraph({
    plan: context.plan,
    recommendation: context.recommendation,
    workOrders,
    nodes,
    waybills: state.plan.waybills,
    materials: state.resource.materials.filter(({ locationCode }) => locationCode === 'AREA-A'),
    resources: state.resource.resources.filter(({ workArea }) => workArea === 'AREA-A'),
  });
}

export function selectResourcePreview(
  state: DemoRootState,
  planId: string,
): readonly ResourcePreviewItem[] {
  if (!canReadAreaA(state)) return deepFreeze([]);
  const tree = selectTaskTree(state, planId);
  if (tree.length === 0) return deepFreeze([]);
  const visibleResources = state.resource.resources
    .filter(({ workArea }) => workArea === 'AREA-A')
    .sort((left, right) => left.id.localeCompare(right.id));
  return deepFreeze(tree.map((node) => ({
    nodeId: node.nodeId,
    stage: node.stage,
    requiredResourceType: node.requiredResourceType,
    typeExists: true as const,
    allocationState: 'PREVIEW_ONLY' as const,
    candidates: visibleResources
      .filter(({ resourceType }) => resourceType === node.requiredResourceType)
      .map(({ id, workArea, status }) => ({ id, workArea, status })),
  })));
}
