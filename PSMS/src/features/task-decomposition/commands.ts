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
  do001Schema,
  do013Schema,
  do005Schema,
  do006Schema,
  roleCodeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
} from '../../contracts';
import { createAuditLedger, validateCommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import { recommendationDraftSchema } from '../recommendation';
import { TASK_DECOMPOSITION_RULE_VERSION, taskModes } from './constants';
import { mergeTaskDraft, splitTaskDraft } from './editEngine';
import type { TaskDecompositionGateway } from './gateway';
import {
  isC06WorkNode,
  isC06WorkOrder,
  parseGenerationVersion,
  validateTaskGeneration,
} from './ownership';
import { explainTaskRoute, generateTaskDraft } from './ruleEngine';
import type {
  TaskDecompositionWorkflowStore,
  TaskGeneration,
  TaskMode,
} from './types';

export type TaskDecompositionCommandService = {
  generateTasks: (planId: string) => Promise<CommandResult>;
  splitTask: (input: {
    planId: string;
    targetNodeId: string;
    reason: string;
  }) => Promise<CommandResult>;
  mergeTasks: (input: {
    planId: string;
    nodeIds: readonly [string, string];
    reason: string;
  }) => Promise<CommandResult>;
  regenerateTasks: (planId: string) => Promise<CommandResult>;
  confirmTasks: (planId: string) => Promise<CommandResult>;
  resetCommandState: () => void;
};

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type TaskDecompositionCommandServiceDependencies = {
  store: DemoStoreApi;
  gateway: TaskDecompositionGateway;
  workflow: TaskDecompositionWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
};

export const taskCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['TD-01', 'TD-02', 'TD-03', 'TD-04', 'TD-05']),
    mode: z.enum(taskModes),
    planVersion: z.number().int().nonnegative(),
    recommendationDraftVersion: z.number().int().nonnegative(),
    resourceVersions: z.record(z.string().trim().min(1), z.number().int().positive()),
    workOrderVersions: z.record(z.string().trim().min(1), z.number().int().positive()),
    nodeVersions: z.record(z.string().trim().min(1), z.number().int().positive()),
    generationVersion: z.number().int().positive(),
    targetNodeId: z.string().trim().min(1).optional(),
    selectedNodeIds: z.array(z.string().trim().min(1)),
    reason: z.string().optional(),
  })
  .strict();

export type TaskCommandPayload = z.infer<typeof taskCommandPayloadSchema>;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C06', sequence),
  trace: (sequence) => sequenceId('TRACE-C06', sequence),
  audit: (sequence) => sequenceId('AUD-C06', sequence),
};

function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*')
    || state.session.dataScope.includes('GLOBAL')
    || state.session.dataScope.includes('AREA-A');
}

function taskPayload(command: DemoCommand): TaskCommandPayload {
  return taskCommandPayloadSchema.parse(command.payload);
}

function ownedGeneration(state: DemoRootState, planId: string): TaskGeneration {
  const workOrders = state.workOrder.workOrders.filter((item) => isC06WorkOrder(item, planId));
  const workOrderPrefix = `C06-WO-${planId}-`;
  const nodes = state.workOrder.nodes.filter(
    (item) => isC06WorkNode(item) && item.workOrderNo.startsWith(workOrderPrefix),
  );
  return { workOrders, nodes };
}

function versionMap(items: readonly Readonly<{ id: string; version: number }>[]): Record<string, number> {
  return Object.fromEntries(
    [...items]
      .sort((left, right) => left.id.localeCompare(right.id))
      .map(({ id, version }) => [id, version]),
  );
}

function assertVersionMap(
  label: string,
  expected: Record<string, number>,
  actual: Record<string, number>,
): void {
  const expectedKeys = Object.keys(expected).sort();
  const actualKeys = Object.keys(actual).sort();
  if (JSON.stringify(expectedKeys) !== JSON.stringify(actualKeys)) {
    throw new Error(`Expected ${label} IDs ${expectedKeys.join(',')}, actual ${actualKeys.join(',')}.`);
  }
  for (const id of expectedKeys) {
    if (actual[id] !== expected[id]) {
      throw new Error(`Expected ${label} ${id} version ${expected[id]}, actual ${actual[id]}.`);
    }
  }
}

