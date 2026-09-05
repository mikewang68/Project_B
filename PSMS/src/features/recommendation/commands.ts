import { z } from 'zod';

import { authorize as authorizePolicy, type PolicyContext } from '../../auth';
import {
  createCommandExecutor,
  transitionState,
  type AuditAppender,
  type CommandExecutor,
  type CommandPermissionDecision,
  type CommandResult,
  type DemoCommand,
} from '../../commands';
import {
  api006RequestSchema,
  do001Schema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type PublicErrorCode,
} from '../../contracts';
import { createAuditLedger, createCommandAuditAppender } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type { RecommendationGateway } from './gateway';
import { calculateReceptionRecommendation } from './ruleEngine';
import {
  RECOMMENDATION_RULE_VERSION,
  recommendationDraftSchema,
  type RecommendationCandidate,
  type RecommendationDraft,
} from './schemas';
import type { RecommendationWorkflowStore } from './types';

export type ConfirmRecommendationInput = Readonly<{
  planId: string;
  candidateId: string;
  reason?: string;
  reviewerId?: string;
}>;

export type RecommendationCommandService = {
  calculateRecommendation: (planId: string) => Promise<CommandResult>;
  confirmRecommendation: (input: ConfirmRecommendationInput) => Promise<CommandResult>;
  resetCommandState: () => void;
};

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type RecommendationCommandServiceDependencies = {
  store: DemoStoreApi;
  gateway: RecommendationGateway;
  workflow: RecommendationWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
};

type RecommendationCommandPayload = {
  current: 'ACCEPTED';
  businessAction: 'RC-01' | 'RC-04';
  inputPlanVersion: number;
  selectedCandidateId?: string;
  trackVersions: Record<string, number>;
  reason?: string;
  reviewerId?: string;
  affectedWorkOrderIds: string[];
};

const recommendationCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['RC-01', 'RC-04']),
    inputPlanVersion: z.number().int().nonnegative(),
    selectedCandidateId: z.string().trim().min(1).optional(),
    trackVersions: z.record(z.string().trim().min(1), z.number().int().positive()),
    reason: z.string().optional(),
    reviewerId: z.string().trim().min(1).optional(),
    affectedWorkOrderIds: z.array(z.string().trim().min(1)),
  })
  .strict();

const highRiskStatuses = new Set(['DISPATCHED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PAUSED']);

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C05', sequence),
  trace: (sequence) => sequenceId('TRACE-C05', sequence),
  audit: (sequence) => sequenceId('AUD-C05', sequence),
};

function canReadAreaA(state: DemoRootState): boolean {
  return (
    state.session.dataScope.includes('*') ||
    state.session.dataScope.includes('GLOBAL') ||
    state.session.dataScope.includes('AREA-A')
  );
}

function recommendationPayload(command: DemoCommand): RecommendationCommandPayload {
  return recommendationCommandPayloadSchema.parse(command.payload);
}

function linkedWorkOrderIds(state: DemoRootState, planId: string): string[] {
  return state.workOrder.workOrders
    .filter((workOrder) => workOrder.planId === planId)
    .map(({ id }) => id)
    .sort((left, right) => left.localeCompare(right));
}

function candidateTrackVersions(draft: RecommendationDraft): Record<string, number> {
  return Object.fromEntries(
    draft.candidates.map(({ trackId, trackVersion }) => [trackId, trackVersion]),
  );
}

function currentTrackVersions(state: DemoRootState): Record<string, number> {
  return Object.fromEntries(state.resource.tracks.map(({ id, version }) => [id, version]));
}

function sameStringArray(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function assertStoredVersions(
  state: DemoRootState,
  planId: string,
  payload: RecommendationCommandPayload,
): void {
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || plan.version !== payload.inputPlanVersion) {
    throw new Error(
      `Expected Plan version ${payload.inputPlanVersion}, actual ${String(plan?.version)}.`,
    );
  }
  for (const [trackId, expectedVersion] of Object.entries(payload.trackVersions)) {
    const actualVersion = state.resource.tracks.find(({ id }) => id === trackId)?.version;
    if (actualVersion !== expectedVersion) {
      throw new Error(
        `Expected Track ${trackId} version ${expectedVersion}, actual ${String(actualVersion)}.`,
      );
    }
  }
}

