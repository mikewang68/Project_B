import { authorize } from '../../auth';
import type { CommandResult } from '../../commands';
import {
  do012Schema,
  do013Schema,
  roleCodeSchema,
  type PublicErrorCode,
  type Report,
} from '../../contracts';
import { validateCommandAuditEntry, type CommandAuditEntry } from '../../governance/audit';
import type { DemoRootState, DemoStoreApi } from '../../stores';
import { deriveReportMetrics } from './reportMetrics';
import type { ReportWorkflowStore } from './reportRuntime';

export type RefreshReportInput = Readonly<{
  commandId?: string;
  reportId: string;
  expectedGeneratedAt: string;
  reason: string;
}>;

export type ReportCommandService = Readonly<{
  refreshReport(input: RefreshReportInput): Promise<CommandResult>;
  resetCommandState(): void;
}>;

export type ReportCommandServiceDependencies = Readonly<{
  store: DemoStoreApi;
  workflow: ReportWorkflowStore;
}>;

class ReportVersionConflict extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReportVersionConflict';
  }
}

function sequenceId(prefix: string, sequence: number): string {
  return `${prefix}-${String(sequence).padStart(3, '0')}`;
}

function findReport(state: DemoRootState, reportId: string): Report | undefined {
  const report = state.report.reports.find(({ id }) => id === reportId);
  return report ? do012Schema.parse(report) : undefined;
}

function failureMessage(errorCode: PublicErrorCode, detail: string): string {
  if (errorCode === 'DEMO-VERSION-001') return `报表快照版本已变化：${detail}`;
  if (errorCode === 'TOS-AUTH-001') return `无权生成或刷新报表：${detail}`;
  return detail;
}

export function createReportCommandService({
  store,
  workflow,
}: ReportCommandServiceDependencies): ReportCommandService {
  let commandSequence = 1;
  let traceSequence = 1;
  let auditSequence = 1;
  const resolvedResults = new Map<string, CommandResult>();

  const nextCommandId = () => sequenceId('CMD-C11', commandSequence++);
  const nextTraceId = () => sequenceId('TRACE-C11', traceSequence++);
  const nextAuditId = () => {
    const usedIds = new Set(store.getState().configAudit.commandAudit.map(({ record }) => record.id));
    let candidate = sequenceId('AUD-C11', auditSequence++);
    while (usedIds.has(candidate)) candidate = sequenceId('AUD-C11', auditSequence++);
    return candidate;
  };

  const buildAudit = (
    input: RefreshReportInput,
    result: CommandResult,
    before: Report | undefined,
    after: Report | undefined,
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
        action: 'RS-03',
        objectType: 'DO-012',
        objectId: input.reportId,
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

  const cacheAndPublish = (
    resultInput: CommandResult,
    message: string,
  ): CommandResult => {
    const result = Object.freeze(resultInput);
    resolvedResults.set(result.commandId, result);
    workflow.recordFeedback({
      ok: result.ok,
      commandId: result.commandId,
      traceId: result.traceId,
      auditLogId: result.auditLogId,
      message,
      ...(!result.ok ? { errorCode: result.errorCode } : {}),
      idempotent: false,
    });
    return result;
  };

  const appendFailureAudit = (
    input: RefreshReportInput,
    result: Extract<CommandResult, { ok: false }>,
    before?: Report,
  ): void => {
    const audit = buildAudit(input, result, before, before);
    store.replaceDomainState((candidate) => {
      candidate.configAudit.commandAudit.push(structuredClone(audit));
    });
  };

  const fail = (
    input: RefreshReportInput,
    commandId: string,
    traceId: string,
    auditLogId: string,
    errorCode: PublicErrorCode,
    detail: string,
    before?: Report,
  ): CommandResult => {
    const message = failureMessage(errorCode, detail);
    const result = Object.freeze({
      ok: false as const,
      commandId,
      traceId,
      auditLogId,
      errorCode,
      message,
    });
    appendFailureAudit(input, result, before);
    return cacheAndPublish(result, message);
  };

  const refreshReport = async (input: RefreshReportInput): Promise<CommandResult> => {
    const commandId = input.commandId === undefined ? nextCommandId() : input.commandId;
    const replay = resolvedResults.get(commandId);
    if (replay) return replay;

    const traceId = nextTraceId();
    const auditLogId = nextAuditId();
    workflow.setPendingReportId(input.reportId);
    const state = store.getState();
    const visibleBefore = findReport(state, input.reportId);
    const permission = authorize({
      session: state.session,
      pageId: 'UI-011',
      permission: 'report:generate',
      objectScope: { type: 'AREA', value: 'AREA-A' },
    });
    if (!permission.allow) {
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        permission.errorCode,
        permission.reason,
      );
    }
    if (!commandId.trim() || !input.reportId.trim() || !input.reason.trim()
      || !input.expectedGeneratedAt.trim()) {
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        'DEMO-SCENARIO-001',
        '报表标识、并发令牌和刷新理由均不能为空。',
        visibleBefore,
      );
    }
    if (!visibleBefore) {
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        'DEMO-SCENARIO-001',
        `未找到可见报表 ${input.reportId}。`,
      );
    }
    if (visibleBefore.generatedAt !== input.expectedGeneratedAt) {
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        'DEMO-VERSION-001',
        `期望 ${input.expectedGeneratedAt}，当前 ${visibleBefore.generatedAt}。`,
        visibleBefore,
      );
    }

    const snapshot = deriveReportMetrics(state);
    let refreshed: Report | undefined;
    try {
      store.replaceDomainState((candidate) => {
        const index = candidate.report.reports.findIndex(({ id }) => id === input.reportId);
        if (index < 0) throw new Error(`Unknown report: ${input.reportId}`);
        const current = do012Schema.parse(candidate.report.reports[index]);
        if (current.generatedAt !== input.expectedGeneratedAt) {
          throw new ReportVersionConflict(
            `期望 ${input.expectedGeneratedAt}，当前 ${current.generatedAt}。`,
          );
        }
        refreshed = do012Schema.parse({
          ...current,
          generateStatus: 'SUCCESS',
          metrics: structuredClone(snapshot.flatMetrics),
          generatedAt: candidate.session.demoTime,
        });
        candidate.report.reports[index] = refreshed;
        const result: CommandResult = {
          ok: true,
          commandId,
          traceId,
          auditLogId,
        };
        candidate.configAudit.commandAudit.push(structuredClone(
          buildAudit(input, result, current, refreshed),
        ));
      });
    } catch (error) {
      const versionConflict = error instanceof ReportVersionConflict;
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        versionConflict ? 'DEMO-VERSION-001' : 'DEMO-SCENARIO-001',
        error instanceof Error ? error.message : '报表快照提交失败。',
        findReport(store.getState(), input.reportId),
      );
    }

    if (!refreshed) {
      return fail(
        input,
        commandId,
        traceId,
        auditLogId,
        'DEMO-SCENARIO-001',
        '报表快照提交未返回结果。',
        visibleBefore,
      );
    }
    return cacheAndPublish({ ok: true, commandId, traceId, auditLogId }, '报表快照已刷新');
  };

  return Object.freeze({
    refreshReport,
    resetCommandState: () => {
      commandSequence = 1;
      traceSequence = 1;
      auditSequence = 1;
      resolvedResults.clear();
    },
  });
}
