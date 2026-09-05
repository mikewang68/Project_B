import type { Appointment, Plan, PublicErrorCode, Resource, Track } from '../../contracts';
import { businessLabel } from '../../presentation/businessCopy';
import type { DemoRootState } from '../../stores';
import { planExceptionFilterValues } from './types';
import type {
  InterfaceHealthViewModel,
  OpenRiskViewModel,
  OverviewKpisViewModel,
  PlanDetailsViewModel,
  PlanEntryQuery,
  PlanEntryWorkflowState,
  PlanDetailSummaryItemViewModel,
  PlanFieldSourceViewModel,
  PlanLedgerRowViewModel,
  PlanLedgerViewModel,
  PlanValidationIssueViewModel,
  VisibleYardObjectsViewModel,
} from './types';

const PLAN_WORK_AREA = 'AREA-A';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function cloneFrozen<T>(value: T): T {
  return deepFreeze(structuredClone(value));
}

function sessionAllowsArea(state: DemoRootState, workArea: string): boolean {
  return (
    state.session.dataScope.includes('*') ||
    state.session.dataScope.includes('GLOBAL') ||
    state.session.dataScope.includes(workArea)
  );
}

function queryAllowsPlans(state: DemoRootState, query: PlanEntryQuery): boolean {
  return query.workArea === PLAN_WORK_AREA && sessionAllowsArea(state, PLAN_WORK_AREA);
}

function resolvedFieldsFor(
  workflow: PlanEntryWorkflowState,
  planId: string,
): ReadonlySet<string> {
  return new Set(workflow.resolvedFields[planId] ?? []);
}

function projectedMissingFields(
  state: DemoRootState,
  plan: Plan,
  workflow: PlanEntryWorkflowState,
): string[] {
  const resolved = resolvedFieldsFor(workflow, plan.id);
  const scenarioFields = state.scenario.activeFault.mutations
    .filter(({ objectId }) => objectId === plan.id)
    .flatMap(({ omitFields }) => omitFields ?? [])
    .filter((field) => !resolved.has(field));
  return [...new Set([...plan.missingFields.filter((field) => !resolved.has(field)), ...scenarioFields])]
    .sort();
}

function syncStatus(
  state: DemoRootState,
  workflow: PlanEntryWorkflowState,
): PlanLedgerRowViewModel['syncStatus'] {
  if (workflow.circuitOpen) return 'CIRCUIT_OPEN';
  if (state.scenario.activeFault.type === 'INTERFACE_TIMEOUT') return 'DEGRADED';
  return 'SYNCED';
}

function formatTimestamp(value: string): string {
  return value.replace('T', ' ').slice(0, 16);
}

function planStatusTone(status: Plan['status']): PlanLedgerRowViewModel['statusTone'] {
  if (status === 'CONFIRMED' || status === 'DECOMPOSED') return 'success';
  if (status === 'BLOCKED') return 'error';
  if (status === 'PENDING_CONFIRM' || status === 'ADJUSTED') return 'warning';
  if (status === 'CANCELLED') return 'default';
  return 'processing';
}

function planProgressPresentation(
  status: Plan['status'],
): PlanLedgerRowViewModel['progress'] {
  switch (status) {
    case 'RECEIVED':
      return { percent: 25, label: '已接收', status: 'normal' };
    case 'VALIDATING':
      return { percent: 40, label: '校验中', status: 'normal' };
    case 'PENDING_CONFIRM':
      return { percent: 75, label: '等待确认', status: 'normal' };
    case 'CONFIRMED':
      return { percent: 100, label: '已确认', status: 'success' };
    case 'DECOMPOSED':
      return { percent: 100, label: '已拆解', status: 'success' };
    case 'BLOCKED':
      return { percent: 50, label: '流程阻断', status: 'exception' };
    case 'CANCELLED':
      return { percent: 0, label: '已取消', status: 'normal' };
    case 'ADJUSTED':
      return { percent: 70, label: '已调整', status: 'normal' };
  }
}

