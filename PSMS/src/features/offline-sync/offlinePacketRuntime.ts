import type {
  OfflinePacketCommandFeedback,
  OfflinePacketWorkflowState,
  OfflinePacketWorkflowStore,
} from './offlinePacketTypes';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function freezeFeedback(
  input: OfflinePacketCommandFeedback | undefined,
): OfflinePacketCommandFeedback | undefined {
  return input ? Object.freeze({ ...input }) : undefined;
}

function freezeState(input: OfflinePacketWorkflowState): OfflinePacketWorkflowState {
  const lastFeedback = freezeFeedback(input.lastFeedback);
  return deepFreeze({
    ...(input.selectedPacketId ? { selectedPacketId: input.selectedPacketId } : {}),
    reason: input.reason,
    validationDraft: structuredClone(input.validationDraft),
    ...(input.pendingAction ? { pendingAction: input.pendingAction } : {}),
    ...(lastFeedback ? { lastFeedback } : {}),
  });
}

function initialState(): OfflinePacketWorkflowState {
  return freezeState({ reason: '', validationDraft: {} });
}

export function createOfflinePacketWorkflowStore(): OfflinePacketWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: OfflinePacketWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectPacket: (selectedPacketId) => replace({ ...state, selectedPacketId }),
    setReason: (reason) => replace({ ...state, reason }),
    setValidationDraft: (validationDraft) => replace({
      ...state,
      validationDraft: structuredClone(validationDraft),
    }),
    setPendingAction: (pendingAction) => replace({ ...state, pendingAction }),
    recordFeedback: (lastFeedback) => replace({
      ...state,
      pendingAction: undefined,
      lastFeedback,
    }),
    reset: () => replace(initialState()),
  };
}
