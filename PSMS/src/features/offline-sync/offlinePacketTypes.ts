import type {
  mergeStatuses,
  OfflinePacket,
  PublicErrorCode,
} from '../../contracts';

export const OFFLINE_SYNC_FEATURE = 'C10' as const;
export const C10_AUDIT_PREFIX = 'OS' as const;
export const OFFLINE_SOURCE_DISCLOSURE = '演示文本上下文，非生产外键' as const;
export const OFFLINE_RECOVERY_GUIDANCE =
  'SCN-06 仅用于冲突识别与恢复引导；请重置到 SCN-01 后继续标准离线包处理闭环。' as const;

export type OfflineMergeStatus = (typeof mergeStatuses)[number];
export type OfflinePacketProgressState = Exclude<OfflineMergeStatus, 'PENDING_UPLOAD'> | 'UPLOADING';

export type OfflinePacketQueryContext = Readonly<{
  packetId?: string;
  terminalId?: string;
  workOrderNo?: string;
  mergeStatus?: OfflineMergeStatus;
  scenarioId?: string;
  from?: string;
}>;

export type OfflinePacketKpis = Readonly<{
  cached: number;
  pendingUpload: number;
  validating: number;
  merged: number;
  conflict: number;
  rejected: number;
  retry: number;
}>;

export type OfflinePacketActionAvailability = Readonly<{
  upload: boolean;
  validate: boolean;
  merge: boolean;
  reject: boolean;
  retry: boolean;
}>;

export type OfflinePacketStateFlowItem = Readonly<{
  status: OfflineMergeStatus;
  current: boolean;
}>;

export type OfflinePacketLedgerItem = Readonly<{
  packet: Readonly<OfflinePacket>;
  progressState: OfflinePacketProgressState;
  stateFlow: readonly OfflinePacketStateFlowItem[];
  versionDelta: number;
  validationValid?: boolean;
  validationIssues: readonly string[];
  conflict: boolean;
  availableActions: OfflinePacketActionAvailability;
}>;

export type OfflinePacketBoard = Readonly<{
  items: readonly OfflinePacketLedgerItem[];
  kpis: OfflinePacketKpis;
  sourceContext: OfflinePacketQueryContext;
  sourceContextLabels: readonly string[];
  sourceDisclosure: typeof OFFLINE_SOURCE_DISCLOSURE;
  recoveryGuidance?: typeof OFFLINE_RECOVERY_GUIDANCE;
}>;

export type OfflinePacketPendingAction = 'UPLOAD' | 'VALIDATE' | 'MERGE' | 'REJECT' | 'RETRY';

export type OfflinePacketCommandFeedback = Readonly<{
  ok: boolean;
  traceId: string;
  auditLogId: string;
  commandId: string;
  errorCode?: PublicErrorCode;
  message: string;
  idempotent: boolean;
}>;

export type OfflinePacketWorkflowState = Readonly<{
  selectedPacketId?: string;
  reason: string;
  validationDraft: Readonly<Record<string, unknown>>;
  pendingAction?: OfflinePacketPendingAction;
  lastFeedback?: OfflinePacketCommandFeedback;
}>;

export type OfflinePacketWorkflowStore = Readonly<{
  getState: () => OfflinePacketWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectPacket: (packetId?: string) => void;
  setReason: (reason: string) => void;
  setValidationDraft: (draft: Readonly<Record<string, unknown>>) => void;
  setPendingAction: (action?: OfflinePacketPendingAction) => void;
  recordFeedback: (feedback?: OfflinePacketCommandFeedback) => void;
  reset: () => void;
}>;