function planRiskPresentation(
  status: Plan['status'],
  missingFields: readonly string[],
  conflicts: Plan['conflicts'],
): PlanLedgerRowViewModel['risk'] {
  if (missingFields.length > 0) {
    return {
      level: 'CRITICAL',
      label: `缺少字段：${missingFields.join('、')}`,
      tone: 'error',
    };
  }
  if (conflicts.length > 0) {
    return { level: 'CRITICAL', label: '存在数据冲突', tone: 'error' };
  }
  if (status === 'BLOCKED') {
    return { level: 'CRITICAL', label: '计划阻断', tone: 'error' };
  }
  return { level: 'NONE', label: '正常', tone: 'default' };
}

function trackStatusPresentation(status: Track['occupyStatus']): Readonly<{
  label: string;
  tone: VisibleYardObjectsViewModel['tracks'][number]['statusTone'];
  markerLabel?: string;
}> {
  switch (status) {
    case 'FREE':
      return { label: '空闲', tone: 'success' };
    case 'OCCUPIED':
      return { label: '占用', tone: 'processing', markerLabel: '当前占用' };
    case 'RELEASING':
      return { label: '释放中', tone: 'warning', markerLabel: '正在释放' };
    case 'BLOCKED':
      return { label: '封锁', tone: 'error', markerLabel: '股道封锁' };
  }
}

function projectTrack(
  track: Track,
  index: number,
): VisibleYardObjectsViewModel['tracks'][number] {
  const presentation = trackStatusPresentation(track.occupyStatus);
  return {
    id: track.id,
    trackNo: track.trackNo,
    laneOrder: index + 1,
    occupyStatus: track.occupyStatus,
    occupyStatusLabel: presentation.label,
    statusTone: presentation.tone,
    compatibleCargoSummary: track.compatibleCargoTypes.map(businessLabel).join(' / '),
    occupancyMarker: presentation.markerLabel
      ? { label: presentation.markerLabel, tone: presentation.tone }
      : undefined,
  };
}

function markerPosition(index: number, count: number): number {
  if (count <= 1) return 50;
  return Math.round(8 + (index * 84) / (count - 1));
}

function resourceStatusTone(
  status: Resource['status'],
): VisibleYardObjectsViewModel['resourceMarkers'][number]['statusTone'] {
  if (status === 'AVAILABLE') return 'success';
  if (status === 'BUSY') return 'processing';
  if (status === 'OFFLINE') return 'error';
  if (status === 'MAINTENANCE') return 'warning';
  return 'default';
}

function appointmentStatusTone(
  status: Appointment['status'],
): VisibleYardObjectsViewModel['appointmentMarkers'][number]['statusTone'] {
  if (status === 'REJECTED' || status === 'NEED_FIX' || status === 'EXCEPTION') return 'error';
  if (status === 'QUEUED' || status === 'SUBMITTED') return 'warning';
  if (status === 'RELEASED' || status === 'EXITED') return 'success';
  if (status === 'DRAFT') return 'default';
  return 'processing';
}

function planExceptionTypes(
  state: DemoRootState,
  plan: Plan,
  missingFields: readonly string[],
): readonly string[] {
  const projectedTypes = new Set<string>();
  if (missingFields.length > 0) projectedTypes.add('VALIDATION_MISSING_FIELD');
  if (plan.conflicts.length > 0) projectedTypes.add('DATA_CONFLICT');

  const scenario = state.scenario.scenarios.find(
    ({ id }) => id === state.scenario.activeScenarioId,
  );
  const faultTargetsPlan =
    scenario?.seedRefs.includes(plan.id) === true ||
    state.scenario.activeFault.mutations.some(({ objectId }) => objectId === plan.id);
  if (faultTargetsPlan && state.scenario.activeFault.type !== 'NONE') {
    projectedTypes.add(state.scenario.activeFault.type);
  }

  return deepFreeze(
    planExceptionFilterValues.filter((type) => projectedTypes.has(type)),
  );
}

