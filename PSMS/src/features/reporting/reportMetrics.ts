import {
  actionLevels,
  exceptionLevels,
  exceptionTypes,
  mergeStatuses,
  planStatuses,
  workOrderStatuses,
} from '../../contracts';
import { recommendationDraftSchema } from '../recommendation/schemas';
import type { DemoRootState } from '../../stores';
import {
  REPORT_DISCLOSURE,
  type ReportDistribution,
  type ReportDistributions,
  type ReportFlatMetrics,
  type ReportKpis,
  type ReportMetricDefinition,
  type ReportMetricSnapshot,
  type ReportRate,
  type ReportRateKey,
} from './reportTypes';

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

const planStatusLabels: Record<(typeof planStatuses)[number], string> = {
  RECEIVED: '已接收', VALIDATING: '校验中', PENDING_CONFIRM: '待确认', CONFIRMED: '已确认',
  DECOMPOSED: '已拆解', BLOCKED: '已阻塞', CANCELLED: '已取消', ADJUSTED: '已调整',
};

const workOrderStatusLabels: Record<(typeof workOrderStatuses)[number], string> = {
  DRAFT: '草稿', READY: '就绪', DISPATCHED: '已派工', ACKNOWLEDGED: '已接收',
  IN_PROGRESS: '执行中', COMPLETED: '已完成', PAUSED: '已暂停', BLOCKED: '已阻塞',
  FAILED: '失败', CANCELLED: '已取消',
};

const exceptionLevelLabels: Record<(typeof exceptionLevels)[number], string> = {
  INFO: '提示', MINOR: '一般', MAJOR: '重大', CRITICAL: '紧急',
};

const exceptionTypeLabels: Record<(typeof exceptionTypes)[number], string> = {
  DEVICE_OFFLINE: '设备离线', DATA_CONFLICT: '数据冲突', INTERLOCK: '安全联锁',
  TIMEOUT: '超时', QUALITY: '质量',
};

const actionLevelLabels: Record<(typeof actionLevels)[number], string> = {
  WARN: '告警', PAUSE: '暂停', FORCE_STOP: '强停',
};

const mergeStatusLabels: Record<(typeof mergeStatuses)[number], string> = {
  CACHED: '已缓存', PENDING_UPLOAD: '待上传', VALIDATING: '校验中', MERGED: '已合并',
  CONFLICT: '冲突', REJECTED: '已驳回', RETRY: '重试',
};

function distribution<T extends string>(
  key: string,
  label: string,
  source: string,
  values: readonly T[],
  labels: Record<T, string>,
  observed: readonly T[],
): ReportDistribution {
  return {
    key,
    label,
    source,
    items: values.map((value) => ({
      key: value,
      label: labels[value],
      value: observed.filter((candidate) => candidate === value).length,
    })),
  };
}

function percent(numerator: number, denominator: number): number {
  return denominator === 0 ? 0 : Math.round((numerator / denominator) * 100);
}

const definitions: readonly ReportMetricDefinition[] = [
  { key: 'planTotal', label: '计划总数', source: 'DO-001 生产计划', formula: '计划对象总数' },
  { key: 'confirmedPlanCount', label: '已确认计划数', source: 'DO-001 生产计划状态', formula: '已确认或已拆解的计划数量' },
  { key: 'appliedRecommendationCount', label: '推荐已应用数', source: 'C05 生产准备建议草稿', formula: '结构校验有效且状态为已确认的推荐草稿数量' },
  { key: 'generatedWorkOrderCount', label: '已生成任务数', source: 'DO-005 作业工单', formula: '作业单对象总数' },
  { key: 'dispatchedWorkOrderCount', label: '已派工任务数', source: 'DO-005 作业工单状态', formula: '已派工、已确认、执行中、已完成或已暂停的任务数量' },
  { key: 'exceptionCount', label: '异常数量', source: 'DO-009 调度异常', formula: '异常对象总数' },
  { key: 'interlockCount', label: '安全联锁数量', source: 'DO-010 安全联锁', formula: '安全联锁对象总数' },
  { key: 'mergedOfflinePacketCount', label: '离线包已合并数量', source: 'DO-011 离线包合并状态', formula: '状态为已合并的离线包数量' },
  { key: 'planConfirmationRate', label: '计划确认率', source: 'DO-001 生产计划状态', formula: '已确认计划数 / 计划总数；分母为 0 时取 0%' },
  { key: 'taskDecompositionRate', label: '任务拆解完成率', source: 'DO-001 生产计划与 DO-005 作业工单计划编号', formula: '有关联作业单的不同计划数 / 计划总数；分母为 0 时取 0%' },
  { key: 'dispatchRate', label: '派工完成率', source: 'DO-005 作业工单状态', formula: '已派工任务数 / 已生成任务数；分母为 0 时取 0%' },
  { key: 'exceptionClosureRate', label: '异常关闭率', source: 'DO-009 调度异常状态', formula: '已关闭异常数量 / 异常总数；分母为 0 时取 0%' },
  { key: 'offlineMergeRate', label: '离线同步合并率', source: 'DO-011 离线包合并状态', formula: '已合并离线包数量 / 离线包总数；分母为 0 时取 0%' },
];

