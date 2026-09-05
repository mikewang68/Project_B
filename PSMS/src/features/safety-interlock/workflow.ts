import type {
  SafetyInterlockWorkflowState,
  SafetyInterlockWorkflowStore,
} from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function freezeState(input: SafetyInterlockWorkflowState): SafetyInterlockWorkflowState {
  const lastCommandError = input.lastCommandError
    ? Object.freeze({ ...input.lastCommandError })
    : undefined;
  return deepFreeze({
    ...(input.selectedInterlockId ? { selectedInterlockId: input.selectedInterlockId } : {}),
    drawerOpen: input.drawerOpen,
    ...(input.mode ? { mode: input.mode } : {}),
    reason: input.reason,
    approvalDraft: input.approvalDraft,
    resetRequestDraft: structuredClone(input.resetRequestDraft),
    ...(lastCommandError ? { lastCommandError } : {}),
  });
}

function initialState(): SafetyInterlockWorkflowState {
  return freezeState({
    drawerOpen: false,
    reason: '',
    approvalDraft: '',
    resetRequestDraft: {},
  });
}

export function createSafetyInterlockWorkflowStore(): SafetyInterlockWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: SafetyInterlockWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectInterlock: (selectedInterlockId) => replace({ ...state, selectedInterlockId }),
    setDrawerOpen: (drawerOpen) => replace({ ...state, drawerOpen }),
    setMode: (mode) => replace({ ...state, mode }),
    setReason: (reason) => replace({ ...state, reason }),
    setApprovalDraft: (approvalDraft) => replace({ ...state, approvalDraft }),
    setResetRequestDraft: (resetRequestDraft) => replace({
      ...state,
      resetRequestDraft: structuredClone(resetRequestDraft),
    }),
    recordCommandError: (lastCommandError) => replace({ ...state, lastCommandError }),
    reset: () => replace(initialState()),
  };
}