function projectPlan(
  state: DemoRootState,
  plan: Plan,
  workflow: PlanEntryWorkflowState,
): PlanLedgerRowViewModel {
  const missingFields = projectedMissingFields(state, plan, workflow);
  const trackNo = missingFields.includes('trackNo') ? undefined : plan.trackNo;
  const validationStatus =
    missingFields.length > 0 ? 'MISSING_FIELD' : plan.conflicts.length > 0 ? 'CONFLICT' : 'VALID';
  const exceptionTypes = planExceptionTypes(state, plan, missingFields);

  return deepFreeze({
    id: plan.id,
    planBatchNo: plan.planBatchNo,
    trainNo: plan.trainNo,
    arrivalDepartureTime: plan.arrivalDepartureTime,
    trackNo,
    cargoType: plan.cargoType,
    status: plan.status,
    statusTone: planStatusTone(plan.status),
    progress: planProgressPresentation(plan.status),
    risk: planRiskPresentation(plan.status, missingFields, plan.conflicts),
    sourceSystem: plan.sourceSystem,
    sourceTime: plan.sourceTime,
    missingFields: deepFreeze([...missingFields]),
    conflicts: cloneFrozen(plan.conflicts),
    exceptionTypes,
    validationStatus,
    syncStatus: syncStatus(state, workflow),
    version: plan.version,
    updatedAt: plan.updatedAt,
    formattedUpdatedAt: formatTimestamp(plan.updatedAt),
  });
}

function plansForQuery(state: DemoRootState, query: PlanEntryQuery): readonly Plan[] {
  if (!queryAllowsPlans(state, query)) return [];
  return state.plan.plans.filter(
    ({ arrivalDepartureTime }) => arrivalDepartureTime.startsWith(query.date),
  );
}

function comparePlans(
  left: PlanLedgerRowViewModel,
  right: PlanLedgerRowViewModel,
  sort: PlanEntryQuery['sort'],
): number {
  if (sort === 'status:asc') {
    return left.status.localeCompare(right.status) || left.id.localeCompare(right.id);
  }
  const direction = sort === 'updatedAt:asc' ? 1 : -1;
  return left.updatedAt.localeCompare(right.updatedAt) * direction || left.id.localeCompare(right.id);
}

function issueErrorCode(state: DemoRootState): PublicErrorCode | undefined {
  return state.scenario.activeFault.errorCode ?? undefined;
}

export function selectValidationIssues(
  state: DemoRootState,
  query: PlanEntryQuery,
  workflow: PlanEntryWorkflowState,
): readonly PlanValidationIssueViewModel[] {
  const plans = plansForQuery(state, query);
  if (plans.length === 0) return deepFreeze([]);
  const errorCode = issueErrorCode(state);
  if (!errorCode) return deepFreeze([]);
  const visiblePlanIds = new Set(plans.map(({ id }) => id));
  const issues = state.scenario.activeFault.mutations.flatMap(({ objectId, omitFields }) => {
    if (!visiblePlanIds.has(objectId)) return [];
    const resolved = resolvedFieldsFor(workflow, objectId);
    return (omitFields ?? [])
      .filter((field) => !resolved.has(field))
      .map((field) => ({ planId: objectId, field, errorCode }));
  });
  return deepFreeze(issues);
}

