import { authorize } from '../../auth';
import { transitionState, type CommandResult, type TransitionResult } from '../../commands';
import {
  configVersionChangesSchema,
  do013Schema,
  do015Schema,
  roleCodeSchema,
  type ConfigVersion,
  type PublicErrorCode,
} from '../../contracts';
import { validateCommandAuditEntry, type CommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import type { SystemSettingsGateway } from './systemSettingsGateway';
import type { SystemSettingsWorkflowStore } from './systemSettingsRuntime';
import type {
  ConfigVersionChanges,
  SystemSettingsCommandFeedback,
} from './systemSettingsTypes';

export type SaveSystemSettingsInput = Readonly<{
  commandId?: string;
  configId: string;
  expectedVersion: number;
  changes: ConfigVersionChanges;
  reason: string;
}>;

export type SystemSettingsCommandService = Readonly<{
  save(input: SaveSystemSettingsInput): Promise<CommandResult>;
  resetCommandState(): void;
}>;

export type SystemSettingsCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  workflow: SystemSettingsWorkflowStore;
  gateway: SystemSettingsGateway;
  transition?: (request: {
    machineId: 'DO-015'; current: string; command: 'edit';
  }) => TransitionResult;
}>;

type ResolvedCommand = Readonly<{
  result: CommandResult;
  feedback: SystemSettingsCommandFeedback;
}>;

class SystemSettingsVersionConflict extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SystemSettingsVersionConflict';
  }
}

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

function findConfig(state: DemoRootState, configId: string): ConfigVersion | undefined {
  const config = state.systemConfig.configVersions.find(({ id }) => id === configId);
  return config ? do015Schema.parse(config) : undefined;
}

function failureMessage(errorCode: PublicErrorCode, detail: string): string {
  if (errorCode === 'TOS-AUTH-001') return `无权保存系统配置：${detail}`;
  if (errorCode === 'DEMO-VERSION-001') return `配置版本已变化：${detail}`;
  return detail;
}

