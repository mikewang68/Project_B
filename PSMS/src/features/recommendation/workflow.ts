import type { RecommendationWorkflowState, RecommendationWorkflowStore } from './types';

function freezeState(input: RecommendationWorkflowState): RecommendationWorkflowState {
  const lastCommandError = input.lastCommandError
    ? Object.freeze({ ...input.lastCommandError })
    : undefined;
  return Object.freeze({
    ...(input.selectedCandidateId
      ? { selectedCandidateId: input.selectedCandidateId }
      : {}),
    adjustmentDrawerOpen: input.adjustmentDrawerOpen,
    ruleDrawerOpen: input.ruleDrawerOpen,
    ...(lastCommandError ? { lastCommandError } : {}),
  });
}

function initialState(): RecommendationWorkflowState {
  return freezeState({ adjustmentDrawerOpen: false, ruleDrawerOpen: false });
}

export function createRecommendationWorkflowStore(): RecommendationWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: RecommendationWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectCandidate: (candidateId) => {
      replace({
        ...state,
        ...(candidateId ? { selectedCandidateId: candidateId } : { selectedCandidateId: undefined }),
      });
    },
    setAdjustmentDrawerOpen: (open) => replace({ ...state, adjustmentDrawerOpen: open }),
    setRuleDrawerOpen: (open) => replace({ ...state, ruleDrawerOpen: open }),
    recordCommandError: (error) => {
      replace({ ...state, ...(error ? { lastCommandError: error } : { lastCommandError: undefined }) });
    },
    reset: () => replace(initialState()),
  };
}