export function selectPlanLedger(
  state: DemoRootState,
  query: PlanEntryQuery,
  workflow: PlanEntryWorkflowState,
): PlanLedgerViewModel {
  const rows = plansForQuery(state, query)
    .filter(({ planBatchNo }) => !query.planBatchNo || planBatchNo.includes(query.planBatchNo))
    .filter(({ trainNo }) => !query.trainNo || trainNo.includes(query.trainNo))
    .filter(({ status }) => query.statuses.length === 0 || query.statuses.includes(status))
    .map((plan) => projectPlan(state, plan, workflow))
    .filter(({ exceptionTypes }) => {
      if (query.exceptionTypes.length === 0) return true;
      return query.exceptionTypes.some((type) => exceptionTypes.includes(type));
    })
    .sort((left, right) => comparePlans(left, right, query.sort));
  const start = (query.page - 1) * query.pageSize;

  return deepFreeze({
    items: rows.slice(start, start + query.pageSize),
    total: rows.length,
    page: query.page,
    pageSize: query.pageSize,
  });
}

export function selectOverviewKpis(
  state: DemoRootState,
  query: PlanEntryQuery,
  workflow: PlanEntryWorkflowState,
): OverviewKpisViewModel {
  if (!queryAllowsPlans(state, query)) {
    return deepFreeze({
      totalPlans: 0,
      pendingConfirmPlans: 0,
      confirmedPlans: 0,
      blockedPlans: 0,
      completedWorkOrders: 0,
      waitingVehicles: 0,
      availableResourceRate: 0,
      openExceptions: 0,
    });
  }

  const plans = state.plan.plans.filter(({ arrivalDepartureTime }) =>
    arrivalDepartureTime.startsWith(query.date),
  );
  const planIds = new Set(plans.map(({ id }) => id));
  const resources = state.resource.resources.filter(({ workArea }) => workArea === query.workArea);
  const availableResources = resources.filter(({ status }) => status === 'AVAILABLE').length;
  const issues = selectValidationIssues(state, query, workflow);
  const blockedPlanIds = new Set([
    ...plans.filter(({ status }) => status === 'BLOCKED').map(({ id }) => id),
    ...issues.map(({ planId }) => planId),
  ]);

  return deepFreeze({
    totalPlans: plans.length,
    pendingConfirmPlans: plans.filter(({ status }) => status === 'PENDING_CONFIRM').length,
    confirmedPlans: plans.filter(({ status }) => status === 'CONFIRMED').length,
    blockedPlans: blockedPlanIds.size,
    completedWorkOrders: state.workOrder.workOrders.filter(
      ({ planId, status }) => planIds.has(planId) && status === 'COMPLETED',
    ).length,
    waitingVehicles: state.vehicle.appointments.filter(({ status }) => status === 'QUEUED').length,
    availableResourceRate:
      resources.length === 0 ? 0 : Math.round((availableResources / resources.length) * 100),
    openExceptions: state.exception.exceptions.filter(({ status }) => status !== 'CLOSED').length,
  });
}

export function selectVisibleYardObjects(
  state: DemoRootState,
  query: PlanEntryQuery,
): VisibleYardObjectsViewModel {
  if (!sessionAllowsArea(state, query.workArea)) {
    return deepFreeze({ tracks: [], resourceMarkers: [], appointmentMarkers: [] });
  }

  const tracks = [...state.resource.tracks]
    .sort((left, right) => left.trackNo.localeCompare(right.trackNo) || left.id.localeCompare(right.id))
    .map((track, index) => projectTrack(track, index));
  const visibleResources = state.resource.resources
    .filter(({ workArea }) => workArea === query.workArea)
    .sort((left, right) => left.location.localeCompare(right.location) || left.id.localeCompare(right.id));
  const appointments = [...state.vehicle.appointments].sort(
    (left, right) => left.queueNo.localeCompare(right.queueNo) || left.id.localeCompare(right.id),
  );

  return deepFreeze({
    tracks,
    resourceMarkers: visibleResources.map((resource, index) => ({
      id: resource.id,
      laneOrder: index + 1,
      positionPercent: markerPosition(index, visibleResources.length),
      resourceType: resource.resourceType,
      status: resource.status,
      statusTone: resourceStatusTone(resource.status),
      location: resource.location,
    })),
    appointmentMarkers: appointments.map((appointment, index) => ({
      id: appointment.id,
      laneOrder: index + 1,
      vehicleNo: appointment.vehicleNo,
      status: appointment.status,
      statusTone: appointmentStatusTone(appointment.status),
      queueNo: appointment.queueNo,
      gateStatus: appointment.gateStatus,
    })),
  });
}

