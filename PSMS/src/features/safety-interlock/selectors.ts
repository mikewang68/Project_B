import { authorize } from '../../auth';
import { transitionState } from '../../commands';
import { interlockStatuses, type Interlock } from '../../contracts';
import type { DemoRootState } from '../../stores';
import { businessLabel } from '../../presentation/businessCopy';
import {
  FORCE_STOP_WARNING,
  INTERLOCK_SOURCE_DISCLOSURE,
} from './constants';
import type {
  InterlockActionAvailability,
  InterlockKpis,
  InterlockLedgerItem,
  InterlockProgressState,
  InterlockQueryContext,
  SafetyInterlockBoard,
} from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*')
    || state.session.dataScope.includes('GLOBAL')
    || state.session.dataScope.includes('AREA-A');
}

function strictInterlock(interlock: Interlock): Interlock {
  return {
    id: interlock.id,
    interlockNo: interlock.interlockNo,
    riskType: interlock.riskType,
    actionLevel: interlock.actionLevel,
    status: interlock.status,
    inputSnapshot: structuredClone(interlock.inputSnapshot),
    receiptStatus: interlock.receiptStatus,
    resetRequest: structuredClone(interlock.resetRequest),
    approvalChain: [...interlock.approvalChain],
    version: interlock.version,
    createdAt: interlock.createdAt,
    updatedAt: interlock.updatedAt,
  };
}

function progressState(interlock: Interlock): InterlockProgressState {
  if (interlock.status === 'RESTORED') return 'RESTORED';
  if (interlock.status === 'OVERRIDE_PENDING' || interlock.status === 'OVERRIDDEN') {
    return 'OVERRIDE';
  }
  if (interlock.status === 'RESET_REQUESTED' || interlock.status === 'APPROVED') {
    return 'RESETTING';
  }
  return 'LOCKED';
}

const actionPolicy = {
  trigger: { command: 'trigger', permission: 'interlock:view' },
  receipt: { command: 'receipt', permission: 'interlock:view' },
  requestReset: { command: 'requestReset', permission: 'interlock:reset' },
  approve: { command: 'approve', permission: 'interlock:approve' },
  restore: { command: 'restore', permission: 'interlock:reset' },
  requestOverride: { command: 'requestOverride', permission: 'interlock:request-override' },
} as const;

function availableActions(
  state: DemoRootState,
  interlock: Interlock,
): InterlockActionAvailability {
  return Object.fromEntries(
    Object.entries(actionPolicy).map(([action, policy]) => {
      const transition = transitionState({
        machineId: 'DO-010',
        current: interlock.status,
        command: policy.command,
      });
      const decision = authorize({
        session: state.session,
        permission: policy.permission,
        objectScope: { type: 'AREA', value: 'AREA-A' },
      });
      return [action, transition.ok && decision.allow];
    }),
  ) as unknown as InterlockActionAvailability;
}

function summaryValue(value: unknown): string {
  if (value === null) return '未填写';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.map(summaryValue).join('、');
  if (typeof value === 'string' || typeof value === 'number') {
    return String(value);
  }
  return JSON.stringify(value) ?? String(value);
}

function resetRequestSummary(interlock: Interlock): string[] {
  const labels: Readonly<Record<string, string>> = {
    requested: '已申请',
    checklist: '检查项',
    note: '备注',
  };
  return Object.entries(interlock.resetRequest)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${labels[key] ?? '申请信息'}：${summaryValue(value)}`);
}

function approvalSummary(interlock: Interlock): string[] {
  return interlock.approvalChain.map((actorId, index) => `审批节点 ${index + 1}：${actorId}`);
}

function sourceLabels(context: InterlockQueryContext): string[] {
  return [
    context.from ? `来源模块：${businessLabel(context.from)}` : undefined,
    context.exceptionId ? `异常上下文：${context.exceptionId}` : undefined,
    context.scenarioId ? `场景：${context.scenarioId}` : undefined,
  ].filter((value): value is string => value !== undefined);
}

function returnExceptionUrl(context: InterlockQueryContext): string {
  const query = new URLSearchParams();
  if (context.exceptionId) query.set('exceptionId', context.exceptionId);
  if (context.scenarioId) query.set('scenarioId', context.scenarioId);
  query.set('from', 'safety-interlock');
  return `/monitor/exceptions?${query.toString()}`;
}

function matchesContext(interlock: Interlock, context: InterlockQueryContext): boolean {
  return (context.status === undefined || interlock.status === context.status)
    && (context.actionLevel === undefined || interlock.actionLevel === context.actionLevel)
    && (context.riskType === undefined || interlock.riskType === context.riskType)
    && (context.receiptStatus === undefined || interlock.receiptStatus === context.receiptStatus);
}

function projectItem(state: DemoRootState, interlock: Interlock): InterlockLedgerItem {
  const strict = strictInterlock(interlock);
  const forceStop = strict.actionLevel === 'FORCE_STOP';
  return {
    interlock: strict,
    progressState: progressState(strict),
    stateFlow: interlockStatuses.map((status) => ({ status, current: status === strict.status })),
    forceStop,
    ...(forceStop ? { forceStopWarning: FORCE_STOP_WARNING } : {}),
    receiptFailed: strict.receiptStatus === 'FAILED',
    resetRequested: strict.resetRequest.requested === true,
    resetRequestSummary: resetRequestSummary(strict),
    approvalSummary: approvalSummary(strict),
    availableActions: availableActions(state, strict),
  };
}

function zeroKpis(): InterlockKpis {
  return {
    locked: 0,
    pendingApproval: 0,
    approved: 0,
    restored: 0,
    forceStop: 0,
    receiptFailed: 0,
  };
}

function kpisFromItems(items: readonly InterlockLedgerItem[]): InterlockKpis {
  return {
    locked: items.filter(({ interlock }) => interlock.status === 'LOCKED').length,
    pendingApproval: items.filter(({ interlock }) =>
      interlock.status === 'RESET_REQUESTED' || interlock.status === 'OVERRIDE_PENDING',
    ).length,
    approved: items.filter(({ interlock }) => interlock.status === 'APPROVED').length,
    restored: items.filter(({ interlock }) => interlock.status === 'RESTORED').length,
    forceStop: items.filter(({ forceStop }) => forceStop).length,
    receiptFailed: items.filter(({ receiptFailed }) => receiptFailed).length,
  };
}

export function selectSafetyInterlockBoard(
  state: DemoRootState,
  context: InterlockQueryContext,
): SafetyInterlockBoard {
  const items = canReadAreaA(state)
    ? state.interlock.interlocks
      .filter((interlock) => matchesContext(interlock, context))
      .sort((left, right) => left.interlockNo.localeCompare(right.interlockNo))
      .map((interlock) => projectItem(state, interlock))
    : [];

  return deepFreeze({
    items,
    kpis: kpisFromItems(items),
    sourceContext: { ...context },
    sourceContextLabels: sourceLabels(context),
    sourceDisclosure: INTERLOCK_SOURCE_DISCLOSURE,
    returnExceptionUrl: returnExceptionUrl(context),
  });
}

export function selectInterlockKpis(state: DemoRootState): InterlockKpis {
  if (!canReadAreaA(state)) return deepFreeze(zeroKpis());
  return deepFreeze(kpisFromItems(state.interlock.interlocks.map((interlock) =>
    projectItem(state, interlock),
  )));
}