function assertStoredVersions(
  state: DemoRootState,
  planId: string,
  payload: TaskCommandPayload,
): void {
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan || plan.version !== payload.planVersion) {
    throw new Error(`Expected Plan version ${payload.planVersion}, actual ${String(plan?.version)}.`);
  }
  const recommendation = recommendationDraftSchema.safeParse(state.recommendation.drafts[planId]);
  const actualDraftVersion = recommendation.success ? recommendation.data.draftVersion : undefined;
  if (actualDraftVersion !== payload.recommendationDraftVersion) {
    throw new Error(
      `Expected RecommendationDraft version ${payload.recommendationDraftVersion}, actual ${String(actualDraftVersion)}.`,
    );
  }
  const visibleResources = state.resource.resources.filter(({ workArea }) => workArea === 'AREA-A');
  const generation = ownedGeneration(state, planId);
  assertVersionMap('Resource', payload.resourceVersions, versionMap(visibleResources));
  assertVersionMap('WorkOrder', payload.workOrderVersions, versionMap(generation.workOrders));
  assertVersionMap('WorkNode', payload.nodeVersions, versionMap(generation.nodes));
}

function currentGenerationVersion(state: DemoRootState, planId: string): number {
  const versions = ownedGeneration(state, planId).workOrders
    .map(({ id }) => parseGenerationVersion(id))
    .filter((value): value is number => value !== undefined);
  return Math.max(0, ...versions);
}

function nextGenerationVersion(state: DemoRootState, planId: string): number {
  return currentGenerationVersion(state, planId) + 1;
}

function permissionFailure(
  decision: Exclude<ReturnType<typeof authorizePolicy>, { allow: true }>,
): CommandPermissionDecision {
  return {
    allow: false,
    errorCode: decision.errorCode,
    message: decision.reason,
  };
}

function scenarioFailure(
  response: ApiSuccessEnvelope,
  message: string,
): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-SCENARIO-001',
    message,
    auditLogId: response.auditLogId,
    traceId: response.traceId,
  };
}

function versionFailure(
  response: ApiSuccessEnvelope,
  message: string,
): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-VERSION-001',
    message,
    auditLogId: response.auditLogId,
    traceId: response.traceId,
  };
}

