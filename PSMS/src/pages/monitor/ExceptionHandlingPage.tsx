import { Alert, Breadcrumb, Button, Space, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

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
import ExceptionActionPanel from '../../features/exception-handling/components/ExceptionActionPanel';
import ExceptionContextHeader from '../../features/exception-handling/components/ExceptionContextHeader';
import ExceptionDetailPanel from '../../features/exception-handling/components/ExceptionDetailPanel';
import ExceptionKpiStrip from '../../features/exception-handling/components/ExceptionKpiStrip';
import ExceptionLedger from '../../features/exception-handling/components/ExceptionLedger';
import {
  parseExceptionQueryContext,
  selectExceptionHandlingBoard,
  type ExceptionHandlingBoard,
  type ExceptionHandlingGatewayResult,
} from '../../features/exception-handling';
import '../../features/exception-handling/exception-handling.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import {
  useDemoRuntime,
  useDemoSelector,
  useExceptionHandlingWorkflow,
} from '../../runtime';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;
type CommandAction = () => Promise<CommandResult>;

const exceptionDemoStages = [
  { id: 'detect', label: '识别异常', detail: '设备离线事件进入台账' },
  { id: 'ack', label: '确认异常', detail: '记录响应人和时间' },
  { id: 'assign', label: '分派处理', detail: '明确责任班组' },
  { id: 'handle', label: '提交处置', detail: '上传诊断与恢复证据' },
  { id: 'review', label: '复核证据', detail: '核对影响与恢复结果' },
  { id: 'close', label: '闭环归档', detail: '关闭异常并保留审计链' },
] as const;

const exceptionDemoSequence = createDemoSequence(exceptionDemoStages.map((stage, index) => ({
  id: stage.id,
  durationMs: index === exceptionDemoStages.length - 1 ? 3_100 : index < 2 ? 900 : 1_150,
})));

function resultError(result: CommandResult): PageError | undefined {
  return result.ok ? undefined : { errorCode: result.errorCode, message: result.message };
}

function isNetworkError(error: PageError): boolean {
  return error.errorCode.startsWith('TOS-EXT-');
}

function hasAreaAVisibility(dataScope: readonly string[]): boolean {
  return dataScope.includes('*') || dataScope.includes('GLOBAL') || dataScope.includes('AREA-A');
}

function externalError(error: unknown): PageError {
  return {
    errorCode: 'TOS-EXT-001',
    message: error instanceof Error ? error.message : 'Exception API response was invalid.',
  };
}

