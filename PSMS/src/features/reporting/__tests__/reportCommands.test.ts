import { describe, expect, it, vi } from 'vitest';

import { do012Schema } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoSessionSeed } from '../../../stores';
import { createReportCommandService } from '../reportCommands';
import { deriveReportMetrics } from '../reportMetrics';
import { createReportWorkflowStore } from '../reportRuntime';

const session: DemoSessionSeed = {
  actorId: 'USER-BUSINESS',
  roleCode: 'BUSINESS',
  dataScope: ['AREA-A'],
  online: true,
  shiftId: 'SHIFT-001',
  scenarioId: 'SCN-01',
};

function setup() {
  const store = createDemoStore(createFixtureSnapshot(), session);
  const workflow = createReportWorkflowStore();
  const commands = createReportCommandService({ store, workflow });
  return { store, workflow, commands };
}

function reportOf(state: DemoRootState, reportId: string) {
  const report = state.report.reports.find(({ id }) => id === reportId);
  if (!report) throw new Error(`Missing test report: ${reportId}`);
  return report;
}

function upstreamSnapshot(state: DemoRootState) {
  return structuredClone({
    plan: state.plan,
    recommendation: state.recommendation,
    workOrder: state.workOrder,
    resource: state.resource,
    vehicle: state.vehicle,
    exception: state.exception,
    interlock: state.interlock,
    offline: state.offline,
  });
}

