import { describe, expect, it } from 'vitest';

import { do013Schema, type AuditLog } from '../../contracts';
import { createMockRuntime } from '../../mocks/scenarios';
import {
  createAuditLedger,
  createCommandAuditAppender,
  type CommandAuditEntry,
} from '../audit';
import type { AuditAppenderInput, CommandResult, DemoCommand } from '../../commands';

function fixtureRecord(): AuditLog {
  return do013Schema.parse(createMockRuntime().getSnapshot().objects['DO-013'][0]);
}

function entry(record = fixtureRecord()): CommandAuditEntry {
  return {
    record,
    metadata: {
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      result: 'SUCCESS',
      errorCode: null,
      clientTime: '2026-07-19T08:00:00+08:00',
      serverTime: '2026-07-19T08:00:01+08:00',
    },
  };
}

const command: DemoCommand = {
  commandId: 'CMD-AUDIT-001',
  action: 'confirm',
  entityType: 'DO-001',
  entityId: 'PLAN-001',
  expectedVersion: 1,
  payload: { reason: 'reviewed' },
  actor: {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
  },
  traceId: 'TRACE-AUDIT-001',
  clientTime: '2026-07-19T08:00:00+08:00',
};

describe('C03 append-only command audit ledger', () => {
  it('appends a wrapper whose record remains strict DO-013', () => {
    const ledger = createAuditLedger();
    const value = entry();

    expect(ledger.append(value)).toBe(value.record.id);
    expect(do013Schema.safeParse(ledger.list()[0].record).success).toBe(true);
    expect(Object.keys(ledger.list()[0].record)).toEqual(Object.keys(fixtureRecord()));
  });

  it('returns deep copies and cannot mutate prior history through caller references', () => {
    const source = entry();
    const ledger = createAuditLedger([source]);

    source.record.reason = 'SOURCE-MUTATION';
    source.metadata.dataScope = ['AREA-B'];
    const firstRead = ledger.list();
    firstRead[0].record.reason = 'READ-MUTATION';
    firstRead[0].metadata.dataScope = ['AREA-C'];

    const secondRead = ledger.list();
    expect(secondRead[0].record.reason).toBe('演示操作');
    expect(secondRead[0].metadata.dataScope).toEqual(['AREA-A']);
  });

  it('rejects an invalid or duplicate complete entry without changing history', () => {
    const valid = entry();
    const ledger = createAuditLedger([valid]);
    const invalidRecord = { ...fixtureRecord(), unexpected: true } as unknown as AuditLog;

    expect(() => ledger.append(entry(invalidRecord))).toThrow();
    expect(() => ledger.append(valid)).toThrow('Duplicate audit record');
    expect(ledger.list()).toHaveLength(1);
  });

  it.each([
    {
      result: {
        ok: true,
        commandId: command.commandId,
        traceId: command.traceId,
        auditLogId: 'AUD-C03-001',
      } as CommandResult,
      expected: { result: 'SUCCESS', errorCode: null },
    },
    {
      result: {
        ok: false,
        commandId: command.commandId,
        traceId: command.traceId,
        auditLogId: 'AUD-C03-002',
        errorCode: 'TOS-AUTH-001',
        message: 'denied',
      } as CommandResult,
      expected: { result: 'DENIED', errorCode: 'TOS-AUTH-001' },
    },
    {
      result: {
        ok: false,
        commandId: command.commandId,
        traceId: command.traceId,
        auditLogId: 'AUD-C03-003',
        errorCode: 'TOS-EXT-001',
        message: 'failed',
      } as CommandResult,
      expected: { result: 'FAILED', errorCode: 'TOS-EXT-001' },
    },
  ] as const)('adapts $expected.result command results into one strict entry', ({ result, expected }) => {
    const ledger = createAuditLedger();
    const append = createCommandAuditAppender(ledger);
    const input: AuditAppenderInput = {
      command,
      result,
      serverTime: '2026-07-19T08:00:01+08:00',
    };

    expect(append(input)).toBe(result.auditLogId);
    expect(ledger.list()).toHaveLength(1);
    expect(ledger.list()[0].metadata).toMatchObject(expected);
    expect(do013Schema.safeParse(ledger.list()[0].record).success).toBe(true);
  });
});
