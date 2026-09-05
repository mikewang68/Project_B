import type {
  DispatchBoardWorkflowState,
  DispatchBoardWorkflowStore,
} from './types';

function freezeState(input: DispatchBoardWorkflowState): DispatchBoardWorkflowState {
  const lastCommandError = input.lastCommandError
    ? Object.freeze({ ...input.lastCommandError })
    : undefined;
  return Object.freeze({
    ...(input.selectedWorkOrderId ? { selectedWorkOrderId: input.selectedWorkOrderId } : {}),
    ...(input.selectedResourceId ? { selectedResourceId: input.selectedResourceId } : {}),
    resourceDrawerOpen: input.resourceDrawerOpen,
    feedbackPanelOpen: input.feedbackPanelOpen,
    ...(input.mode ? { mode: input.mode } : {}),
    ...(lastCommandError ? { lastCommandError } : {}),
  });
}

function initialState(): DispatchBoardWorkflowState {
  return freezeState({
    resourceDrawerOpen: false,
    feedbackPanelOpen: false,
  });
}

export function createDispatchBoardWorkflowStore(): DispatchBoardWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: DispatchBoardWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectWorkOrder: (selectedWorkOrderId) => replace({
      ...state,
      selectedWorkOrderId,
      ...(selectedWorkOrderId === state.selectedWorkOrderId
        ? {}
        : { selectedResourceId: undefined }),
    }),
    selectResource: (selectedResourceId) => replace({ ...state, selectedResourceId }),
    setResourceDrawerOpen: (resourceDrawerOpen) => replace({ ...state, resourceDrawerOpen }),
    setFeedbackPanelOpen: (feedbackPanelOpen) => replace({ ...state, feedbackPanelOpen }),
    setMode: (mode) => replace({ ...state, mode }),
    recordCommandError: (lastCommandError) => replace({ ...state, lastCommandError }),
    reset: () => replace(initialState()),
  };
}
