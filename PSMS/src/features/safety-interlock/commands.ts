import { z } from 'zod';

import {
  actionPolicies,
  authorize as authorizePolicy,
  type PolicyContext,
} from '../../auth';
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
  do010Schema,
  do013Schema,
  roleCodeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type Interlock,
} from '../../contracts';
import { createAuditLedger, validateCommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type {
  InterlockCommandRequest,
  SafetyInterlockGateway,
} from './gateway';
import type {
  SafetyInterlockMode,
  SafetyInterlockWorkflowStore,
} from './types';

type InterlockCommandInput = Readonly<{
  interlockId: string;
  reason: string;
}>;

export type SafetyInterlockCommandService = Readonly<{
  triggerInterlock: (input: InterlockCommandInput) => Promise<CommandResult>;
  receiptInterlock: (input: InterlockCommandInput) => Promise<CommandResult>;
  requestReset: (input: InterlockCommandInput & {
    resetRequest: Readonly<Record<string, unknown>>;
  }) => Promise<CommandResult>;
  approveInterlock: (input: InterlockCommandInput & {
    approvalUserId: string;
  }) => Promise<CommandResult>;
  restoreInterlock: (input: InterlockCommandInput) => Promise<CommandResult>;
  requestOverride: (input: InterlockCommandInput) => Promise<CommandResult>;
  resetCommandState: () => void;
}>;

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type SafetyInterlockCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  gateway: SafetyInterlockGateway;
  workflow: SafetyInterlockWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
}>;

const domainCommands = [
  'trigger',
  'receipt',
  'requestReset',
  'approve',
  'restore',
  'requestOverride',
] as const;

export const safetyInterlockCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['SI-01', 'SI-02', 'SI-03', 'SI-04', 'SI-05']),
    domainCommand: z.enum(domainCommands),
    interlockVersion: z.number().int().nonnegative(),
    reason: z.string(),
    approvalUserId: z.string().optional(),
    resetRequest: z.record(z.string(), z.unknown()),
  })
  .strict()
  .superRefine((payload, context) => {
    const valid = payload.businessAction === 'SI-01'
      && (payload.domainCommand === 'trigger' || payload.domainCommand === 'receipt')
      || payload.businessAction === 'SI-02' && payload.domainCommand === 'requestReset'
      || payload.businessAction === 'SI-03' && payload.domainCommand === 'approve'
      || payload.businessAction === 'SI-04' && payload.domainCommand === 'restore'
      || payload.businessAction === 'SI-05'
        && (payload.domainCommand === 'requestOverride' || payload.domainCommand === 'approve');
    if (!valid) {
      context.addIssue({
        code: 'custom',
        path: ['domainCommand'],
        message: `${payload.businessAction} does not map to ${payload.domainCommand}.`,
      });
    }
  });

export type SafetyInterlockCommandPayload = z.infer<
  typeof safetyInterlockCommandPayloadSchema
>;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C09', sequence),
  trace: (sequence) => sequenceId('TRACE-C09', sequence),
  audit: (sequence) => sequenceId('AUD-C09', sequence),
};

const modeByCommand: Record<
  SafetyInterlockCommandPayload['domainCommand'],
  SafetyInterlockMode
> = {
  trigger: 'TRIGGER',
  receipt: 'RECEIPT',
  requestReset: 'REQUEST_RESET',
  approve: 'APPROVE',
  restore: 'RESTORE',
  requestOverride: 'REQUEST_OVERRIDE',
};

const permissionByCommand = {
  trigger: 'interlock:view',
  receipt: 'interlock:view',
  requestReset: 'interlock:reset',
  approve: 'interlock:approve',
  restore: 'interlock:reset',
  requestOverride: 'interlock:request-override',
} as const;

const apiActionByCommand: Record<
  SafetyInterlockCommandPayload['domainCommand'],
  InterlockCommandRequest['action']
> = {
  trigger: 'TRIGGER',
  receipt: 'RECEIPT',
  requestReset: 'REQUEST_RESET',
  approve: 'APPROVE',
  restore: 'RESTORE',
  requestOverride: 'REQUEST_OVERRIDE',
};

function payloadFor(command: DemoCommand): SafetyInterlockCommandPayload {
  return safetyInterlockCommandPayloadSchema.parse(command.payload);
}

function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*')
    || state.session.dataScope.includes('GLOBAL')
    || state.session.dataScope.includes('AREA-A');
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