export function selectOpenRisks(
  state: DemoRootState,
  query: PlanEntryQuery,
): readonly OpenRiskViewModel[] {
  if (!sessionAllowsArea(state, query.workArea)) return deepFreeze([]);
  const risks: OpenRiskViewModel[] = [];
  const fault = state.scenario.activeFault;
  if (fault.type !== 'NONE') {
    risks.push({
      id: `FAULT-${state.scenario.activeScenarioId}`,
      source: 'SCENARIO',
      type: fault.type,
      status: 'OPEN',
      level: fault.type === 'INTERLOCK_FORCE_STOP' ? 'CRITICAL' : 'MAJOR',
      ...(fault.errorCode ? { errorCode: fault.errorCode } : {}),
      ...(fault.mutations[0]?.objectId ? { objectId: fault.mutations[0].objectId } : {}),
    });
  }
  for (const exception of state.exception.exceptions) {
    if (exception.status === 'CLOSED') continue;
    if (query.exceptionTypes.length > 0 && !query.exceptionTypes.includes(exception.type)) continue;
    risks.push({
      id: exception.id,
      source: 'EXCEPTION',
      type: exception.type,
      status: exception.status,
      level: exception.level,
    });
  }
  for (const interlock of state.interlock.interlocks.filter(({ status }) => status !== 'RESTORED')) {
    risks.push({
      id: interlock.id,
      source: 'INTERLOCK',
      type: interlock.riskType,
      status: interlock.status,
      level: interlock.actionLevel,
    });
  }
  for (const packet of state.offline.packets.filter(({ mergeStatus }) =>
    ['CONFLICT', 'REJECTED', 'RETRY'].includes(mergeStatus),
  )) {
    risks.push({
      id: packet.id,
      source: 'OFFLINE',
      type: 'OFFLINE_PACKET',
      status: packet.mergeStatus,
      level: packet.mergeStatus === 'CONFLICT' ? 'MAJOR' : 'MINOR',
      objectId: packet.workOrderNo,
    });
  }
  return deepFreeze(risks);
}

export function selectInterfaceHealth(
  state: DemoRootState,
  workflow: PlanEntryWorkflowState,
): InterfaceHealthViewModel {
  const fault = state.scenario.activeFault;
  const status = workflow.circuitOpen
    ? 'CIRCUIT_OPEN'
    : fault.type === 'INTERFACE_TIMEOUT'
      ? 'UNAVAILABLE'
      : fault.type === 'NONE'
        ? 'HEALTHY'
        : 'DEGRADED';
  return deepFreeze({
    status,
    ...(fault.errorCode ? { errorCode: fault.errorCode } : {}),
    retryCount: workflow.retryCount,
    circuitOpen: workflow.circuitOpen,
    ...(workflow.lastSuccessAt ? { lastSuccessAt: workflow.lastSuccessAt } : {}),
    recoveryVisible:
      state.scenario.activeScenarioId === 'SCN-03' &&
      state.session.roleCode === 'INTERFACE_OPS',
  });
}

function fieldSources(row: PlanLedgerRowViewModel): readonly PlanFieldSourceViewModel[] {
  const values: Array<[string, string | undefined]> = [
    ['planBatchNo', row.planBatchNo],
    ['trainNo', row.trainNo],
    ['arrivalDepartureTime', row.arrivalDepartureTime],
    ['trackNo', row.trackNo],
    ['cargoType', row.cargoType],
    ['status', row.status],
  ];
  return deepFreeze(
    values.map(([field, value]) => ({ field, source: row.sourceSystem, ...(value ? { value } : {}) })),
  );
}

