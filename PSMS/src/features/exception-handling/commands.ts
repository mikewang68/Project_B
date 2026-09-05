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
  do009Schema,
  do013Schema,
  roleCodeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type DispatchException,
} from '../../contracts';
import { createAuditLedger, validateCommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type {
  ExceptionCommandRequest,
  ExceptionHandlingGateway,
} from './gateway';
import type {
  ExceptionHandlingMode,
  ExceptionHandlingWorkflowStore,
} from './types';

export type ExceptionHandlingCommandService = Readonly<{
  ackException: (input: { exceptionId: string; reason: string }) => Promise<CommandResult>;
  assignException: (input: {
    exceptionId: string;
    owner: string;
    reason: string;
  }) => Promise<CommandResult>;
  submitExceptionHandling: (input: {
    exceptionId: string;
    evidence: readonly string[];
    reason: string;
  }) => Promise<CommandResult>;
  reviewException: (input: { exceptionId: string; reason: string }) => Promise<CommandResult>;
  closeException: (input: { exceptionId: string; reason: string }) => Promise<CommandResult>;
  reopenException: (input: { exceptionId: string; reason: string }) => Promise<CommandResult>;
  resetCommandState: () => void;
}>;

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type ExceptionHandlingCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  gateway: ExceptionHandlingGateway;
  workflow: ExceptionHandlingWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
}>;

const domainCommands = ['ack', 'assign', 'handle', 'review', 'close', 'reopen'] as const;

export const exceptionHandlingCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['EX-01', 'EX-02', 'EX-03', 'EX-04', 'EX-05']),
    domainCommand: z.enum(domainCommands),
    exceptionVersion: z.number().int().nonnegative(),
    reason: z.string(),
    owner: z.string().optional(),
    evidence: z.array(z.string()),
  })
  .strict()
  .superRefine((payload, context) => {
    const valid = payload.businessAction === 'EX-01' && payload.domainCommand === 'ack'
      || payload.businessAction === 'EX-02' && payload.domainCommand === 'assign'
      || payload.businessAction === 'EX-03' && payload.domainCommand === 'handle'
      || payload.businessAction === 'EX-04'
        && (payload.domainCommand === 'review' || payload.domainCommand === 'close')
      || payload.businessAction === 'EX-05' && payload.domainCommand === 'reopen';
    if (!valid) {
      context.addIssue({
        code: 'custom',
        path: ['domainCommand'],
        message: `${payload.businessAction} does not map to ${payload.domainCommand}.`,
      });
    }
  });

export type ExceptionHandlingCommandPayload = z.infer<
  typeof exceptionHandlingCommandPayloadSchema
>;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C08', sequence),
  trace: (sequence) => sequenceId('TRACE-C08', sequence),
  audit: (sequence) => sequenceId('AUD-C08', sequence),
};

const modeByCommand: Record<ExceptionHandlingCommandPayload['domainCommand'], ExceptionHandlingMode> = {
  ack: 'ACK',
  assign: 'ASSIGN',
  handle: 'HANDLE',
  review: 'REVIEW',
  close: 'CLOSE',
  reopen: 'REOPEN',
};

const permissionByCommand = {
  ack: 'exception:ack',
  assign: 'exception:handle',
  handle: 'exception:handle',
  review: 'exception:review',
  close: 'exception:close',
  reopen: 'exception:reopen',
} as const;

const apiActionByCommand: Record<
  ExceptionHandlingCommandPayload['domainCommand'],
  ExceptionCommandRequest['action']
> = {
  ack: 'ACK',
  assign: 'ASSIGN',
  handle: 'HANDLE',
  review: 'REVIEW',
  close: 'CLOSE',
  reopen: 'REOPEN',
};

function payloadFor(command: DemoCommand): ExceptionHandlingCommandPayload {
  return exceptionHandlingCommandPayloadSchema.parse(command.payload);
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

function findException(state: DemoRootState, exceptionId: string): DispatchException {
  const exception = state.exception.exceptions.find(({ id }) => id === exceptionId);
  if (!exception) throw new Error(`Unknown DispatchException: ${exceptionId}`);
  return do009Schema.parse(exception);
}

function assertStoredSnapshot(
  state: DemoRootState,
  command: DemoCommand,
  payload: ExceptionHandlingCommandPayload,
): DispatchException {
  if (!canReadAreaA(state)) throw new Error('DispatchException is outside AREA-A data scope.');
  const exception = findException(state, command.entityId);
  if (
    exception.version !== payload.exceptionVersion
    || exception.version !== command.expectedVersion
  ) {
    throw new Error(
      `Expected DispatchException version ${payload.exceptionVersion}, actual ${exception.version}.`,
    );
  }
  return exception;
}

function assertBusinessPreconditions(
  state: DemoRootState,
  command: DemoCommand,
  payload: ExceptionHandlingCommandPayload,
): DispatchException {
  const exception = assertStoredSnapshot(state, command, payload);
  if (!payload.reason.trim()) throw new Error(`${payload.businessAction} requires a reason.`);
  if (payload.domainCommand === 'assign' && !payload.owner?.trim()) {
    throw new Error('EX-02 requires an owner.');
  }
  if (payload.domainCommand === 'handle' && (
    payload.evidence.length === 0
    || payload.evidence.some((item) => !item.trim())
  )) {
    throw new Error('EX-03 requires non-empty evidence.');
  }
  const transition = transitionState({
    machineId: 'DO-009',
    current: exception.status,
    command: payload.domainCommand,
  });
  if (!transition.ok) {
    throw new Error(
      `DO-009 ${exception.status} + ${payload.domainCommand} transition was rejected.`,
    );
  }
  return exception;
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

function interlockFailure(command: DemoCommand): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'TOS-IL-001',
    message: 'TOS-IL-001: C08 links to UI-009 and never clears or overrides an interlock.',
    auditLogId: 'MOCK-C08-INTERLOCK',
    traceId: command.traceId,
  };
}

