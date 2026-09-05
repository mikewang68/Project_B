import type { AuditLog } from '../../contracts';
import type { PublicErrorCode } from '../../contracts';
import type { CommandAuditEntry } from '../../governance/audit';

export const auditResultCategories = [
  'RECORDED',
  'SUCCESS',
  'DENIED',
  'VERSION_CONFLICT',
  'IDEMPOTENT_HIT',
  'BUSINESS_ERROR',
] as const;

export type AuditResultCategory = (typeof auditResultCategories)[number];

export const auditSourceModules = [
  'BASELINE',
  'C03',
  'C04',
  'C05',
  'C06',
  'C07',
  'C08',
  'C09',
  'C10',
  'C11',
  'UNKNOWN',
] as const;

export type AuditSourceModule = (typeof auditSourceModules)[number];

export type AuditFilters = Readonly<{
  auditId?: string;
  module?: AuditSourceModule;
  action?: string;
  objectType?: string;
  result?: AuditResultCategory;
  actorId?: string;
  objectId?: string;
  traceId?: string;
  period?: string;
}>;

export type AuditQueryContext = AuditFilters & Readonly<{
  scenarioId?: string;
  from?: string;
}>;

export type ExplicitAuditFeedback = Readonly<{
  auditLogId: string;
  idempotent: boolean;
}>;

export type AuditLedgerItem = Readonly<{
  record: Readonly<AuditLog>;
  commandMetadata?: Readonly<CommandAuditEntry['metadata']>;
  sourceModule: AuditSourceModule;
  resultCategory: AuditResultCategory;
}>;

export type AuditKpis = Readonly<{
  total: number;
  success: number;
  denied: number;
  versionConflict: number;
  idempotentHit: number;
  businessError: number;
  recentTraceId?: string;
}>;

export type AuditProjection = Readonly<{
  items: readonly AuditLedgerItem[];
  kpis: AuditKpis;
}>;

export type AuditTrace = Readonly<{
  traceId: string;
  entries: readonly AuditLedgerItem[];
  count: number;
}>;

export type AuditReadState =
  | Readonly<{ kind: 'idle' }>
  | Readonly<{ kind: 'loading' }>
  | Readonly<{ kind: 'success' }>
  | Readonly<{ kind: 'network-error'; message: string }>
  | Readonly<{ kind: 'malformed-response'; message: string }>
  | Readonly<{ kind: 'business-error'; errorCode: PublicErrorCode; message: string }>;

export type AuditReadObservation =
  | Readonly<{
      kind: 'success';
      now: string;
      scenarioId: string;
      traceId: string;
      auditLogId: string;
    }>
  | Readonly<{
      kind: 'business-error';
      errorCode: PublicErrorCode;
      message: string;
      traceId: string;
      auditLogId: string;
    }>
  | Readonly<{
      kind: 'network-error' | 'malformed-response';
      message: string;
    }>;

export type AuditTrailFeedback = Readonly<{
  kind: 'success' | 'stale-trace';
  message: string;
}>;
