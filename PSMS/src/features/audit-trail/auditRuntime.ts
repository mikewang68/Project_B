import type { PublicErrorCode } from '../../contracts';
import type {
  AuditFilters,
  AuditReadObservation,
  AuditReadState,
  AuditTrailFeedback,
} from './auditTypes';

export type AuditTrailWorkflowState = Readonly<{
  filters: AuditFilters;
  selectedAuditId?: string;
  expandedTraceId?: string;
  detailDrawerOpen: boolean;
  pending: boolean;
  readState: AuditReadState;
  readObservation?: AuditReadObservation;
  lastFeedback?: AuditTrailFeedback;
}>;

export type AuditTrailWorkflowStore = Readonly<{
  getState(): AuditTrailWorkflowState;
  subscribe(listener: () => void): () => void;
  setFilters(filters: AuditFilters): void;
  openDetail(auditId: string): void;
  closeDetail(): void;
  setExpandedTrace(traceId?: string): void;
  beginRead(): void;
  recordReadSuccess(observation: Readonly<{
    now: string;
    scenarioId: string;
    traceId: string;
    auditLogId: string;
  }>): void;
  recordReadBusinessError(observation: Readonly<{
    errorCode: PublicErrorCode;
    message: string;
    traceId: string;
    auditLogId: string;
  }>): void;
  recordReadFailure(kind: 'network-error' | 'malformed-response', message: string): void;
  recordFeedback(feedback: AuditTrailFeedback): void;
  reset(): void;
}>;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function freezeState(input: AuditTrailWorkflowState): AuditTrailWorkflowState {
  return deepFreeze({
    filters: { ...input.filters },
    ...(input.selectedAuditId ? { selectedAuditId: input.selectedAuditId } : {}),
    ...(input.expandedTraceId ? { expandedTraceId: input.expandedTraceId } : {}),
    detailDrawerOpen: input.detailDrawerOpen,
    pending: input.pending,
    readState: { ...input.readState },
    ...(input.readObservation ? { readObservation: { ...input.readObservation } } : {}),
    ...(input.lastFeedback ? { lastFeedback: { ...input.lastFeedback } } : {}),
  });
}

function initialState(): AuditTrailWorkflowState {
  return freezeState({
    filters: {},
    detailDrawerOpen: false,
    pending: false,
    readState: { kind: 'idle' },
  });
}

export function createAuditTrailWorkflowStore(): AuditTrailWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: AuditTrailWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return Object.freeze({
    getState: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    setFilters: (filters) => replace({ ...state, filters: { ...filters } }),
    openDetail: (selectedAuditId) => replace({
      ...state,
      selectedAuditId,
      detailDrawerOpen: true,
    }),
    closeDetail: () => replace({
      ...state,
      selectedAuditId: undefined,
      detailDrawerOpen: false,
    }),
    setExpandedTrace: (expandedTraceId) => replace({ ...state, expandedTraceId }),
    beginRead: () => replace({
      ...state,
      pending: true,
      readState: { kind: 'loading' },
      readObservation: undefined,
    }),
    recordReadSuccess: (observation) => replace({
      ...state,
      pending: false,
      readState: { kind: 'success' },
      readObservation: { kind: 'success', ...observation },
    }),
    recordReadBusinessError: (observation) => replace({
      ...state,
      pending: false,
      readState: {
        kind: 'business-error',
        errorCode: observation.errorCode,
        message: observation.message,
      },
      readObservation: { kind: 'business-error', ...observation },
    }),
    recordReadFailure: (kind, message) => replace({
      ...state,
      pending: false,
      readState: { kind, message },
      readObservation: { kind, message },
    }),
    recordFeedback: (lastFeedback) => replace({ ...state, lastFeedback }),
    reset: () => replace(initialState()),
  });
}
