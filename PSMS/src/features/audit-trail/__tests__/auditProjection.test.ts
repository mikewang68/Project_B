import { describe, expect, it } from 'vitest';

import { do013Schema, type AuditLog, type PublicErrorCode } from '../../../contracts';
import { validateCommandAuditEntry, type CommandAuditEntry } from '../../../governance/audit';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { buildAuditTrace, projectAuditTrail } from '../auditProjection';

const session: DemoSessionSeed = {
  actorId: 'USER-AUDITOR',
  roleCode: 'AUDITOR',
  dataScope: ['GLOBAL'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
};

function createState(): DemoRootState {
  return structuredClone(createDemoStore(createFixtureSnapshot(), session).getState());
}

function commandEntry(
  id: string,
  result: CommandAuditEntry['metadata']['result'],
  errorCode: PublicErrorCode | null,
  overrides: Partial<AuditLog> = {},
): CommandAuditEntry {
  return validateCommandAuditEntry({
    record: do013Schema.parse({
      id,
      actorId: 'USER-RUNTIME',
      operatorTerminal: 'WEB-DEMO',
      action: 'execute',
      objectType: 'DO-001',
      objectId: 'PLAN-001',
      before: { version: 1 },
      after: result === 'SUCCESS' ? { version: 2 } : { version: 1 },
      reason: result === 'SUCCESS' ? '' : `failure ${errorCode}`,
      traceId: `TRACE-${id}`,
      occurredAt: '2026-07-16T09:00:00+08:00',
      ...overrides,
    }),
    metadata: {
      roleCode: 'DISPATCHER',
      dataScope: ['AREA-A'],
      result,
      errorCode,
      clientTime: '2026-07-16T08:59:59+08:00',
      serverTime: '2026-07-16T09:00:00+08:00',
    },
  });
}

describe('C12 strict DO-013 audit projection', () => {
  it('projects the nine frozen records without inventing outcomes or fields', () => {
    const projection = projectAuditTrail(createState());

    expect(projection.items).toHaveLength(9);
    expect(Object.keys(projection.items[0]!.record).sort()).toEqual([
      'action',
      'actorId',
      'after',
      'before',
      'id',
      'objectId',
      'objectType',
      'occurredAt',
      'operatorTerminal',
      'reason',
      'traceId',
    ].sort());
    expect(projection.items.every(({ resultCategory }) => resultCategory === 'RECORDED'))
      .toBe(true);
    expect(projection.kpis).toEqual({
      total: 9,
      success: 0,
      denied: 0,
      versionConflict: 0,
      idempotentHit: 0,
      businessError: 0,
      recentTraceId: 'TRACE-009',
    });
    expect(projection.items[0]?.record.id).toBe('AUD-009');
  });

  it('classifies only explicit runtime metadata and explicit matched idempotent feedback', () => {
    const state = createState();
    state.configAudit.commandAudit = [
      commandEntry('AUD-C04-001', 'SUCCESS', null),
      commandEntry('AUD-C05-001', 'DENIED', 'TOS-AUTH-001'),
      commandEntry('AUD-C06-001', 'FAILED', 'DEMO-VERSION-001'),
      commandEntry('AUD-C07-001', 'FAILED', 'DEMO-SCENARIO-001'),
      commandEntry('AUD-C10-001', 'SUCCESS', null),
    ];

    const projection = projectAuditTrail(state, {
      explicitFeedback: [
        { auditLogId: 'AUD-C10-001', idempotent: true },
        { auditLogId: 'AUD-NOT-RECORDED', idempotent: true },
      ],
    });

    expect(projection.items).toHaveLength(14);
    expect(Object.fromEntries(projection.items
      .filter(({ record }) => record.id.startsWith('AUD-C'))
      .map(({ record, resultCategory, sourceModule }) => [
        record.id,
        { resultCategory, sourceModule },
      ])))
      .toEqual({
        'AUD-C04-001': { resultCategory: 'SUCCESS', sourceModule: 'C04' },
        'AUD-C05-001': { resultCategory: 'DENIED', sourceModule: 'C05' },
        'AUD-C06-001': { resultCategory: 'VERSION_CONFLICT', sourceModule: 'C06' },
        'AUD-C07-001': { resultCategory: 'BUSINESS_ERROR', sourceModule: 'C07' },
        'AUD-C10-001': { resultCategory: 'IDEMPOTENT_HIT', sourceModule: 'C10' },
      });
    expect(projection.kpis).toMatchObject({
      total: 14,
      success: 1,
      denied: 1,
      versionConflict: 1,
      idempotentHit: 1,
      businessError: 1,
    });
  });

  it('merges identical ids, rejects conflicting ids, and never mutates the input state', () => {
    const state = createState();
    const original = structuredClone(state);
    const frozenRecord = state.configAudit.audit[0]!;
    state.configAudit.commandAudit = [commandEntry(
      frozenRecord.id,
      'SUCCESS',
      null,
      frozenRecord,
    )];
    const expectedInput = structuredClone(state);

    const merged = projectAuditTrail(state);

    expect(merged.items).toHaveLength(9);
    expect(merged.items.find(({ record }) => record.id === frozenRecord.id))
      .toMatchObject({ resultCategory: 'SUCCESS', sourceModule: 'BASELINE' });
    expect(state).toEqual(expectedInput);
    expect(original.configAudit.commandAudit).toEqual([]);

    const conflict = createState();
    conflict.configAudit.commandAudit = [commandEntry(
      conflict.configAudit.audit[0]!.id,
      'SUCCESS',
      null,
      { objectId: 'PLAN-CONFLICT' },
    )];
    expect(() => projectAuditTrail(conflict)).toThrow('Conflicting audit record');
  });

  it.each(['C03', 'C04', 'C05', 'C06', 'C07', 'C08', 'C09', 'C10', 'C11'] as const)(
    'maps the AUD-%s prefix without guessing from action text',
    (module) => {
      const state = createState();
      state.configAudit.commandAudit = [commandEntry(`AUD-${module}-001`, 'SUCCESS', null)];
      expect(projectAuditTrail(state).items.find(
        ({ record }) => record.id === `AUD-${module}-001`,
      )?.sourceModule).toBe(module);
    },
  );

  it('deep-freezes returned records, metadata, items, and KPI values', () => {
    const state = createState();
    state.configAudit.commandAudit = [commandEntry('AUD-C11-001', 'SUCCESS', null)];

    const projection = projectAuditTrail(state);
    const runtime = projection.items.find(({ record }) => record.id === 'AUD-C11-001')!;

    expect(Object.isFrozen(projection)).toBe(true);
    expect(Object.isFrozen(projection.items)).toBe(true);
    expect(Object.isFrozen(runtime)).toBe(true);
    expect(Object.isFrozen(runtime.record)).toBe(true);
    expect(Object.isFrozen(runtime.record.before)).toBe(true);
    expect(Object.isFrozen(runtime.commandMetadata)).toBe(true);
    expect(Object.isFrozen(projection.kpis)).toBe(true);
  });

  it('builds one immutable trace in chronological and stable input order', () => {
    const state = createState();
    state.configAudit.audit = [];
    state.configAudit.commandAudit = [
      commandEntry('AUD-LATE', 'SUCCESS', null, {
        traceId: 'TRACE-SHARED',
        occurredAt: '2026-07-16T09:05:00+08:00',
      }),
      commandEntry('AUD-EARLY-A', 'SUCCESS', null, {
        traceId: 'TRACE-SHARED',
        occurredAt: '2026-07-16T09:00:00+08:00',
      }),
      commandEntry('AUD-OTHER', 'SUCCESS', null, {
        traceId: 'TRACE-OTHER',
        occurredAt: '2026-07-16T09:01:00+08:00',
      }),
      commandEntry('AUD-EARLY-B', 'SUCCESS', null, {
        traceId: 'TRACE-SHARED',
        occurredAt: '2026-07-16T09:00:00+08:00',
      }),
    ];
    const items = projectAuditTrail(state).items;

    const trace = buildAuditTrace(items, 'TRACE-SHARED');

    expect(trace?.entries.map(({ record }) => record.id)).toEqual([
      'AUD-EARLY-A',
      'AUD-EARLY-B',
      'AUD-LATE',
    ]);
    expect(trace).toMatchObject({ traceId: 'TRACE-SHARED', count: 3 });
    expect(Object.isFrozen(trace)).toBe(true);
    expect(Object.isFrozen(trace?.entries)).toBe(true);
    expect(trace?.entries.every(({ record }) => record.traceId === 'TRACE-SHARED')).toBe(true);
    expect(buildAuditTrace(items, 'TRACE-OTHER')?.count).toBe(1);
    expect(buildAuditTrace(items, 'TRACE-MISSING')).toBeUndefined();
  });
});