export default function ExceptionHandlingPage() {
  const location = useLocation();
  const context = parseExceptionQueryContext(location.search);
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useExceptionHandlingWorkflow((current) => current);
  const visible = hasAreaAVisibility(state.session.dataScope);
  const [listLoading, setListLoading] = useState(visible);
  const [listError, setListError] = useState<PageError>();
  const [commandLoading, setCommandLoading] = useState(false);
  const [commandError, setCommandError] = useState<PageError>();
  const [feedback, setFeedback] = useState<string>();
  const [listRequest, setListRequest] = useState(0);
  const listFlight = useRef<Readonly<{
    request: number;
    scenarioId: string;
    promise: ReturnType<typeof runtime.exceptionHandling.gateway.listExceptions>;
  }>>();
  const retryAction = useRef<CommandAction>();
  const inFlightCommand = useRef<Promise<CommandResult> | undefined>(undefined);

  useEffect(() => {
    if (!visible) {
      setListLoading(false);
      return undefined;
    }
    let active = true;
    setListLoading(true);
    setListError(undefined);
    const scenarioId = state.scenario.activeScenarioId;
    const existing = listFlight.current;
    const promise = existing?.request === listRequest && existing.scenarioId === scenarioId
      ? existing.promise
      : runtime.exceptionHandling.gateway.listExceptions();
    if (promise !== existing?.promise) {
      listFlight.current = { request: listRequest, scenarioId, promise };
    }
    void promise
      .then((response: ExceptionHandlingGatewayResult) => {
        if (!active) return;
        if (!response.ok) {
          setListError({ errorCode: response.errorCode, message: response.message });
          return;
        }
        if (response.data.scenarioId !== scenarioId) {
          setListError({
            errorCode: 'DEMO-SCENARIO-001',
            message: 'Exception response scenario does not match the active scenario.',
          });
        }
      })
      .catch((error: unknown) => {
        if (active) setListError(externalError(error));
      })
      .finally(() => {
        if (active) setListLoading(false);
      });
    return () => { active = false; };
  }, [listRequest, runtime, state.scenario.activeScenarioId, visible]);

  let board: ExceptionHandlingBoard | undefined;
  let projectionInvalid = false;
  try {
    board = visible ? selectExceptionHandlingBoard(state, context) : undefined;
  } catch {
    projectionInvalid = true;
  }

  const selectedItem = board?.items.find(
    ({ exception }) => exception.id === workflow.selectedExceptionId,
  ) ?? board?.items[0];
  const selectedExceptionId = selectedItem?.exception.id;
  const interlocked = selectedItem?.exception.type === 'INTERLOCK'
    || state.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP';
  const audits = state.configAudit.commandAudit.filter(
    ({ record }) => record.objectId === selectedExceptionId && /^EX-0[1-5]$/.test(record.action),
  );

  const demoPlayback = useDemoSequence({
    sequence: exceptionDemoSequence,
    autoplay: demoAutoplayEnabled(location.search),
    onReset: async () => {
      setFeedback(undefined);
      setCommandError(undefined);
      const result = await runtime.commands.resetScenario(state.scenario.activeScenarioId);
      if (!result.ok) throw new Error(result.message);
      runtime.exceptionHandling.workflow.selectException('EX-001');
      runtime.exceptionHandling.workflow.setReason('演示：设备离线异常闭环');
      runtime.exceptionHandling.workflow.setOwnerDraft('TEAM-09');
      runtime.exceptionHandling.workflow.setEvidenceDraft([
        '设备心跳恢复',
        '现场复核通过',
      ]);
    },
    onStep: async (step) => {
      const exceptionId = 'EX-001';
      runtime.exceptionHandling.workflow.selectException(exceptionId);
      if (step.id === 'detect') {
        setFeedback('检测到设备离线，异常已进入处置台账。');
      }
      if (step.id === 'ack') {
        const result = await runtime.exceptionHandling.commands.ackException({
          exceptionId,
          reason: '演示：确认设备离线异常',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('异常已确认，响应时点已记录。');
      }
      if (step.id === 'assign') {
        const result = await runtime.exceptionHandling.commands.assignException({
          exceptionId,
          owner: 'TEAM-09',
          reason: '演示：分派设备维保班组',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('异常已分派给设备维保班组。');
      }
      if (step.id === 'handle') {
        const result = await runtime.exceptionHandling.commands.submitExceptionHandling({
          exceptionId,
          evidence: ['设备心跳恢复', '现场复核通过'],
          reason: '演示：提交恢复证据',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('处置证据已提交，等待复核。');
      }
      if (step.id === 'review') {
        setFeedback('证据、影响范围和恢复结果复核通过。');
      }
      if (step.id === 'close') {
        const result = await runtime.exceptionHandling.commands.closeException({
          exceptionId,
          reason: '演示：复核通过并闭环',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('异常已闭环归档，完整审计链可追溯。');
      }
    },
  });

  const execute = (action: CommandAction): Promise<CommandResult> => {
    if (inFlightCommand.current) return inFlightCommand.current;
    retryAction.current = action;
    setCommandLoading(true);
    setCommandError(undefined);
    const operation = (async () => {
      const result = await action();
      setCommandError(resultError(result));
      if (result.ok) {
        setFeedback(`命令 ${result.commandId} · ${result.traceId} · ${result.auditLogId}`);
      }
      return result;
    })();
    inFlightCommand.current = operation;
    void operation.finally(() => {
      if (inFlightCommand.current === operation) inFlightCommand.current = undefined;
      setCommandLoading(false);
    });
    return operation;
  };

  const retryCommand = (): void => {
    if (retryAction.current) void execute(retryAction.current);
  };

  const selectException = (exceptionId: string): void => {
    runtime.exceptionHandling.workflow.selectException(exceptionId);
    setCommandError(undefined);
    setFeedback(undefined);
  };

  const forSelected = (
    action: (exceptionId: string) => Promise<CommandResult>,
  ): void => {
    if (selectedExceptionId) void execute(() => action(selectedExceptionId));
  };

  const renderWorkspace = () => {
    if (!visible) return <PageStatePanel state="not-found" />;
    if (listLoading) return <PageStatePanel state="loading" />;
    if (listError) {
      return (
        <PageStatePanel
          state={isNetworkError(listError) ? 'network-error' : 'business-error'}
          errorCode={listError.errorCode}
          recoveryLabel="重新加载"
          onRecover={() => setListRequest((value: number) => value + 1)}
        />
      );
    }
    if (projectionInvalid || !board) {
      return <PageStatePanel state="business-error" errorCode="DEMO-SCENARIO-001" />;
    }
    if (board.items.length === 0) return <PageStatePanel state="empty" />;

    return (
      <>
        {interlocked ? (
          <Alert
            type="error"
            showIcon
            title="TOS-IL-001：安全联锁已阻断异常写操作"
            description="C08 仅提供 UI-009 入口，不解除、覆盖或复位联锁。"
          />
        ) : null}
        {commandLoading ? <Alert type="info" showIcon title="正在提交异常命令" /> : null}
        {commandError && isNetworkError(commandError) ? (
          <PageStatePanel
            state="network-error"
            errorCode={commandError.errorCode}
            recoveryLabel="重试原动作"
            onRecover={retryCommand}
          />
        ) : commandError ? (
          <PageStatePanel
            state="business-error"
            errorCode={commandError.errorCode}
            recoveryLabel="重试原动作"
            onRecover={retryCommand}
          />
        ) : null}
        {feedback ? <Alert type="success" showIcon title={feedback} /> : null}
        <ExceptionContextHeader board={board} />
        <ExceptionKpiStrip kpis={board.kpis} />
        <div className="exception-handling-main">
          <ExceptionLedger
            items={board.items}
            selectedExceptionId={selectedExceptionId}
            onSelect={selectException}
          />
          <ExceptionDetailPanel item={selectedItem} audits={audits} />
          <div className="exception-action-column">
            <ExceptionActionPanel
              item={selectedItem}
              loading={commandLoading}
              interlocked={interlocked}
              reason={workflow.reason}
              owner={workflow.ownerDraft}
              evidence={workflow.evidenceDraft}
              onReason={runtime.exceptionHandling.workflow.setReason}
              onOwner={runtime.exceptionHandling.workflow.setOwnerDraft}
              onEvidence={runtime.exceptionHandling.workflow.setEvidenceDraft}
              onAck={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.ackException({
                  exceptionId,
                  reason: workflow.reason,
                }))}
              onAssign={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.assignException({
                  exceptionId,
                  owner: workflow.ownerDraft,
                  reason: workflow.reason,
                }))}
              onHandle={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.submitExceptionHandling({
                  exceptionId,
                  evidence: workflow.evidenceDraft,
                  reason: workflow.reason,
                }))}
              onReview={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.reviewException({
                  exceptionId,
                  reason: workflow.reason,
                }))}
              onClose={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.closeException({
                  exceptionId,
                  reason: workflow.reason,
                }))}
              onReopen={() => forSelected((exceptionId) =>
                runtime.exceptionHandling.commands.reopenException({
                  exceptionId,
                  reason: workflow.reason,
                }))}
            />
          </div>
        </div>
      </>
    );
  };

  return (
    <div
      className="exception-handling-page"
      aria-label="UI-008 异常处置页面"
      data-demo-active={demoPlayback.status === 'running'}
    >
      <div className="exception-handling-header">
        <PageIdentity pageId="UI-008" name="异常处置" path="/monitor/exceptions" />
        <Space size={8} wrap>
          <Tag>/monitor/exceptions</Tag>
          <Tag color="processing">{state.scenario.activeScenarioId}</Tag>
          {context.planId ? <Tag>{context.planId}</Tag> : null}
        </Space>
      </div>
      <Breadcrumb
        items={[
          { title: <Link to="/monitor/operations">UI-007 全流程监控</Link> },
          { title: '异常处置' },
        ]}
      />
      <DemoPlaybackControls
        title="异常识别到闭环演示"
        boundary="异常处置形成确认、分派、证据、复核和关闭审计链；安全联锁异常仍必须转 UI-009 处理。"
        playback={demoPlayback}
      />
      <DemoStageRail
        ariaLabel="异常闭环演示阶段"
        currentStepIndex={demoPlayback.currentStepIndex}
        stages={exceptionDemoStages}
      />
      {renderWorkspace()}
    </div>
  );
}
