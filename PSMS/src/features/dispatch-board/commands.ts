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
  do005Schema,
  do006Schema,
  do013Schema,
  roleCodeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type Resource,
  type WorkNode,
  type WorkOrder,
} from '../../contracts';
import { createAuditLedger, validateCommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type { DispatchBoardGateway } from './gateway';
import {
  hasAreaAVisibility,
  isDispatchBoardWorkNode,
  isDispatchBoardWorkOrder,
  isDispatchResourceVisible,
} from './ownership';
import { selectDispatchBoard } from './selectors';
import type { DispatchBoardWorkflowStore } from './types';

export type DispatchBoardCommandService = Readonly<{
  bindDispatchResource: (input: {
    workOrderId: string;
    resourceId: string;
    reason?: string;
  }) => Promise<CommandResult>;
  dispatchWorkOrder: (workOrderId: string) => Promise<CommandResult>;
  acknowledgeWorkOrder: (workOrderId: string) => Promise<CommandResult>;
  startWorkOrder: (workOrderId: string) => Promise<CommandResult>;
  pauseWorkOrder: (workOrderId: string) => Promise<CommandResult>;
  completeWorkOrder: (workOrderId: string) => Promise<CommandResult>;
  resetCommandState: () => void;
}>;

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type DispatchBoardCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  gateway: DispatchBoardGateway;
  workflow: DispatchBoardWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
}>;

export const dispatchCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['DB-01', 'DB-02', 'DB-03', 'DB-04']),
    planId: z.string().trim().min(1),
    planVersion: z.number().int().nonnegative(),
    workOrderVersion: z.number().int().nonnegative(),
    workNodeId: z.string(),
    workNodeVersion: z.number().int().nonnegative(),
    resourceId: z.string(),
    resourceVersion: z.number().int().nonnegative(),
    feedbackAction: z.enum(['ACK', 'START', 'PAUSE', 'COMPLETE']).optional(),
    reason: z.string().optional(),
  })
  .strict()
  .superRefine((payload, context) => {
    if (payload.businessAction === 'DB-03' && payload.feedbackAction !== 'ACK') {
      context.addIssue({
        code: 'custom',
        path: ['feedbackAction'],
        message: 'DB-03 requires ACK.',
      });
    }
    if (payload.businessAction === 'DB-04'
      && !['START', 'PAUSE', 'COMPLETE'].includes(payload.feedbackAction ?? '')) {
      context.addIssue({
        code: 'custom',
        path: ['feedbackAction'],
        message: 'DB-04 requires START, PAUSE, or COMPLETE.',
      });
    }
    if ((payload.businessAction === 'DB-01' || payload.businessAction === 'DB-02')
      && payload.feedbackAction !== undefined) {
      context.addIssue({
        code: 'custom',
        path: ['feedbackAction'],
        message: `${payload.businessAction} does not use local feedback.`,
      });
    }
  });

export type DispatchCommandPayload = z.infer<typeof dispatchCommandPayloadSchema>;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C07', sequence),
  trace: (sequence) => sequenceId('TRACE-C07', sequence),
  audit: (sequence) => sequenceId('AUD-C07', sequence),
};

