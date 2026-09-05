import { Alert, Empty, Space, Tag } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';

import { authorize } from '../../auth';
import AuditContextHeader from '../../features/audit-trail/components/AuditContextHeader';
import AuditDetailDrawer from '../../features/audit-trail/components/AuditDetailDrawer';
import AuditFilterBar from '../../features/audit-trail/components/AuditFilterBar';
import AuditKpiGrid from '../../features/audit-trail/components/AuditKpiGrid';
import AuditLedger from '../../features/audit-trail/components/AuditLedger';
import AuditReadBanner from '../../features/audit-trail/components/AuditReadBanner';
import AuditTracePanel from '../../features/audit-trail/components/AuditTracePanel';
import {
  buildAuditTrace,
  filterAuditItems,
  parseAuditQuery,
  projectAuditTrail,
  type AuditFilters,
  type AuditGatewayResult,
  type AuditProjection,
  type AuditQueryContext,
} from '../../features/audit-trail';
import '../../features/audit-trail/audit-trail.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import { businessLabel } from '../../presentation/businessCopy';
import { useAuditTrailWorkflow, useDemoRuntime, useDemoSelector } from '../../runtime';

function sourceLabels(context: ReturnType<typeof parseAuditQuery>): string[] {
  return [
    context.from ? `来源模块：${businessLabel(context.from)}` : undefined,
    context.scenarioId ? `场景：${context.scenarioId}` : undefined,
    context.auditId ? `审计：${context.auditId}` : undefined,
    context.traceId ? `链路：${context.traceId}` : undefined,
  ].filter((value): value is string => value !== undefined);
}