function detailSummary(
  row: PlanLedgerRowViewModel,
): readonly PlanDetailSummaryItemViewModel[] {
  return deepFreeze([
    { label: '计划编号', value: row.id },
    { label: '计划批次', value: row.planBatchNo },
    { label: '车次', value: row.trainNo },
    { label: '股道', value: row.trackNo ?? '待补录' },
    { label: '货类', value: row.cargoType },
    { label: '状态', value: row.status },
  ]);
}

function maskedRawSummary(row: PlanLedgerRowViewModel): string {
  return JSON.stringify(
    {
      id: row.id,
      planBatchNo: row.planBatchNo,
      trainNo: row.trainNo,
      arrivalDepartureTime: row.arrivalDepartureTime,
      trackNo: row.trackNo ?? '***',
      cargoType: row.cargoType,
      sourceSystem: row.sourceSystem,
      sourceTime: row.sourceTime,
      version: row.version,
    },
    null,
    2,
  );
}

function workflowStepIndex(status: PlanLedgerRowViewModel['status']): number {
  if (status === 'VALIDATING') return 1;
  if (status === 'BLOCKED' || status === 'ADJUSTED') return 2;
  if (status === 'PENDING_CONFIRM') return 3;
  if (status === 'CONFIRMED' || status === 'DECOMPOSED') return 4;
  return 0;
}

function retryHistory(
  workflow: PlanEntryWorkflowState,
  errorCode?: PublicErrorCode,
): PlanDetailsViewModel['retryHistory'] {
  return deepFreeze(
    Array.from({ length: workflow.retryCount }, (_, index) => ({
      attempt: index + 1,
      status: 'FAILED' as const,
      ...(errorCode ? { errorCode } : {}),
    })),
  );
}

function supplementProjection(
  state: DemoRootState,
  row: PlanLedgerRowViewModel,
): PlanDetailsViewModel['supplement'] {
  const needsSupplement =
    row.validationStatus !== 'VALID' ||
    row.missingFields.length > 0 ||
    row.conflicts.length > 0;
  const visible = state.session.roleCode === 'DISPATCHER' && needsSupplement;
  const reviewerOptions = visible
    ? state.configAudit.userRoles
        .filter(
          ({ id, roleCode, status }) =>
            id !== state.session.actorId &&
            roleCode === 'DISPATCHER' &&
            status === 'ACTIVE',
        )
        .map(({ id }) => ({ value: id, label: id }))
    : [];

  return deepFreeze({
    visible,
    actorId: state.session.actorId,
    reviewerOptions,
  });
}

export function selectPlanDetails(
  state: DemoRootState,
  query: PlanEntryQuery,
  planId: string,
  workflow: PlanEntryWorkflowState,
): PlanDetailsViewModel | undefined {
  if (!queryAllowsPlans(state, query)) return undefined;
  const plan = state.plan.plans.find(({ id }) => id === planId);
  if (!plan) return undefined;
  const row = projectPlan(state, plan, workflow);
  const errorCode = issueErrorCode(state);
  const validationIssues = errorCode
    ? row.missingFields.map((field) => ({ planId, field, errorCode }))
    : [];

  return deepFreeze({
    ...row,
    summary: detailSummary(row),
    maskedRawSummary: maskedRawSummary(row),
    workflowStepIndex: workflowStepIndex(row.status),
    waybills: cloneFrozen(
      state.plan.waybills.filter(({ cargoType }) => cargoType === plan.cargoType),
    ),
    validationIssues,
    fieldSources: fieldSources(row),
    retryHistory: retryHistory(workflow, errorCode),
    changeHistory: cloneFrozen(
      state.configAudit.commandAudit.filter(({ record }) => record.objectId === planId),
    ),
    supplement: supplementProjection(state, row),
  });
}