function findInterlock(state: DemoRootState, interlockId: string): Interlock {
  const interlock = state.interlock.interlocks.find(({ id }) => id === interlockId);
  if (!interlock) throw new Error(`Unknown Interlock: ${interlockId}`);
  return do010Schema.parse(interlock);
}

function assertStoredSnapshot(
  state: DemoRootState,
  command: DemoCommand,
  payload: SafetyInterlockCommandPayload,
): Interlock {
  if (!canReadAreaA(state)) throw new Error('Interlock is outside AREA-A data scope.');
  const interlock = findInterlock(state, command.entityId);
  if (
    interlock.version !== payload.interlockVersion
    || interlock.version !== command.expectedVersion
  ) {
    throw new Error(
      `Expected Interlock version ${payload.interlockVersion}, actual ${interlock.version}.`,
    );
  }
  return interlock;
}

function assertApprovalUser(
  state: DemoRootState,
  approvalUserId: string | undefined,
): string {
  const candidate = approvalUserId?.trim();
  if (!candidate) throw new Error('SI-03/SI-05 approval requires an approval user.');
  const user = state.configAudit.userRoles.find(({ id }) => id === candidate);
  if (
    !user
    || user.status !== 'ACTIVE'
    || !actionPolicies['interlock:approve'].some((role) => role === user.roleCode)
  ) {
    throw new Error(`Approval user ${candidate} is not an active interlock approver.`);
  }
  return candidate;
}