function scenarioFailure(response: ApiSuccessEnvelope, message: string): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-SCENARIO-001',
    message,
    traceId: response.traceId,
    auditLogId: response.auditLogId,
  };
}

function versionFailure(response: ApiSuccessEnvelope, message: string): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-VERSION-001',
    message,
    traceId: response.traceId,
    auditLogId: response.auditLogId,
  };
}

function currentCandidate(
  state: DemoRootState,
  draft: RecommendationDraft,
  candidateId: string,
): RecommendationCandidate | undefined {
  const plan = state.plan.plans.find(({ id }) => id === draft.planId);
  if (!plan) return undefined;
  const calculated = calculateReceptionRecommendation({
    plan,
    tracks: state.resource.tracks,
    generatedAt: state.session.demoTime,
  });
  return calculated.candidates.find((candidate) => candidate.candidateId === candidateId);
}

function permissionFailure(errorCode: PublicErrorCode, message: string): CommandPermissionDecision {
  if (errorCode !== 'TOS-AUTH-001' && errorCode !== 'DEMO-VERSION-001') {
    return { allow: false, errorCode: 'TOS-AUTH-001', message };
  }
  return { allow: false, errorCode, message };
}

export function createRecommendationCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: RecommendationCommandServiceDependencies): RecommendationCommandService {
  const idFormatters: CommandIdFormatters = { ...defaultIdFormatters, ...inputFormatters };
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  let executor: CommandExecutor;
  const traceIdByCommandId = new Map<string, string>();
  const calculationsByCommandId = new Map<
    string,
    ReturnType<typeof calculateReceptionRecommendation>
  >();
  const processedCommandIds = new Set<string>();

  const nextCommandId = (): string => idFormatters.command(commandSequence++);
  const nextTraceId = (commandId: string): string => {
    const replay = traceIdByCommandId.get(commandId);
    if (replay) return replay;
    const traceId = idFormatters.trace(traceSequence++);
    traceIdByCommandId.set(commandId, traceId);
    return traceId;
  };
  const nextAuditId = (): string => {
    const usedIds = new Set(
      store.getState().configAudit.commandAudit.map(({ record }) => record.id),
    );
    for (let attempts = 0; attempts <= usedIds.size; attempts += 1) {
      const candidate = idFormatters.audit(auditSequence++);
      if (!usedIds.has(candidate)) return candidate;
    }
    throw new Error('Unable to allocate a unique C05 audit ID.');
  };

  const currentTime = (): string => store.getState().session.demoTime;
  const currentActor = () => {
    const session = store.getState().session;
    return {
      actorId: session.actorId,
      roleCode: session.roleCode,
      dataScope: [...session.dataScope],
      online: session.online,
    };
  };

  const authorize = (command: DemoCommand): CommandPermissionDecision => {
    const state = store.getState();
    const payload = recommendationPayload(command);
    const visible = canReadAreaA(state);
    const plan = visible
      ? state.plan.plans.find(({ id }) => id === command.entityId)
      : undefined;
    const draftResult = visible
      ? recommendationDraftSchema.safeParse(state.recommendation.drafts[command.entityId])
      : undefined;
    const selected = draftResult?.success
      ? draftResult.data.candidates.find(
          ({ candidateId }) => candidateId === payload.selectedCandidateId,
        )
      : undefined;
    const isConfirm = payload.businessAction === 'RC-04';
    const isAlternative = isConfirm && selected !== undefined && selected.rank !== 1;
    const affectedOrders = visible
      ? state.workOrder.workOrders.filter(({ id }) => payload.affectedWorkOrderIds.includes(id))
      : [];
    const highRisk = isAlternative && affectedOrders.some(({ status }) => highRiskStatuses.has(status));
    const objectScope = { type: 'AREA', value: 'AREA-A' } as const;
    const contexts: PolicyContext[] = [
      {
        session: state.session,
        pageId: 'UI-003',
        permission: 'plan:recommend',
        objectScope,
        expectedVersion: command.expectedVersion,
        actualVersion: plan?.version,
      },
      ...(isAlternative
        ? [{ session: state.session, pageId: 'UI-003', permission: 'plan:adjust', objectScope }]
        : []),
      ...(isConfirm
        ? [
            {
              session: state.session,
              pageId: 'UI-003',
              permission: 'plan:confirm',
              objectScope,
              ...(highRisk
                ? {
                    highRisk: true,
                    applicantId: state.session.actorId,
                    approverId: payload.reviewerId,
                  }
                : {}),
            },
          ]
        : []),
    ];

    for (const context of contexts) {
      const decision = authorizePolicy(context);
      if (!decision.allow) return permissionFailure(decision.errorCode, decision.reason);
    }

    for (const [trackId, expectedVersion] of Object.entries(payload.trackVersions)) {
      const actualVersion = state.resource.tracks.find(({ id }) => id === trackId)?.version;
      if (actualVersion !== expectedVersion) {
        return {
          allow: false,
          errorCode: 'DEMO-VERSION-001',
          message: `Expected Track ${trackId} version ${expectedVersion}, actual ${String(actualVersion)}.`,
        };
      }
    }
    return { allow: true };
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported recommendation command.');
    }
    const payload = recommendationPayload(command);
    const state = store.getState();
    if (!canReadAreaA(state)) throw new Error('Plan is outside AREA-A data scope.');
    const plan = state.plan.plans.find(({ id }) => id === command.entityId);
    if (!plan) throw new Error(`Unknown plan: ${command.entityId}`);
    do001Schema.parse(plan);
    if (plan.status !== 'CONFIRMED') throw new Error('Recommendation requires a CONFIRMED Plan.');
    if (plan.missingFields.length > 0) throw new Error('Recommendation requires a complete Plan.');

    assertStoredVersions(state, command.entityId, payload);

    if (payload.businessAction === 'RC-01') {
      calculateReceptionRecommendation({
        plan,
        tracks: state.resource.tracks,
        generatedAt: state.session.demoTime,
      });
      return;
    }

    const draft = recommendationDraftSchema.parse(state.recommendation.drafts[command.entityId]);
    if (draft.status !== 'CALCULATED') throw new Error('Only a CALCULATED draft may be confirmed.');
    const candidate = draft.candidates.find(
      ({ candidateId }) => candidateId === payload.selectedCandidateId,
    );
    if (!candidate) throw new Error(`Unknown recommendation candidate: ${payload.selectedCandidateId}`);
    const eligible = currentCandidate(state, draft, candidate.candidateId);
    if (!eligible || eligible.trackVersion !== candidate.trackVersion) {
      throw new Error('The selected recommendation candidate is no longer eligible.');
    }
    const affectedWorkOrderIds = linkedWorkOrderIds(state, command.entityId);
    if (!sameStringArray(affectedWorkOrderIds, payload.affectedWorkOrderIds)) {
      throw new Error('Affected work orders changed after command creation.');
    }
    const isAlternative = candidate.rank !== 1;
    if (isAlternative && !payload.reason?.trim()) {
      throw new Error('An alternative recommendation requires a reason.');
    }
    const affectedOrders = state.workOrder.workOrders.filter(({ id }) =>
      affectedWorkOrderIds.includes(id),
    );
    if (isAlternative && affectedOrders.some(({ status }) => status === 'COMPLETED')) {
      throw new Error('A completed work order blocks alternative confirmation.');
    }
    const highRisk =
      isAlternative && affectedOrders.some(({ status }) => highRiskStatuses.has(status));
    if (highRisk && !payload.reviewerId) {
      throw new Error('A high-risk alternative requires an independent reviewer.');
    }
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = recommendationPayload(command);
    if (payload.businessAction === 'RC-01') {
      const response = await gateway.getRecommendation(command.entityId, payload.inputPlanVersion);
      if (!response.ok) return response;
      if (response.data.items[0]?.id !== command.entityId) {
        return scenarioFailure(response, `Expected response Plan ${command.entityId}.`);
      }
      const state = store.getState();
      try {
        assertStoredVersions(state, command.entityId, payload);
      } catch (error) {
        return versionFailure(response, error instanceof Error ? error.message : 'Version changed.');
      }
      const plan = state.plan.plans.find(({ id }) => id === command.entityId);
      if (!plan) return scenarioFailure(response, `Unknown plan: ${command.entityId}`);
      calculationsByCommandId.set(
        command.commandId,
        calculateReceptionRecommendation({
          plan,
          tracks: state.resource.tracks,
          generatedAt: state.session.demoTime,
        }),
      );
      return { ...response, data: { ...response.data, status: 'ACCEPTED' } } satisfies ApiSuccessEnvelope;
    }

    const state = store.getState();
    const draft = recommendationDraftSchema.parse(state.recommendation.drafts[command.entityId]);
    const candidate = draft.candidates.find(
      ({ candidateId }) => candidateId === payload.selectedCandidateId,
    );
    if (!candidate) throw new Error(`Unknown recommendation candidate: ${payload.selectedCandidateId}`);
    const response = await gateway.confirmRecommendation(
      command.entityId,
      api006RequestSchema.parse({
        trackNo: candidate.trackNo,
        window: `${candidate.windowStart}/${candidate.windowEnd}`,
        ...(payload.reason?.trim() ? { reason: payload.reason.trim() } : {}),
      }),
    );
    if (!response.ok) return response;
    try {
      assertStoredVersions(store.getState(), command.entityId, payload);
    } catch (error) {
      return versionFailure(response, error instanceof Error ? error.message : 'Version changed.');
    }
    return { ...response, data: { ...response.data, status: 'ACCEPTED' } } satisfies ApiSuccessEnvelope;
  };

  const commit = (command: DemoCommand): void => {
    const payload = recommendationPayload(command);
    if (payload.businessAction === 'RC-01') {
      const calculation = calculationsByCommandId.get(command.commandId);
      if (!calculation) throw new Error('Recommendation calculation result is missing.');
      store.replaceDomainState((candidateState) => {
        assertStoredVersions(candidateState, command.entityId, payload);
        const previousResult = recommendationDraftSchema.safeParse(
          candidateState.recommendation.drafts[command.entityId],
        );
        candidateState.recommendation.drafts[command.entityId] = recommendationDraftSchema.parse({
          planId: command.entityId,
          draftVersion: previousResult.success ? previousResult.data.draftVersion + 1 : 1,
          status: 'CALCULATED',
          inputPlanVersion: payload.inputPlanVersion,
          ruleVersion: RECOMMENDATION_RULE_VERSION,
          generatedAt: candidateState.session.demoTime,
          candidates: calculation.candidates,
          excluded: calculation.excluded,
        });
      });
      calculationsByCommandId.delete(command.commandId);
      workflow.selectCandidate(undefined);
      return;
    }

    store.replaceDomainState((candidateState) => {
      assertStoredVersions(candidateState, command.entityId, payload);
      const draft = recommendationDraftSchema.parse(
        candidateState.recommendation.drafts[command.entityId],
      );
      if (draft.status !== 'CALCULATED') throw new Error('Only a CALCULATED draft may be confirmed.');
      const selected = draft.candidates.find(
        ({ candidateId }) => candidateId === payload.selectedCandidateId,
      );
      if (!selected) throw new Error(`Unknown recommendation candidate: ${payload.selectedCandidateId}`);
      const original = draft.candidates.find(({ rank }) => rank === 1);
      if (!original) throw new Error('The recommendation has no first-ranked candidate.');
      const isAlternative = selected.rank !== 1;
      candidateState.recommendation.drafts[command.entityId] = recommendationDraftSchema.parse({
        ...draft,
        draftVersion: draft.draftVersion + 1,
        status: 'CONFIRMED',
        selectedCandidateId: selected.candidateId,
        ...(isAlternative
          ? {
              adjustment: {
                originalCandidateId: original.candidateId,
                finalCandidateId: selected.candidateId,
                reason: payload.reason?.trim(),
                affectedWorkOrderIds: payload.affectedWorkOrderIds,
                ...(payload.reviewerId ? { reviewerId: payload.reviewerId } : {}),
              },
            }
          : {}),
        confirmation: {
          actorId: candidateState.session.actorId,
          roleCode: candidateState.session.roleCode,
          confirmedAt: candidateState.session.demoTime,
          commandId: command.commandId,
          traceId: command.traceId,
        },
      });
    });
  };

  const appendRecommendationAudit: AuditAppender = (input) => {
    const ledger = createAuditLedger(
      store.getState().configAudit.commandAudit,
      (entries) => {
        store.replaceDomainState((candidate) => {
          candidate.configAudit.commandAudit = structuredClone(entries);
        });
      },
    );
    const baseAppender = createCommandAuditAppender(ledger);
    return baseAppender({
      ...input,
      command: {
        ...input.command,
        action: recommendationPayload(input.command).businessAction,
      },
    });
  };

  const buildExecutor = (): void => {
    executor = createCommandExecutor({
      authorize,
      validate,
      invokeMock,
      transition: transitionState,
      commit,
      appendAudit: appendRecommendationAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const commandIdentity = () => {
    const commandId = nextCommandId();
    return { commandId, traceId: nextTraceId(commandId) };
  };

  const commandFor = (
    planId: string,
    payload: RecommendationCommandPayload,
  ): DemoCommand<RecommendationCommandPayload> => ({
    ...commandIdentity(),
    action: 'execute',
    entityType: 'SM-007',
    entityId: planId,
    expectedVersion: payload.inputPlanVersion,
    payload,
    actor: currentActor(),
    clientTime: currentTime(),
  });

  const calculateCommand = (planId: string): DemoCommand<RecommendationCommandPayload> => {
    const state = store.getState();
    const visible = canReadAreaA(state);
    const plan = visible ? state.plan.plans.find(({ id }) => id === planId) : undefined;
    return commandFor(planId, {
      current: 'ACCEPTED',
      businessAction: 'RC-01',
      inputPlanVersion: plan?.version ?? 0,
      trackVersions: visible ? currentTrackVersions(state) : {},
      affectedWorkOrderIds: visible ? linkedWorkOrderIds(state, planId) : [],
    });
  };

  const confirmCommand = (
    input: ConfirmRecommendationInput,
  ): DemoCommand<RecommendationCommandPayload> => {
    const state = store.getState();
    const visible = canReadAreaA(state);
    const plan = visible ? state.plan.plans.find(({ id }) => id === input.planId) : undefined;
    const draftResult = visible
      ? recommendationDraftSchema.safeParse(state.recommendation.drafts[input.planId])
      : undefined;
    return commandFor(input.planId, {
      current: 'ACCEPTED',
      businessAction: 'RC-04',
      inputPlanVersion: draftResult?.success ? draftResult.data.inputPlanVersion : plan?.version ?? 0,
      selectedCandidateId: input.candidateId,
      trackVersions: draftResult?.success ? candidateTrackVersions(draftResult.data) : {},
      ...(input.reason !== undefined ? { reason: input.reason } : {}),
      ...(input.reviewerId !== undefined ? { reviewerId: input.reviewerId } : {}),
      affectedWorkOrderIds: visible ? linkedWorkOrderIds(state, input.planId) : [],
    });
  };

  const recordResult = (result: CommandResult): CommandResult => {
    if (!processedCommandIds.has(result.commandId)) {
      processedCommandIds.add(result.commandId);
      workflow.recordCommandError(
        result.ok ? undefined : { errorCode: result.errorCode, message: result.message },
      );
    }
    return result;
  };

  const calculateRecommendation = async (planId: string): Promise<CommandResult> =>
    recordResult(await executor.execute(calculateCommand(planId)));

  const confirmRecommendation = async (
    input: ConfirmRecommendationInput,
  ): Promise<CommandResult> => recordResult(await executor.execute(confirmCommand(input)));

  const resetCommandState = (): void => {
    commandSequence = 1;
    traceSequence = 1;
    auditSequence = 1;
    traceIdByCommandId.clear();
    calculationsByCommandId.clear();
    processedCommandIds.clear();
    buildExecutor();
  };

  buildExecutor();

  return { calculateRecommendation, confirmRecommendation, resetCommandState };
}
