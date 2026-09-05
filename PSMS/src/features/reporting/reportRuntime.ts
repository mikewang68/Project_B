import type { PublicErrorCode } from '../../contracts';
import type { GenerateStatus, ReportCommandFeedback, ReportType } from './reportTypes';

export type ReportFilters = Readonly<{
  reportType?: ReportType;
  period?: string;
  generateStatus?: GenerateStatus;
}>;

export type ReportWorkflowState = Readonly<{
  selectedReportId?: string;
  filters: ReportFilters;
  metricsDrawerOpen: boolean;
  pendingReportId?: string;
  snapshotSequence: number;
  lastFeedback?: ReportCommandFeedback;
}>;

export type ReportWorkflowStore = Readonly<{
  getState(): ReportWorkflowState;
  subscribe(listener: () => void): () => void;
  selectReport(selectedReportId?: string): void;
  setFilters(filters: ReportFilters): void;
  setMetricsDrawerOpen(metricsDrawerOpen: boolean): void;
  setPendingReportId(pendingReportId?: string): void;
  recordFeedback(feedback: Readonly<{
    ok: boolean;
    commandId: string;
    traceId: string;
    auditLogId: string;
    message: string;
    errorCode?: PublicErrorCode;
    idempotent: boolean;
  }>): void;
  reset(): void;
}>;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function freezeState(input: ReportWorkflowState): ReportWorkflowState {
  return deepFreeze({
    ...(input.selectedReportId ? { selectedReportId: input.selectedReportId } : {}),
    filters: { ...input.filters },
    metricsDrawerOpen: input.metricsDrawerOpen,
    ...(input.pendingReportId ? { pendingReportId: input.pendingReportId } : {}),
    snapshotSequence: input.snapshotSequence,
    ...(input.lastFeedback ? { lastFeedback: { ...input.lastFeedback } } : {}),
  });
}

function initialState(): ReportWorkflowState {
  return freezeState({ filters: {}, metricsDrawerOpen: false, snapshotSequence: 0 });
}

export function createReportWorkflowStore(): ReportWorkflowStore {
  let state = initialState();
  const listeners = new Set<() => void>();

  const replace = (candidate: ReportWorkflowState): void => {
    state = freezeState(candidate);
    for (const listener of listeners) listener();
  };

  return Object.freeze({
    getState: () => state,
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    selectReport: (selectedReportId?: string) => replace({ ...state, selectedReportId }),
    setFilters: (filters: ReportFilters) => replace({ ...state, filters: { ...filters } }),
    setMetricsDrawerOpen: (metricsDrawerOpen: boolean) => replace({
      ...state,
      metricsDrawerOpen,
    }),
    setPendingReportId: (pendingReportId?: string) => replace({ ...state, pendingReportId }),
    recordFeedback: (lastFeedback) => replace({
      ...state,
      pendingReportId: undefined,
      snapshotSequence: state.snapshotSequence + (lastFeedback.ok ? 1 : 0),
      lastFeedback,
    }),
    reset: () => replace(initialState()),
  });
}
