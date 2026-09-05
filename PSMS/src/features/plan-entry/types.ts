import {
  exceptionTypes,
  type Appointment,
  type Plan,
  type PublicErrorCode,
  type Resource,
  type Track,
  type Waybill,
} from '../../contracts';
import type { CommandAuditEntry } from '../../governance/audit';
import type { DemoScenario } from '../../mocks/fixtures';

export const planExceptionFilterValues = [
  ...exceptionTypes,
  'VALIDATION_MISSING_FIELD',
  'INTERFACE_TIMEOUT',
  'INTERLOCK_FORCE_STOP',
  'OFFLINE_VERSION_CONFLICT',
  'SOURCE_DATA_CONFLICT',
] as const;

export type PlanEntryQuery = {
  date: string;
  workArea: string;
  scenarioId: DemoScenario['id'];
  planBatchNo: string;
  trainNo: string;
  statuses: string[];
  exceptionTypes: string[];
  page: number;
  pageSize: 20;
  sort: 'updatedAt:desc' | 'updatedAt:asc' | 'status:asc';
  planId?: string;
  from?: 'overview';
};

export type PlanEntryWorkflowState = {
  selectedPlanId?: string;
  retryCount: number;
  circuitOpen: boolean;
  lastSuccessAt?: string;
  resolvedFields: Readonly<Record<string, readonly string[]>>;
};

export type PlanEntryWorkflowStore = {
  getState: () => PlanEntryWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectPlan: (planId?: string) => void;
  recordRetry: () => void;
  recordSuccess: (serverTime: string) => void;
  resolveFields: (planId: string, fields: readonly string[]) => void;
  reset: () => void;
};

export type OverviewKpisViewModel = Readonly<{
  totalPlans: number;
  pendingConfirmPlans: number;
  confirmedPlans: number;
  blockedPlans: number;
  completedWorkOrders: number;
  waitingVehicles: number;
  availableResourceRate: number;
  openExceptions: number;
}>;

export type VisibleYardObjectsViewModel = Readonly<{
  tracks: readonly YardTrackViewModel[];
  resourceMarkers: readonly YardResourceMarkerViewModel[];
  appointmentMarkers: readonly YardAppointmentMarkerViewModel[];
}>;

export type PresentationTone = 'default' | 'success' | 'processing' | 'warning' | 'error';

export type YardTrackViewModel = Readonly<{
  id: string;
  trackNo: string;
  laneOrder: number;
  occupyStatus: Track['occupyStatus'];
  occupyStatusLabel: string;
  statusTone: PresentationTone;
  compatibleCargoSummary: string;
  occupancyMarker?: Readonly<{
    label: string;
    tone: PresentationTone;
  }>;
}>;

export type YardResourceMarkerViewModel = Readonly<{
  id: string;
  laneOrder: number;
  positionPercent: number;
  resourceType: Resource['resourceType'];
  status: Resource['status'];
  statusTone: PresentationTone;
  location: string;
}>;

export type YardAppointmentMarkerViewModel = Readonly<{
  id: string;
  laneOrder: number;
  vehicleNo: string;
  status: Appointment['status'];
  statusTone: PresentationTone;
  queueNo: string;
  gateStatus: Appointment['gateStatus'];
}>;

export type OpenRiskViewModel = Readonly<{
  id: string;
  source: 'SCENARIO' | 'EXCEPTION' | 'INTERLOCK' | 'OFFLINE';
  type: string;
  status: string;
  level: string;
  errorCode?: PublicErrorCode;
  objectId?: string;
}>;

export type PlanValidationIssueViewModel = Readonly<{
  planId: string;
  field: string;
  errorCode: PublicErrorCode;
}>;

export type PlanLedgerRowViewModel = Readonly<{
  id: string;
  planBatchNo: string;
  trainNo: string;
  arrivalDepartureTime: string;
  trackNo?: string;
  cargoType: Plan['cargoType'];
  status: Plan['status'];
  statusTone: PresentationTone;
  progress: Readonly<{
    percent: number;
    label: string;
    status: 'normal' | 'exception' | 'success';
  }>;
  risk: Readonly<{
    level: 'NONE' | 'WARNING' | 'CRITICAL';
    label: string;
    tone: PresentationTone;
  }>;
  sourceSystem: string;
  sourceTime: string;
  missingFields: readonly string[];
  conflicts: readonly Readonly<Record<string, unknown>>[];
  exceptionTypes: readonly string[];
  validationStatus: 'VALID' | 'MISSING_FIELD' | 'CONFLICT';
  syncStatus: 'SYNCED' | 'DEGRADED' | 'CIRCUIT_OPEN';
  version: number;
  updatedAt: string;
  formattedUpdatedAt: string;
}>;

export type PlanLedgerViewModel = Readonly<{
  items: readonly PlanLedgerRowViewModel[];
  total: number;
  page: number;
  pageSize: 20;
}>;

export type InterfaceHealthViewModel = Readonly<{
  status: 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE' | 'CIRCUIT_OPEN';
  errorCode?: PublicErrorCode;
  retryCount: number;
  circuitOpen: boolean;
  lastSuccessAt?: string;
  recoveryVisible: boolean;
}>;

export type PlanFieldSourceViewModel = Readonly<{
  field: string;
  source: string;
  value?: string;
}>;

export type PlanDetailSummaryItemViewModel = Readonly<{
  label: string;
  value: string;
}>;

export type PlanRetryHistoryViewModel = Readonly<{
  attempt: number;
  status: 'FAILED';
  errorCode?: PublicErrorCode;
}>;

export type PlanReviewerOptionViewModel = Readonly<{
  value: string;
  label: string;
}>;

export type PlanSupplementViewModel = Readonly<{
  visible: boolean;
  actorId: string;
  reviewerOptions: readonly PlanReviewerOptionViewModel[];
}>;

export type PlanDetailsViewModel = PlanLedgerRowViewModel &
  Readonly<{
    summary: readonly PlanDetailSummaryItemViewModel[];
    maskedRawSummary: string;
    workflowStepIndex: number;
    waybills: readonly Readonly<Waybill>[];
    validationIssues: readonly PlanValidationIssueViewModel[];
    fieldSources: readonly PlanFieldSourceViewModel[];
    retryHistory: readonly PlanRetryHistoryViewModel[];
    changeHistory: readonly Readonly<CommandAuditEntry>[];
    supplement: PlanSupplementViewModel;
  }>;
