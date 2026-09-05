import {
  createCommandExecutor,
  createCommandPermissionEvaluator,
  transitionState,
  type AuditAppender,
  type CommandExecutor,
  type CommandResult,
  type DemoCommand,
} from '../../commands';
import {
  api003RequestSchema,
  api004RequestSchema,
  api025RequestSchema,
  planStatusSchema,
  type ApiErrorEnvelope,
  type ApiSuccessEnvelope,
} from '../../contracts';
import { DEMO_SESSION_STORAGE_KEY, type PolicyContext, type RoleCode } from '../../auth';
import {
  createAuditLedger,
  createCommandAuditAppender,
  type CommandAuditEntry,
} from '../../governance/audit';
import { createFixtureSnapshot, type DemoScenario } from '../../mocks/fixtures';
import type { DemoStoreApi } from '../../stores';
import type { SupplementPlanValues } from './components/SupplementPlanForm';
import type { PlanEntryGateway } from './gateway';
import type { PlanEntryWorkflowStore } from './types';

type PlanAction = keyof typeof planPermissionByCommand;

type PlanCommandPayload = {
  values?: SupplementPlanValues;
};

type ControlPayload =
  | { kind: 'SYNC'; scenarioId: DemoScenario['id'] }
  | { kind: 'RESET'; scenarioId: DemoScenario['id'] };

type CommandIdFormatters = Readonly<{
  command: (sequence: number) => string;
  trace: (sequence: number) => string;
  audit: (sequence: number) => string;
}>;

type SessionStorageWriter = {
  setItem: (key: string, value: string) => void;
};

export type PlanEntryCommandService = {
  syncPlans: (scenarioId: DemoScenario['id']) => Promise<CommandResult>;
  supplementPlan: (planId: string, values: SupplementPlanValues) => Promise<CommandResult>;
  confirmPlan: (planId: string, values?: SupplementPlanValues) => Promise<CommandResult>;
  resetScenario: (scenarioId: DemoScenario['id']) => Promise<CommandResult>;
  switchRole: (roleCode: RoleCode) => void;
  resetCommandState: () => void;
};

export type PlanEntryCommandServiceDependencies = {
  store: DemoStoreApi;
  gateway: PlanEntryGateway;
  workflow: PlanEntryWorkflowStore;
  storage?: SessionStorageWriter;
  idFormatters?: Partial<CommandIdFormatters>;
  onSuccessfulReset?: () => void;
};

const planPermissionByCommand = {
  adjust: 'plan:adjust',
  confirm: 'plan:confirm',
} as const;

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

const defaultIdFormatters: CommandIdFormatters = {
  command: (sequence) => sequenceId('CMD-C04', sequence),
  trace: (sequence) => sequenceId('TRACE-C04', sequence),
  audit: (sequence) => sequenceId('AUD-C04', sequence),
};

function isPlanAction(action: string): action is PlanAction {
  return action === 'adjust' || action === 'confirm';
}

function isControlPayload(payload: Record<string, unknown>): payload is ControlPayload {
  return (
    (payload.kind === 'SYNC' || payload.kind === 'RESET') &&
    typeof payload.scenarioId === 'string'
  );
}

function planPayload(command: DemoCommand): PlanCommandPayload {
  return command.payload as PlanCommandPayload;
}

function api004Payload(values?: SupplementPlanValues) {
  if (!values) return api004RequestSchema.parse({});
  return api004RequestSchema.parse({
    reason: values.reason,
    supplements: {
      trackNo: values.trackNo,
      effectiveUntil: values.effectiveUntil,
      reviewerId: values.reviewerId,
    },
  });
}