function assertBusinessPreconditions(
  state: DemoRootState,
  planId: string,
  payload: TaskCommandPayload,
): void {
  if (!canReadAreaA(state)) throw new Error('Plan is outside AREA-A data scope.');
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan) throw new Error(`Unknown plan: ${planId}`);
  do001Schema.parse(plan);
  if (plan.status !== 'CONFIRMED' || plan.missingFields.length > 0) {
    throw new Error('Task decomposition requires a complete CONFIRMED Plan.');
  }
  const recommendation = recommendationDraftSchema.parse(state.recommendation.drafts[planId]);
  if (recommendation.status !== 'CONFIRMED' || recommendation.planId !== planId) {
    throw new Error('Task decomposition requires a CONFIRMED C05 recommendation.');
  }
  assertStoredVersions(state, planId, payload);
  const isGenerationAction = payload.businessAction === 'TD-01'
    || payload.businessAction === 'TD-04';
  const expectedGenerationVersion = isGenerationAction
    ? nextGenerationVersion(state, planId)
    : currentGenerationVersion(state, planId);
  if (payload.generationVersion !== expectedGenerationVersion) {
    throw new Error(
      `Expected generation G${String(expectedGenerationVersion).padStart(3, '0')}.`,
    );
  }

  const generation = ownedGeneration(state, planId);
  if (payload.businessAction === 'TD-01') {
    if (generation.workOrders.some(({ status }) => status === 'DRAFT')) {
      throw new Error('C06 DRAFT tasks already exist; use TD-04 重新生成.');
    }
    if (generation.workOrders.length > 0 || generation.nodes.length > 0) {
      throw new Error('Existing C06 tasks cannot be overwritten by TD-01.');
    }
    return;
  }

  if (payload.businessAction === 'TD-04') {
    if (generation.workOrders.length === 0) {
      throw new Error('TD-04 requires an existing C06 DRAFT generation.');
    }
    validateTaskGeneration(generation, planId);
    return;
  }

  if (payload.businessAction === 'TD-05') {
    validateTaskGeneration(generation, planId);
    const route = explainTaskRoute(plan.cargoType).route;
    const catalogTypes = new Set(state.resource.resources.map(({ resourceType }) => resourceType));
    const missingResourceTypes = [...new Set(
      generation.workOrders
        .map(({ type }) => route.find(({ taskType }) => taskType === type)?.requiredResourceType)
        .filter((resourceType) => !resourceType || !catalogTypes.has(resourceType)),
    )].sort();
    if (missingResourceTypes.length > 0) {
      throw new Error(`Required resource types are absent: ${missingResourceTypes.join(',')}.`);
    }
    return;
  }

  if (payload.businessAction === 'TD-02' || payload.businessAction === 'TD-03') {
    validateTaskGeneration(generation, planId);
    if (!payload.reason?.trim()) throw new Error(`${payload.businessAction} requires a reason.`);
    if (payload.businessAction === 'TD-02') {
      if (!payload.targetNodeId || payload.selectedNodeIds.length !== 1
        || payload.selectedNodeIds[0] !== payload.targetNodeId) {
        throw new Error('TD-02 requires exactly the target WorkNode selection.');
      }
      splitTaskDraft({
        ...generation,
        targetNodeId: payload.targetNodeId,
        demoTime: state.session.demoTime,
      });
      return;
    }
    if (payload.selectedNodeIds.length !== 2) {
      throw new Error('TD-03 requires exactly two selected WorkNodes.');
    }
    mergeTaskDraft({
      ...generation,
      nodeIds: [payload.selectedNodeIds[0]!, payload.selectedNodeIds[1]!],
      demoTime: state.session.demoTime,
    });
    return;
  }

  throw new Error(`${payload.businessAction} is implemented by a later C06 task.`);
}

