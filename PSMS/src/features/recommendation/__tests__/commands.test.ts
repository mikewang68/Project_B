import { describe, expect, it, vi } from 'vitest';
import { do001Schema, type ApiErrorEnvelope } from '../../../contracts';
import { createFixtureSnapshot } from '../../../mocks/fixtures';
import { createDemoStore } from '../../../stores';
import { createRecommendationCommandService } from '../commands';
import type {
  RecommendationGateway,
  RecommendationGatewaySuccess,
} from '../gateway';
import { selectReceptionRecommendations } from '../selectors';
import { createRecommendationWorkflowStore } from '../workflow';

function gatewaySuccess(
  apiId: 'API-005' | 'API-006',
  call: number,
): RecommendationGatewaySuccess {
  const rawPlan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
  if (!rawPlan) throw new Error('PLAN-001 missing.');
  const plan = do001Schema.parse(rawPlan);
  return {
    ok: true,
    data: {
      apiId,
      operationId:
        apiId === 'API-005'
          ? 'GET_mock_plans_id_recommendation'
          : 'POST_mock_plans_id_recommendation_confirm',
      now: '2026-07-16T09:00:00+08:00',
      scenarioId: 'SCN-01',
      items: [plan],
    },
    auditLogId: `MOCK-AUD-${call}`,
    traceId: `MOCK-TRACE-${call}`,
  };
}

function gatewayFailure(errorCode: ApiErrorEnvelope['errorCode']): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode,
    message: `Strict gateway failure ${errorCode}`,
    auditLogId: 'MOCK-AUD-FAIL',
    traceId: 'MOCK-TRACE-FAIL',
  };
}

function createContext(
  options: Readonly<{
    actorId?: string;
    roleCode?: 'DISPATCHER' | 'BUSINESS';
    commandFormatter?: (sequence: number) => string;
  }> = {},
) {
  const store = createDemoStore(createFixtureSnapshot(), {
    actorId: options.actorId ?? 'USER-001',
    roleCode: options.roleCode ?? 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
    shiftId: 'SHIFT-001',
    scenarioId: 'SCN-01',
  });
  store.replaceDomainState((candidate) => {
    const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
    if (!plan) throw new Error('PLAN-001 missing.');
    plan.status = 'CONFIRMED';
    plan.version = 2;
  });
  const workflow = createRecommendationWorkflowStore();
  const getRecommendation = vi.fn<RecommendationGateway['getRecommendation']>(
    async () => gatewaySuccess('API-005', 1),
  );
  const confirmRecommendation = vi.fn<RecommendationGateway['confirmRecommendation']>(
    async () => gatewaySuccess('API-006', 1),
  );
  const gateway = { getRecommendation, confirmRecommendation } satisfies RecommendationGateway;
  const commands = createRecommendationCommandService({
    store,
    gateway,
    workflow,
    ...(options.commandFormatter
      ? { idFormatters: { command: options.commandFormatter } }
      : {}),
  });
  return { store, workflow, commands, getRecommendation, confirmRecommendation };
}

function draftFrom(context: ReturnType<typeof createContext>) {
  const draft = selectReceptionRecommendations(context.store.getState(), 'PLAN-001');
  if (!draft) throw new Error('Recommendation draft missing.');
  return draft;
}

function candidateId(context: ReturnType<typeof createContext>, trackNo: string): string {
  const candidate = draftFrom(context).candidates.find((item) => item.trackNo === trackNo);
  if (!candidate) throw new Error(`Candidate ${trackNo} missing.`);
  return candidate.candidateId;
}

