import type { WorkOrder } from '../contracts';
import type { DemoScenario } from '../mocks/fixtures';
import type { DemoRootState } from './types';

export const selectSession = (state: DemoRootState): DemoRootState['session'] => state.session;
export const selectPlans = (state: DemoRootState): DemoRootState['plan']['plans'] => state.plan.plans;
export const selectWorkOrders = (
  state: DemoRootState,
): DemoRootState['workOrder']['workOrders'] => state.workOrder.workOrders;

export function selectActiveScenario(state: DemoRootState): DemoScenario {
  const scenario = state.scenario.scenarios.find(({ id }) => id === state.scenario.activeScenarioId);
  if (!scenario) throw new Error(`Active scenario missing: ${state.scenario.activeScenarioId}`);
  return scenario;
}

const planWorkOrderCache = new WeakMap<DemoRootState, readonly WorkOrder[]>();

export function selectPlanWorkOrders(state: DemoRootState): readonly WorkOrder[] {
  const cached = planWorkOrderCache.get(state);
  if (cached) return cached;

  const planIds = new Set(state.plan.plans.map(({ id }) => id));
  const selected = Object.freeze(
    state.workOrder.workOrders.filter(({ planId }) => planIds.has(planId)),
  );
  planWorkOrderCache.set(state, selected);
  return selected;
}
