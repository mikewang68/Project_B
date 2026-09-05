import { do013Schema, type AuditLog } from '../../contracts';
import { validateCommandAuditEntry, type CommandAuditEntry } from '../../governance/audit';
import type { DemoRootState } from '../../stores';
import type {
  AuditFilters,
  AuditLedgerItem,
  AuditProjection,
  AuditResultCategory,
  AuditSourceModule,
  AuditTrace,
  ExplicitAuditFeedback,
} from './auditTypes';

type MutableProjectedAudit = {
  record: AuditLog;
  commandMetadata?: CommandAuditEntry['metadata'];
  sourceModule: AuditSourceModule;
  inputIndex: number;
};

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function recordsEqual(left: AuditLog, right: AuditLog): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function moduleFromAuditId(id: string): AuditSourceModule {
  const match = /^AUD-(C(?:0[3-9]|1[01]))(?:-|$)/.exec(id);
  return match?.[1] as AuditSourceModule | undefined ?? 'UNKNOWN';
}

function resultFor(
  entry: MutableProjectedAudit,
  idempotentIds: ReadonlySet<string>,
): AuditResultCategory {
  if (idempotentIds.has(entry.record.id)) return 'IDEMPOTENT_HIT';
  const metadata = entry.commandMetadata;
  if (!metadata) return 'RECORDED';
  if (metadata.result === 'DENIED' || metadata.errorCode === 'TOS-AUTH-001') return 'DENIED';
  if (metadata.errorCode === 'DEMO-VERSION-001') return 'VERSION_CONFLICT';
  if (metadata.result === 'FAILED') return 'BUSINESS_ERROR';
  return 'SUCCESS';
}

function includesText(value: string, expected?: string): boolean {
  if (!expected) return true;
  return value.toLocaleLowerCase().includes(expected.toLocaleLowerCase());
}

export function filterAuditItems(
  items: readonly AuditLedgerItem[],
  filters: AuditFilters,
): readonly AuditLedgerItem[] {
  return deepFreeze(items.filter((item) =>
    (!filters.auditId || item.record.id === filters.auditId)
    && (!filters.module || item.sourceModule === filters.module)
    && (!filters.result || item.resultCategory === filters.result)
    && includesText(item.record.action, filters.action)
    && includesText(item.record.objectType, filters.objectType)
    && includesText(item.record.actorId, filters.actorId)
    && includesText(item.record.objectId, filters.objectId)
    && includesText(item.record.traceId, filters.traceId)
    && includesText(item.record.occurredAt, filters.period)
  ));
}

export function projectAuditTrail(
  state: DemoRootState,
  options: Readonly<{ explicitFeedback?: readonly ExplicitAuditFeedback[] }> = {},
): AuditProjection {
  const byId = new Map<string, MutableProjectedAudit>();
  let inputIndex = 0;

  for (const recordInput of state.configAudit.audit) {
    const record = do013Schema.parse(structuredClone(recordInput));
    const existing = byId.get(record.id);
    if (existing && !recordsEqual(existing.record, record)) {
      throw new Error(`Conflicting audit record: ${record.id}`);
    }
    if (!existing) {
      byId.set(record.id, {
        record,
        sourceModule: 'BASELINE',
        inputIndex: inputIndex++,
      });
    }
  }

  for (const entryInput of state.configAudit.commandAudit) {
    const entry = validateCommandAuditEntry(entryInput);
    const existing = byId.get(entry.record.id);
    if (existing && !recordsEqual(existing.record, entry.record)) {
      throw new Error(`Conflicting audit record: ${entry.record.id}`);
    }
    if (existing) {
      existing.commandMetadata = structuredClone(entry.metadata);
    } else {
      byId.set(entry.record.id, {
        record: structuredClone(entry.record),
        commandMetadata: structuredClone(entry.metadata),
        sourceModule: moduleFromAuditId(entry.record.id),
        inputIndex: inputIndex++,
      });
    }
  }

  const knownIds = new Set(byId.keys());
  const idempotentIds = new Set(
    (options.explicitFeedback ?? [])
      .filter(({ auditLogId, idempotent }) => idempotent && knownIds.has(auditLogId))
      .map(({ auditLogId }) => auditLogId),
  );

  const items = [...byId.values()]
    .sort((left, right) =>
      right.record.occurredAt.localeCompare(left.record.occurredAt)
      || left.inputIndex - right.inputIndex)
    .map((entry): AuditLedgerItem => ({
      record: entry.record,
      ...(entry.commandMetadata ? { commandMetadata: entry.commandMetadata } : {}),
      sourceModule: entry.sourceModule,
      resultCategory: resultFor(entry, idempotentIds),
    }));

  const count = (category: AuditResultCategory) =>
    items.filter(({ resultCategory }) => resultCategory === category).length;
  const recentTraceId = items[0]?.record.traceId;

  return deepFreeze({
    items,
    kpis: {
      total: items.length,
      success: count('SUCCESS'),
      denied: count('DENIED'),
      versionConflict: count('VERSION_CONFLICT'),
      idempotentHit: count('IDEMPOTENT_HIT'),
      businessError: count('BUSINESS_ERROR'),
      ...(recentTraceId ? { recentTraceId } : {}),
    },
  });
}

export function buildAuditTrace(
  items: readonly AuditLedgerItem[],
  traceId: string,
): AuditTrace | undefined {
  const entries = items
    .map((entry, inputIndex) => ({ entry, inputIndex }))
    .filter(({ entry }) => entry.record.traceId === traceId)
    .sort((left, right) =>
      left.entry.record.occurredAt.localeCompare(right.entry.record.occurredAt)
      || left.inputIndex - right.inputIndex)
    .map(({ entry }) => entry);

  if (entries.length === 0) return undefined;
  return deepFreeze({ traceId, entries, count: entries.length });
}
