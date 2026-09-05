import { describe, expect, it } from 'vitest';

import type { Report } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { selectReportDashboard, selectReportLedger } from '../reportProjection';
import { parseReportQuery } from '../reportQueries';

const session = (overrides: Partial<DemoSessionSeed> = {}): DemoSessionSeed => ({
  actorId: 'USER-BUSINESS',
  roleCode: 'BUSINESS',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
  ...overrides,
});

function createStore(overrides: Partial<DemoSessionSeed> = {}) {
  return createDemoStore(createFixtureSnapshot(), session(overrides));
}

describe('C11 strict DO-012 projection', () => {
  it('projects only the six frozen fields and keeps reportId display-only', () => {
    const state = structuredClone(createStore().getState()) as DemoRootState;
    state.report.reports[1] = {
      ...state.report.reports[1]!,
      reportId: 'RP-INVENTED',
      version: 99,
      updatedAt: '2099-01-01T00:00:00+08:00',
    } as Report;

    const ledger = selectReportLedger(state, parseReportQuery(
      '?reportId=RP-002&reportType=DAILY&period=2026-07-17'
      + '&generateStatus=FAILED&scenarioId=SCN-01&from=monitor',
    ));

    expect(ledger.items).toHaveLength(1);
    expect(ledger.items[0]?.report).toEqual({
      id: 'RP-002',
      reportType: 'DAILY',
      period: '2026-07-17',
      generateStatus: 'FAILED',
      metrics: { completedWorkOrders: 24, exceptions: 2 },
      generatedAt: '2026-07-16T18:02:00+08:00',
    });
    expect(Object.keys(ledger.items[0]!.report).sort()).toEqual(
      ['generateStatus', 'generatedAt', 'id', 'metrics', 'period', 'reportType'].sort(),
    );
    expect(ledger.items[0]?.report).not.toHaveProperty('reportId');
    expect(ledger.items[0]?.report).not.toHaveProperty('version');
    expect(ledger.sourceContextLabels).toEqual([
      '来源模块：monitor',
      '场景：SCN-01',
      '报表：RP-002',
      '类型：日报',
      '周期：2026-07-17',
      '状态：生成失败',
    ]);
  });

  it('filters by type, period, and status in deterministic report order', () => {
    const all = selectReportLedger(createStore().getState(), parseReportQuery(''));
    const filtered = selectReportLedger(
      createStore().getState(),
      parseReportQuery('?reportType=DAILY&period=2026-07-17&generateStatus=FAILED'),
    );

    expect(all.items.map(({ report }) => report.id)).toEqual(['RP-001', 'RP-002', 'RP-003']);
    expect(all.totalCount).toBe(3);
    expect(all.filteredCount).toBe(3);
    expect(filtered.items.map(({ report }) => report.id)).toEqual(['RP-002']);
    expect(filtered.totalCount).toBe(3);
    expect(filtered.filteredCount).toBe(1);
  });

  it('applies data scope before object selection and distinguishes empty reasons', () => {
    const hidden = selectReportLedger(
      createStore({ dataScope: ['AREA-B'] }).getState(),
      parseReportQuery('?reportId=RP-001'),
    );
    const filterEmpty = selectReportLedger(
      createStore().getState(),
      parseReportQuery('?period=2099-01-01'),
    );
    const store = createStore();
    store.replaceDomainState((candidate) => { candidate.report.reports = []; });
    const storeEmpty = selectReportLedger(store.getState(), parseReportQuery(''));

    expect(hidden).toMatchObject({ items: [], emptyReason: 'FORBIDDEN' });
    expect(filterEmpty).toMatchObject({ items: [], emptyReason: 'FILTER' });
    expect(storeEmpty).toMatchObject({ items: [], emptyReason: 'STORE' });
  });

  it('selects the requested report, falls back to the first row, and deep-freezes output', () => {
    const requested = selectReportDashboard(
      createStore().getState(),
      parseReportQuery('?reportId=RP-003'),
    );
    const fallback = selectReportDashboard(createStore().getState(), parseReportQuery(''));

    expect(requested.selected?.report.id).toBe('RP-003');
    expect(fallback.selected?.report.id).toBe('RP-001');
    expect(Object.isFrozen(requested)).toBe(true);
    expect(Object.isFrozen(requested.ledger.items)).toBe(true);
    expect(Object.isFrozen(requested.ledger.items[0]?.report)).toBe(true);
    expect(Object.isFrozen(requested.ledger.items[0]?.report.metrics)).toBe(true);
  });
});
