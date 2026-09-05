import type { Plan, PublicErrorCode, Resource, WorkNode, WorkOrder } from '../../contracts';
import type { c07ProgressStates, DISPATCH_BOARD_RULE_VERSION } from './constants';

export type DispatchProgressState = (typeof c07ProgressStates)[number];

export type DispatchPlanSummary = Readonly<Pick<
  Plan,
  'id' | 'planBatchNo' | 'trainNo' | 'cargoType' | 'status' | 'version'
>>;

export type DispatchResourceCandidate = Readonly<Pick<
  Resource,
  | 'id'
  | 'deviceId'
  | 'personId'
  | 'resourceType'
  | 'capabilityTags'
  | 'status'
  | 'workArea'
  | 'location'
  | 'version'
  | 'updatedAt'
> & {
  assignable: boolean;
  unavailableReason?: string;
}>;

export type DispatchUnavailableReason = Readonly<{
  resourceId: string;
  reason: string;
}>;

export type DispatchWorkOrderView = Readonly<{
  workOrder: Readonly<WorkOrder>;
  workNode: Readonly<WorkNode>;
  stage: 'RECOGNITION' | 'UNLOAD' | 'TRANSFER' | 'STORAGE';
  dependencyIds: readonly string[];
  upstreamWorkOrderId?: string;
  downstreamWorkOrderIds: readonly string[];
  requiredResourceType: Resource['resourceType'];
  resources: readonly DispatchResourceCandidate[];
  assignableResourceIds: readonly string[];
  unavailableReasons: readonly DispatchUnavailableReason[];
  progressState: DispatchProgressState;
  exceptionEntryUrl: string;
}>;

export type DispatchKpis = Readonly<{
  ready: number;
  assigned: number;
  dispatched: number;
  executing: number;
  completed: number;
  exceptionEntry: number;
}>;

export type DispatchBoardView = Readonly<{
  plan: DispatchPlanSummary;
  ruleVersion: typeof DISPATCH_BOARD_RULE_VERSION;
  sourceUi: 'UI-004';
  orders: readonly DispatchWorkOrderView[];
  resourcePool: readonly DispatchResourceCandidate[];
  kpis: DispatchKpis;
}>;

export type DispatchBoardWorkflowMode = 'ASSIGN' | 'DISPATCH' | 'FEEDBACK';

export type DispatchBoardWorkflowState = Readonly<{
  selectedWorkOrderId?: string;
  selectedResourceId?: string;
  resourceDrawerOpen: boolean;
  feedbackPanelOpen: boolean;
  mode?: DispatchBoardWorkflowMode;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type DispatchBoardWorkflowStore = Readonly<{
  getState: () => DispatchBoardWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectWorkOrder: (workOrderId?: string) => void;
  selectResource: (resourceId?: string) => void;
  setResourceDrawerOpen: (open: boolean) => void;
  setFeedbackPanelOpen: (open: boolean) => void;
  setMode: (mode?: DispatchBoardWorkflowMode) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
}>;
