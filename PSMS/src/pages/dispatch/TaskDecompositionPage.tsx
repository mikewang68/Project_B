import { Alert, Breadcrumb, Button, Modal, Space, Tag, Typography } from 'antd';
import { useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';

import type { CommandResult } from '../../commands';
import type { PublicErrorCode } from '../../contracts';
import {
  createDemoSequence,
  DemoPlaybackControls,
  DemoStageRail,
  demoAutoplayEnabled,
  useDemoSequence,
} from '../../features/demo-presentation';
import '../../features/demo-presentation/demo-presentation.css';
import CargoSummaryCard from '../../features/task-decomposition/components/CargoSummaryCard';
import ResourcePreview from '../../features/task-decomposition/components/ResourcePreview';
import RuleExplainPanel from '../../features/task-decomposition/components/RuleExplainPanel';
import TaskContextHeader from '../../features/task-decomposition/components/TaskContextHeader';
import TaskEditDrawer, {
  type TaskEditValues,
} from '../../features/task-decomposition/components/TaskEditDrawer';
import TaskTreePanel from '../../features/task-decomposition/components/TaskTreePanel';
import {
  canReadAreaA,
  selectCargoSummary,
  selectResourcePreview,
  selectRuleExplanation,
  selectTaskTree,
  type CargoSummaryView,
  type ResourcePreviewItem,
  type TaskRouteExplanation,
  type TaskTreeNodeView,
} from '../../features/task-decomposition';
import '../../features/task-decomposition/task-decomposition.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import {
  recommendationDraftSchema,
  selectReceptionRecommendations,
  type RecommendationDraft,
} from '../../features/recommendation';
import {
  useDemoRuntime,
  useDemoSelector,
  useTaskDecompositionWorkflow,
} from '../../runtime';
import { businessLabel } from '../../presentation/businessCopy';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;

const taskDemoStages = [
  { id: 'source', label: '读取计划', detail: '锁定车次与货类' },
  { id: 'route', label: '匹配规则', detail: '生成识别节点' },
  { id: 'unload', label: '卸料准备', detail: '绑定翻车机类型' },
  { id: 'transfer', label: '输送转运', detail: '建立直接依赖' },
  { id: 'storage', label: '筒仓入库', detail: '闭合任务链' },
  { id: 'validate', label: '完整性校验', detail: '检查孤儿与循环' },
  { id: 'ready', label: '形成就绪工单', detail: '不直接下发设备' },
] as const;

const taskDemoSequence = createDemoSequence(taskDemoStages.map((stage, index) => ({
  id: stage.id,
  durationMs: index === taskDemoStages.length - 1 ? 3_200 : index === 5 ? 1_200 : 800,
})));

function resultError(result: CommandResult): PageError | undefined {
  return result.ok ? undefined : { errorCode: result.errorCode, message: result.message };
}

function isNetworkError(error: PageError): boolean {
  return error.errorCode.startsWith('TOS-EXT-');
}

function generationLabel(nodes: readonly TaskTreeNodeView[]): string {
  const match = nodes[0]?.workOrderId.match(/-G(\d{3})(?:-|$)/);
  return match ? `G${match[1]}` : '当前代次';
}

function invalidMergeReason(nodes: readonly TaskTreeNodeView[]): string | undefined {
  if (nodes.length !== 2) return '合并要求恰好选择两个节点';
  const [first, second] = [...nodes].sort((left, right) => left.sequence - right.sequence);
  if (!first || !second
    || first.taskType !== second.taskType
    || first.objectId !== second.objectId
    || second.dependencyIds[0] !== first.workOrderId) {
    return '合并要求两个同类型、相邻且直接依赖的节点';
  }
  return undefined;
}

