import { describe, expect, it } from 'vitest';

import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoSessionSeed } from '../../../stores';
import { filterAuditItems, projectAuditTrail } from '../auditProjection';
import { parseAuditQuery } from '../auditQueries';

const session: DemoSessionSeed = {
  actorId: 'USER-AUDITOR',
  roleCode: 'AUDITOR',
  dataScope: ['GLOBAL'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
};

function frozenItems() {
  const store = createDemoStore(createFixtureSnapshot(), session);
  return projectAuditTrail(store.getState()).items;
}

describe('C12 audit query hydration and filtering', () => {
  it('parses every supported filter, selection, and source parameter', () => {
    const query = parseAuditQuery(
      '?auditId=AUD-C11-001&module=C11&action=RS-03&objectType=DO-012'
      + '&result=SUCCESS&actorId=USER-1&objectId=RP-001&traceId=TRACE-1'
      + '&period=2026-07-16&scenarioId=SCN-01&from=reports',
    );

    expect(query).toEqual({
      auditId: 'AUD-C11-001',
      module: 'C11',
      action: 'RS-03',
      objectType: 'DO-012',
      result: 'SUCCESS',
      actorId: 'USER-1',
      objectId: 'RP-001',
      traceId: 'TRACE-1',
      period: '2026-07-16',
      scenarioId: 'SCN-01',
      from: 'reports',
    });
    expect(Object.isFrozen(query)).toBe(true);
  });

  it('sanitizes text, caps length, and ignores invalid enums and unknown keys', () => {
    const query = parseAuditQuery(new URLSearchParams({
      auditId: ' AUD-001\u0000 ',
      module: 'C99',
      action: ` PLAN_${'X'.repeat(180)} `,
      objectType: ' PLAN\r ',
      result: 'OK',
      actorId: ' USER-001\n ',
      unknown: 'invented',
    }));

    expect(query.auditId).toBe('AUD-001');
    expect(query.module).toBeUndefined();
    expect(query.action).toHaveLength(128);
    expect(query.objectType).toBe('PLAN');
    expect(query.result).toBeUndefined();
    expect(query.actorId).toBe('USER-001');
    expect(query).not.toHaveProperty('unknown');
    expect(parseAuditQuery('?module=C99&result=OK&auditId=%00%20')).toEqual({});
  });

  it('combines source, action, object, result, actor, trace, and period filters with AND', () => {
    const items = frozenItems();
    const filtered = filterAuditItems(items, {
      module: 'BASELINE',
      action: 'plan_confirm',
      objectType: 'plan',
      result: 'RECORDED',
      actorId: 'user-001',
      objectId: 'plan-001',
      traceId: 'trace-001',
      period: '2026-07-16T08:01',
    });

    expect(filtered.map(({ record }) => record.id)).toEqual(['AUD-001']);
    expect(Object.isFrozen(filtered)).toBe(true);
  });

  it('distinguishes store empty, filter empty, selected record, and selected not-found', () => {
    const items = frozenItems();
    const selected = filterAuditItems(items, { auditId: 'AUD-003' });
    const missing = filterAuditItems(items, { auditId: 'AUD-NOT-FOUND' });
    const filterEmpty = filterAuditItems(items, { actorId: 'NO-SUCH-ACTOR' });

    expect(selected.map(({ record }) => record.id)).toEqual(['AUD-003']);
    expect(missing).toEqual([]);
    expect(filterEmpty).toEqual([]);
    expect(filterAuditItems([], {})).toEqual([]);
  });
});
