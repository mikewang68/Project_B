import { describe, expect, it, vi } from 'vitest';

import { createAuditTrailWorkflowStore } from '../auditRuntime';

describe('C12 audit trail workflow store', () => {
  it('starts from the exact UI-only state and publishes immutable navigation transitions', () => {
    const workflow = createAuditTrailWorkflowStore();
    const listener = vi.fn();
    const unsubscribe = workflow.subscribe(listener);

    expect(workflow.getState()).toEqual({
      filters: {},
      detailDrawerOpen: false,
      pending: false,
      readState: { kind: 'idle' },
    });

    workflow.setFilters({ module: 'C11', actorId: 'USER-001' });
    workflow.openDetail('AUD-C11-001');
    workflow.setExpandedTrace('TRACE-C11-001');

    expect(workflow.getState()).toMatchObject({
      filters: { module: 'C11', actorId: 'USER-001' },
      selectedAuditId: 'AUD-C11-001',
      detailDrawerOpen: true,
      expandedTraceId: 'TRACE-C11-001',
    });
    expect(Object.isFrozen(workflow.getState())).toBe(true);
    expect(Object.isFrozen(workflow.getState().filters)).toBe(true);

    workflow.closeDetail();
    workflow.setExpandedTrace(undefined);
    expect(workflow.getState().detailDrawerOpen).toBe(false);
    expect(workflow.getState().selectedAuditId).toBeUndefined();
    expect(workflow.getState().expandedTraceId).toBeUndefined();
    expect(listener).toHaveBeenCalledTimes(5);
    unsubscribe();
  });

  it('records success and business observations without turning envelope auditLogId into a record id', () => {
    const workflow = createAuditTrailWorkflowStore();

    workflow.beginRead();
    expect(workflow.getState()).toMatchObject({ pending: true, readState: { kind: 'loading' } });

    workflow.recordReadSuccess({
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      traceId: 'TRACE-ENVELOPE-024',
      auditLogId: 'AUD-ENVELOPE-024',
    });
    expect(workflow.getState()).toMatchObject({
      pending: false,
      readState: { kind: 'success' },
      readObservation: {
        kind: 'success',
        traceId: 'TRACE-ENVELOPE-024',
        auditLogId: 'AUD-ENVELOPE-024',
      },
    });
    expect(workflow.getState().selectedAuditId).toBeUndefined();

    workflow.beginRead();
    workflow.recordReadBusinessError({
      errorCode: 'DEMO-SCENARIO-001',
      message: '当前场景不允许读取审计日志。',
      traceId: 'TRACE-ERROR-024',
      auditLogId: 'AUD-ERROR-024',
    });
    expect(workflow.getState()).toMatchObject({
      pending: false,
      readState: {
        kind: 'business-error',
        errorCode: 'DEMO-SCENARIO-001',
        message: '当前场景不允许读取审计日志。',
      },
      readObservation: {
        kind: 'business-error',
        traceId: 'TRACE-ERROR-024',
        auditLogId: 'AUD-ERROR-024',
      },
    });
  });

  it('distinguishes transport failures and page feedback, then resets exactly', () => {
    const workflow = createAuditTrailWorkflowStore();

    workflow.beginRead();
    workflow.recordReadFailure('network-error', 'audit network down');
    expect(workflow.getState()).toMatchObject({
      pending: false,
      readState: { kind: 'network-error', message: 'audit network down' },
    });

    workflow.beginRead();
    workflow.recordReadFailure('malformed-response', 'API-024 response is invalid');
    workflow.recordFeedback({ kind: 'success', message: '已打开审计详情。' });
    expect(workflow.getState()).toMatchObject({
      readState: { kind: 'malformed-response' },
      lastFeedback: { kind: 'success', message: '已打开审计详情。' },
    });

    workflow.recordFeedback({ kind: 'stale-trace', message: '原链路已失效。' });
    expect(workflow.getState().lastFeedback).toEqual({
      kind: 'stale-trace',
      message: '原链路已失效。',
    });

    workflow.reset();
    expect(workflow.getState()).toEqual({
      filters: {},
      detailDrawerOpen: false,
      pending: false,
      readState: { kind: 'idle' },
    });
  });
});