function rate(
  key: ReportRateKey,
  label: string,
  numerator: number,
  denominator: number,
  source: string,
  formula: string,
): ReportRate {
  return { key, label, value: percent(numerator, denominator), numerator, denominator, source, formula };
}

export function deriveReportMetrics(state: DemoRootState): ReportMetricSnapshot {
  const visible = canReadAreaA(state);
  const plans = visible ? state.plan.plans : [];
  const workOrders = visible ? state.workOrder.workOrders : [];
  const exceptions = visible ? state.exception.exceptions : [];
  const interlocks = visible ? state.interlock.interlocks : [];
  const packets = visible ? state.offline.packets : [];
  const drafts = visible ? Object.values(state.recommendation.drafts) : [];
  const confirmedPlanCount = plans.filter(({ status }) =>
    status === 'CONFIRMED' || status === 'DECOMPOSED').length;
  const appliedRecommendationCount = drafts.filter((candidate) => {
    const parsed = recommendationDraftSchema.safeParse(candidate);
    return parsed.success && parsed.data.status === 'CONFIRMED';
  }).length;
  const dispatchedStatuses = new Set([
    'DISPATCHED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'COMPLETED', 'PAUSED',
  ]);
  const dispatchedWorkOrderCount = workOrders.filter(({ status }) =>
    dispatchedStatuses.has(status)).length;
  const planIds = new Set(plans.map(({ id }) => id));
  const taskPlanIds = new Set(
    workOrders.map(({ planId }) => planId).filter((planId) => planIds.has(planId)),
  );
  const closedExceptionCount = exceptions.filter(({ status }) => status === 'CLOSED').length;
  const mergedOfflinePacketCount = packets.filter(({ mergeStatus }) => mergeStatus === 'MERGED').length;
  const kpis: ReportKpis = {
    planTotal: plans.length,
    confirmedPlanCount,
    appliedRecommendationCount,
    generatedWorkOrderCount: workOrders.length,
    dispatchedWorkOrderCount,
    exceptionCount: exceptions.length,
    interlockCount: interlocks.length,
    mergedOfflinePacketCount,
  };
  const rates: readonly ReportRate[] = [
    rate('planConfirmationRate', '计划确认率', confirmedPlanCount, plans.length, 'DO-001 生产计划状态', '已确认计划数 / 计划总数'),
    rate('taskDecompositionRate', '任务拆解完成率', taskPlanIds.size, plans.length, 'DO-001 生产计划与 DO-005 作业工单计划编号', '有关联作业单的不同计划数 / 计划总数'),
    rate('dispatchRate', '派工完成率', dispatchedWorkOrderCount, workOrders.length, 'DO-005 作业工单状态', '已派工任务数 / 已生成任务数'),
    rate('exceptionClosureRate', '异常关闭率', closedExceptionCount, exceptions.length, 'DO-009 调度异常状态', '已关闭异常数量 / 异常总数'),
    rate('offlineMergeRate', '离线同步合并率', mergedOfflinePacketCount, packets.length, 'DO-011 离线包合并状态', '已合并离线包数量 / 离线包总数'),
  ];
  const distributions: ReportDistributions = {
    planStatus: distribution('planStatus', '计划状态分布', 'DO-001 生产计划状态', planStatuses, planStatusLabels, plans.map(({ status }) => status)),
    workOrderStatus: distribution('workOrderStatus', '作业状态分布', 'DO-005 作业工单状态', workOrderStatuses, workOrderStatusLabels, workOrders.map(({ status }) => status)),
    exceptionLevel: distribution('exceptionLevel', '异常等级分布', 'DO-009 调度异常等级', exceptionLevels, exceptionLevelLabels, exceptions.map(({ level }) => level)),
    exceptionType: distribution('exceptionType', '异常类型分布', 'DO-009 调度异常类型', exceptionTypes, exceptionTypeLabels, exceptions.map(({ type }) => type)),
    interlockAction: distribution('interlockAction', '联锁动作等级分布', 'DO-010 安全联锁动作等级', actionLevels, actionLevelLabels, interlocks.map(({ actionLevel }) => actionLevel)),
    offlineStatus: distribution('offlineStatus', '离线包状态分布', 'DO-011 离线包合并状态', mergeStatuses, mergeStatusLabels, packets.map(({ mergeStatus }) => mergeStatus)),
  };
  const rateValues = Object.fromEntries(rates.map(({ key, value }) => [key, value])) as Record<ReportRateKey, number>;
  const flatMetrics: ReportFlatMetrics = { ...kpis, ...rateValues };

  return deepFreeze({
    disclosure: REPORT_DISCLOSURE,
    asOf: state.session.demoTime,
    kpis,
    rates,
    distributions,
    definitions: [...definitions],
    flatMetrics,
  });
}
