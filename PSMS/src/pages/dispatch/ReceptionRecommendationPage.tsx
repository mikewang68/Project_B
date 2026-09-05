import { Alert, Breadcrumb, Button, Card, Space, Tag, Typography } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';

import type { CommandResult } from '../../commands';
import type { PublicErrorCode } from '../../contracts';
import AdjustmentDrawer, {
  type RecommendationAdjustmentValues,
} from '../../features/recommendation/components/AdjustmentDrawer';
import ExcludedOptionList from '../../features/recommendation/components/ExcludedOptionList';
import PlanSummaryCard from '../../features/recommendation/components/PlanSummaryCard';
import RecommendationCardList from '../../features/recommendation/components/RecommendationCardList';
import RuleExplanationDrawer from '../../features/recommendation/components/RuleExplanationDrawer';
import TrackTimeline from '../../features/recommendation/components/TrackTimeline';
import {
  recommendationDraftSchema,
  selectAffectedWorkOrders,
  selectConfirmedPlan,
  selectReceptionRecommendations,
  type RecommendationDraft,
} from '../../features/recommendation';
import '../../features/recommendation/recommendation.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import {
  useDemoRuntime,
  useDemoSelector,
  useRecommendationWorkflow,
} from '../../runtime';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;

function hasAreaA(dataScope: readonly string[]): boolean {
  return dataScope.includes('*') || dataScope.includes('GLOBAL') || dataScope.includes('AREA-A');
}

function failureFrom(result: CommandResult): PageError | undefined {
  return result.ok ? undefined : { errorCode: result.errorCode, message: result.message };
}

function isNetworkError(error: PageError): boolean {
  return error.errorCode.startsWith('TOS-EXT-');
}