export function createTaskDecompositionCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: TaskDecompositionCommandServiceDependencies): TaskDecompositionCommandService {
  const idFormatters: CommandIdFormatters = { ...defaultIdFormatters, ...inputFormatters };
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  let executor: CommandExecutor;
  const traceIdByCommandId = new Map<string, string>();
  const stagedCandidates = new Map<string, TaskGeneration>();
  const stagedConfirmations = new Map<string, Readonly<{
    workOrderIds: readonly string[];
    nodeIds: readonly string[];
  }>>();
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
    throw new Error('Unable to allocate a unique C06 audit ID.');
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
    const payload = taskPayload(command);
    const visible = canReadAreaA(state);
    const plan = visible
      ? state.plan.plans.find(({ id }) => id === command.entityId)
      : undefined;
    const permission = payload.businessAction === 'TD-02' || payload.businessAction === 'TD-03'
      ? 'task:edit'
      : 'task:decompose';
    const context: PolicyContext = {
      session: state.session,
      pageId: 'UI-004',
      permission,
      objectScope: { type: 'AREA', value: 'AREA-A' },
      expectedVersion: command.expectedVersion,
      actualVersion: plan?.version,
    };
    const decision = authorizePolicy(context);
    return decision.allow ? { allow: true } : permissionFailure(decision);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported task-decomposition command.');
    }
    const payload = taskPayload(command);
    assertBusinessPreconditions(store.getState(), command.entityId, payload);
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = taskPayload(command);
    const stateBeforeGateway = store.getState();
    if (stateBeforeGateway.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP') {
      return {
        ok: false,
        errorCode: 'TOS-IL-001',
        message: 'TOS-IL-001: INTERLOCK_FORCE_STOP blocks all C06 write actions.',
        auditLogId: 'MOCK-C06-INTERLOCK',
        traceId: command.traceId,
      } satisfies ApiErrorEnvelope;
    }
    const response = await gateway.decomposePlan(command.entityId, {
      ruleVersion: TASK_DECOMPOSITION_RULE_VERSION,
      mode: payload.mode,
    });
    if (!response.ok) return response;
    if (response.data.items[0]?.id !== command.entityId) {
      return scenarioFailure(response, `Expected response Plan ${command.entityId}.`);
    }

    const state = store.getState();
    try {
      assertStoredVersions(state, command.entityId, payload);
      assertBusinessPreconditions(state, command.entityId, payload);
    } catch (error) {
      return versionFailure(
        response,
        error instanceof Error ? error.message : 'Task input version changed.',
      );
    }
    const generation = ownedGeneration(state, command.entityId);
    if (payload.businessAction === 'TD-05') {
      stagedConfirmations.set(command.commandId, {
        workOrderIds: generation.workOrders.map(({ id }) => id).sort(),
        nodeIds: generation.nodes.map(({ id }) => id).sort(),
      });
    } else if (payload.businessAction === 'TD-02') {
      stagedCandidates.set(command.commandId, splitTaskDraft({
        ...generation,
        targetNodeId: payload.targetNodeId!,
        demoTime: state.session.demoTime,
      }));
    } else if (payload.businessAction === 'TD-03') {
      stagedCandidates.set(command.commandId, mergeTaskDraft({
        ...generation,
        nodeIds: [payload.selectedNodeIds[0]!, payload.selectedNodeIds[1]!],
        demoTime: state.session.demoTime,
      }));
    } else {
      const plan = state.plan.plans.find(({ id }) => id === command.entityId);
      if (!plan) return scenarioFailure(response, `Unknown plan: ${command.entityId}`);
      const recommendation = recommendationDraftSchema.parse(
        state.recommendation.drafts[command.entityId],
      );
      stagedCandidates.set(command.commandId, generateTaskDraft({
        plan,
        recommendation,
        waybills: state.plan.waybills,
        materials: state.resource.materials.filter(({ locationCode }) => locationCode === 'AREA-A'),
        resources: state.resource.resources.filter(({ workArea }) => workArea === 'AREA-A'),
        demoTime: state.session.demoTime,
        generationVersion: payload.generationVersion,
      }));
    }
    return {
      ...response,
      data: { ...response.data, status: 'ACCEPTED' },
    } satisfies ApiSuccessEnvelope;
  };

  const commit = (command: DemoCommand): void => {
    const payload = taskPayload(command);
    if (payload.businessAction === 'TD-05') {
      const staged = stagedConfirmations.get(command.commandId);
      if (!staged) throw new Error('Task-confirmation candidate is missing.');
      store.replaceDomainState((candidateState) => {
        assertStoredVersions(candidateState, command.entityId, payload);
        assertBusinessPreconditions(candidateState, command.entityId, payload);
        const generation = ownedGeneration(candidateState, command.entityId);
        const workOrderIds = generation.workOrders.map(({ id }) => id).sort();
        const nodeIds = generation.nodes.map(({ id }) => id).sort();
        if (JSON.stringify(workOrderIds) !== JSON.stringify(staged.workOrderIds)
          || JSON.stringify(nodeIds) !== JSON.stringify(staged.nodeIds)) {
          throw new Error('The staged C06 confirmation set changed.');
        }

        const plan = candidateState.plan.plans.find(({ id }) => id === command.entityId);
        if (!plan) throw new Error(`Unknown plan: ${command.entityId}`);
        const planTransition = transitionState({
          machineId: 'DO-001',
          current: plan.status,
          command: 'decompose',
        });
        if (!planTransition.ok || planTransition.next !== 'DECOMPOSED') {
          throw new Error('DO-001 confirmation transition was rejected.');
        }
        const orderTransitions = generation.workOrders.map((order) => ({
          order,
          transition: transitionState({
            machineId: 'DO-005',
            current: order.status,
            command: 'assign',
          }),
        }));
        if (orderTransitions.some(({ transition }) =>
          !transition.ok || transition.next !== 'READY',
        )) {
          throw new Error('A DO-005 confirmation transition was rejected.');
        }

        const updatedAt = candidateState.session.demoTime;
        Object.assign(plan, do001Schema.parse({
          ...plan,
          status: 'DECOMPOSED',
          version: plan.version + 1,
          updatedAt,
        }));
        for (const { order } of orderTransitions) {
          const candidateOrder = candidateState.workOrder.workOrders.find(({ id }) => id === order.id);
          if (!candidateOrder) throw new Error(`Missing staged WorkOrder: ${order.id}`);
          Object.assign(candidateOrder, do005Schema.parse({
            ...candidateOrder,
            status: 'READY',
            resourceId: '',
            teamId: '',
            version: candidateOrder.version + 1,
            updatedAt,
          }));
        }
        for (const nodeId of staged.nodeIds) {
          const candidateNode = candidateState.workOrder.nodes.find(({ id }) => id === nodeId);
          if (!candidateNode) throw new Error(`Missing staged WorkNode: ${nodeId}`);
          Object.assign(candidateNode, do006Schema.parse({
            ...candidateNode,
            version: candidateNode.version + 1,
            updatedAt,
          }));
        }
      });
      stagedConfirmations.delete(command.commandId);
      return;
    }
    const staged = stagedCandidates.get(command.commandId);
    if (!staged) throw new Error('Task-generation candidate is missing.');
    store.replaceDomainState((candidateState) => {
      assertStoredVersions(candidateState, command.entityId, payload);
      assertBusinessPreconditions(candidateState, command.entityId, payload);
      if (payload.businessAction === 'TD-02'
        || payload.businessAction === 'TD-03'
        || payload.businessAction === 'TD-04') {
        const removedOrders = candidateState.workOrder.workOrders.filter(
          (item) => isC06WorkOrder(item, command.entityId) && item.status === 'DRAFT',
        );
        const removedNumbers = new Set(removedOrders.map(({ workOrderNo }) => workOrderNo));
        candidateState.workOrder.workOrders = candidateState.workOrder.workOrders.filter(
          (item) => !removedOrders.some(({ id }) => id === item.id),
        );
        candidateState.workOrder.nodes = candidateState.workOrder.nodes.filter(
          (item) => !removedNumbers.has(item.workOrderNo),
        );
      }
      candidateState.workOrder.workOrders.push(...structuredClone(staged.workOrders));
      candidateState.workOrder.nodes.push(...structuredClone(staged.nodes));
    });
    stagedCandidates.delete(command.commandId);
  };

  const appendTaskAudit: AuditAppender = (input) => {
    const ledger = createAuditLedger(
      store.getState().configAudit.commandAudit,
      (entries) => {
        store.replaceDomainState((candidate) => {
          candidate.configAudit.commandAudit = structuredClone(entries);
        });
      },
    );
    const payload = taskPayload(input.command);
    const resultMetadata = input.result.ok
      ? { result: 'SUCCESS' as const, errorCode: null }
      : input.result.errorCode === 'TOS-AUTH-001'
        ? { result: 'DENIED' as const, errorCode: input.result.errorCode }
        : { result: 'FAILED' as const, errorCode: input.result.errorCode };
    return ledger.append(validateCommandAuditEntry({
      record: do013Schema.parse({
        id: input.result.auditLogId,
        actorId: input.command.actor.actorId,
        operatorTerminal: 'WEB-DEMO',
        action: payload.businessAction,
        objectType: input.command.entityType,
        objectId: input.command.entityId,
        before: {},
        after: {},
        reason: payload.reason?.trim() || (input.result.ok ? '' : input.result.message),
        traceId: input.command.traceId,
        occurredAt: input.serverTime,
      }),
      metadata: {
        roleCode: roleCodeSchema.parse(input.command.actor.roleCode),
        dataScope: [...input.command.actor.dataScope],
        ...resultMetadata,
        clientTime: input.command.clientTime,
        serverTime: input.serverTime,
      },
    }));
  };

  const buildExecutor = (): void => {
    executor = createCommandExecutor({
      authorize,
      validate,
      invokeMock,
      transition: transitionState,
      commit,
      appendAudit: appendTaskAudit,
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
    businessAction: TaskCommandPayload['businessAction'],
    mode: TaskMode,
    additional: Partial<Pick<TaskCommandPayload, 'targetNodeId' | 'selectedNodeIds' | 'reason'>> = {},
  ): DemoCommand<TaskCommandPayload> => {
    const state = store.getState();
    const visible = canReadAreaA(state);
    const plan = visible ? state.plan.plans.find(({ id }) => id === planId) : undefined;
    const recommendation = visible
      ? recommendationDraftSchema.safeParse(state.recommendation.drafts[planId])
      : undefined;
    const generation = visible ? ownedGeneration(state, planId) : { workOrders: [], nodes: [] };
    const payload = taskCommandPayloadSchema.parse({
      current: 'ACCEPTED',
      businessAction,
      mode,
      planVersion: plan?.version ?? 0,
      recommendationDraftVersion: recommendation?.success
        ? recommendation.data.draftVersion
        : 0,
      resourceVersions: visible
        ? versionMap(state.resource.resources.filter(({ workArea }) => workArea === 'AREA-A'))
        : {},
      workOrderVersions: versionMap(generation.workOrders),
      nodeVersions: versionMap(generation.nodes),
      generationVersion: visible
        ? (businessAction === 'TD-01' || businessAction === 'TD-04'
            ? nextGenerationVersion(state, planId)
            : Math.max(1, currentGenerationVersion(state, planId)))
        : 1,
      selectedNodeIds: additional.selectedNodeIds ?? [],
      ...(additional.targetNodeId ? { targetNodeId: additional.targetNodeId } : {}),
      ...(additional.reason !== undefined ? { reason: additional.reason } : {}),
    });
    return {
      ...commandIdentity(),
      action: 'execute',
      entityType: 'SM-007',
      entityId: planId,
      expectedVersion: payload.planVersion,
      payload,
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const recordResult = (
    result: CommandResult,
    businessAction: TaskCommandPayload['businessAction'],
  ): CommandResult => {
    if (!processedCommandIds.has(result.commandId)) {
      processedCommandIds.add(result.commandId);
      if (result.ok && (
        businessAction === 'TD-02'
        || businessAction === 'TD-03'
        || businessAction === 'TD-05'
      )) {
        workflow.closeEditor();
      } else {
        workflow.recordCommandError(
          result.ok ? undefined : { errorCode: result.errorCode, message: result.message },
        );
      }
    }
    return result;
  };

  const execute = async (command: DemoCommand<TaskCommandPayload>): Promise<CommandResult> => {
    const businessAction = command.payload.businessAction;
    return recordResult(await executor.execute(command), businessAction);
  };

  const resetCommandState = (): void => {
    commandSequence = 1;
    traceSequence = 1;
    auditSequence = 1;
    traceIdByCommandId.clear();
    stagedCandidates.clear();
    stagedConfirmations.clear();
    processedCommandIds.clear();
    buildExecutor();
  };

  buildExecutor();

  return {
    generateTasks: (planId) => execute(commandFor(planId, 'TD-01', 'AUTO')),
    splitTask: ({ planId, targetNodeId, reason }) => execute(commandFor(
      planId,
      'TD-02',
      'SPLIT',
      { targetNodeId, selectedNodeIds: [targetNodeId], reason },
    )),
    mergeTasks: ({ planId, nodeIds, reason }) => execute(commandFor(
      planId,
      'TD-03',
      'MERGE',
      { selectedNodeIds: [...nodeIds], reason },
    )),
    regenerateTasks: (planId) => execute(commandFor(planId, 'TD-04', 'REGENERATE')),
    confirmTasks: (planId) => execute(commandFor(planId, 'TD-05', 'CONFIRM')),
    resetCommandState,
  };
}