export function createExceptionHandlingCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: ExceptionHandlingCommandServiceDependencies): ExceptionHandlingCommandService {
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
    throw new Error('Unable to allocate a unique C08 audit ID.');
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
    const exception = canReadAreaA(state)
      ? state.exception.exceptions.find(({ id }) => id === command.entityId)
      : undefined;
    const context: PolicyContext = {
      session: state.session,
      pageId: 'UI-008',
      permission: permissionByCommand[payload.domainCommand],
      objectScope: { type: 'AREA', value: 'AREA-A' },
      ...(exception
        ? { expectedVersion: command.expectedVersion, actualVersion: exception.version }
        : {}),
    };
    const decision = authorizePolicy(context);
    return decision.allow ? { allow: true } : permissionFailure(decision);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported exception-handling command.');
    }
    assertBusinessPreconditions(store.getState(), command, payloadFor(command));
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = payloadFor(command);
    const stateBeforeGateway = store.getState();
    const exception = assertBusinessPreconditions(stateBeforeGateway, command, payload);
    if (
      exception.type === 'INTERLOCK'
      || stateBeforeGateway.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP'
    ) {
      return interlockFailure(command);
    }

    const response = await gateway.commandException(command.entityId, {
      action: apiActionByCommand[payload.domainCommand],
      reason: payload.reason.trim(),
      ...(payload.owner?.trim() ? { owner: payload.owner.trim() } : {}),
      ...(payload.evidence.length > 0
        ? { evidence: payload.evidence.map((item) => item.trim()) }
        : {}),
    });
    if (!response.ok) return response;
    if (response.data.scenarioId !== store.getState().scenario.activeScenarioId) {
      return scenarioFailure(response, 'Exception response scenario does not match the active scenario.');
    }
    try {
      assertBusinessPreconditions(store.getState(), command, payload);
    } catch (error) {
      return versionFailure(
        response,
        error instanceof Error ? error.message : 'DispatchException input version changed.',
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
      const exception = assertBusinessPreconditions(candidate, command, payload);
      const transition = transitionState({
        machineId: 'DO-009',
        current: exception.status,
        command: payload.domainCommand,
      });
      if (!transition.ok) {
        throw new Error(
          `DO-009 ${exception.status} + ${payload.domainCommand} transition was rejected.`,
        );
      }
      const next = do009Schema.parse({
        ...exception,
        status: transition.next,
        ...(payload.domainCommand === 'assign' ? { owner: payload.owner!.trim() } : {}),
        ...(payload.domainCommand === 'handle'
          ? { evidence: [...exception.evidence, ...payload.evidence.map((item) => item.trim())] }
          : {}),
        version: exception.version + 1,
        updatedAt: candidate.session.demoTime,
      });
      const index = candidate.exception.exceptions.findIndex(({ id }) => id === exception.id);
      candidate.exception.exceptions[index] = next;
    });
  };

  const appendExceptionAudit: AuditAppender = (input) => {
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
      appendAudit: appendExceptionAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const commandFor = (
    exceptionId: string,
    businessAction: ExceptionHandlingCommandPayload['businessAction'],
    domainCommand: ExceptionHandlingCommandPayload['domainCommand'],
    input: { reason: string; owner?: string; evidence?: readonly string[] },
  ): DemoCommand<ExceptionHandlingCommandPayload> => {
    const state = store.getState();
    const exception = canReadAreaA(state)
      ? state.exception.exceptions.find(({ id }) => id === exceptionId)
      : undefined;
    const payload = exceptionHandlingCommandPayloadSchema.parse({
      current: 'ACCEPTED',
      businessAction,
      domainCommand,
      exceptionVersion: exception?.version ?? 0,
      reason: input.reason,
      ...(input.owner !== undefined ? { owner: input.owner } : {}),
      evidence: [...(input.evidence ?? [])],
    });
    const commandId = nextCommandId();
    return {
      commandId,
      traceId: nextTraceId(commandId),
      action: 'execute',
      entityType: 'SM-007',
      entityId: exceptionId,
      expectedVersion: payload.exceptionVersion,
      payload,
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const execute = async (
    command: DemoCommand<ExceptionHandlingCommandPayload>,
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
    ackException: ({ exceptionId, reason }) => execute(commandFor(
      exceptionId, 'EX-01', 'ack', { reason },
    )),
    assignException: ({ exceptionId, owner, reason }) => execute(commandFor(
      exceptionId, 'EX-02', 'assign', { owner, reason },
    )),
    submitExceptionHandling: ({ exceptionId, evidence, reason }) => execute(commandFor(
      exceptionId, 'EX-03', 'handle', { evidence, reason },
    )),
    reviewException: ({ exceptionId, reason }) => execute(commandFor(
      exceptionId, 'EX-04', 'review', { reason },
    )),
    closeException: ({ exceptionId, reason }) => execute(commandFor(
      exceptionId, 'EX-04', 'close', { reason },
    )),
    reopenException: ({ exceptionId, reason }) => execute(commandFor(
      exceptionId, 'EX-05', 'reopen', { reason },
    )),
    resetCommandState,
  };
}
