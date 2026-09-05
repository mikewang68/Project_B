import { Alert, Breadcrumb, Button, Space, Tag } from 'antd';
import { useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

import { authorize } from '../../auth';
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
import DispatchContextHeader from '../../features/dispatch-board/components/DispatchContextHeader';
import DispatchKpiStrip from '../../features/dispatch-board/components/DispatchKpiStrip';
import ExecutionFeedbackPanel from '../../features/dispatch-board/components/ExecutionFeedbackPanel';
import ResourceAssignmentPanel from '../../features/dispatch-board/components/ResourceAssignmentPanel';
import WorkOrderDetailPanel from '../../features/dispatch-board/components/WorkOrderDetailPanel';
import WorkOrderQueue from '../../features/dispatch-board/components/WorkOrderQueue';
import {
  hasAreaAVisibility,
  selectDispatchBoard,
  type DispatchBoardView,
} from '../../features/dispatch-board';
import '../../features/dispatch-board/dispatch-board.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import { selectReceptionRecommendations } from '../../features/recommendation';
import {
  useDemoRuntime,
  useDemoSelector,
  useDispatchBoardWorkflow,
} from '../../runtime';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;
type CommandAction = () => Promise<CommandResult>;

const dispatchDemoStages = [
  { id: 'scan', label: '扫描资源池', detail: '按作业区与类型过滤' },
  { id: 'rank', label: '计算匹配度', detail: '状态、位置、负载综合排序' },
  { id: 'choose', label: '锁定最优资源', detail: '选中可用翻车机' },
  { id: 'bind', label: '绑定工单', detail: '形成资源占用关系' },
  { id: 'dispatch', label: '下发派工', detail: '提交接口并写入审计' },
  { id: 'receipt', label: '接收回执', detail: '回写已确认状态' },
  { id: 'execute', label: '进入执行', detail: '现场控制仍由设备控制系统负责' },
] as const;

const dispatchDemoSequence = createDemoSequence(dispatchDemoStages.map((stage, index) => ({
  id: stage.id,
  durationMs: index === dispatchDemoStages.length - 1 ? 3_000 : index < 3 ? 900 : 1_100,
})));

function resultError(result: CommandResult): PageError | undefined {
  return result.ok ? undefined : { errorCode: result.errorCode, message: result.message };
}

function isNetworkError(error: PageError): boolean {
  return error.errorCode.startsWith('TOS-EXT-');
}

export default function DispatchBoardPage() {
  const location = useLocation();
  const query = new URLSearchParams(location.search);
  const planId = query.get('planId') ?? 'PLAN-001';
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useDispatchBoardWorkflow((current) => current);
  const [loading, setLoading] = useState(false);
  const [pageError, setPageError] = useState<PageError>();
  const [feedback, setFeedback] = useState<string>();
  const retryAction = useRef<CommandAction>();
  const inFlightCommand = useRef<Promise<CommandResult> | undefined>(undefined);
  const demoOrderId = useRef<string>();
  const demoResourceId = useRef<string>();

  const visible = hasAreaAVisibility(state.session.dataScope);
  const plan = visible ? state.plan.plans.find(({ id }) => id === planId) : undefined;
  let board: DispatchBoardView | undefined;
  let projectionInvalid = false;
  try {
    board = visible ? selectDispatchBoard(state, planId) : undefined;
  } catch {
    projectionInvalid = true;
  }

  const defaultOrder = board?.orders.find(({ stage, assignableResourceIds }) =>
    stage === 'UNLOAD' && assignableResourceIds.length > 0,
  ) ?? board?.orders.find(({ assignableResourceIds }) => assignableResourceIds.length > 0)
    ?? board?.orders[0];
  const selectedOrder = board?.orders.find(
    ({ workOrder }) => workOrder.id === workflow.selectedWorkOrderId,
  ) ?? defaultOrder;
  const selectedResource = selectedOrder?.resources.find(
    ({ id }) => id === workflow.selectedResourceId,
  ) ?? selectedOrder?.resources.find(({ id }) => id === selectedOrder.workOrder.resourceId)
    ?? selectedOrder?.resources.find(({ assignable }) => assignable);
  const selectedWorkOrderId = selectedOrder?.workOrder.id;
  const selectedResourceId = selectedResource?.id;
  const scenarioId = state.scenario.activeScenarioId;
  const interlocked = state.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP';
  const returnUrl = `/dispatch/plans/${encodeURIComponent(planId)}/tasks?scenarioId=${encodeURIComponent(scenarioId)}&from=dispatch-board`;
  const interlockUrl = `/safety/interlocks?scenarioId=${encodeURIComponent(scenarioId)}&planId=${encodeURIComponent(planId)}&from=dispatch-board`;
  const audits = state.configAudit.commandAudit.filter(
    ({ record }) => record.objectId === selectedWorkOrderId && record.action.startsWith('DB-'),
  );

  const permitted = (permission: string): boolean => authorize({
    session: state.session,
    pageId: 'UI-005',
    permission,
    objectScope: { type: 'AREA', value: 'AREA-A' },
  }).allow;
  const bindingPermission = selectedOrder?.workOrder.resourceId
    ? 'dispatch:reassign'
    : 'dispatch:assign';
  const canBind = permitted(bindingPermission);
  const canSend = permitted('dispatch:send');
  const canPause = permitted('dispatch:pause');

  const demoPlayback = useDemoSequence({
    sequence: dispatchDemoSequence,
    autoplay: demoAutoplayEnabled(location.search),
    onReset: async () => {
      setFeedback(undefined);
      setPageError(undefined);
      const reset = await runtime.commands.resetScenario(scenarioId);
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
      const generate = await runtime.taskDecomposition.commands.generateTasks(planId);
      if (!generate.ok) throw new Error(generate.message);
      const confirmTasks = await runtime.taskDecomposition.commands.confirmTasks(planId);
      if (!confirmTasks.ok) throw new Error(confirmTasks.message);
      const currentBoard = selectDispatchBoard(runtime.store.getState(), planId);
      if (!currentBoard) throw new Error('未找到可演示的派工看板');
      const order = currentBoard.orders.find(({ stage, assignableResourceIds }) =>
        stage === 'UNLOAD' && assignableResourceIds.length > 0,
      ) ?? currentBoard.orders.find(({ assignableResourceIds }) => assignableResourceIds.length > 0);
      const resource = order?.resources.find(({ assignable }) => assignable);
      if (!order || !resource) throw new Error('未找到可演示的工单与资源');
      demoOrderId.current = order.workOrder.id;
      demoResourceId.current = resource.id;
      runtime.dispatchBoard.workflow.selectWorkOrder(order.workOrder.id);
      runtime.dispatchBoard.workflow.selectResource(resource.id);
    },
    onStep: async (step) => {
      const workOrderId = demoOrderId.current;
      const resourceId = demoResourceId.current;
      if (!workOrderId || !resourceId) throw new Error('演示资源上下文缺失');
      if (step.id === 'choose') {
        runtime.dispatchBoard.workflow.selectWorkOrder(workOrderId);
        runtime.dispatchBoard.workflow.selectResource(resourceId);
        setFeedback(`已选出最优候选资源 ${resourceId}。`);
      }
      if (step.id === 'bind') {
        const result = await runtime.dispatchBoard.commands.bindDispatchResource({
          workOrderId,
          resourceId,
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('资源绑定完成，工单进入可下发状态。');
      }
      if (step.id === 'dispatch') {
        const result = await runtime.dispatchBoard.commands.dispatchWorkOrder(workOrderId);
        if (!result.ok) throw new Error(result.message);
        setFeedback('派工指令已提交，并已写入追踪与审计记录。');
      }
      if (step.id === 'receipt') {
        const result = await runtime.dispatchBoard.commands.acknowledgeWorkOrder(workOrderId);
        if (!result.ok) throw new Error(result.message);
        setFeedback('现场接单回执已回写。');
      }
      if (step.id === 'execute') {
        const result = await runtime.dispatchBoard.commands.startWorkOrder(workOrderId);
        if (!result.ok) throw new Error(result.message);
        setFeedback('工单已进入执行；生产调度系统不越级直控现场设备。');
      }
    },
  });

  const execute = (action: CommandAction): Promise<CommandResult> => {
    if (inFlightCommand.current) return inFlightCommand.current;
    retryAction.current = action;
    setLoading(true);
    setPageError(undefined);
    const operation = (async () => {
      const result = await action();
      setPageError(resultError(result));
      if (result.ok) {
        setFeedback(`命令 ${result.commandId} · ${result.traceId} · ${result.auditLogId}`);
      }
      return result;
    })();
    inFlightCommand.current = operation;
    void operation.finally(() => {
      if (inFlightCommand.current === operation) inFlightCommand.current = undefined;
      setLoading(false);
    });
    return operation;
  };

  const retry = (): void => {
    if (retryAction.current) void execute(retryAction.current);
  };

  const selectOrder = (workOrderId: string): void => {
    runtime.dispatchBoard.workflow.selectWorkOrder(workOrderId);
    setPageError(undefined);
    setFeedback(undefined);
  };

  const bind = (): void => {
    if (!selectedWorkOrderId || !selectedResourceId) return;
    void execute(() => runtime.dispatchBoard.commands.bindDispatchResource({
      workOrderId: selectedWorkOrderId,
      resourceId: selectedResourceId,
    }));
  };

  const invokeForSelected = (
    action: (workOrderId: string) => Promise<CommandResult>,
  ): void => {
    if (selectedWorkOrderId) void execute(() => action(selectedWorkOrderId));
  };

  const renderBody = () => {
    if (!visible || !plan) return <PageStatePanel state="not-found" />;
    if (plan.status !== 'DECOMPOSED' || projectionInvalid || !board) {
      return (
        <Alert
          type="error"
          showIcon
          title="业务处理失败"
          description={
            <Space orientation="vertical">
              <span>UI-005 仅接收 UI-004 已确认的 C06 工单。</span>
              <span><code>DEMO-SCENARIO-001</code></span>
              <Link to={returnUrl}>返回 UI-004 任务拆解</Link>
            </Space>
          }
        />
      );
    }
    if (board.orders.length === 0) return <PageStatePanel state="empty" />;

    const bindDisabled = loading
      || interlocked
      || !canBind
      || selectedOrder?.workOrder.status !== 'READY'
      || !selectedResource?.assignable;
    return (
      <>
        {interlocked ? (
          <Alert
            type="error"
            showIcon
            title="TOS-IL-001：安全联锁已阻断派工写操作"
            action={<Link to={interlockUrl}>前往 UI-009 安全联锁</Link>}
          />
        ) : null}
        {loading ? <Alert type="info" showIcon title="正在提交派工命令" /> : null}
        {pageError && isNetworkError(pageError) ? (
          <PageStatePanel
            state="network-error"
            errorCode={pageError.errorCode}
            recoveryLabel="重试原动作"
            onRecover={retry}
          />
        ) : pageError ? (
          <Alert
            type="error"
            showIcon
            title={`${pageError.errorCode}: ${pageError.message}`}
            action={pageError.errorCode === 'TOS-IL-001'
              ? <Link to={interlockUrl}>前往 UI-009 安全联锁</Link>
              : <Button onClick={retry}>重试原动作</Button>}
          />
        ) : null}
        {feedback ? <Alert type="success" showIcon title={feedback} /> : null}
        <DispatchContextHeader
          plan={board.plan}
          ruleVersion={board.ruleVersion}
          sourceUi={board.sourceUi}
          scenarioId={scenarioId}
        />
        <DispatchKpiStrip kpis={board.kpis} />
        <div className="dispatch-board-main">
          <WorkOrderQueue
            orders={board.orders}
            selectedWorkOrderId={selectedWorkOrderId}
            onSelect={selectOrder}
          />
          <div className="dispatch-board-center">
            <WorkOrderDetailPanel item={selectedOrder} audits={audits} />
            <ExecutionFeedbackPanel
              item={selectedOrder}
              loading={loading}
              interlocked={interlocked}
              canSend={canSend}
              canPause={canPause}
              onDispatch={() => invokeForSelected(runtime.dispatchBoard.commands.dispatchWorkOrder)}
              onAcknowledge={() => invokeForSelected(runtime.dispatchBoard.commands.acknowledgeWorkOrder)}
              onStart={() => invokeForSelected(runtime.dispatchBoard.commands.startWorkOrder)}
              onPause={() => invokeForSelected(runtime.dispatchBoard.commands.pauseWorkOrder)}
              onComplete={() => invokeForSelected(runtime.dispatchBoard.commands.completeWorkOrder)}
            />
          </div>
          <div className="dispatch-board-side">
            <ResourceAssignmentPanel
              item={selectedOrder}
              selectedResourceId={selectedResourceId}
              loading={loading}
              bindDisabled={bindDisabled}
              onSelect={(resourceId) => runtime.dispatchBoard.workflow.selectResource(resourceId)}
              onBind={bind}
            />
          </div>
        </div>
      </>
    );
  };

  return (
    <div
      className="dispatch-board-page"
      aria-label="UI-005 派工看板页面"
      data-demo-active={demoPlayback.status === 'running'}
    >
      <div className="dispatch-board-page-header">
        <PageIdentity pageId="UI-005" name="派工看板" path="/dispatch/work-orders" />
        <Space size={8} wrap>
          <Tag>/dispatch/work-orders</Tag>
          <Tag color="processing">{scenarioId}</Tag>
          <Tag>{planId}</Tag>
        </Space>
      </div>
      <Breadcrumb
        items={[
          { title: <Link to={returnUrl}>UI-004 任务拆解</Link> },
          { title: planId },
          { title: '派工看板' },
        ]}
      />
      <DemoPlaybackControls
        title="资源智能匹配与派工演示"
        boundary="生产调度系统负责匹配、绑定和下发；设备控制系统（ECS）与可编程逻辑控制器（PLC）负责现场执行。"
        playback={demoPlayback}
      />
      <DemoStageRail
        ariaLabel="资源匹配与派工演示阶段"
        currentStepIndex={demoPlayback.currentStepIndex}
        stages={dispatchDemoStages}
      />
      {renderBody()}
    </div>
  );
}
