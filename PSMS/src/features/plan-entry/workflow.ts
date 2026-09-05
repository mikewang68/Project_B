import type { PlanEntryWorkflowState, PlanEntryWorkflowStore } from './types';

function freezeState(input: PlanEntryWorkflowState): PlanEntryWorkflowState {
  const resolvedFields = Object.fromEntries(
    Object.entries(input.resolvedFields).map(([planId, fields]) => [
      planId,
      Object.freeze([...new Set(fields)].sort()),
    ]),
  );
  return Object.freeze({ ...input, resolvedFields: Object.freeze(resolvedFields) });
}

function initialState(): PlanEntryWorkflowState {
  return freezeState({ retryCount: 0, circuitOpen: false, resolvedFields: {} });
}

export function createPlanEntryWorkflowStore(): PlanEntryWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: PlanEntryWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectPlan: (planId) => {
      replace({ ...state, ...(planId ? { selectedPlanId: planId } : { selectedPlanId: undefined }) });
    },
    recordRetry: () => {
      const retryCount = Math.min(3, state.retryCount + 1);
      replace({ ...state, retryCount, circuitOpen: retryCount === 3 });
    },
    recordSuccess: (serverTime) => {
      replace({
        ...state,
        retryCount: 0,
        circuitOpen: false,
        lastSuccessAt: serverTime,
      });
    },
    resolveFields: (planId, fields) => {
      replace({
        ...state,
        resolvedFields: {
          ...state.resolvedFields,
          [planId]: [...new Set(fields)].sort(),
        },
      });
    },
    reset: () => replace(initialState()),
  };
}
