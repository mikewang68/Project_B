import type {
  DispatchException,
  PublicErrorCode,
  exceptionLevels,
  exceptionStatuses,
  exceptionTypes,
} from '../../contracts';
import type { c08ProgressStates, EXCEPTION_SOURCE_DISCLOSURE } from './constants';

export type ExceptionStatus = (typeof exceptionStatuses)[number];
export type ExceptionLevel = (typeof exceptionLevels)[number];
export type ExceptionType = (typeof exceptionTypes)[number];
export type ExceptionProgressState = (typeof c08ProgressStates)[number];

export type ExceptionQueryContext = Readonly<{
  workOrderId?: string;
  planId?: string;
  scenarioId?: string;
  from?: string;
  status?: ExceptionStatus;
  level?: ExceptionLevel;
  type?: ExceptionType;
  owner?: string;
}>;

export type ExceptionKpis = Readonly<{
  unacknowledged: number;
  handling: number;
  pendingReview: number;
  closed: number;
  overdue: number;
  interlock: number;
}>;

export type ExceptionActionAvailability = Readonly<{
  ack: boolean;
  assign: boolean;
  handle: boolean;
  review: boolean;
  close: boolean;
  reopen: boolean;
}>;

export type ExceptionStateFlowItem = Readonly<{
  status: ExceptionStatus;
  current: boolean;
}>;

export type ExceptionLedgerItem = Readonly<{
  exception: Readonly<DispatchException>;
  evidenceCount: number;
  dueState: 'DUE' | 'OVERDUE' | 'CLOSED';
  progressState: ExceptionProgressState;
  stateFlow: readonly ExceptionStateFlowItem[];
  interlockEntryUrl?: string;
  availableActions: ExceptionActionAvailability;
}>;

export type ExceptionHandlingBoard = Readonly<{
  items: readonly ExceptionLedgerItem[];
  kpis: ExceptionKpis;
  sourceContext: ExceptionQueryContext;
  sourceContextLabels: readonly string[];
  sourceDisclosure: typeof EXCEPTION_SOURCE_DISCLOSURE;
}>;

export type ExceptionHandlingMode = 'ACK' | 'ASSIGN' | 'HANDLE' | 'REVIEW' | 'CLOSE' | 'REOPEN';

export type ExceptionHandlingWorkflowState = Readonly<{
  selectedExceptionId?: string;
  drawerOpen: boolean;
  mode?: ExceptionHandlingMode;
  reason: string;
  ownerDraft: string;
  evidenceDraft: readonly string[];
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type ExceptionHandlingWorkflowStore = Readonly<{
  getState: () => ExceptionHandlingWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectException: (exceptionId?: string) => void;
  setDrawerOpen: (open: boolean) => void;
  setMode: (mode?: ExceptionHandlingMode) => void;
  setReason: (reason: string) => void;
  setOwnerDraft: (owner: string) => void;
  setEvidenceDraft: (evidence: readonly string[]) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
}>;
