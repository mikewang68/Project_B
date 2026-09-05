import { z } from 'zod';

import {
  do013Schema,
  publicErrorCodeSchema,
  roleCodeSchema,
  type AuditLog,
  type PublicErrorCode,
} from '../contracts';
import type { AuditAppender, AuditAppenderInput } from '../commands/types';
import type { RoleCode } from '../auth/types';

export type CommandAuditEntry = {
  record: AuditLog;
  metadata: {
    roleCode: RoleCode;
    dataScope: readonly string[];
    result: 'SUCCESS' | 'DENIED' | 'FAILED';
    errorCode: PublicErrorCode | null;
    clientTime: string;
    serverTime: string;
  };
};

export type AuditLedger = {
  append: (entry: CommandAuditEntry) => string;
  list: () => CommandAuditEntry[];
};

export type AuditLedgerReplace = (entries: CommandAuditEntry[]) => void;

const commandAuditEntrySchema = z
  .object({
    record: do013Schema,
    metadata: z
      .object({
        roleCode: roleCodeSchema,
        dataScope: z.array(z.string()),
        result: z.enum(['SUCCESS', 'DENIED', 'FAILED']),
        errorCode: publicErrorCodeSchema.nullable(),
        clientTime: z.string(),
        serverTime: z.string(),
      })
      .strict(),
  })
  .strict();

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

export function validateCommandAuditEntry(input: unknown): CommandAuditEntry {
  return commandAuditEntrySchema.parse(structuredClone(input));
}

export function createAuditLedger(
  initial: readonly CommandAuditEntry[] = [],
  replace?: AuditLedgerReplace,
): AuditLedger {
  let entries = deepFreeze(initial.map((entry) => validateCommandAuditEntry(entry)));

  const append = (entryInput: CommandAuditEntry): string => {
    const entry = validateCommandAuditEntry(entryInput);
    if (entries.some(({ record }) => record.id === entry.record.id)) {
      throw new Error(`Duplicate audit record: ${entry.record.id}`);
    }

    const candidate = deepFreeze([...entries, entry].map(validateCommandAuditEntry));
    replace?.(structuredClone(candidate));
    entries = candidate;
    return entry.record.id;
  };

  const list = (): CommandAuditEntry[] => structuredClone(entries);

  return { append, list };
}

function commandResultMetadata(
  input: AuditAppenderInput,
): Pick<CommandAuditEntry['metadata'], 'result' | 'errorCode'> {
  if (input.result.ok) return { result: 'SUCCESS', errorCode: null };
  if (input.result.errorCode === 'TOS-AUTH-001') {
    return { result: 'DENIED', errorCode: input.result.errorCode };
  }
  return { result: 'FAILED', errorCode: input.result.errorCode };
}

export function createCommandAuditAppender(ledger: AuditLedger): AuditAppender {
  return (input) => {
    const resultMetadata = commandResultMetadata(input);
    const record = do013Schema.parse({
      id: input.result.auditLogId,
      actorId: input.command.actor.actorId,
      operatorTerminal: 'WEB-DEMO',
      action: input.command.action,
      objectType: input.command.entityType,
      objectId: input.command.entityId,
      before: {},
      after: {},
      reason: input.result.ok ? '' : input.result.message,
      traceId: input.command.traceId,
      occurredAt: input.serverTime,
    });
    const entry = validateCommandAuditEntry({
      record,
      metadata: {
        roleCode: roleCodeSchema.parse(input.command.actor.roleCode),
        dataScope: [...input.command.actor.dataScope],
        ...resultMetadata,
        clientTime: input.command.clientTime,
        serverTime: input.serverTime,
      },
    });

    return ledger.append(entry);
  };
}