function payloadFor(command: DemoCommand): DispatchCommandPayload {
  return dispatchCommandPayloadSchema.parse(command.payload);
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

type DispatchPair = Readonly<{
  plan: DemoRootState['plan']['plans'][number];
  order: WorkOrder;
  node: WorkNode;
  requiredResourceType: Resource['resourceType'];
}>;

function findPair(state: DemoRootState, workOrderId: string): DispatchPair {
  const order = state.workOrder.workOrders.find(({ id }) => id === workOrderId);
  if (!order || !isDispatchBoardWorkOrder(order, order.planId)) {
    throw new Error(`Unknown C06 READY-or-later WorkOrder: ${workOrderId}`);
  }
  const ownedNumbers = new Set([order.workOrderNo]);
  const node = state.workOrder.nodes.find((candidate) =>
    candidate.workOrderNo === order.workOrderNo
    && isDispatchBoardWorkNode(candidate, ownedNumbers),
  );
  if (!node) throw new Error(`Missing C06 WorkNode for ${workOrderId}.`);
  const plan = state.plan.plans.find(({ id }) => id === order.planId);
  if (!plan || plan.status !== 'DECOMPOSED') {
    throw new Error(`WorkOrder ${workOrderId} requires a DECOMPOSED Plan.`);
  }
  const boardOrder = selectDispatchBoard(state, order.planId)?.orders.find(
    (candidate) => candidate.workOrder.id === workOrderId,
  );
  if (!boardOrder) throw new Error(`Missing dispatch projection for ${workOrderId}.`);
  return { plan, order, node, requiredResourceType: boardOrder.requiredResourceType };
}

function findResource(
  state: DemoRootState,
  resourceId: string,
  requiredResourceType: Resource['resourceType'],
): Resource {
  const resource = state.resource.resources.find(({ id }) => id === resourceId);
  if (!resource || !isDispatchResourceVisible(resource, state.session.dataScope)) {
    throw new Error(`Resource ${resourceId || '(empty)'} is outside AREA-A visibility.`);
  }
  if (resource.resourceType !== requiredResourceType) {
    throw new Error(`Resource ${resourceId} type ${resource.resourceType} does not match ${requiredResourceType}.`);
  }
  return resource;
}

function teamIdentifier(resource: Resource): string {
  return resource.personId || resource.deviceId || resource.id;
}

function assertStoredVersions(
  state: DemoRootState,
  command: DemoCommand,
  payload: DispatchCommandPayload,
): DispatchPair & { resource: Resource } {
  if (!hasAreaAVisibility(state.session.dataScope)) {
    throw new Error('WorkOrder is outside AREA-A data scope.');
  }
  const pair = findPair(state, command.entityId);
  if (pair.plan.id !== payload.planId || pair.plan.version !== payload.planVersion) {
    throw new Error(`Expected Plan ${payload.planId} version ${payload.planVersion}.`);
  }
  if (pair.order.version !== payload.workOrderVersion) {
    throw new Error(`Expected WorkOrder version ${payload.workOrderVersion}, actual ${pair.order.version}.`);
  }
  if (pair.node.id !== payload.workNodeId || pair.node.version !== payload.workNodeVersion) {
    throw new Error(`Expected WorkNode ${payload.workNodeId} version ${payload.workNodeVersion}.`);
  }
  const resource = findResource(state, payload.resourceId, pair.requiredResourceType);
  if (resource.version !== payload.resourceVersion) {
    throw new Error(`Expected Resource version ${payload.resourceVersion}, actual ${resource.version}.`);
  }
  return { ...pair, resource };
}

function assertBusinessPreconditions(
  state: DemoRootState,
  command: DemoCommand,
  payload: DispatchCommandPayload,
): DispatchPair & { resource: Resource } {
  const resolved = assertStoredVersions(state, command, payload);
  const hasBinding = resolved.order.resourceId !== '' && resolved.order.teamId !== '';
  if (payload.businessAction === 'DB-01' || payload.businessAction === 'DB-02') {
    if (resolved.order.status !== 'READY') {
      throw new Error(`${payload.businessAction} requires a READY WorkOrder.`);
    }
    if (resolved.node.status !== 'WAITING') {
      throw new Error(`${payload.businessAction} requires a WAITING WorkNode.`);
    }
    if (resolved.resource.status !== 'AVAILABLE') {
      throw new Error(`Resource ${resolved.resource.id} status ${resolved.resource.status} is not AVAILABLE.`);
    }
  }
  if (payload.businessAction === 'DB-02') {
    if (resolved.order.resourceId === '' || resolved.order.teamId === '') {
      throw new Error('DB-02 requires resourceId and teamId.');
    }
    if (resolved.order.resourceId !== resolved.resource.id) {
      throw new Error('DB-02 resource snapshot does not match the bound resource.');
    }
    return resolved;
  }
  if (payload.businessAction === 'DB-01') return resolved;

  if (!hasBinding || resolved.order.resourceId !== resolved.resource.id) {
    throw new Error(`${payload.businessAction} requires the bound resource snapshot.`);
  }
  if (payload.businessAction === 'DB-03') {
    if (payload.feedbackAction !== 'ACK'
      || resolved.order.status !== 'DISPATCHED'
      || resolved.node.status !== 'READY') {
      throw new Error('DB-03 requires DISPATCHED/READY and ACK feedback.');
    }
    return resolved;
  }

  if (payload.feedbackAction === 'START') {
    const validOrder = resolved.order.status === 'ACKNOWLEDGED'
      || resolved.order.status === 'PAUSED';
    const validNode = resolved.order.status === 'ACKNOWLEDGED'
      ? resolved.node.status === 'READY'
      : resolved.node.status === 'IN_PROGRESS';
    if (!validOrder || !validNode) {
      throw new Error('DB-04 START requires ACKNOWLEDGED/READY or PAUSED/IN_PROGRESS.');
    }
    return resolved;
  }
  if (payload.feedbackAction === 'PAUSE' || payload.feedbackAction === 'COMPLETE') {
    if (resolved.order.status !== 'IN_PROGRESS' || resolved.node.status !== 'IN_PROGRESS') {
      throw new Error(`DB-04 ${payload.feedbackAction} requires IN_PROGRESS.`);
    }
    return resolved;
  }
  throw new Error('Unsupported local dispatch feedback.');
}

function versionFailure(response: ApiSuccessEnvelope, message: string): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-VERSION-001',
    message,
    auditLogId: response.auditLogId,
    traceId: response.traceId,
  };
}

