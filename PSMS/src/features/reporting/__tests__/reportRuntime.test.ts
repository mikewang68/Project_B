import { describe, expect, it, vi } from 'vitest';

import { createReportWorkflowStore } from '../reportRuntime';

describe('C11 report workflow store', () => {
  it('starts from the exact UI-only state and publishes immutable transitions', () => {
    const workflow = createReportWorkflowStore();
    const listener = vi.fn();
    const unsubscribe = workflow.subscribe(listener);

    expect(workflow.getState()).toEqual({
      filters: {},
      metricsDrawerOpen: false,
      snapshotSequence: 0,
    });

    workflow.selectReport('RP-002');
    workflow.setFilters({
      reportType: 'DAILY',
      period: '2026-07-17',
      generateStatus: 'FAILED',
    });
    workflow.setMetricsDrawerOpen(true);
    workflow.setPendingReportId('RP-002');

    expect(workflow.getState()).toMatchObject({
      selectedReportId: 'RP-002',
      filters: {
        reportType: 'DAILY',
        period: '2026-07-17',
        generateStatus: 'FAILED',
      },
      metricsDrawerOpen: true,
      pendingReportId: 'RP-002',
      snapshotSequence: 0,
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().filters)).toBe(true);
    expect(listener).toHaveBeenCalledTimes(4);

    unsubscribe();
  });

  it('records success/failure feedback, advances only successful snapshots, and resets exactly', () => {
    const workflow = createReportWorkflowStore();

    workflow.setPendingReportId('RP-002');
    workflow.recordFeedback({
      ok: false,
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
      message: '版本冲突',
      errorCode: 'DEMO-VERSION-001',
      idempotent: false,
    });
    expect(workflow.getState()).toMatchObject({
      snapshotSequence: 0,
      lastFeedback: { ok: false, errorCode: 'DEMO-VERSION-001' },
    });
    expect(workflow.getState().pendingReportId).toBeUndefined();

    workflow.setPendingReportId('RP-002');
    workflow.recordFeedback({
      ok: true,
      commandId: 'CMD-C11-002',
      traceId: 'TRACE-C11-002',
      auditLogId: 'AUD-C11-002',
      message: '报表快照已刷新',
      idempotent: false,
    });
    expect(workflow.getState()).toMatchObject({
      snapshotSequence: 1,
      lastFeedback: { ok: true, message: '报表快照已刷新' },
    });

    workflow.reset();
    expect(workflow.getState()).toEqual({
      filters: {},
      metricsDrawerOpen: false,
      snapshotSequence: 0,
    });
  });
});
