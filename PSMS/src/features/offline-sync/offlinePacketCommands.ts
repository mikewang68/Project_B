import { z } from 'zod';

import {
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
  do011Schema,
  do013Schema,
  roleCodeSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
  type OfflinePacket,
} from '../../contracts';
import { createAuditLedger, validateCommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type {
  OfflinePacketCommandRequest,
  OfflinePacketGateway,
} from './offlinePacketGateway';
import type {
  OfflinePacketPendingAction,
  OfflinePacketWorkflowStore,
} from './offlinePacketTypes';

type OfflinePacketCommandInput = Readonly<{
  packetId: string;
  reason: string;
}>;

type ValidateOfflinePacketInput = OfflinePacketCommandInput & Readonly<{
  validation: Readonly<Record<string, unknown>>;
}>;

export type OfflinePacketCommandService = Readonly<{
  uploadPacket: (input: OfflinePacketCommandInput) => Promise<CommandResult>;
  validatePacket: (input: ValidateOfflinePacketInput) => Promise<CommandResult>;
  mergePacket: (input: OfflinePacketCommandInput) => Promise<CommandResult>;
  rejectPacket: (input: OfflinePacketCommandInput) => Promise<CommandResult>;
  retryPacket: (input: OfflinePacketCommandInput) => Promise<CommandResult>;
  resetCommandState: () => void;
}>;

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

export type OfflinePacketCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  gateway: OfflinePacketGateway;
  workflow: OfflinePacketWorkflowStore;
  idFormatters?: Partial<CommandIdFormatters>;
}>;

const domainCommands = ['upload', 'validate', 'merge', 'reject', 'retry'] as const;

const validationResultSchema = z
  .object({
    valid: z.boolean(),
    issues: z.array(z.string()),
  })
  .strict();

export const offlinePacketCommandPayloadSchema = z
  .object({
    current: z.literal('ACCEPTED'),
    businessAction: z.enum(['OS-01', 'OS-02', 'OS-03', 'OS-04', 'OS-05']),
    domainCommand: z.enum(domainCommands),
    packetVersion: z.number().int().nonnegative(),
    reason: z.string(),
    validation: z.record(z.string(), z.unknown()),
  })
  .strict()
  .superRefine((payload, context) => {
    const mapped = payload.businessAction === 'OS-01' && payload.domainCommand === 'upload'
      || payload.businessAction === 'OS-02' && payload.domainCommand === 'validate'
      || payload.businessAction === 'OS-03' && payload.domainCommand === 'merge'
      || payload.businessAction === 'OS-04' && payload.domainCommand === 'reject'
      || payload.businessAction === 'OS-05' && payload.domainCommand === 'retry';
    if (!mapped) {
      context.addIssue({
        code: 'custom',
        path: ['domainCommand'],
        message: `${payload.businessAction} does not map to ${payload.domainCommand}.`,
      });
    }
  });

export type OfflinePacketCommandPayload = z.infer<typeof offlinePacketCommandPayloadSchema>;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C10', sequence),
  trace: (sequence) => sequenceId('TRACE-C10', sequence),
  audit: (sequence) => sequenceId('AUD-C10', sequence),
};

const businessActionByCommand = {
  upload: 'OS-01',
  validate: 'OS-02',
  merge: 'OS-03',
  reject: 'OS-04',
  retry: 'OS-05',
} as const satisfies Record<
  OfflinePacketCommandPayload['domainCommand'],
  OfflinePacketCommandPayload['businessAction']
>;

const pendingActionByCommand = {
  upload: 'UPLOAD',
  validate: 'VALIDATE',
  merge: 'MERGE',
  reject: 'REJECT',
  retry: 'RETRY',
} as const satisfies Record<
  OfflinePacketCommandPayload['domainCommand'],
  OfflinePacketPendingAction
>;

