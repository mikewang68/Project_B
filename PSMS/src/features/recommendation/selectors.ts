import type { Plan, WorkOrder } from '../../contracts';
import type { DemoRootState } from '../../stores';
import { recommendationDraftSchema, type RecommendationDraft } from './schemas';
import type { TrackConflictViewModel } from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function cloneFrozen<T>(value: T): T {
  return deepFreeze(structuredClone(value));
}

function canReadAreaA(state: DemoRootState): boolean {
  return (
    state.session.dataScope.includes('*') ||
    state.session.dataScope.includes('GLOBAL') ||
    state.session.dataScope.includes('AREA-A')
  );
}

export function selectConfirmedPlan(
  state: DemoRootState,
  planId: string,
): Readonly<Plan> | undefined {
  if (!canReadAreaA(state)) return undefined;
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || plan.status !== 'CONFIRMED') return undefined;
  return cloneFrozen(plan);
}

export function selectReceptionRecommendations(
  state: DemoRootState,
  planId: string,
): RecommendationDraft | undefined {
  if (!canReadAreaA(state)) return undefined;
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || plan.status !== 'CONFIRMED') return undefined;
  const raw = state.recommendation.drafts[planId];
  if (raw === undefined) return undefined;
  return recommendationDraftSchema.parse(structuredClone(raw));
}

export function selectTrackConflicts(
  state: DemoRootState,
  planId: string,
): readonly TrackConflictViewModel[] {
  if (!canReadAreaA(state)) return deepFreeze([]);
  const draft = selectReceptionRecommendations(state, planId);
  if (!draft) return deepFreeze([]);
  return deepFreeze(
    [...draft.excluded]
      .sort(
        (left, right) =>
          left.trackNo.localeCompare(right.trackNo) || left.trackId.localeCompare(right.trackId),
      )
      .map((item) => ({
        trackId: item.trackId,
        trackNo: item.trackNo,
        exclusionCode: item.exclusionCode,
        reason: item.reason,
        sourceRefs: [...item.sourceRefs],
      })),
  );
}

export function selectAffectedWorkOrders(
  state: DemoRootState,
  planId: string,
): readonly Readonly<WorkOrder>[] {
  if (!canReadAreaA(state)) return deepFreeze([]);
  return cloneFrozen(
    state.workOrder.workOrders
      .filter((workOrder) => workOrder.planId === planId)
      .sort((left, right) => left.id.localeCompare(right.id)),
  );
}
