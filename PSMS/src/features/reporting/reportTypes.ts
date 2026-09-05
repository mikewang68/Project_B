import type { PublicErrorCode, Report } from '../../contracts';
import { generateStatuses, reportTypes } from '../../contracts';

export type ReportType = (typeof reportTypes)[number];
export type GenerateStatus = (typeof generateStatuses)[number];

export const REPORT_DISCLOSURE = '演示用确定性统计口径' as const;

export const reportTypeLabels: Record<ReportType, string> = {
  SHIFT: '班报',
  DAILY: '日报',
  MONTHLY: '月报',
  CUSTOM: '自定义报表',
};

export const generateStatusLabels: Record<GenerateStatus, string> = {
  PENDING: '待生成',
  RUNNING: '生成中',
  SUCCESS: '生成成功',
  FAILED: '生成失败',
};

export type ReportQueryContext = Readonly<{
  reportId?: string;
  reportType?: ReportType;
  period?: string;
  generateStatus?: GenerateStatus;
  scenarioId?: string;
  from?: string;
}>;

export type ReportLedgerItem = Readonly<{
  report: Readonly<Report>;
  reportTypeLabel: string;
  generateStatusLabel: string;
  metricKeys: readonly string[];
}>;

export type ReportLedger = Readonly<{
  items: readonly ReportLedgerItem[];
  totalCount: number;
  filteredCount: number;
  sourceContext: ReportQueryContext;
  sourceContextLabels: readonly string[];
  disclosure: typeof REPORT_DISCLOSURE;
  emptyReason?: 'FORBIDDEN' | 'STORE' | 'FILTER';
}>;

export type ReportKpis = Readonly<{
  planTotal: number;
  confirmedPlanCount: number;
  appliedRecommendationCount: number;
  generatedWorkOrderCount: number;
  dispatchedWorkOrderCount: number;
  exceptionCount: number;
  interlockCount: number;
  mergedOfflinePacketCount: number;
}>;

export type ReportRateKey =
  | 'planConfirmationRate'
  | 'taskDecompositionRate'
  | 'dispatchRate'
  | 'exceptionClosureRate'
  | 'offlineMergeRate';

export type ReportRate = Readonly<{
  key: ReportRateKey;
  label: string;
  value: number;
  numerator: number;
  denominator: number;
  source: string;
  formula: string;
}>;

export type ReportDistributionItem = Readonly<{
  key: string;
  label: string;
  value: number;
}>;

export type ReportDistribution = Readonly<{
  key: string;
  label: string;
  source: string;
  items: readonly ReportDistributionItem[];
}>;

export type ReportDistributions = Readonly<{
  planStatus: ReportDistribution;
  workOrderStatus: ReportDistribution;
  exceptionLevel: ReportDistribution;
  exceptionType: ReportDistribution;
  interlockAction: ReportDistribution;
  offlineStatus: ReportDistribution;
}>;

export type ReportMetricDefinition = Readonly<{
  key: string;
  label: string;
  source: string;
  formula: string;
}>;

export type ReportFlatMetrics = Readonly<ReportKpis & Record<ReportRateKey, number>>;

export type ReportMetricSnapshot = Readonly<{
  disclosure: typeof REPORT_DISCLOSURE;
  asOf: string;
  kpis: ReportKpis;
  rates: readonly ReportRate[];
  distributions: ReportDistributions;
  definitions: readonly ReportMetricDefinition[];
  flatMetrics: ReportFlatMetrics;
}>;

export type ReportDashboard = Readonly<{
  ledger: ReportLedger;
  metrics: ReportMetricSnapshot;
  selected?: ReportLedgerItem;
}>;

export type ReportCommandFeedback = Readonly<{
  ok: boolean;
  commandId: string;
  traceId: string;
  auditLogId: string;
  message: string;
  errorCode?: PublicErrorCode;
  idempotent: boolean;
}>;
