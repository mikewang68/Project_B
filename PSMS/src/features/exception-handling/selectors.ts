import { authorize } from '../../auth';
import { exceptionStatuses, type DispatchException } from '../../contracts';
import { transitionState } from '../../commands';
import type { DemoRootState } from '../../stores';
import { businessLabel } from '../../presentation/businessCopy';
import { EXCEPTION_SOURCE_DISCLOSURE } from './constants';
import type {
  ExceptionActionAvailability,
  ExceptionHandlingBoard,
  ExceptionKpis,
  ExceptionLedgerItem,
  ExceptionProgressState,
  ExceptionQueryContext,
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

function strictException(exception: DispatchException): DispatchException {
  return {
    id: exception.id,
    exceptionNo: exception.exceptionNo,
    type: exception.type,
    level: exception.level,
    status: exception.status,
    owner: exception.owner,
    dueAt: exception.dueAt,
    evidence: [...exception.evidence],
    version: exception.version,
    createdAt: exception.createdAt,
    updatedAt: exception.updatedAt,
  };
}

function progressState(exception: DispatchException): ExceptionProgressState {
  if (exception.status === 'PENDING_REVIEW') return 'REVIEW';
  if (exception.status === 'CLOSED') return 'CLOSED';
  if (exception.status === 'ACKNOWLEDGED' || exception.status === 'HANDLING') return 'HANDLING';
  return 'OPEN_QUEUE';
}

function dueState(
  exception: DispatchException,
  demoTime: string,
): ExceptionLedgerItem['dueState'] {
  if (exception.status === 'CLOSED') return 'CLOSED';
  const due = Date.parse(exception.dueAt);
  const now = Date.parse(demoTime);
  return Number.isFinite(due) && Number.isFinite(now) && due < now ? 'OVERDUE' : 'DUE';
}

const commandPermissions = {
  ack: 'exception:ack',
  assign: 'exception:handle',
  handle: 'exception:handle',
  review: 'exception:review',
  close: 'exception:close',
  reopen: 'exception:reopen',
} as const;

function availableActions(
  state: DemoRootState,
  exception: DispatchException,
): ExceptionActionAvailability {
  return Object.fromEntries(
    Object.entries(commandPermissions).map(([command, permission]) => {
      const transition = transitionState({
        machineId: 'DO-009',
        current: exception.status,
        command,
      });
      const decision = authorize({
        session: state.session,
        permission,
        objectScope: { type: 'AREA', value: 'AREA-A' },
      });
      return [command, exception.type !== 'INTERLOCK' && transition.ok && decision.allow];
    }),
  ) as unknown as ExceptionActionAvailability;
}

function sourceLabels(context: ExceptionQueryContext): string[] {
  return [
    context.from ? `来源模块：${businessLabel(context.from)}` : undefined,
    context.workOrderId ? `工单上下文：${context.workOrderId}` : undefined,
    context.planId ? `计划上下文：${context.planId}` : undefined,
    context.scenarioId ? `场景：${context.scenarioId}` : undefined,
  ].filter((value): value is string => value !== undefined);
}

function matchesContext(exception: DispatchException, context: ExceptionQueryContext): boolean {
  return (context.status === undefined || exception.status === context.status)
    && (context.type === undefined || exception.type === context.type)
    && (context.level === undefined || exception.level === context.level)
    && (context.owner === undefined || exception.owner === context.owner);
}

function projectItem(state: DemoRootState, exception: DispatchException): ExceptionLedgerItem {
  const strict = strictException(exception);
  return {
    exception: strict,
    evidenceCount: strict.evidence.length,
    dueState: dueState(strict, state.session.demoTime),
    progressState: progressState(strict),
    stateFlow: exceptionStatuses.map((status) => ({ status, current: status === strict.status })),
    ...(strict.type === 'INTERLOCK'
      ? {
          interlockEntryUrl: `/safety/interlocks?exceptionId=${encodeURIComponent(strict.id)}`
            + `&scenarioId=${encodeURIComponent(state.scenario.activeScenarioId)}`
            + '&from=exception-handling',
        }
      : {}),
    availableActions: availableActions(state, strict),
  };
}

function zeroKpis(): ExceptionKpis {
  return {
    unacknowledged: 0,
    handling: 0,
    pendingReview: 0,
    closed: 0,
    overdue: 0,
    interlock: 0,
  };
}

function kpisFromItems(items: readonly ExceptionLedgerItem[]): ExceptionKpis {
  return {
    unacknowledged: items.filter(({ exception }) =>
      exception.status === 'OPEN' || exception.status === 'REOPENED',
    ).length,
    handling: items.filter(({ exception }) =>
      exception.status === 'ACKNOWLEDGED' || exception.status === 'HANDLING',
    ).length,
    pendingReview: items.filter(({ exception }) => exception.status === 'PENDING_REVIEW').length,
    closed: items.filter(({ exception }) => exception.status === 'CLOSED').length,
    overdue: items.filter(({ dueState: state }) => state === 'OVERDUE').length,
    interlock: items.filter(({ exception }) => exception.type === 'INTERLOCK').length,
  };
}

export function selectExceptionHandlingBoard(
  state: DemoRootState,
  context: ExceptionQueryContext,
): ExceptionHandlingBoard {
  const items = canReadAreaA(state)
    ? state.exception.exceptions
      .filter((exception) => matchesContext(exception, context))
      .sort((left, right) => left.exceptionNo.localeCompare(right.exceptionNo))
      .map((exception) => projectItem(state, exception))
    : [];

  return deepFreeze({
    items,
    kpis: kpisFromItems(items),
    sourceContext: { ...context },
    sourceContextLabels: sourceLabels(context),
    sourceDisclosure: EXCEPTION_SOURCE_DISCLOSURE,
  });
}

export function selectExceptionKpis(state: DemoRootState): ExceptionKpis {
  if (!canReadAreaA(state)) return deepFreeze(zeroKpis());
  return deepFreeze(kpisFromItems(state.exception.exceptions.map((exception) =>
    projectItem(state, exception),
  )));
}
