import type { Report } from '../../contracts';
import { businessLabel } from '../../presentation/businessCopy';
import type { DemoRootState } from '../../stores';
import { deriveReportMetrics } from './reportMetrics';
import {
  REPORT_DISCLOSURE,
  generateStatusLabels,
  reportTypeLabels,
  type ReportDashboard,
  type ReportLedger,
  type ReportLedgerItem,
  type ReportQueryContext,
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

function strictReport(report: Report): Report {
  return {
    id: report.id,
    reportType: report.reportType,
    period: report.period,
    generateStatus: report.generateStatus,
    metrics: structuredClone(report.metrics),
    generatedAt: report.generatedAt,
  };
}

function projectReport(report: Report): ReportLedgerItem {
  const strict = strictReport(report);
  return {
    report: strict,
    reportTypeLabel: reportTypeLabels[strict.reportType],
    generateStatusLabel: generateStatusLabels[strict.generateStatus],
    metricKeys: Object.keys(strict.metrics).sort(),
  };
}

function matches(report: Report, context: ReportQueryContext): boolean {
  return (context.reportId === undefined || report.id === context.reportId)
    && (context.reportType === undefined || report.reportType === context.reportType)
    && (context.period === undefined || report.period === context.period)
    && (context.generateStatus === undefined || report.generateStatus === context.generateStatus);
}

function sourceLabels(context: ReportQueryContext): string[] {
  return [
    context.from ? `来源模块：${businessLabel(context.from)}` : undefined,
    context.scenarioId ? `场景：${context.scenarioId}` : undefined,
    context.reportId ? `报表：${context.reportId}` : undefined,
    context.reportType ? `类型：${reportTypeLabels[context.reportType]}` : undefined,
    context.period ? `周期：${context.period}` : undefined,
    context.generateStatus ? `状态：${generateStatusLabels[context.generateStatus]}` : undefined,
  ].filter((value): value is string => value !== undefined);
}

export function selectReportLedger(
  state: DemoRootState,
  context: ReportQueryContext,
): ReportLedger {
  const visible = canReadAreaA(state);
  const totalCount = visible ? state.report.reports.length : 0;
  const items = visible
    ? state.report.reports
      .filter((report) => matches(report, context))
      .sort((left, right) => left.id.localeCompare(right.id))
      .map(projectReport)
    : [];
  const emptyReason = !visible
    ? 'FORBIDDEN' as const
    : totalCount === 0
      ? 'STORE' as const
      : items.length === 0
        ? 'FILTER' as const
        : undefined;

  return deepFreeze({
    items,
    totalCount,
    filteredCount: items.length,
    sourceContext: { ...context },
    sourceContextLabels: sourceLabels(context),
    disclosure: REPORT_DISCLOSURE,
    ...(emptyReason ? { emptyReason } : {}),
  });
}

export function selectReportDashboard(
  state: DemoRootState,
  context: ReportQueryContext,
): ReportDashboard {
  const ledger = selectReportLedger(state, context);
  const selected = context.reportId
    ? ledger.items.find(({ report }) => report.id === context.reportId)
    : ledger.items[0];
  return deepFreeze({
    ledger,
    metrics: deriveReportMetrics(state),
    ...(selected ? { selected } : {}),
  });
}