describe('C11 report refresh command', () => {
  it('atomically refreshes one DO-012 with current derived metrics and one audit', async () => {
    const { store, workflow, commands } = setup();
    const upstreamBefore = upstreamSnapshot(store.getState());
    const reportsBefore = structuredClone(store.getState().report.reports);
    const before = reportOf(store.getState(), 'RP-002');
    const expectedMetrics = deriveReportMetrics(store.getState()).flatMetrics;

    const result = await commands.refreshReport({
      commandId: 'CMD-C11-001',
      reportId: 'RP-002',
      expectedGeneratedAt: before.generatedAt,
      reason: '刷新日报快照',
    });

    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });
    expect(reportOf(store.getState(), 'RP-002')).toEqual({
      ...before,
      generateStatus: 'SUCCESS',
      metrics: expectedMetrics,
      generatedAt: store.getState().session.demoTime,
    });
    expect(store.getState().report.reports.filter(({ id }) => id !== 'RP-002'))
      .toEqual(reportsBefore.filter(({ id }) => id !== 'RP-002'));
    expect(upstreamSnapshot(store.getState())).toEqual(upstreamBefore);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(store.getState().configAudit.commandAudit[0]).toMatchObject({
      record: {
        id: 'AUD-C11-001',
        action: 'RS-03',
        objectType: 'DO-012',
        objectId: 'RP-002',
        before,
        after: reportOf(store.getState(), 'RP-002'),
        reason: '刷新日报快照',
        traceId: 'TRACE-C11-001',
      },
      metadata: { result: 'SUCCESS', errorCode: null },
    });
    expect(workflow.getState()).toMatchObject({
      snapshotSequence: 1,
      lastFeedback: {
        ok: true,
        commandId: 'CMD-C11-001',
        message: '报表快照已刷新',
        idempotent: false,
      },
    });
  });

  it.each([
    ['permission', { roleCode: 'SHIFT_LEADER' as const }, 'TOS-AUTH-001'],
    ['data scope', { dataScope: ['AREA-B'] }, 'TOS-AUTH-001'],
  ])('rejects %s before changing reports', async (_label, sessionPatch, errorCode) => {
    const { store, workflow, commands } = setup();
    store.replaceDomainState((candidate) => {
      Object.assign(candidate.session, sessionPatch);
    });
    const reportsBefore = structuredClone(store.getState().report.reports);
    const before = reportOf(store.getState(), 'RP-002');

    const result = await commands.refreshReport({
      commandId: 'CMD-C11-001',
      reportId: 'RP-002',
      expectedGeneratedAt: before.generatedAt,
      reason: '尝试刷新',
    });

    expect(result).toMatchObject({ ok: false, errorCode });
    expect(store.getState().report.reports).toEqual(reportsBefore);
    expect(workflow.getState().snapshotSequence).toBe(0);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(store.getState().configAudit.commandAudit[0]?.metadata.result).toBe('DENIED');
  });

  it.each([
    ['blank reason', 'RP-002', '', undefined, 'DEMO-SCENARIO-001'],
    ['unknown report', 'RP-404', '刷新不存在报表', '2026-07-01T00:00:00+08:00', 'DEMO-SCENARIO-001'],
    ['stale generatedAt', 'RP-002', '刷新旧快照', '2026-07-01T00:00:00+08:00', 'DEMO-VERSION-001'],
  ])('rejects %s without a report write', async (
    _label,
    reportId,
    reason,
    expectedGeneratedAt,
    errorCode,
  ) => {
    const { store, workflow, commands } = setup();
    const reportsBefore = structuredClone(store.getState().report.reports);
    const token = expectedGeneratedAt ?? reportOf(store.getState(), 'RP-002').generatedAt;

    const result = await commands.refreshReport({
      commandId: 'CMD-C11-001',
      reportId,
      expectedGeneratedAt: token,
      reason,
    });

    expect(result).toMatchObject({ ok: false, errorCode });
    expect(store.getState().report.reports).toEqual(reportsBefore);
    expect(workflow.getState().snapshotSequence).toBe(0);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('maps second-read drift to DEMO-VERSION-001 without overwriting the newer report', async () => {
    const { store, workflow, commands } = setup();
    const before = reportOf(store.getState(), 'RP-002');
    const originalReplace = store.replaceDomainState.bind(store);
    const driftedAt = '2026-07-16T08:59:30+08:00';
    let injected = false;
    store.replaceDomainState = vi.fn((mutator) => {
      if (!injected) {
        injected = true;
        originalReplace((candidate) => {
          const report = reportOf(candidate, 'RP-002');
          report.generatedAt = driftedAt;
        });
      }
      originalReplace(mutator);
    });

    const result = await commands.refreshReport({
      commandId: 'CMD-C11-001',
      reportId: 'RP-002',
      expectedGeneratedAt: before.generatedAt,
      reason: '并发刷新测试',
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
    expect(reportOf(store.getState(), 'RP-002')).toEqual({ ...before, generatedAt: driftedAt });
    expect(workflow.getState().snapshotSequence).toBe(0);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('replays the first frozen result without another commit, audit, or workflow sequence', async () => {
    const { store, workflow, commands } = setup();
    const before = reportOf(store.getState(), 'RP-003');
    const input = {
      commandId: 'CMD-C11-REPLAY',
      reportId: 'RP-003',
      expectedGeneratedAt: before.generatedAt,
      reason: '幂等刷新',
    } as const;

    const first = await commands.refreshReport(input);
    const stateAfterFirst = workflow.getState();
    const reportAfterFirst = reportOf(store.getState(), 'RP-003');
    const replay = await commands.refreshReport(input);

    expect(replay).toBe(first);
    expect(Object.isFrozen(first)).toBe(true);
    expect(reportOf(store.getState(), 'RP-003')).toEqual(reportAfterFirst);
    expect(store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(workflow.getState()).toBe(stateAfterFirst);
    expect(workflow.getState().snapshotSequence).toBe(1);
  });

  it('resets generated IDs and the idempotency cache deterministically', async () => {
    const { store, workflow, commands } = setup();
    const firstReport = reportOf(store.getState(), 'RP-001');
    await expect(commands.refreshReport({
      reportId: 'RP-001',
      expectedGeneratedAt: firstReport.generatedAt,
      reason: '第一次刷新',
    })).resolves.toMatchObject({
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });

    commands.resetCommandState();
    workflow.reset();
    store.replaceDomainState((candidate) => {
      candidate.configAudit.commandAudit = [];
      candidate.report.reports = do012Schema.array().parse(
        createFixtureSnapshot().objects['DO-012'],
      );
    });
    const resetReport = reportOf(store.getState(), 'RP-001');

    await expect(commands.refreshReport({
      reportId: 'RP-001',
      expectedGeneratedAt: resetReport.generatedAt,
      reason: '重置后刷新',
    })).resolves.toMatchObject({
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });
  });
});