const permissionByCommand = {
  upload: 'offline:retry',
  validate: 'offline:resolve',
  merge: 'offline:resolve',
  reject: 'offline:resolve',
  retry: 'offline:retry',
} as const;

const apiActionByCommand = {
  upload: 'UPLOAD',
  validate: 'VALIDATE',
  merge: 'MERGE',
  reject: 'REJECT',
  retry: 'RETRY',
} as const satisfies Record<
  OfflinePacketCommandPayload['domainCommand'],
  OfflinePacketCommandRequest['action']
>;

function payloadFor(command: DemoCommand): OfflinePacketCommandPayload {
  return offlinePacketCommandPayloadSchema.parse(command.payload);
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

function findPacket(state: DemoRootState, packetId: string): OfflinePacket {
  const packet = state.offline.packets.find(({ id }) => id === packetId);
  if (!packet) throw new Error(`Unknown OfflinePacket: ${packetId}`);
  return do011Schema.parse(packet);
}

function assertStoredSnapshot(
  state: DemoRootState,
  command: DemoCommand,
  payload: OfflinePacketCommandPayload,
): OfflinePacket {
  if (!canReadAreaA(state)) throw new Error('OfflinePacket is outside AREA-A data scope.');
  const packet = findPacket(state, command.entityId);
  if (
    packet.version !== payload.packetVersion
    || packet.version !== command.expectedVersion
  ) {
    throw new Error(
      `Expected OfflinePacket version ${payload.packetVersion}, actual ${packet.version}.`,
    );
  }
  return packet;
}

function assertBusinessPreconditions(
  state: DemoRootState,
  command: DemoCommand,
  payload: OfflinePacketCommandPayload,
): OfflinePacket {
  const packet = assertStoredSnapshot(state, command, payload);
  if (!payload.reason.trim()) throw new Error(`${payload.businessAction} requires a reason.`);
  if (payload.domainCommand === 'validate') {
    validationResultSchema.parse(payload.validation);
  } else if (Object.keys(payload.validation).length > 0) {
    throw new Error(`${payload.businessAction} does not accept a validation payload.`);
  }
  if (payload.domainCommand === 'merge' && packet.validation.valid !== true) {
    throw new Error('OS-03 merge requires validation.valid to be true.');
  }
  const transition = transitionState({
    machineId: 'DO-011',
    current: packet.mergeStatus,
    command: payload.domainCommand,
  });
  if (!transition.ok) {
    throw new Error(
      `DO-011 ${packet.mergeStatus} + ${payload.domainCommand} transition was rejected.`,
    );
  }
  return packet;
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

export function createOfflinePacketCommandService({
  store,
  gateway,
  workflow,
  idFormatters: inputFormatters,
}: OfflinePacketCommandServiceDependencies): OfflinePacketCommandService {
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
    throw new Error('Unable to allocate a unique C10 audit ID.');
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
    const packet = canReadAreaA(state)
      ? state.offline.packets.find(({ id }) => id === command.entityId)
      : undefined;
    const context: PolicyContext = {
      session: state.session,
      pageId: 'UI-010',
      permission: permissionByCommand[payload.domainCommand],
      objectScope: { type: 'AREA', value: 'AREA-A' },
      ...(packet
        ? { expectedVersion: command.expectedVersion, actualVersion: packet.version }
        : {}),
    };
    const decision = authorizePolicy(context);
    return decision.allow ? { allow: true } : permissionFailure(decision);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported offline-packet command.');
    }
    assertBusinessPreconditions(store.getState(), command, payloadFor(command));
  };

  const invokeMock = async (command: DemoCommand) => {
    const payload = payloadFor(command);
    assertBusinessPreconditions(store.getState(), command, payload);
    const response = await gateway.commandPacket(command.entityId, {
      action: apiActionByCommand[payload.domainCommand],
      reason: payload.reason.trim(),
      ...(payload.domainCommand === 'validate'
        ? { validation: structuredClone(payload.validation) }
        : {}),
    });
    if (!response.ok) return response;
    if (response.data.scenarioId !== store.getState().scenario.activeScenarioId) {
      return scenarioFailure(
        response,
        'OfflinePacket response scenario does not match the active scenario.',
      );
    }
    try {
      assertBusinessPreconditions(store.getState(), command, payload);
    } catch (error) {
      return versionFailure(
        response,
        error instanceof Error ? error.message : 'OfflinePacket input version changed.',
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
      const packet = assertBusinessPreconditions(candidate, command, payload);
      const transition = transitionState({
        machineId: 'DO-011',
        current: packet.mergeStatus,
        command: payload.domainCommand,
      });
      if (!transition.ok) {
        throw new Error(
          `DO-011 ${packet.mergeStatus} + ${payload.domainCommand} transition was rejected.`,
        );
      }
      const next = do011Schema.parse({
        ...packet,
        mergeStatus: transition.next,
        ...(payload.domainCommand === 'validate'
          ? { validation: structuredClone(payload.validation) }
          : {}),
        ...(payload.domainCommand === 'merge'
          ? { serverVersion: packet.packageVersion }
          : {}),
        version: packet.version + 1,
        updatedAt: candidate.session.demoTime,
      });
      const index = candidate.offline.packets.findIndex(({ id }) => id === packet.id);
      candidate.offline.packets[index] = next;
    });
  };

  const appendOfflineAudit: AuditAppender = (input) => {
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
      appendAudit: appendOfflineAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const commandFor = (
    packetId: string,
    domainCommand: OfflinePacketCommandPayload['domainCommand'],
    input: {
      reason: string;
      validation?: Readonly<Record<string, unknown>>;
    },
  ): DemoCommand<OfflinePacketCommandPayload> => {
    const state = store.getState();
    const packet = canReadAreaA(state)
      ? state.offline.packets.find(({ id }) => id === packetId)
      : undefined;
    const payload = offlinePacketCommandPayloadSchema.parse({
      current: 'ACCEPTED',
      businessAction: businessActionByCommand[domainCommand],
      domainCommand,
      packetVersion: packet?.version ?? 0,
      reason: input.reason,
      validation: structuredClone(input.validation ?? {}),
    });
    const commandId = nextCommandId();
    return {
      commandId,
      traceId: nextTraceId(commandId),
      action: 'execute',
      entityType: 'SM-007',
      entityId: packetId,
      expectedVersion: payload.packetVersion,
      payload,
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const execute = async (
    command: DemoCommand<OfflinePacketCommandPayload>,
  ): Promise<CommandResult> => {
    const replay = processedCommandIds.has(command.commandId);
    if (!replay) {
      workflow.setPendingAction(pendingActionByCommand[command.payload.domainCommand]);
    }
    const result = await executor.execute(command);
    if (!processedCommandIds.has(result.commandId)) {
      processedCommandIds.add(result.commandId);
      workflow.recordFeedback({
        ok: result.ok,
        traceId: result.traceId,
        auditLogId: result.auditLogId,
        commandId: result.commandId,
        ...(!result.ok ? { errorCode: result.errorCode } : {}),
        message: result.ok ? '离线包命令执行成功' : result.message,
        idempotent: false,
      });
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
    uploadPacket: ({ packetId, reason }) => execute(commandFor(
      packetId, 'upload', { reason },
    )),
    validatePacket: ({ packetId, reason, validation }) => execute(commandFor(
      packetId, 'validate', { reason, validation },
    )),
    mergePacket: ({ packetId, reason }) => execute(commandFor(
      packetId, 'merge', { reason },
    )),
    rejectPacket: ({ packetId, reason }) => execute(commandFor(
      packetId, 'reject', { reason },
    )),
    retryPacket: ({ packetId, reason }) => execute(commandFor(
      packetId, 'retry', { reason },
    )),
    resetCommandState,
  };
}