export default function TaskDecompositionPage() {
  const { planId: routePlanId } = useParams<{ planId: string }>();
  const planId = routePlanId ?? '';
  const location = useLocation();
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useTaskDecompositionWorkflow((current) => current);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState<PageError>();
  const [feedback, setFeedback] = useState<string>();
  const [regenerateOpen, setRegenerateOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [demoNodeCount, setDemoNodeCount] = useState(0);
  const retryAction = useRef<(() => Promise<CommandResult>) | undefined>(undefined);

  const visible = canReadAreaA(state);
  const plan = visible ? state.plan.plans.find(({ id }) => id === planId) : undefined;
  let recommendation: RecommendationDraft | undefined;
  let cargo: CargoSummaryView | undefined;
  let rule: TaskRouteExplanation | undefined;
  let tree: readonly TaskTreeNodeView[] = [];
  let resources: readonly ResourcePreviewItem[] = [];
  let projectionInvalid = false;
  try {
    const parsed = recommendationDraftSchema.safeParse(state.recommendation.drafts[planId]);
    recommendation = parsed.success && parsed.data.status === 'CONFIRMED'
      ? parsed.data
      : undefined;
    cargo = selectCargoSummary(state, planId);
    rule = selectRuleExplanation(state, planId);
    tree = selectTaskTree(state, planId);
    resources = selectResourcePreview(state, planId);
  } catch {
    projectionInvalid = true;
  }

  const selectedNodes = tree.filter(({ nodeId }) => workflow.selectedNodeIds.includes(nodeId));
  const locked = plan?.status === 'DECOMPOSED' || tree.some(({ status }) => status === 'READY');
  const returnUrl = `/dispatch/plans/${encodeURIComponent(planId)}/recommendation`;
  const dispatchUrl = `/dispatch/work-orders?planId=${encodeURIComponent(planId)}&scenarioId=${encodeURIComponent(state.scenario.activeScenarioId)}&from=task-decomposition`;
  const interlockUrl = `/safety/interlocks?scenarioId=${encodeURIComponent(state.scenario.activeScenarioId)}&planId=${encodeURIComponent(planId)}&from=task-decomposition`;

  const demoPlayback = useDemoSequence({
    sequence: taskDemoSequence,
    autoplay: demoAutoplayEnabled(location.search),
    onReset: async () => {
      setDemoNodeCount(0);
      setFeedback(undefined);
      setPageError(undefined);
      const reset = await runtime.commands.resetScenario(state.scenario.activeScenarioId);
      if (!reset.ok) throw new Error(reset.message);
      const confirmPlan = await runtime.commands.confirmPlan(planId);
      if (!confirmPlan.ok) throw new Error(confirmPlan.message);
      const calculate = await runtime.recommendation.commands.calculateRecommendation(planId);
      if (!calculate.ok) throw new Error(calculate.message);
      const recommendation = selectReceptionRecommendations(runtime.store.getState(), planId);
      const candidate = recommendation?.candidates.find(({ recommended }) => recommended);
      if (!candidate) throw new Error('未找到可确认的生产准备建议');
      const confirmRecommendation = await runtime.recommendation.commands.confirmRecommendation({
        planId,
        candidateId: candidate.candidateId,
      });
      if (!confirmRecommendation.ok) throw new Error(confirmRecommendation.message);
    },
    onStep: async (step, index) => {
      if (step.id === 'route') {
        const existing = selectTaskTree(runtime.store.getState(), planId);
        if (existing.length === 0) {
          const result = await runtime.taskDecomposition.commands.generateTasks(planId);
          if (!result.ok) throw new Error(result.message);
        }
      }
      setDemoNodeCount(Math.max(0, Math.min(4, index)));
      if (step.id === 'ready') {
        const currentPlan = runtime.store.getState().plan.plans.find(({ id }) => id === planId);
        if (currentPlan?.status === 'CONFIRMED') {
          const result = await runtime.taskDecomposition.commands.confirmTasks(planId);
          if (!result.ok) throw new Error(result.message);
        }
        setDemoNodeCount(4);
        setFeedback('任务定义校验通过，已形成可派工的就绪工单。');
      }
    },
  });

  const execute = async (action: () => Promise<CommandResult>): Promise<CommandResult> => {
    retryAction.current = action;
    setLoading(true);
    setPageError(undefined);
    const result = await action();
    setLoading(false);
    const error = resultError(result);
    setPageError(error);
    if (result.ok) {
      setFeedback(`命令 ${result.commandId} · ${result.traceId} · ${result.auditLogId}`);
    }
    return result;
  };

  const retry = (): void => {
    if (!retryAction.current) return;
    void execute(retryAction.current).then((result) => {
      if (result.ok && workflow.editDrawerOpen) {
        runtime.taskDecomposition.workflow.closeEditor();
      }
    });
  };

  const toggleNode = (nodeId: string): void => {
    const selected = workflow.selectedNodeIds.includes(nodeId)
      ? workflow.selectedNodeIds.filter((id) => id !== nodeId)
      : [...workflow.selectedNodeIds, nodeId];
    runtime.taskDecomposition.workflow.selectNodes(selected);
  };

  const openSplit = (): void => {
    const targetNodeId = selectedNodes[0]?.nodeId;
    if (targetNodeId) runtime.taskDecomposition.workflow.openEditor('SPLIT', targetNodeId);
  };

  const openMerge = (): void => {
    runtime.taskDecomposition.workflow.openEditor('MERGE');
  };

  const submitEdit = async (values: TaskEditValues): Promise<void> => {
    if (values.mode === 'SPLIT' && values.targetNodeId) {
      const result = await execute(() => runtime.taskDecomposition.commands.splitTask({
        planId,
        targetNodeId: values.targetNodeId!,
        reason: values.reason,
      }));
      if (result.ok) runtime.taskDecomposition.workflow.closeEditor();
      return;
    }
    const ordered = [...selectedNodes].sort((left, right) => left.sequence - right.sequence);
    const first = ordered[0]?.nodeId;
    const second = ordered[1]?.nodeId;
    if (!first || !second) return;
    const result = await execute(() => runtime.taskDecomposition.commands.mergeTasks({
      planId,
      nodeIds: [first, second],
      reason: values.reason,
    }));
    if (result.ok) runtime.taskDecomposition.workflow.closeEditor();
  };

  const renderBody = () => {
    if (!visible || !plan) {
      return <PageStatePanel state="not-found" />;
    }
    if ((plan.status !== 'CONFIRMED' && plan.status !== 'DECOMPOSED')
      || !recommendation
      || projectionInvalid
      || !cargo
      || !rule) {
      return (
        <Alert
          type="error"
          showIcon
          title="任务拆解前置条件未满足"
          description={
            <Space orientation="vertical">
              <Typography.Text>业务处理失败</Typography.Text>
              <Typography.Text code>DEMO-SCENARIO-001</Typography.Text>
              <Link to={returnUrl}>返回生产准备建议</Link>
            </Space>
          }
        />
      );
    }
    if (loading && tree.length === 0) return <PageStatePanel state="loading" />;
    if (pageError && tree.length === 0) {
      return (
        <div className="task-decomposition-error-state">
          <PageStatePanel
            state={isNetworkError(pageError) ? 'network-error' : 'business-error'}
            errorCode={pageError.errorCode}
            recoveryLabel="重试原动作"
            onRecover={retry}
          />
          {pageError.errorCode === 'TOS-IL-001' ? (
            <Link to={interlockUrl}>前往 UI-009 安全联锁</Link>
          ) : null}
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
            action={pageError.errorCode === 'TOS-IL-001'
              ? <Link to={interlockUrl}>前往 UI-009 安全联锁</Link>
              : <Button onClick={retry}>重试原动作</Button>}
          />
        ) : null}
        {feedback ? <Alert type="success" showIcon title={feedback} /> : null}
        <TaskContextHeader plan={plan} recommendation={recommendation} />
        <div className="task-decomposition-main">
          <div className="task-decomposition-primary">
            <CargoSummaryCard summary={cargo} />
            <TaskTreePanel
              nodes={tree}
              selectedNodeIds={workflow.selectedNodeIds}
              locked={locked}
              loading={loading}
              onToggleNode={toggleNode}
              onGenerate={() => void execute(() => runtime.taskDecomposition.commands.generateTasks(planId))}
              onOpenSplit={openSplit}
              onOpenMerge={openMerge}
              onRegenerate={() => setRegenerateOpen(true)}
              onConfirm={() => setConfirmOpen(true)}
              {...(demoPlayback.status === 'idle' ? {} : { visibleNodeCount: demoNodeCount })}
            />
          </div>
          <div className="task-decomposition-side">
            <RuleExplainPanel
              explanation={rule}
              open={workflow.rulePanelOpen}
              onOpenChange={(open) => runtime.taskDecomposition.workflow.setRulePanelOpen(open)}
            />
            <ResourcePreview items={resources} nodes={tree} confirmed={locked} />
          </div>
        </div>
        {locked ? (
          <div className="task-decomposition-next-step">
            <Space orientation="vertical" size={2}>
              <Typography.Text strong>任务定义已确认，资源实例待 UI-005 正式分配。</Typography.Text>
              <Link to={dispatchUrl}>进入派工看板</Link>
            </Space>
          </div>
        ) : null}
      </>
    );
  };

  const query = new URLSearchParams(location.search);
  return (
    <main className="task-decomposition-page">
      <div className="task-decomposition-page-header">
        <PageIdentity
          pageId="UI-004"
          name="任务拆解"
          path={`/dispatch/plans/${planId}/tasks`}
        />
        <Space size={8} wrap>
          <Tag color="processing">{query.get('scenarioId') ?? state.scenario.activeScenarioId}</Tag>
          {planId ? <Tag>{planId}</Tag> : null}
        </Space>
      </div>
      <Breadcrumb
        items={[
          { title: <Link to={returnUrl}>生产准备建议</Link> },
          { title: planId || '未知计划' },
          { title: '任务拆解' },
        ]}
      />
      <DemoPlaybackControls
        title="任务自动拆解演示"
        boundary="仅生成并确认任务定义；资源实例仍由派工环节分配，设备动作仍由控制系统执行。"
        playback={demoPlayback}
      />
      <DemoStageRail
        ariaLabel="任务拆解演示阶段"
        currentStepIndex={demoPlayback.currentStepIndex}
        stages={taskDemoStages}
      />
      {renderBody()}
      <TaskEditDrawer
        open={workflow.editDrawerOpen}
        mode={workflow.mode === 'MERGE' ? 'MERGE' : 'SPLIT'}
        {...(workflow.targetNodeId ? { targetNodeId: workflow.targetNodeId } : {})}
        selectedNodes={selectedNodes}
        reason={workflow.reason}
        {...(workflow.mode === 'MERGE' && invalidMergeReason(selectedNodes)
          ? { invalidReason: invalidMergeReason(selectedNodes) }
          : {})}
        loading={loading}
        error={workflow.lastCommandError}
        onReasonChange={(reason) => runtime.taskDecomposition.workflow.setReason(reason)}
        onClose={() => runtime.taskDecomposition.workflow.closeEditor()}
        onSubmit={submitEdit}
        onRetry={retry}
      />
      <Modal
        title="重新生成系统建议"
        open={regenerateOpen}
        okText="确认重新生成"
        cancelText="取消"
        confirmLoading={loading}
        onCancel={() => setRegenerateOpen(false)}
        onOk={() => void execute(() => runtime.taskDecomposition.commands.regenerateTasks(planId))
          .then((result) => {
            if (result.ok) setRegenerateOpen(false);
          })}
      >
        <Typography.Paragraph>
          将丢弃当前 {generationLabel(tree)} 人工编辑版本，并按当前 Store 真值生成下一代系统建议；历史审计仍保留。
        </Typography.Paragraph>
      </Modal>
      <Modal
        title="确认工单草稿"
        open={confirmOpen}
        okText="确认任务定义"
        cancelText="取消"
        confirmLoading={loading}
        onCancel={() => setConfirmOpen(false)}
        onOk={() => void execute(() => runtime.taskDecomposition.commands.confirmTasks(planId))
          .then((result) => {
            if (result.ok) setConfirmOpen(false);
          })}
      >
        <Space orientation="vertical" size={8}>
          <Typography.Text>图完整性校验：无孤儿、循环和错配</Typography.Text>
          <Typography.Text>资源类型校验：冻结目录存在所需类型</Typography.Text>
          <Typography.Text title="CONFIRMED → DECOMPOSED">
            计划：{businessLabel('CONFIRMED')} → {businessLabel('DECOMPOSED')}
          </Typography.Text>
          <Typography.Text title="DRAFT → READY">
            工单：{businessLabel('DRAFT')} → {businessLabel('READY')}
          </Typography.Text>
              <Typography.Text strong>“就绪”仅表示任务定义可进入派工；不下发资源实例。</Typography.Text>
        </Space>
      </Modal>
    </main>
  );
}