function assertBusinessPreconditions(
  state: DemoRootState,
  command: DemoCommand,
  payload: SafetyInterlockCommandPayload,
): Interlock {
  const interlock = assertStoredSnapshot(state, command, payload);
  if (!payload.reason.trim()) throw new Error(`${payload.businessAction} requires a reason.`);
  if (
    payload.domainCommand === 'requestReset'
    && payload.resetRequest.requested !== true
  ) {
    throw new Error('SI-02 resetRequest.requested must be true.');
  }
  if (payload.domainCommand === 'approve') {
    assertApprovalUser(state, payload.approvalUserId);
  }
  const transition = transitionState({
    machineId: 'DO-010',
    current: interlock.status,
    command: payload.domainCommand,
  });
  if (!transition.ok) {
    throw new Error(
      `DO-010 ${interlock.status} + ${payload.domainCommand} transition was rejected.`,
    );
  }
  return interlock;
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

export function createSafetyInterlockCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: SafetyInterlockCommandServiceDependencies): SafetyInterlockCommandService {
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
    throw new Error('Unable to allocate a unique C09 audit ID.');
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
    const interlock = canReadAreaA(state)
      ? state.interlock.interlocks.find(({ id }) => id === command.entityId)
      : undefined;
    const context: PolicyContext = {
      session: state.session,
      pageId: 'UI-009',
      permission: permissionByCommand[payload.domainCommand],
      objectScope: { type: 'AREA', value: 'AREA-A' },
      ...(interlock
        ? { expectedVersion: command.expectedVersion, actualVersion: interlock.version }
        : {}),
    };
    const decision = authorizePolicy(context);
    return decision.allow ? { allow: true } : permissionFailure(decision);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported safety-interlock command.');
    }
    assertBusinessPreconditions(store.getState(), command, payloadFor(command));
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = payloadFor(command);
    assertBusinessPreconditions(store.getState(), command, payload);
    const response = await gateway.commandInterlock(command.entityId, {
      action: apiActionByCommand[payload.domainCommand],
      reason: payload.reason.trim(),
      ...(payload.approvalUserId?.trim()
        ? { approvalUserId: payload.approvalUserId.trim() }
        : {}),
      ...(payload.domainCommand === 'requestReset'
        ? { resetRequest: structuredClone(payload.resetRequest) }
        : {}),
    });
    if (!response.ok) return response;
    if (response.data.scenarioId !== store.getState().scenario.activeScenarioId) {
      return scenarioFailure(response, 'Interlock response scenario does not match the active scenario.');
    }
    try {
      assertBusinessPreconditions(store.getState(), command, payload);
    } catch (error) {
      return versionFailure(
        response,
        error instanceof Error ? error.message : 'Interlock input version changed.',
      );
    }
    return {
      ...response,
      data: { ...response.data, status: 'ACCEPTED' },
    } satisfies ApiSuccessEnvelope;
  };

  const commit = (command: DemoCommand): void => {
    const payload = payloadFor(command);
    store.replaceDomainState((candidate) => {
      const interlock = assertBusinessPreconditions(candidate, command, payload);
      const transition = transitionState({
        machineId: 'DO-010',
        current: interlock.status,
        command: payload.domainCommand,
      });
      if (!transition.ok) {
        throw new Error(
          `DO-010 ${interlock.status} + ${payload.domainCommand} transition was rejected.`,
        );
      }
      const next = do010Schema.parse({
        ...interlock,
        status: transition.next,
        ...(payload.domainCommand === 'trigger' ? { receiptStatus: 'PENDING' } : {}),
        ...(payload.domainCommand === 'receipt'
          ? { receiptStatus: interlock.status === 'WAITING_RECEIPT' ? 'RECEIVED' : 'PENDING' }
          : {}),
        ...(payload.domainCommand === 'requestReset'
          ? { resetRequest: structuredClone(payload.resetRequest) }
          : {}),
        ...(payload.domainCommand === 'approve'
          ? { approvalChain: [...interlock.approvalChain, payload.approvalUserId!.trim()] }
          : {}),
        version: interlock.version + 1,
        updatedAt: candidate.session.demoTime,
      });
      const index = candidate.interlock.interlocks.findIndex(({ id }) => id === interlock.id);
      candidate.interlock.interlocks[index] = next;
    });
  };

  const appendInterlockAudit: AuditAppender = (input) => {
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
        reason: payload.reason.trim() || (input.result.ok ? '' : input.result.message),
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
      appendAudit: appendInterlockAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const commandFor = (
    interlockId: string,
    businessAction: SafetyInterlockCommandPayload['businessAction'],
    domainCommand: SafetyInterlockCommandPayload['domainCommand'],
    input: {
      reason: string;
      approvalUserId?: string;
      resetRequest?: Readonly<Record<string, unknown>>;
    },
  ): DemoCommand<SafetyInterlockCommandPayload> => {
    const state = store.getState();
    const interlock = canReadAreaA(state)
      ? state.interlock.interlocks.find(({ id }) => id === interlockId)
      : undefined;
    const payload = safetyInterlockCommandPayloadSchema.parse({
      current: 'ACCEPTED',
      businessAction,
      domainCommand,
      interlockVersion: interlock?.version ?? 0,
      reason: input.reason,
      ...(input.approvalUserId !== undefined
        ? { approvalUserId: input.approvalUserId }
        : {}),
      resetRequest: structuredClone(input.resetRequest ?? {}),
    });
    const commandId = nextCommandId();
    return {
      commandId,
      traceId: nextTraceId(commandId),
      action: 'execute',
      entityType: 'SM-007',
      entityId: interlockId,
      expectedVersion: payload.interlockVersion,
      payload,
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const execute = async (
    command: DemoCommand<SafetyInterlockCommandPayload>,
  ): Promise<CommandResult> => {
    const result = await executor.execute(command);
    if (!processedCommandIds.has(result.commandId)) {
      processedCommandIds.add(result.commandId);
      workflow.setMode(modeByCommand[command.payload.domainCommand]);
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
    triggerInterlock: ({ interlockId, reason }) => execute(commandFor(
      interlockId, 'SI-01', 'trigger', { reason },
    )),
    receiptInterlock: ({ interlockId, reason }) => execute(commandFor(
      interlockId, 'SI-01', 'receipt', { reason },
    )),
    requestReset: ({ interlockId, reason, resetRequest }) => execute(commandFor(
      interlockId, 'SI-02', 'requestReset', { reason, resetRequest },
    )),
    approveInterlock: ({ interlockId, reason, approvalUserId }) => {
      const state = store.getState();
      const interlock = canReadAreaA(state)
        ? state.interlock.interlocks.find(({ id }) => id === interlockId)
        : undefined;
      const businessAction = interlock?.status === 'OVERRIDE_PENDING' ? 'SI-05' : 'SI-03';
      return execute(commandFor(
        interlockId, businessAction, 'approve', { reason, approvalUserId },
      ));
    },
    restoreInterlock: ({ interlockId, reason }) => execute(commandFor(
      interlockId, 'SI-04', 'restore', { reason },
    )),
    requestOverride: ({ interlockId, reason }) => execute(commandFor(
      interlockId, 'SI-05', 'requestOverride', { reason },
    )),
    resetCommandState,
  };
}
