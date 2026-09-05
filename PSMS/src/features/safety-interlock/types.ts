import type {
  actionLevels,
  Interlock,
  interlockStatuses,
  PublicErrorCode,
  receiptStatuses,
} from '../../contracts';
import type {
  c09ProgressStates,
  FORCE_STOP_WARNING,
  INTERLOCK_SOURCE_DISCLOSURE,
} from './constants';

export type InterlockStatus = (typeof interlockStatuses)[number];
export type InterlockActionLevel = (typeof actionLevels)[number];
export type InterlockReceiptStatus = (typeof receiptStatuses)[number];
export type InterlockProgressState = (typeof c09ProgressStates)[number];

export type InterlockQueryContext = Readonly<{
  exceptionId?: string;
  scenarioId?: string;
  from?: string;
  status?: InterlockStatus;
  actionLevel?: InterlockActionLevel;
  riskType?: string;
  receiptStatus?: InterlockReceiptStatus;
}>;

export type InterlockKpis = Readonly<{
  locked: number;
  pendingApproval: number;
  approved: number;
  restored: number;
  forceStop: number;
  receiptFailed: number;
}>;

export type InterlockActionAvailability = Readonly<{
  trigger: boolean;
  receipt: boolean;
  requestReset: boolean;
  approve: boolean;
  restore: boolean;
  requestOverride: boolean;
}>;

export type InterlockStateFlowItem = Readonly<{
  status: InterlockStatus;
  current: boolean;
}>;

export type InterlockLedgerItem = Readonly<{
  interlock: Readonly<Interlock>;
  progressState: InterlockProgressState;
  stateFlow: readonly InterlockStateFlowItem[];
  forceStop: boolean;
  forceStopWarning?: typeof FORCE_STOP_WARNING;
  receiptFailed: boolean;
  resetRequested: boolean;
  resetRequestSummary: readonly string[];
  approvalSummary: readonly string[];
  availableActions: InterlockActionAvailability;
}>;

export type SafetyInterlockBoard = Readonly<{
  items: readonly InterlockLedgerItem[];
  kpis: InterlockKpis;
  sourceContext: InterlockQueryContext;
  sourceContextLabels: readonly string[];
  sourceDisclosure: typeof INTERLOCK_SOURCE_DISCLOSURE;
  returnExceptionUrl: string;
}>;

export type SafetyInterlockMode =
  | 'TRIGGER'
  | 'RECEIPT'
  | 'REQUEST_RESET'
  | 'APPROVE'
  | 'RESTORE'
  | 'REQUEST_OVERRIDE';

export type SafetyInterlockWorkflowState = Readonly<{
  selectedInterlockId?: string;
  drawerOpen: boolean;
  mode?: SafetyInterlockMode;
  reason: string;
  approvalDraft: string;
  resetRequestDraft: Readonly<Record<string, unknown>>;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type SafetyInterlockWorkflowStore = Readonly<{
  getState: () => SafetyInterlockWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectInterlock: (interlockId?: string) => void;
  setDrawerOpen: (open: boolean) => void;
  setMode: (mode?: SafetyInterlockMode) => void;
  setReason: (reason: string) => void;
  setApprovalDraft: (approvalUserId: string) => void;
  setResetRequestDraft: (draft: Readonly<Record<string, unknown>>) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
}>;