function scenarioFailure(response: ApiSuccessEnvelope, message: string): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-SCENARIO-001',
    message,
    auditLogId: response.auditLogId,
    traceId: response.traceId,
  };
}

export function createDispatchBoardCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: DispatchBoardCommandServiceDependencies): DispatchBoardCommandService {
  const idFormatters: CommandIdFormatters = { ...defaultIdFormatters, ...inputFormatters };
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  let executor: CommandExecutor;
  const traceIdByCommandId = new Map<string, string>();
  const processedCommandIds = new Set<string>();

  const nextCommandId = (): string => idFormatters.command(commandSequence++);
  const nextTraceId = (commandId: string): string => {
    const existing = traceIdByCommandId.get(commandId);
    if (existing) return existing;
    const traceId = idFormatters.trace(traceSequence++);
    traceIdByCommandId.set(commandId, traceId);
    return traceId;
  };
  const nextAuditId = (): string => {
    const usedIds = new Set(store.getState().configAudit.commandAudit.map(({ record }) => record.id));
    for (let attempts = 0; attempts <= usedIds.size; attempts += 1) {
      const candidate = idFormatters.audit(auditSequence++);
      if (!usedIds.has(candidate)) return candidate;
    }
    throw new Error('Unable to allocate a unique C07 audit ID.');
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
    const payload = payloadFor(command);
    const visible = hasAreaAVisibility(state.session.dataScope);
    const order = visible
      ? state.workOrder.workOrders.find(({ id }) => id === command.entityId)
      : undefined;
    const permission = payload.businessAction === 'DB-01'
      ? order?.resourceId ? 'dispatch:reassign' : 'dispatch:assign'
      : payload.businessAction === 'DB-04' && payload.feedbackAction === 'PAUSE'
        ? 'dispatch:pause'
        : 'dispatch:send';
    const context: PolicyContext = {
      session: state.session,
      pageId: 'UI-005',
      permission,
      objectScope: { type: 'AREA', value: 'AREA-A' },
      ...(order
        ? { expectedVersion: command.expectedVersion, actualVersion: order.version }
        : {}),
    };
    const decision = authorizePolicy(context);
    return decision.allow ? { allow: true } : permissionFailure(decision);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported dispatch-board command.');
    }
    assertBusinessPreconditions(store.getState(), command, payloadFor(command));
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = payloadFor(command);
    const stateBeforeGateway = store.getState();
    if (stateBeforeGateway.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP') {
      return {
        ok: false,
        errorCode: 'TOS-IL-001',
        message: 'TOS-IL-001: INTERLOCK_FORCE_STOP blocks DB-01 through DB-04.',
        auditLogId: 'MOCK-C07-INTERLOCK',
        traceId: command.traceId,
      } satisfies ApiErrorEnvelope;
    }

    if (payload.businessAction === 'DB-01' || payload.businessAction === 'DB-02') {
      const response = payload.businessAction === 'DB-01'
        ? await gateway.assignWorkOrder(command.entityId, {
            resourceId: payload.resourceId,
            ...(payload.reason ? { reason: payload.reason } : {}),
          })
        : await gateway.dispatchWorkOrder(command.entityId, {
            target: 'AREA-A',
            simulateReceipt: true,
          });
      if (!response.ok) return response;
      if (response.data.scenarioId !== store.getState().scenario.activeScenarioId) {
        return scenarioFailure(response, 'Dispatch response scenario does not match the active scenario.');
      }
      try {
        assertBusinessPreconditions(store.getState(), command, payload);
      } catch (error) {
        return versionFailure(
          response,
          error instanceof Error ? error.message : 'Dispatch input version changed.',
        );
      }
      return {
        ...response,
        data: { ...response.data, status: 'ACCEPTED' },
      } satisfies ApiSuccessEnvelope;
    }

    await Promise.resolve();
    const localResponse: ApiSuccessEnvelope = {
      ok: true,
      data: {
        status: 'ACCEPTED',
        boundary: 'C07_LOCAL_DEMO_FEEDBACK',
        feedbackAction: payload.feedbackAction ?? '',
      },
      auditLogId: `LOCAL-AUD-${payload.businessAction}`,
      traceId: command.traceId,
    };
    try {
      assertBusinessPreconditions(store.getState(), command, payload);
    } catch (error) {
      return versionFailure(
        localResponse,
        error instanceof Error ? error.message : 'Local feedback input version changed.',
      );
    }
    return localResponse;
  };

  const commit = (command: DemoCommand): void => {
    const payload = payloadFor(command);
    store.replaceDomainState((candidate) => {
      const { order, node, resource } = assertBusinessPreconditions(candidate, command, payload);
      const updatedAt = candidate.session.demoTime;
      if (payload.businessAction === 'DB-01') {
        Object.assign(order, do005Schema.parse({
          ...order,
          resourceId: resource.id,
          teamId: teamIdentifier(resource),
          version: order.version + 1,
          updatedAt,
        }));
        return;
      }

      if (payload.businessAction === 'DB-02') {
        const transition = transitionState({
          machineId: 'DO-005',
          current: order.status,
          command: 'dispatch',
        });
        if (!transition.ok || transition.next !== 'DISPATCHED') {
          throw new Error('DO-005 READY + dispatch transition was rejected.');
        }
        Object.assign(order, do005Schema.parse({
          ...order,
          status: 'DISPATCHED',
          version: order.version + 1,
          updatedAt,
        }));
        Object.assign(node, do006Schema.parse({
          ...node,
          status: 'READY',
          version: node.version + 1,
          updatedAt,
        }));
        return;
      }

      const localAction = payload.feedbackAction?.toLowerCase();
      if (!localAction) throw new Error('Local feedback action is missing.');
      const transition = transitionState({
        machineId: 'DO-005',
        current: order.status,
        command: localAction,
      });
      if (!transition.ok) {
        throw new Error(`DO-005 ${order.status} + ${localAction} transition was rejected.`);
      }
      Object.assign(order, do005Schema.parse({
        ...order,
        status: transition.next,
        ...(payload.feedbackAction === 'ACK' ? { ackStatus: 'ACKNOWLEDGED' as const } : {}),
        version: order.version + 1,
        updatedAt,
      }));
      if (payload.businessAction === 'DB-03') return;

      const nextNodeStatus = payload.feedbackAction === 'COMPLETE'
        ? 'COMPLETED' as const
        : 'IN_PROGRESS' as const;
      Object.assign(node, do006Schema.parse({
        ...node,
        status: nextNodeStatus,
        ...(payload.feedbackAction === 'START' && node.actualStartTime === ''
          ? { actualStartTime: updatedAt }
          : {}),
        ...(payload.feedbackAction === 'COMPLETE'
          ? { actualFinishTime: updatedAt }
          : {}),
        version: node.version + 1,
        updatedAt,
      }));
    });
  };

  const appendDispatchAudit: AuditAppender = (input) => {
    const ledger = createAuditLedger(
      store.getState().configAudit.commandAudit,
      (entries) => {
        store.replaceDomainState((candidate) => {
          candidate.configAudit.commandAudit = structuredClone(entries);
        });
      },
    );
    const payload = payloadFor(input.command);
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
      appendAudit: appendDispatchAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const commandFor = (
    workOrderId: string,
    businessAction: DispatchCommandPayload['businessAction'],
    resourceIdInput?: string,
    reason?: string,
    feedbackAction?: DispatchCommandPayload['feedbackAction'],
  ): DemoCommand<DispatchCommandPayload> => {
    const state = store.getState();
    const visible = hasAreaAVisibility(state.session.dataScope);
    const order = visible
      ? state.workOrder.workOrders.find(({ id }) => id === workOrderId)
      : undefined;
    const node = order
      ? state.workOrder.nodes.find(({ workOrderNo }) => workOrderNo === order.workOrderNo)
      : undefined;
    const plan = order ? state.plan.plans.find(({ id }) => id === order.planId) : undefined;
    const resourceId = resourceIdInput ?? order?.resourceId ?? '';
    const resource = visible
      ? state.resource.resources.find(({ id }) => id === resourceId)
      : undefined;
    const payload = dispatchCommandPayloadSchema.parse({
      current: 'ACCEPTED',
      businessAction,
      planId: order?.planId ?? 'UNKNOWN-PLAN',
      planVersion: plan?.version ?? 0,
      workOrderVersion: order?.version ?? 0,
      workNodeId: node?.id ?? '',
      workNodeVersion: node?.version ?? 0,
      resourceId,
      resourceVersion: resource?.version ?? 0,
      ...(feedbackAction ? { feedbackAction } : {}),
      ...(reason !== undefined ? { reason } : {}),
    });
    const commandId = nextCommandId();
    return {
      commandId,
      traceId: nextTraceId(commandId),
      action: 'execute',
      entityType: 'SM-007',
      entityId: workOrderId,
      expectedVersion: payload.workOrderVersion,
      payload,
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const execute = async (command: DemoCommand<DispatchCommandPayload>): Promise<CommandResult> => {
    const result = await executor.execute(command);
    if (!processedCommandIds.has(result.commandId)) {
      processedCommandIds.add(result.commandId);
      workflow.setMode(
        command.payload.businessAction === 'DB-01'
          ? 'ASSIGN'
          : command.payload.businessAction === 'DB-02'
            ? 'DISPATCH'
            : 'FEEDBACK',
      );
      workflow.recordCommandError(
        result.ok ? undefined : { errorCode: result.errorCode, message: result.message },
      );
    }
    return result;
  };

  const resetCommandState = (): void => {
    commandSequence = 1;
    traceSequence = 1;
    auditSequence = 1;
    traceIdByCommandId.clear();
    processedCommandIds.clear();
    buildExecutor();
  };

  buildExecutor();

  return {
    bindDispatchResource: ({ workOrderId, resourceId, reason }) => execute(commandFor(
      workOrderId,
      'DB-01',
      resourceId,
      reason,
    )),
    dispatchWorkOrder: (workOrderId) => execute(commandFor(workOrderId, 'DB-02')),
    acknowledgeWorkOrder: (workOrderId) => execute(commandFor(
      workOrderId,
      'DB-03',
      undefined,
      undefined,
      'ACK',
    )),
    startWorkOrder: (workOrderId) => execute(commandFor(
      workOrderId,
      'DB-04',
      undefined,
      undefined,
      'START',
    )),
    pauseWorkOrder: (workOrderId) => execute(commandFor(
      workOrderId,
      'DB-04',
      undefined,
      undefined,
      'PAUSE',
    )),
    completeWorkOrder: (workOrderId) => execute(commandFor(
      workOrderId,
      'DB-04',
      undefined,
      undefined,
      'COMPLETE',
    )),
    resetCommandState,
  };
}