export default function ReceptionRecommendationPage() {
  const { planId: routePlanId } = useParams<{ planId: string }>();
  const planId = routePlanId ?? '';
  const location = useLocation();
  const navigate = useNavigate();
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useRecommendationWorkflow((current) => current);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState<PageError>();
  const [confirmLoading, setConfirmLoading] = useState(false);
  const requestedInput = useRef<string>();
  const visible = hasAreaA(state.session.dataScope);
  const rawPlan = visible ? state.plan.plans.find(({ id }) => id === planId) : undefined;
  const confirmedPlan = selectConfirmedPlan(state, planId);
  let draftInvalid = false;
  let draft: RecommendationDraft | undefined;
  try {
    draft = selectReceptionRecommendations(state, planId);
  } catch {
    draftInvalid = true;
  }
  const affectedWorkOrders = selectAffectedWorkOrders(state, planId);
  const selectedCandidate = draft?.candidates.find(
    ({ candidateId }) => candidateId === workflow.selectedCandidateId,
  );
  const selectedTrack = selectedCandidate
    ? state.resource.tracks.find(({ id }) => id === selectedCandidate.trackId)
    : undefined;
  const adjustmentSummary = draft?.adjustment
    ? {
        originalTrackNo:
          draft.candidates.find(
            ({ candidateId }) => candidateId === draft.adjustment?.originalCandidateId,
          )?.trackNo ?? '未知',
        finalTrackNo:
          draft.candidates.find(
            ({ candidateId }) => candidateId === draft.adjustment?.finalCandidateId,
          )?.trackNo ?? '未知',
        reason: draft.adjustment.reason,
        reviewerId: draft.adjustment.reviewerId ?? '未要求',
      }
    : undefined;

  const returnUrl = useMemo(() => {
    const query = new URLSearchParams(location.search);
    if (!query.has('date')) query.set('date', '2026-07-16');
    if (!query.has('workArea')) query.set('workArea', 'AREA-A');
    if (!query.has('scenarioId')) query.set('scenarioId', state.scenario.activeScenarioId);
    if (planId) query.set('planId', planId);
    return `/dispatch/plans?${query.toString()}`;
  }, [location.search, planId, state.scenario.activeScenarioId]);

  const calculate = async (): Promise<void> => {
    if (!confirmedPlan) return;
    setLoading(true);
    setPageError(undefined);
    const result = await runtime.recommendation.commands.calculateRecommendation(planId);
    setLoading(false);
    setPageError(failureFrom(result));
  };

  useEffect(() => {
    if (!confirmedPlan || draft || draftInvalid) return;
    const key = `${planId}:${confirmedPlan.version}`;
    if (requestedInput.current === key) return;
    requestedInput.current = key;
    void calculate();
  }, [confirmedPlan, draft, draftInvalid, planId]);

  useEffect(() => {
    if (!draft || draft.candidates.length === 0 || workflow.selectedCandidateId) return;
    runtime.recommendation.workflow.selectCandidate(draft.candidates[0]?.candidateId);
  }, [draft, runtime.recommendation.workflow, workflow.selectedCandidateId]);

  const recalculate = (): void => {
    runtime.recommendation.workflow.setAdjustmentDrawerOpen(false);
    requestedInput.current = undefined;
    void calculate();
  };

  const submitConfirmation = async (
    values: RecommendationAdjustmentValues,
  ): Promise<void> => {
    setConfirmLoading(true);
    setPageError(undefined);
    const result = await runtime.recommendation.commands.confirmRecommendation({
      planId,
      candidateId: values.candidateId,
      ...((values.reason ?? '').trim() ? { reason: (values.reason ?? '').trim() } : {}),
      ...(values.reviewerId ? { reviewerId: values.reviewerId } : {}),
    });
    setConfirmLoading(false);
    setPageError(failureFrom(result));
    if (result.ok) runtime.recommendation.workflow.setAdjustmentDrawerOpen(false);
  };

  const renderState = () => {
    if (!visible || !rawPlan) {
      return (
        <PageStatePanel
          state="not-found"
          recoveryLabel="返回计划台账"
          onRecover={() => void navigate(returnUrl)}
        />
      );
    }
    if (rawPlan.status !== 'CONFIRMED') {
      return (
        <PageStatePanel
          state="business-error"
          errorCode="DEMO-SCENARIO-001"
          recoveryLabel="返回计划确认"
          onRecover={() => void navigate(returnUrl)}
        />
      );
    }
    if (draftInvalid) {
      return (
        <PageStatePanel
          state="business-error"
          errorCode="DEMO-SCENARIO-001"
          recoveryLabel="返回计划台账"
          onRecover={() => void navigate(returnUrl)}
        />
      );
    }
    if (loading && !draft) return <PageStatePanel state="loading" />;
    if (pageError && !draft) {
      return (
        <PageStatePanel
          state={isNetworkError(pageError) ? 'network-error' : 'business-error'}
          errorCode={pageError.errorCode}
          recoveryLabel="重新计算"
          onRecover={recalculate}
        />
      );
    }
    if (!draft || !confirmedPlan) return <PageStatePanel state="loading" />;
    if (draft.candidates.length === 0) {
      return (
        <div className="recommendation-main-grid">
          <div className="recommendation-primary-column">
            <PlanSummaryCard plan={confirmedPlan} />
            <Card>
              <Alert type="warning" showIcon title="暂无可推荐股道" />
              <Space className="recommendation-empty-actions">
                <Button onClick={() => void navigate(returnUrl)}>返回计划台账</Button>
                <Button type="primary" onClick={recalculate} loading={loading}>重新计算</Button>
              </Space>
            </Card>
          </div>
          <div className="recommendation-side-column">
            <ExcludedOptionList exclusions={draft.excluded} />
          </div>
        </div>
      );
    }

    return (
      <>
        {pageError ? (
          <Alert
            type={isNetworkError(pageError) ? 'warning' : 'error'}
            showIcon
            title={`${pageError.errorCode}: ${pageError.message}`}
            action={<Button onClick={recalculate}>重新计算</Button>}
          />
        ) : null}
        <div className="recommendation-main-grid">
          <div className="recommendation-primary-column">
            <PlanSummaryCard plan={confirmedPlan} />
            <RecommendationCardList
              candidates={draft.candidates}
              selectedCandidateId={workflow.selectedCandidateId}
              disabled={loading || confirmLoading || draft.status === 'CONFIRMED'}
              onSelect={(candidateId) => runtime.recommendation.workflow.selectCandidate(candidateId)}
            />
          </div>
          <div className="recommendation-side-column">
            <TrackTimeline plan={confirmedPlan} track={selectedTrack} candidate={selectedCandidate} />
            <ExcludedOptionList exclusions={draft.excluded} />
          </div>
        </div>
        <div className="recommendation-actions">
          <Space size={8} wrap>
            <Tag color="blue">规则版本</Tag>
            <Typography.Text code>{draft.ruleVersion}</Typography.Text>
            <Button onClick={() => runtime.recommendation.workflow.setRuleDrawerOpen(true)}>
              查看规则说明
            </Button>
          </Space>
          {draft.status === 'CONFIRMED' ? (
            <Space orientation="vertical" align="end" size={2}>
              <Link to={`/dispatch/plans/${encodeURIComponent(planId)}/tasks`}>
                进入任务拆解
              </Link>
              <Typography.Text type="secondary">
                命令追踪：{draft.confirmation?.traceId} · 审计动作：RC-04
              </Typography.Text>
              {adjustmentSummary ? (
                <Typography.Text type="secondary">
                  {`调整溯源：${adjustmentSummary.originalTrackNo} → ${adjustmentSummary.finalTrackNo} · 原因：${adjustmentSummary.reason} · 复核员：${adjustmentSummary.reviewerId}`}
                </Typography.Text>
              ) : null}
            </Space>
          ) : (
            <Button
              type="primary"
              disabled={!selectedCandidate || loading || confirmLoading}
              onClick={() => runtime.recommendation.workflow.setAdjustmentDrawerOpen(true)}
            >
              {selectedCandidate && selectedCandidate.rank > 1 ? '调整并确认' : '确认推荐'}
            </Button>
          )}
        </div>
      </>
    );
  };

  const parsedDraft = draft ? recommendationDraftSchema.parse(draft) : undefined;
  return (
    <main className="recommendation-page">
      <div className="recommendation-page-header">
        <PageIdentity
          pageId="UI-003"
          name="生产准备建议"
          path={`/dispatch/plans/${planId}/recommendation`}
        />
        <Space size={8} wrap>
          <Tag color="processing">{state.scenario.activeScenarioId}</Tag>
          {planId ? <Tag>{planId}</Tag> : null}
        </Space>
      </div>
      <Breadcrumb
        items={[
          { title: <Link to={returnUrl}>外部到发信息台账</Link> },
          { title: planId || '未知计划' },
          { title: '生产准备建议' },
        ]}
      />
      {renderState()}
      <RuleExplanationDrawer
        open={workflow.ruleDrawerOpen}
        draft={parsedDraft}
        onClose={() => runtime.recommendation.workflow.setRuleDrawerOpen(false)}
      />
      <AdjustmentDrawer
        open={workflow.adjustmentDrawerOpen}
        candidate={selectedCandidate}
        affectedWorkOrders={affectedWorkOrders}
        reviewers={state.configAudit.userRoles}
        actorId={state.session.actorId}
        loading={confirmLoading}
        error={workflow.lastCommandError}
        onClose={() => runtime.recommendation.workflow.setAdjustmentDrawerOpen(false)}
        onSubmit={submitConfirmation}
        onRecalculate={recalculate}
      />
    </main>
  );
}