function versionFailure(
  response: ApiSuccessEnvelope,
  expectedVersion: number,
  actualVersion: number,
): ApiErrorEnvelope {
  return {
    ok: false,
    errorCode: 'DEMO-VERSION-001',
    message: `Expected version ${expectedVersion}, actual ${actualVersion}.`,
    traceId: response.traceId,
    auditLogId: response.auditLogId,
  };
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

function storedSession(state: ReturnType<DemoStoreApi['getState']>) {
  return {
    actorId: state.session.actorId,
    roleCode: state.session.roleCode,
    dataScope: [...state.session.dataScope],
    online: state.session.online,
  };
}

function freezeResult<T extends CommandResult>(result: T): T {
  return Object.freeze(result);
}

export function createPlanEntryCommandService({
  store,
  gateway,
  workflow,
  storage,
  idFormatters: inputFormatters,
  onSuccessfulReset,
}: PlanEntryCommandServiceDependencies): PlanEntryCommandService {
  const idFormatters: CommandIdFormatters = {
    ...defaultIdFormatters,
    ...inputFormatters,
  };
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  let executor: CommandExecutor;
  let keepOnlyResetAudit = false;
  let successfulResetCommitted = false;
  let lastSyncFailure: Extract<CommandResult, { ok: false }> | undefined;
  const processedSyncCommandIds = new Set<string>();
  const traceIdByCommandId = new Map<string, string>();
  const normalizedPlanStateByCommandId = new Map<string, string>();

  const nextCommandId = (): string => idFormatters.command(commandSequence++);

  const nextTraceId = (commandId: string): string => {
    const replayTraceId = traceIdByCommandId.get(commandId);
    if (replayTraceId) return replayTraceId;
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
    throw new Error('Unable to allocate a unique C04 audit ID.');
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

  const isRecovery = (command: DemoCommand): boolean =>
    isControlPayload(command.payload) &&
    command.payload.kind === 'RESET' &&
    store.getState().scenario.activeScenarioId === 'SCN-03' &&
    command.payload.scenarioId === 'SCN-01';

  const policyContext = (command: DemoCommand): PolicyContext => {
    const state = store.getState();
    if (command.entityType === 'DO-001' && isPlanAction(command.action)) {
      const currentPlan = state.plan.plans.find(({ id }) => id === command.entityId);
      const values = planPayload(command).values;
      const common: PolicyContext = {
        session: state.session,
        pageId: 'UI-002',
        permission: planPermissionByCommand[command.action],
        objectScope: { type: 'AREA', value: 'AREA-A' },
        expectedVersion: command.expectedVersion,
        actualVersion: currentPlan?.version,
      };
      return values?.reviewerId
        ? {
            ...common,
            highRisk: true,
            applicantId: state.session.actorId,
            approverId: values?.reviewerId,
          }
        : common;
    }

    if (isControlPayload(command.payload) && command.payload.kind === 'SYNC') {
      return {
        session: state.session,
        pageId: 'UI-002',
        permission: 'plan:view',
        objectScope: { type: 'AREA', value: 'AREA-A' },
      };
    }

    return {
      session: state.session,
      pageId: isRecovery(command) ? 'UI-002' : 'UI-001',
      permission: isRecovery(command) ? 'interface:retry' : 'demo:reset',
      objectScope: { type: 'AREA', value: 'AREA-A' },
    };
  };

  const evaluatePermission = createCommandPermissionEvaluator(policyContext);

  const authorize = (command: DemoCommand) => {
    if (isRecovery(command) && store.getState().session.roleCode !== 'INTERFACE_OPS') {
      return {
        allow: false as const,
        errorCode: 'TOS-AUTH-001' as const,
        message: 'SCN-03 recovery requires the INTERFACE_OPS role.',
      };
    }
    return evaluatePermission(command);
  };

  const validate = (command: DemoCommand): void => {
    if (command.entityType === 'DO-001' && isPlanAction(command.action)) {
      const currentPlan = store.getState().plan.plans.find(({ id }) => id === command.entityId);
      if (!currentPlan) throw new Error(`Unknown plan: ${command.entityId}`);
      const values = planPayload(command).values;
      if (command.action === 'adjust' && !values) {
        throw new Error('Plan adjustment requires complete supplement values.');
      }
      if (command.action === 'confirm' && values && values.trackNo !== currentPlan.trackNo) {
        throw new Error('Plan confirmation supplements must match the adjusted Store track.');
      }
      api004Payload(values);
      return;
    }
    if (command.entityType !== 'SM-007' || command.action !== 'execute') {
      throw new Error('Unsupported plan-entry command.');
    }
    if (!isControlPayload(command.payload)) throw new Error('Invalid plan-entry control payload.');
    if (!store.getState().scenario.scenarios.some(({ id }) => id === command.payload.scenarioId)) {
      throw new Error(`Unknown scenario: ${command.payload.scenarioId}`);
    }
    if (command.payload.kind === 'SYNC') {
      api003RequestSchema.parse({ scenarioId: command.payload.scenarioId });
    } else {
      api025RequestSchema.parse({ scenarioId: command.payload.scenarioId });
    }
  };

  const invokeMock = async (command: DemoCommand) => {
    if (command.entityType === 'DO-001' && isPlanAction(command.action)) {
      const response = await gateway.confirmPlan(
        command.entityId,
        api004Payload(planPayload(command).values),
      );
      if (!response.ok) return response;
      const currentPlan = store.getState().plan.plans.find(({ id }) => id === command.entityId);
      if (!currentPlan) return scenarioFailure(response, `Unknown plan: ${command.entityId}`);
      if (currentPlan.version !== command.expectedVersion) {
        return versionFailure(response, command.expectedVersion, currentPlan.version);
      }
      normalizedPlanStateByCommandId.set(command.commandId, currentPlan.status);
      return {
        ...response,
        data: {
          ...response.data,
          ...structuredClone(currentPlan),
          status: currentPlan.status,
        },
      } satisfies ApiSuccessEnvelope;
    }

    if (!isControlPayload(command.payload)) {
      throw new Error('Invalid plan-entry control payload.');
    }
    const response = command.payload.kind === 'SYNC'
      ? await gateway.syncPlans({ scenarioId: command.payload.scenarioId })
      : await gateway.resetDemo({ scenarioId: command.payload.scenarioId });
    return response.ok
      ? {
          ...response,
          data: { ...response.data, status: 'ACCEPTED' },
        }
      : response;
  };

  const commitPlan = (command: DemoCommand): void => {
    const values = planPayload(command).values;
    const normalizedState = normalizedPlanStateByCommandId.get(command.commandId);
    store.replaceDomainState((candidate) => {
      const plan = candidate.plan.plans.find(({ id }) => id === command.entityId);
      if (!plan) throw new Error(`Unknown plan: ${command.entityId}`);
      if (plan.version !== command.expectedVersion) {
        throw new Error(`Expected version ${command.expectedVersion}, actual ${plan.version}.`);
      }
      if (plan.status !== normalizedState) {
        throw new Error('Store plan changed after strict Gateway normalization.');
      }
      const transition = transitionState({
        machineId: 'DO-001',
        current: plan.status,
        command: command.action,
      });
      if (!transition.ok) throw new Error(`Plan transition failed: ${transition.reason}`);
      if (command.action === 'adjust' && values) plan.trackNo = values.trackNo;
      plan.status = planStatusSchema.parse(transition.next);
      plan.version += 1;
      plan.updatedAt = currentTime();
    });
    normalizedPlanStateByCommandId.delete(command.commandId);
    if (command.action === 'adjust') workflow.resolveFields(command.entityId, ['trackNo']);
  };

  const commitControl = (command: DemoCommand): void => {
    if (!isControlPayload(command.payload)) throw new Error('Invalid plan-entry control payload.');
    if (command.payload.kind === 'SYNC') {
      workflow.recordSuccess(currentTime());
      return;
    }

    const current = store.getState();
    const nextSession = {
      actorId: current.session.actorId,
      roleCode: current.session.roleCode,
      dataScope: [...current.session.dataScope],
      shiftId: current.session.shiftId,
      online: current.session.online,
      scenarioId: command.payload.scenarioId,
    };
    storage?.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify(storedSession(current)));
    store.resetFromSnapshot(createFixtureSnapshot(), nextSession);
    workflow.reset();
    keepOnlyResetAudit = true;
    successfulResetCommitted = true;
  };

  const commit = (command: DemoCommand): void => {
    if (command.entityType === 'DO-001') {
      commitPlan(command);
    } else {
      commitControl(command);
    }
  };

  const buildExecutor = (): void => {
    const appendAudit: AuditAppender = (input) => {
      const ledger = createAuditLedger(
        store.getState().configAudit.commandAudit,
        (entries: CommandAuditEntry[]) => {
          const committedEntries = keepOnlyResetAudit ? entries.slice(-1) : entries;
          store.replaceDomainState((candidate) => {
            candidate.configAudit.commandAudit = structuredClone(committedEntries);
          });
          keepOnlyResetAudit = false;
        },
      );
      return createCommandAuditAppender(ledger)(input);
    };
    executor = createCommandExecutor({
      authorize,
      validate,
      invokeMock,
      transition: transitionState,
      commit: (command) => commit(command),
      appendAudit,
      nextAuditId,
      now: currentTime,
    });
  };

  const resetCommandState = (): void => {
    commandSequence = 1;
    traceSequence = 1;
    auditSequence = 1;
    successfulResetCommitted = false;
    keepOnlyResetAudit = false;
    lastSyncFailure = undefined;
    processedSyncCommandIds.clear();
    traceIdByCommandId.clear();
    normalizedPlanStateByCommandId.clear();
    buildExecutor();
  };

  const commandIdentity = () => {
    const commandId = nextCommandId();
    return { commandId, traceId: nextTraceId(commandId) };
  };

  const planCommand = (
    action: PlanAction,
    planId: string,
    values?: SupplementPlanValues,
  ): DemoCommand<PlanCommandPayload> => {
    const currentPlan = store.getState().plan.plans.find(({ id }) => id === planId);
    return {
      ...commandIdentity(),
      action,
      entityType: 'DO-001',
      entityId: planId,
      expectedVersion: currentPlan?.version ?? 0,
      payload: values ? { values: structuredClone(values) } : {},
      actor: currentActor(),
      clientTime: currentTime(),
    };
  };

  const controlCommand = (payload: ControlPayload): DemoCommand<ControlPayload> => ({
    ...commandIdentity(),
    action: 'execute',
    entityType: 'SM-007',
    entityId: payload.scenarioId,
    expectedVersion: 0,
    payload,
    actor: currentActor(),
    clientTime: currentTime(),
  });

  const syncPlans = async (scenarioId: DemoScenario['id']): Promise<CommandResult> => {
    if (workflow.getState().circuitOpen && lastSyncFailure) return lastSyncFailure;
    const result = await executor.execute(controlCommand({ kind: 'SYNC', scenarioId }));
    if (!processedSyncCommandIds.has(result.commandId)) {
      processedSyncCommandIds.add(result.commandId);
      if (!result.ok) {
        lastSyncFailure = freezeResult(result);
        if (result.errorCode === 'TOS-EXT-001') workflow.recordRetry();
      } else {
        lastSyncFailure = undefined;
      }
    }
    return result;
  };

  const supplementPlan = (planId: string, values: SupplementPlanValues): Promise<CommandResult> =>
    executor.execute(planCommand('adjust', planId, values));

  const confirmPlan = (
    planId: string,
    values?: SupplementPlanValues,
  ): Promise<CommandResult> => executor.execute(planCommand('confirm', planId, values));

  const resetScenario = async (scenarioId: DemoScenario['id']): Promise<CommandResult> => {
    successfulResetCommitted = false;
    const result = await executor.execute(controlCommand({ kind: 'RESET', scenarioId }));
    if (result.ok && successfulResetCommitted) {
      onSuccessfulReset?.();
      resetCommandState();
    }
    return result;
  };

  const switchRole = (roleCode: RoleCode): void => {
    store.replaceDomainState((candidate) => {
      candidate.session.roleCode = roleCode;
    });
    storage?.setItem(DEMO_SESSION_STORAGE_KEY, JSON.stringify(storedSession(store.getState())));
  };

  buildExecutor();

  return {
    syncPlans,
    supplementPlan,
    confirmPlan,
    resetScenario,
    switchRole,
    resetCommandState,
  };
}
