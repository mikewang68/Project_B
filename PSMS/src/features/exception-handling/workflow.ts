import type {
  ExceptionHandlingWorkflowState,
  ExceptionHandlingWorkflowStore,
} from './types';

function freezeState(input: ExceptionHandlingWorkflowState): ExceptionHandlingWorkflowState {
  const lastCommandError = input.lastCommandError
    ? Object.freeze({ ...input.lastCommandError })
    : undefined;
  return Object.freeze({
    ...(input.selectedExceptionId ? { selectedExceptionId: input.selectedExceptionId } : {}),
    drawerOpen: input.drawerOpen,
    ...(input.mode ? { mode: input.mode } : {}),
    reason: input.reason,
    ownerDraft: input.ownerDraft,
    evidenceDraft: Object.freeze([...input.evidenceDraft]),
    ...(lastCommandError ? { lastCommandError } : {}),
  });
}

function initialState(): ExceptionHandlingWorkflowState {
  return freezeState({
    drawerOpen: false,
    reason: '',
    ownerDraft: '',
    evidenceDraft: [],
  });
}

export function createExceptionHandlingWorkflowStore(): ExceptionHandlingWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: ExceptionHandlingWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectException: (selectedExceptionId) => replace({ ...state, selectedExceptionId }),
    setDrawerOpen: (drawerOpen) => replace({ ...state, drawerOpen }),
    setMode: (mode) => replace({ ...state, mode }),
    setReason: (reason) => replace({ ...state, reason }),
    setOwnerDraft: (ownerDraft) => replace({ ...state, ownerDraft }),
    setEvidenceDraft: (evidenceDraft) => replace({ ...state, evidenceDraft: [...evidenceDraft] }),
    recordCommandError: (lastCommandError) => replace({ ...state, lastCommandError }),
    reset: () => replace(initialState()),
  };
}
