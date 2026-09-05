import type {
  TaskDecompositionWorkflowState,
  TaskDecompositionWorkflowStore,
} from './types';

function freezeState(input: TaskDecompositionWorkflowState): TaskDecompositionWorkflowState {
  const lastCommandError = input.lastCommandError
    ? Object.freeze({ ...input.lastCommandError })
    : undefined;
  return Object.freeze({
    selectedNodeIds: Object.freeze([...input.selectedNodeIds]),
    editDrawerOpen: input.editDrawerOpen,
    rulePanelOpen: input.rulePanelOpen,
    ...(input.mode ? { mode: input.mode } : {}),
    ...(input.targetNodeId ? { targetNodeId: input.targetNodeId } : {}),
    reason: input.reason,
    ...(lastCommandError ? { lastCommandError } : {}),
  });
}

function initialState(): TaskDecompositionWorkflowState {
  return freezeState({
    selectedNodeIds: [],
    editDrawerOpen: false,
    rulePanelOpen: false,
    reason: '',
  });
}

export function createTaskDecompositionWorkflowStore(): TaskDecompositionWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: TaskDecompositionWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectNodes: (nodeIds) => replace({ ...state, selectedNodeIds: [...nodeIds] }),
    openEditor: (mode, targetNodeId) => replace({
      ...state,
      editDrawerOpen: true,
      mode,
      ...(targetNodeId ? { targetNodeId } : { targetNodeId: undefined }),
    }),
    closeEditor: () => replace({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: state.rulePanelOpen,
      reason: '',
    }),
    setReason: (reason) => replace({ ...state, reason }),
    setRulePanelOpen: (open) => replace({ ...state, rulePanelOpen: open }),
    recordCommandError: (error) => replace({
      ...state,
      ...(error ? { lastCommandError: error } : { lastCommandError: undefined }),
    }),
    reset: () => replace(initialState()),
  };
}