export default function AuditLogPage() {
  const location = useLocation();
  const context: AuditQueryContext = useMemo(
    () => parseAuditQuery(location.search),
    [location.search],
  );
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useAuditTrailWorkflow((current) => current);
  const projection: AuditProjection = useMemo(() => projectAuditTrail(state), [state]);
  const effectiveFilters: AuditFilters = {
    auditId: workflow.filters.auditId ?? context.auditId,
    module: workflow.filters.module ?? context.module,
    action: workflow.filters.action ?? context.action,
    objectType: workflow.filters.objectType ?? context.objectType,
    result: workflow.filters.result ?? context.result,
    actorId: workflow.filters.actorId ?? context.actorId,
    objectId: workflow.filters.objectId ?? context.objectId,
    traceId: workflow.filters.traceId ?? context.traceId,
    period: workflow.filters.period ?? context.period,
  };
  const filtered = filterAuditItems(projection.items, effectiveFilters);
  const selectedAuditId = workflow.selectedAuditId ?? context.auditId;
  const selected = projection.items.find(({ record }) => record.id === selectedAuditId);
  const trace = workflow.expandedTraceId
    ? buildAuditTrace(projection.items, workflow.expandedTraceId)
    : undefined;
  const completedTraceId = workflow.readObservation
    && 'traceId' in workflow.readObservation
    ? workflow.readObservation.traceId
    : undefined;
  const [readRequest, setReadRequest] = useState(0);
  const [resetting, setResetting] = useState(false);
  const hydratedSearch = useRef<string>();
  const listFlight = useRef<Readonly<{
    key: string;
    promise: Promise<AuditGatewayResult>;
  }>>();

  useEffect(() => {
    if (hydratedSearch.current === location.search) return;
    hydratedSearch.current = location.search;
    if (context.auditId && projection.items.some(({ record }) => record.id === context.auditId)) {
      runtime.auditTrail.workflow.openDetail(context.auditId);
    }
    if (context.traceId) runtime.auditTrail.workflow.setExpandedTrace(context.traceId);
  }, [context.auditId, context.traceId, location.search, projection.items, runtime]);

  useEffect(() => {
    if (!workflow.expandedTraceId || trace) return;
    runtime.auditTrail.workflow.setExpandedTrace(undefined);
    runtime.auditTrail.workflow.recordFeedback({ kind: 'stale-trace', message: '原链路已失效。' });
  }, [runtime, trace, workflow.expandedTraceId]);

  useEffect(() => {
    let active = true;
    runtime.auditTrail.workflow.beginRead();
    const expectedAuditId = context.auditId
      && projection.items.some(({ record }) => record.id === context.auditId)
      ? context.auditId
      : undefined;
    const requestKey = JSON.stringify({
      readRequest,
      scenarioId: state.scenario.activeScenarioId,
      expectedAuditId,
    });
    const existing = listFlight.current;
    const promise: Promise<AuditGatewayResult> = existing?.key === requestKey
      ? existing.promise
      : runtime.auditTrail.gateway.listAuditLogs({
          expectedScenarioId: state.scenario.activeScenarioId,
          expectedAuditId,
        });
    if (promise !== existing?.promise) listFlight.current = { key: requestKey, promise };
    void promise
      .then((response: AuditGatewayResult) => {
        if (!active || listFlight.current?.promise !== promise) return;
        if (response.ok) {
          runtime.auditTrail.workflow.recordReadSuccess({
            now: response.data.now,
            scenarioId: response.data.scenarioId,
            traceId: response.traceId,
            auditLogId: response.auditLogId,
          });
        } else {
          runtime.auditTrail.workflow.recordReadBusinessError({
            errorCode: response.errorCode,
            message: response.message,
            traceId: response.traceId,
            auditLogId: response.auditLogId,
          });
        }
      })
      .catch((error: unknown) => {
        if (!active || listFlight.current?.promise !== promise) return;
        if (error instanceof TypeError) {
          runtime.auditTrail.workflow.recordReadFailure(
            'network-error',
            error.message || 'API-024 network failure.',
          );
        } else {
          runtime.auditTrail.workflow.recordReadFailure(
            'malformed-response',
            error instanceof Error ? error.message : 'API-024 response contract is invalid.',
          );
        }
      });
    return () => { active = false; };
  }, [context.auditId, projection.items, readRequest, runtime, state.scenario.activeScenarioId]);

  const resetDecision = authorize({ session: state.session, permission: 'demo:reset' });
  const resetReason = `${businessLabel(state.session.roleCode)}无场景重置权限；本页保持只读。`;
  const resetToScenarioOne = (): void => {
    if (resetting || !resetDecision.allow) return;
    setResetting(true);
    void runtime.commands.resetScenario('SCN-01')
      .then((result) => {
        if (result.ok) setReadRequest((value: number) => value + 1);
      })
      .finally(() => setResetting(false));
  };
  const openDetail = (auditId: string): void => {
    runtime.auditTrail.workflow.openDetail(auditId);
    runtime.auditTrail.workflow.recordFeedback({ kind: 'success', message: '已打开审计详情。' });
  };
  const openTrace = (traceId: string): void => {
    runtime.auditTrail.workflow.setExpandedTrace(traceId);
  };

  const workspace = context.auditId && !selected ? (
    <PageStatePanel state="not-found" />
  ) : (
    <>
      <AuditFilterBar
        filters={effectiveFilters}
        pending={workflow.pending}
        onFilters={runtime.auditTrail.workflow.setFilters}
      />
      {projection.items.length === 0 ? (
        <Empty description="当前场景暂无领域审计记录" />
      ) : null}
      <div className="audit-workspace">
        <div>
          <AuditLedger
            items={filtered}
            selectedAuditId={selected?.record.id}
            onOpenDetail={openDetail}
          />
          {projection.items.length > 0 && filtered.length === 0 ? (
            <Empty description="当前筛选条件下暂无审计记录" />
          ) : null}
        </div>
        <AuditTracePanel
          trace={trace}
          onClose={() => runtime.auditTrail.workflow.setExpandedTrace(undefined)}
        />
      </div>
    </>
  );

  return (
    <div className="audit-page" aria-label="UI-013 审计日志页面">
      <div className="audit-page-heading">
        <PageIdentity pageId="UI-013" name="审计日志" path="/governance/audit" />
        <Space size={8} wrap>
          <Tag>/governance/audit</Tag>
          <Tag color="processing">API-024 · 查询</Tag>
        </Space>
      </div>
      <AuditContextHeader
        scenarioId={state.scenario.activeScenarioId}
        visibleCount={filtered.length}
        totalCount={projection.items.length}
        sourceLabels={sourceLabels(context)}
        resetting={resetting}
        canReset={resetDecision.allow}
        resetReason={resetReason}
        onReset={resetToScenarioOne}
      />
      <AuditReadBanner
        state={workflow.readState}
        observation={workflow.readObservation}
        pending={workflow.pending}
        onRetry={() => setReadRequest((value: number) => value + 1)}
      />
      {workflow.lastFeedback ? (
        <Alert
          className="audit-feedback-alert"
          type={workflow.lastFeedback.kind === 'success' ? 'success' : 'warning'}
          showIcon
          title={workflow.lastFeedback.message}
        />
      ) : null}
      <AuditKpiGrid kpis={projection.kpis} recentTraceId={completedTraceId} />
      {workspace}
      <AuditDetailDrawer
        item={selected}
        open={workflow.detailDrawerOpen}
        onClose={runtime.auditTrail.workflow.closeDetail}
        onOpenTrace={openTrace}
      />
    </div>
  );
}
