import { Alert, Button, Card, Empty, Skeleton, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';

import { authorize } from '../../auth';
import type { PublicErrorCode } from '../../contracts';
import ReportCommandFeedback from '../../features/reporting/components/ReportCommandFeedback';
import ReportContextHeader from '../../features/reporting/components/ReportContextHeader';
import ReportDetail from '../../features/reporting/components/ReportDetail';
import ReportDistributionGrid from '../../features/reporting/components/ReportDistributionGrid';
import ReportEfficiencyPanel from '../../features/reporting/components/ReportEfficiencyPanel';
import ReportKpiGrid from '../../features/reporting/components/ReportKpiGrid';
import ReportLedger from '../../features/reporting/components/ReportLedger';
import ReportMetricDrawer from '../../features/reporting/components/ReportMetricDrawer';
import {
  parseReportQuery,
  selectReportDashboard,
  type ReportGatewayResult,
  type ReportFilters,
} from '../../features/reporting';
import '../../features/reporting/reporting.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import { useDemoRuntime, useDemoSelector, useReportWorkflow } from '../../runtime';

type ReadFailure = Readonly<{
  kind: 'network' | 'malformed' | 'business';
  message: string;
  errorCode?: PublicErrorCode;
  traceId?: string;
  auditLogId?: string;
}>;

type ReadState =
  | Readonly<{ kind: 'loading' | 'success' }>
  | ReadFailure;

const defaultReason = '刷新统计报表快照';

function hasAreaAVisibility(dataScope: readonly string[]): boolean {
  return dataScope.includes('*') || dataScope.includes('GLOBAL') || dataScope.includes('AREA-A');
}

function failureFrom(error: unknown): ReadFailure {
  if (error instanceof TypeError) {
    return { kind: 'network', message: 'API-020 网络请求失败。' };
  }
  return {
    kind: 'malformed',
    message: 'API-020 响应结构校验失败。',
  };
}

function ReportReadBanner({
  state,
  onRetry,
}: {
  state: ReadState;
  onRetry: () => void;
}) {
  if (state.kind === 'success') return null;
  if (state.kind === 'loading') {
    return (
      <Card className="report-loading-card" size="small" aria-label="报表加载中">
        <Skeleton active paragraph={{ rows: 2 }} />
      </Card>
    );
  }
  const failure = state as ReadFailure;
  const title = failure.kind === 'network'
    ? '报表读取暂不可用'
    : failure.kind === 'malformed'
      ? '报表响应契约不完整'
      : failure.message;
  return (
    <Alert
      className="report-read-alert"
      type={failure.kind === 'network' ? 'warning' : 'error'}
      showIcon
      title={title}
      description={(
        <Space size={[8, 6]} wrap>
          {failure.errorCode ? <Tag color="error">{failure.errorCode}</Tag> : null}
          {failure.kind !== 'business' ? <Typography.Text>{failure.message}</Typography.Text> : null}
          {failure.traceId ? <Typography.Text code>{failure.traceId}</Typography.Text> : null}
          {failure.auditLogId ? <Typography.Text code>{failure.auditLogId}</Typography.Text> : null}
          <Button size="small" onClick={onRetry}>重新加载</Button>
        </Space>
      )}
    />
  );
}

export default function OperationReportPage() {
  const location = useLocation();
  const context = parseReportQuery(location.search);
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useReportWorkflow((current) => current);
  const visible = hasAreaAVisibility(state.session.dataScope);
  const effectiveFilters: ReportFilters = {
    reportType: workflow.filters.reportType ?? context.reportType,
    period: workflow.filters.period ?? context.period,
    generateStatus: workflow.filters.generateStatus ?? context.generateStatus,
  };
  const dashboard = selectReportDashboard(state, {
    ...context,
    ...effectiveFilters,
  });
  const selected = dashboard.ledger.items.find(
    ({ report }) => report.id === workflow.selectedReportId,
  ) ?? dashboard.selected ?? dashboard.ledger.items[0];
  const [readRequest, setReadRequest] = useState(0);
  const [readState, setReadState] = useState<ReadState>({ kind: 'loading' });
  const [reason, setReason] = useState(defaultReason);
  const [resetting, setResetting] = useState(false);
  const [commandPending, setCommandPending] = useState(false);
  const listFlight = useRef<Readonly<{
    key: string;
    promise: Promise<ReportGatewayResult>;
  }>>();

  useEffect(() => {
    if (!visible) return undefined;
    let active = true;
    setReadState({ kind: 'loading' });
    const expectedReportId = context.reportId && state.report.reports.some(({ id }) => id === context.reportId)
      ? context.reportId
      : undefined;
    const requestKey = JSON.stringify({
      readRequest,
      reportType: effectiveFilters.reportType,
      period: effectiveFilters.period,
      scenarioId: state.scenario.activeScenarioId,
      expectedReportId,
    });
    const existing = listFlight.current;
    const promise = existing?.key === requestKey
      ? existing.promise
      : runtime.reporting.gateway.listReports({
          reportType: effectiveFilters.reportType,
          period: effectiveFilters.period,
          dimensions: ['workArea', 'team'],
          expectedScenarioId: state.scenario.activeScenarioId,
          expectedReportId,
        });
    if (promise !== existing?.promise) listFlight.current = { key: requestKey, promise };
    void promise
      .then((response: ReportGatewayResult) => {
        if (!active || listFlight.current?.promise !== promise) return;
        if (!response.ok) {
          setReadState({
            kind: 'business',
            message: response.message,
            errorCode: response.errorCode,
            traceId: response.traceId,
            auditLogId: response.auditLogId,
          });
          return;
        }
        setReadState({ kind: 'success' });
      })
      .catch((error: unknown) => {
        if (active && listFlight.current?.promise === promise) setReadState(failureFrom(error));
      });
    return () => { active = false; };
  }, [
    context.reportId,
    effectiveFilters.period,
    effectiveFilters.reportType,
    readRequest,
    runtime,
    state.report.reports,
    state.scenario.activeScenarioId,
    visible,
  ]);

  const generateDecision = authorize({
    session: state.session,
    pageId: 'UI-011',
    permission: 'report:generate',
    objectScope: { type: 'AREA', value: 'AREA-A' },
  });
  const pending = commandPending || workflow.pendingReportId === selected?.report.id;

  const refreshSelected = (): void => {
    if (!selected || pending || !generateDecision.allow) return;
    setCommandPending(true);
    void new Promise<void>((resolve) => window.setTimeout(resolve, 0))
      .then(() => runtime.reporting.commands.refreshReport({
        reportId: selected.report.id,
        expectedGeneratedAt: selected.report.generatedAt,
        reason,
      }))
      .finally(() => setCommandPending(false));
  };

  const resetToScenarioOne = (): void => {
    if (resetting) return;
    setResetting(true);
    void runtime.commands.resetScenario('SCN-01')
      .then((result) => {
        if (result.ok) {
          setReason(defaultReason);
          setReadRequest((value: number) => value + 1);
        }
      })
      .finally(() => setResetting(false));
  };

  const renderLedgerWorkspace = () => {
    if (dashboard.ledger.emptyReason === 'STORE') {
      return <Empty description="当前作业区暂无可见报表" />;
    }
    if (dashboard.ledger.emptyReason === 'FILTER' && context.reportId) {
      return <PageStatePanel state="not-found" />;
    }
    return (
      <>
        <div className="report-ledger-layout">
          <div>
            <ReportLedger
              items={dashboard.ledger.items}
              filters={effectiveFilters}
              selectedReportId={selected?.report.id}
              onFilters={runtime.reporting.workflow.setFilters}
              onSelect={runtime.reporting.workflow.selectReport}
            />
            {dashboard.ledger.emptyReason === 'FILTER' ? (
              <Empty className="report-filter-empty" description="当前筛选条件下暂无报表" />
            ) : null}
          </div>
          <div className="report-detail-column">
            <ReportDetail
              item={selected}
              pending={pending}
              canGenerate={generateDecision.allow}
              disabledReason={generateDecision.allow ? undefined : generateDecision.reason}
              reason={reason}
              snapshotMetricCount={Object.keys(dashboard.metrics.flatMetrics).length}
              snapshotAsOf={dashboard.metrics.asOf}
              onReason={setReason}
              onRefresh={refreshSelected}
              onOpenMetrics={() => runtime.reporting.workflow.setMetricsDrawerOpen(true)}
            />
            <ReportCommandFeedback
              feedback={workflow.lastFeedback}
              pendingReportId={workflow.pendingReportId}
            />
          </div>
        </div>
        <ReportMetricDrawer
          open={workflow.metricsDrawerOpen}
          metrics={dashboard.metrics}
          onClose={() => runtime.reporting.workflow.setMetricsDrawerOpen(false)}
        />
      </>
    );
  };

  return (
    <div className="reporting-page" aria-label="UI-011 统计报表页面">
      <div className="reporting-page-heading">
        <PageIdentity pageId="UI-011" name="统计报表" path="/reports/operations" />
        <Space size={8} wrap>
          <Tag>/reports/operations</Tag>
          <Tag color="processing">API-020 · 查询</Tag>
        </Space>
      </div>
      {!visible ? <PageStatePanel state="not-found" /> : (
        <>
          <ReportContextHeader
            ledger={dashboard.ledger}
            activeScenarioId={state.scenario.activeScenarioId}
            asOf={dashboard.metrics.asOf}
            resetting={resetting}
            onReset={resetToScenarioOne}
          />
          <ReportReadBanner state={readState} onRetry={() => setReadRequest((value: number) => value + 1)} />
          <ReportKpiGrid kpis={dashboard.metrics.kpis} />
          <div className="report-analysis-layout">
            <ReportEfficiencyPanel rates={dashboard.metrics.rates} />
            <ReportDistributionGrid distributions={dashboard.metrics.distributions} />
          </div>
          {renderLedgerWorkspace()}
        </>
      )}
    </div>
  );
}