export function createSystemSettingsCommandService({
  store,
  workflow,
  gateway,
  transition = transitionState,
}: SystemSettingsCommandServiceDependencies): SystemSettingsCommandService {
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  const resolved = new Map<string, ResolvedCommand>();

  const nextCommandId = () => sequenceId('CMD-C13', commandSequence++);
  const nextTraceId = () => sequenceId('TRACE-C13', traceSequence++);
  const nextAuditId = () => {
    const used = new Set(store.getState().configAudit.commandAudit.map(({ record }) => record.id));
    let candidate = sequenceId('AUD-C13', auditSequence++);
    while (used.has(candidate)) candidate = sequenceId('AUD-C13', auditSequence++);
    return candidate;
  };

  const buildAudit = (
    input: SaveSystemSettingsInput,
    result: CommandResult,
    before: ConfigVersion | undefined,
    after: ConfigVersion | undefined,
  ): CommandAuditEntry => {
    const state = store.getState();
    const metadataResult = result.ok
      ? { result: 'SUCCESS' as const, errorCode: null }
      : result.errorCode === 'TOS-AUTH-001'
        ? { result: 'DENIED' as const, errorCode: result.errorCode }
        : { result: 'FAILED' as const, errorCode: result.errorCode };
    return validateCommandAuditEntry({
      record: do013Schema.parse({
        id: result.auditLogId,
        actorId: state.session.actorId,
        operatorTerminal: 'WEB-DEMO',
        action: 'SS-05',
        objectType: 'DO-015',
        objectId: input.configId,
        before: before ? structuredClone(before) : {},
        after: after ? structuredClone(after) : {},
        reason: input.reason.trim() || (result.ok ? '' : result.message),
        traceId: result.traceId,
        occurredAt: state.session.demoTime,
      }),
      metadata: {
        roleCode: roleCodeSchema.parse(state.session.roleCode),
        dataScope: [...state.session.dataScope],
        ...metadataResult,
        clientTime: state.session.demoTime,
        serverTime: state.session.demoTime,
      },
    });
  };

  const publish = (
    resultInput: CommandResult,
    feedbackInput: SystemSettingsCommandFeedback,
  ): CommandResult => {
    const result = Object.freeze(resultInput);
    const feedback = Object.freeze(feedbackInput);
    resolved.set(result.commandId, Object.freeze({ result, feedback }));
    workflow.recordFeedback(feedback);
    return result;
  };

  const appendFailureAudit = (
    input: SaveSystemSettingsInput,
    result: Extract<CommandResult, { ok: false }>,
    before?: ConfigVersion,
  ): void => {
    const entry = buildAudit(input, result, before, before);
    store.replaceDomainState((candidate) => {
      candidate.configAudit.commandAudit.push(structuredClone(entry));
    });
  };

  const fail = (
    input: SaveSystemSettingsInput,
    ids: Readonly<{ commandId: string; traceId: string; domainAuditLogId: string }>,
    errorCode: PublicErrorCode,
    detail: string,
    before?: ConfigVersion,
    transportAuditLogId = 'UNAVAILABLE',
  ): CommandResult => {
    const message = failureMessage(errorCode, detail);
    const result = Object.freeze({
      ok: false as const,
      commandId: ids.commandId,
      traceId: ids.traceId,
      auditLogId: ids.domainAuditLogId,
      errorCode,
      message,
    });
    appendFailureAudit(input, result, before);
    return publish(result, {
      ok: false,
      commandId: result.commandId,
      traceId: result.traceId,
      transportAuditLogId,
      domainAuditLogId: result.auditLogId,
      message,
      errorCode,
      idempotent: false,
    });
  };

  const save = async (input: SaveSystemSettingsInput): Promise<CommandResult> => {
    const commandId = input.commandId === undefined ? nextCommandId() : input.commandId;
    const replay = resolved.get(commandId);
    if (replay) {
      workflow.recordFeedback({ ...replay.feedback, idempotent: true });
      return replay.result;
    }

    const localTraceId = nextTraceId();
    const domainAuditLogId = nextAuditId();
    const localIds = { commandId, traceId: localTraceId, domainAuditLogId };
    const state = store.getState();
    const visibleBefore = findConfig(state, input.configId);
    const permission = authorize({
      session: state.session,
      pageId: 'UI-012',
      permission: 'settings:edit',
    });
    if (!permission.allow) {
      return fail(input, localIds, permission.errorCode, permission.reason, visibleBefore);
    }

    if (!commandId.trim() || !input.configId.trim() || !input.reason.trim()) {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        '命令标识、配置标识和变更说明均不能为空。',
        visibleBefore,
      );
    }
    if (!visibleBefore) {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        `未找到配置 ${input.configId}。`,
      );
    }
    if (visibleBefore.status !== 'DRAFT') {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        `配置 ${input.configId} 当前状态 ${visibleBefore.status} 不允许 edit。`,
        visibleBefore,
      );
    }
    if (!Number.isInteger(input.expectedVersion) || input.expectedVersion <= 0) {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        'expectedVersion 必须是正整数。',
        visibleBefore,
      );
    }
    if (visibleBefore.version !== input.expectedVersion) {
      return fail(
        input,
        localIds,
        'DEMO-VERSION-001',
        `期望 ${input.expectedVersion}，当前 ${visibleBefore.version}。`,
        visibleBefore,
      );
    }
    const parsedChanges = configVersionChangesSchema.safeParse(input.changes);
    if (!parsedChanges.success) {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        parsedChanges.error.issues.map(({ message }) => message).join('；'),
        visibleBefore,
      );
    }

    workflow.setPending(true);
    let gatewayResult;
    try {
      gatewayResult = await gateway.editConfig({
        configId: input.configId,
        expectedScenarioId: state.scenario.activeScenarioId,
        expectedNow: state.session.demoTime,
        expectedVersion: input.expectedVersion,
        changes: parsedChanges.data,
        reason: input.reason.trim(),
      });
    } catch (error) {
      return fail(
        input,
        localIds,
        'DEMO-SCENARIO-001',
        error instanceof Error ? error.message : '系统配置接口不可用。',
        visibleBefore,
      );
    }

    const responseIds = {
      commandId,
      traceId: gatewayResult.traceId,
      domainAuditLogId,
    };
    if (!gatewayResult.ok) {
      return fail(
        input,
        responseIds,
        gatewayResult.errorCode,
        gatewayResult.message,
        visibleBefore,
        gatewayResult.auditLogId,
      );
    }
    if (gatewayResult.data.apiId !== 'API-023') {
      return fail(
        input,
        responseIds,
        'DEMO-SCENARIO-001',
        `API-023 command received ${gatewayResult.data.apiId}.`,
        visibleBefore,
        gatewayResult.auditLogId,
      );
    }

    const current = findConfig(store.getState(), input.configId);
    if (!current || current.version !== input.expectedVersion || current.status !== 'DRAFT') {
      return fail(
        input,
        responseIds,
        'DEMO-VERSION-001',
        `期望 DRAFT v${input.expectedVersion}，当前 ${current?.status ?? 'missing'} v${current?.version ?? 'missing'}。`,
        current,
        gatewayResult.auditLogId,
      );
    }
    const transitionResult = transition({
      machineId: 'DO-015', current: current.status, command: 'edit',
    });
    if (!transitionResult.ok || transitionResult.next !== 'DRAFT') {
      return fail(
        input,
        responseIds,
        'DEMO-SCENARIO-001',
        `DO-015 edit transition failed: ${transitionResult.ok ? transitionResult.next : transitionResult.reason}.`,
        current,
        gatewayResult.auditLogId,
      );
    }

    let after: ConfigVersion | undefined;
    try {
      store.replaceDomainState((candidate) => {
        const index = candidate.systemConfig.configVersions.findIndex(({ id }) => id === input.configId);
        if (index < 0) throw new SystemSettingsVersionConflict('Config disappeared before commit.');
        const commitCurrent = do015Schema.parse(candidate.systemConfig.configVersions[index]);
        if (commitCurrent.version !== input.expectedVersion || commitCurrent.status !== 'DRAFT') {
          throw new SystemSettingsVersionConflict(
            `Expected DRAFT v${input.expectedVersion}, received ${commitCurrent.status} v${commitCurrent.version}.`,
          );
        }
        after = do015Schema.parse({
          ...commitCurrent,
          ...parsedChanges.data,
          version: commitCurrent.version + 1,
          updatedAt: candidate.session.demoTime,
          updatedBy: candidate.session.actorId,
        });
        const successResult: CommandResult = {
          ok: true,
          commandId,
          traceId: gatewayResult.traceId,
          auditLogId: domainAuditLogId,
        };
        candidate.systemConfig.configVersions[index] = structuredClone(after);
        candidate.configAudit.commandAudit.push(structuredClone(
          buildAudit(input, successResult, commitCurrent, after),
        ));
      });
    } catch (error) {
      return fail(
        input,
        responseIds,
        error instanceof SystemSettingsVersionConflict
          ? 'DEMO-VERSION-001'
          : 'DEMO-SCENARIO-001',
        error instanceof Error ? error.message : '系统配置提交失败。',
        findConfig(store.getState(), input.configId),
        gatewayResult.auditLogId,
      );
    }

    if (!after) {
      return fail(
        input,
        responseIds,
        'DEMO-SCENARIO-001',
        '系统配置提交未返回结果。',
        current,
        gatewayResult.auditLogId,
      );
    }
    const result: CommandResult = Object.freeze({
      ok: true,
      commandId,
      traceId: gatewayResult.traceId,
      auditLogId: domainAuditLogId,
    });
    return publish(result, {
      ok: true,
      commandId,
      traceId: gatewayResult.traceId,
      transportAuditLogId: gatewayResult.auditLogId,
      domainAuditLogId,
      message: '系统配置已保存',
      idempotent: false,
      version: after.version,
    });
  };

  return Object.freeze({
    save,
    resetCommandState: () => {
      commandSequence = 1;
      traceSequence = 1;
      auditSequence = 1;
      resolved.clear();
    },
  });
}