describe('RecommendationCommandService', () => {
  it('requires CONFIRMED PLAN-001 v2, invokes API-005, writes exact CALCULATED scores, and appends RC-01', async () => {
    const context = createContext();

    const result = await context.commands.calculateRecommendation('PLAN-001');

    expect(result).toEqual({
      ok: true,
      commandId: 'CMD-C05-001',
      traceId: 'TRACE-C05-001',
      auditLogId: 'AUD-C05-001',
    });
    expect(context.getRecommendation).toHaveBeenCalledOnce();
    expect(context.getRecommendation).toHaveBeenCalledWith('PLAN-001', 2);
    expect(draftFrom(context).candidates.map(({ trackNo, score, rank }) => [trackNo, score, rank])).toEqual([
      ['T1', 100, 1],
      ['T3', 62, 2],
      ['T2', 52, 3],
    ]);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(context.store.getState().configAudit.commandAudit[0]).toMatchObject({
      record: {
        id: 'AUD-C05-001',
        action: 'RC-01',
        objectId: 'PLAN-001',
        occurredAt: '2026-07-16T09:00:00+08:00',
      },
      metadata: { result: 'SUCCESS' },
    });
  });

  it('recalculation replaces the draft atomically, increments draftVersion, and clears selection', async () => {
    const context = createContext();
    await context.commands.calculateRecommendation('PLAN-001');
    context.workflow.selectCandidate(candidateId(context, 'T3'));
    const first = draftFrom(context);

    await context.commands.calculateRecommendation('PLAN-001');
    const second = draftFrom(context);

    expect(second).not.toBe(first);
    expect(second).toMatchObject({ draftVersion: 2, status: 'CALCULATED' });
    expect(second).not.toHaveProperty('selectedCandidateId');
    expect(context.workflow.getState().selectedCandidateId).toBeUndefined();
    expect(context.getRecommendation).toHaveBeenCalledTimes(2);
    expect(context.store.getState().configAudit.commandAudit.map(({ record }) => record.action)).toEqual([
      'RC-01',
      'RC-01',
    ]);
  });

  it('confirms T1 with the exact API-006 body and never changes Plan or Track facts', async () => {
    const context = createContext();
    await context.commands.calculateRecommendation('PLAN-001');
    const planBefore = structuredClone(context.store.getState().plan);
    const resourceBefore = structuredClone(context.store.getState().resource);
    const selectedCandidateId = candidateId(context, 'T1');
    context.workflow.selectCandidate(selectedCandidateId);

    const result = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: selectedCandidateId,
    });

    expect(result).toMatchObject({ ok: true, commandId: 'CMD-C05-002', traceId: 'TRACE-C05-002' });
    expect(context.confirmRecommendation).toHaveBeenCalledWith('PLAN-001', {
      trackNo: 'T1',
      window: '2026-07-16T08:01:00+08:00/2026-07-16T10:01:00+08:00',
    });
    expect(draftFrom(context)).toMatchObject({
      draftVersion: 2,
      status: 'CONFIRMED',
      selectedCandidateId,
      confirmation: {
        actorId: 'USER-001',
        roleCode: 'DISPATCHER',
        confirmedAt: '2026-07-16T09:00:00+08:00',
        commandId: 'CMD-C05-002',
        traceId: 'TRACE-C05-002',
      },
    });
    expect(context.store.getState().plan).toEqual(planBefore);
    expect(context.store.getState().resource).toEqual(resourceBefore);
    expect(context.store.getState().configAudit.commandAudit.at(-1)).toMatchObject({
      record: { action: 'RC-04', objectId: 'PLAN-001' },
      metadata: { result: 'SUCCESS' },
    });
  });

  it('requires reason and an independent reviewer for the high-risk T3 alternative', async () => {
    const context = createContext();
    await context.commands.calculateRecommendation('PLAN-001');
    const t3 = candidateId(context, 'T3');

    const missingReason = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: t3,
      reviewerId: 'USER-002',
    });
    const missingReviewer = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: t3,
      reason: '采用错峰方案',
    });

    expect(missingReason).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(missingReviewer).toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });
    expect(context.confirmRecommendation).not.toHaveBeenCalled();

    const confirmed = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: t3,
      reason: '  错峰释放 T1，采用 T3  ',
      reviewerId: 'USER-002',
    });
    expect(confirmed).toMatchObject({ ok: true });
    expect(context.confirmRecommendation).toHaveBeenCalledWith('PLAN-001', {
      trackNo: 'T3',
      window: '2026-07-16T11:03:00+08:00/2026-07-16T13:03:00+08:00',
      reason: '错峰释放 T1，采用 T3',
    });
    expect(draftFrom(context).adjustment).toEqual({
      originalCandidateId: candidateId(context, 'T1'),
      finalCandidateId: t3,
      reason: '错峰释放 T1，采用 T3',
      affectedWorkOrderIds: ['WO-001', 'WO-004', 'WO-007', 'WO-010'],
      reviewerId: 'USER-002',
    });
  });

  it('denies same-person review before API, preserves the draft, and appends one DENIED RC-04 audit', async () => {
    const context = createContext({ actorId: 'USER-001' });
    await context.commands.calculateRecommendation('PLAN-001');
    const before = context.store.getState().recommendation.drafts['PLAN-001'];
    const auditsBefore = context.store.getState().configAudit.commandAudit.length;

    const result = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: candidateId(context, 'T3'),
      reason: '采用错峰方案',
      reviewerId: 'USER-001',
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001' });
    expect(context.confirmRecommendation).not.toHaveBeenCalled();
    expect(context.store.getState().recommendation.drafts['PLAN-001']).toEqual(before);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(auditsBefore + 1);
    expect(context.store.getState().configAudit.commandAudit.at(-1)).toMatchObject({
      record: { action: 'RC-04' },
      metadata: { result: 'DENIED', errorCode: 'TOS-AUTH-001' },
    });
  });

  it('rejects unknown, completed-work-order, and no-longer-eligible alternatives before API', async () => {
    const unknown = createContext();
    await unknown.commands.calculateRecommendation('PLAN-001');
    await expect(
      unknown.commands.confirmRecommendation({
        planId: 'PLAN-001',
        candidateId: 'UNKNOWN-CANDIDATE',
        reason: 'unknown',
        reviewerId: 'USER-002',
      }),
    ).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });

    const completed = createContext();
    await completed.commands.calculateRecommendation('PLAN-001');
    completed.store.replaceDomainState((candidate) => {
      const order = candidate.workOrder.workOrders.find(({ id }) => id === 'WO-004');
      if (!order) throw new Error('WO-004 missing.');
      order.status = 'COMPLETED';
    });
    await expect(
      completed.commands.confirmRecommendation({
        planId: 'PLAN-001',
        candidateId: candidateId(completed, 'T3'),
        reason: 'completed',
        reviewerId: 'USER-002',
      }),
    ).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });

    const ineligible = createContext();
    await ineligible.commands.calculateRecommendation('PLAN-001');
    ineligible.store.replaceDomainState((candidate) => {
      const track = candidate.resource.tracks.find(({ trackNo }) => trackNo === 'T3');
      if (!track) throw new Error('T3 missing.');
      track.occupyStatus = 'BLOCKED';
    });
    await expect(
      ineligible.commands.confirmRecommendation({
        planId: 'PLAN-001',
        candidateId: candidateId(ineligible, 'T3'),
        reason: 'ineligible',
        reviewerId: 'USER-002',
      }),
    ).resolves.toMatchObject({ ok: false, errorCode: 'DEMO-SCENARIO-001' });

    expect(unknown.confirmRecommendation).not.toHaveBeenCalled();
    expect(completed.confirmRecommendation).not.toHaveBeenCalled();
    expect(ineligible.confirmRecommendation).not.toHaveBeenCalled();
  });

  it.each(['plan', 'track'] as const)(
    'rejects a changed %s version before API and retains selection and draft',
    async (kind) => {
      const context = createContext();
      await context.commands.calculateRecommendation('PLAN-001');
      const t3 = candidateId(context, 'T3');
      context.workflow.selectCandidate(t3);
      const draftBefore = context.store.getState().recommendation.drafts['PLAN-001'];
      context.store.replaceDomainState((candidate) => {
        if (kind === 'plan') {
          const plan = candidate.plan.plans.find(({ id }) => id === 'PLAN-001');
          if (!plan) throw new Error('PLAN-001 missing.');
          plan.version += 1;
        } else {
          const track = candidate.resource.tracks.find(({ trackNo }) => trackNo === 'T3');
          if (!track) throw new Error('T3 missing.');
          track.version += 1;
        }
      });

      const result = await context.commands.confirmRecommendation({
        planId: 'PLAN-001',
        candidateId: t3,
        reason: 'version test',
        reviewerId: 'USER-002',
      });

      expect(result).toMatchObject({ ok: false, errorCode: 'DEMO-VERSION-001' });
      expect(context.confirmRecommendation).not.toHaveBeenCalled();
      expect(context.store.getState().recommendation.drafts['PLAN-001']).toEqual(draftBefore);
      expect(context.workflow.getState().selectedCandidateId).toBe(t3);
    },
  );

  it('replays an injected commandId without a second request, draft commit, or audit', async () => {
    const context = createContext({ commandFormatter: () => 'CMD-C05-REPLAY' });

    const first = await context.commands.calculateRecommendation('PLAN-001');
    const draft = context.store.getState().recommendation.drafts['PLAN-001'];
    const state = context.store.getState();
    const second = await context.commands.calculateRecommendation('PLAN-001');

    expect(second).toBe(first);
    expect(context.getRecommendation).toHaveBeenCalledOnce();
    expect(context.store.getState().recommendation.drafts['PLAN-001']).toBe(draft);
    expect(context.store.getState()).toBe(state);
    expect(context.store.getState().configAudit.commandAudit).toHaveLength(1);
  });

  it('retains draft and workflow values after API-006 failure and writes one FAILED audit', async () => {
    const context = createContext();
    await context.commands.calculateRecommendation('PLAN-001');
    const t3 = candidateId(context, 'T3');
    context.workflow.selectCandidate(t3);
    const draftBefore = context.store.getState().recommendation.drafts['PLAN-001'];
    context.confirmRecommendation.mockResolvedValueOnce(gatewayFailure('TOS-EXT-001'));

    const result = await context.commands.confirmRecommendation({
      planId: 'PLAN-001',
      candidateId: t3,
      reason: '保留输入',
      reviewerId: 'USER-002',
    });

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001' });
    expect(context.store.getState().recommendation.drafts['PLAN-001']).toEqual(draftBefore);
    expect(context.workflow.getState()).toMatchObject({
      selectedCandidateId: t3,
      lastCommandError: { errorCode: 'TOS-EXT-001' },
    });
    expect(context.store.getState().configAudit.commandAudit.at(-1)).toMatchObject({
      record: { action: 'RC-04' },
      metadata: { result: 'FAILED', errorCode: 'TOS-EXT-001' },
    });
  });
});
