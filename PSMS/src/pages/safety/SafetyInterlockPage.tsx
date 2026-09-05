import { Alert, Breadcrumb, Space, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';

import { actionPolicies } from '../../auth';
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
import InterlockActionPanel from '../../features/safety-interlock/components/InterlockActionPanel';
import InterlockContextHeader from '../../features/safety-interlock/components/InterlockContextHeader';
import InterlockDetailPanel from '../../features/safety-interlock/components/InterlockDetailPanel';
import InterlockKpiStrip from '../../features/safety-interlock/components/InterlockKpiStrip';
import InterlockLedger from '../../features/safety-interlock/components/InterlockLedger';
import {
  parseInterlockQueryContext,
  selectSafetyInterlockBoard,
  type SafetyInterlockBoard,
  type SafetyInterlockGatewayResult,
} from '../../features/safety-interlock';
import '../../features/safety-interlock/safety-interlock.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import {
  useDemoRuntime,
  useDemoSelector,
  useSafetyInterlockWorkflow,
} from '../../runtime';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;
type CommandAction = () => Promise<CommandResult>;

const interlockDemoStages = [
  { id: 'detect', label: '检测人员侵入', detail: '传感器触发风险输入' },
  { id: 'lock', label: '联锁停机', detail: '调度写操作立即阻断' },
  { id: 'verifyLock', label: '核验联锁状态', detail: '确认锁定与风险输入' },
  { id: 'request', label: '申请复位', detail: '记录清场与传感器复核' },
  { id: 'approve', label: '双人审批', detail: '申请人与审批人职责分离' },
  { id: 'restore', label: '登记恢复', detail: '仅登记，不直接控制设备' },
] as const;

const interlockDemoSequence = createDemoSequence(interlockDemoStages.map((stage, index) => ({
  id: stage.id,
  durationMs: index === interlockDemoStages.length - 1
    ? 3_200
    : index < 3 ? 900 : 1_200,
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
    message: error instanceof Error ? error.message : 'Interlock API response was invalid.',
  };
}

export default function SafetyInterlockPage() {
  const location = useLocation();
  const context = parseInterlockQueryContext(location.search);
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useSafetyInterlockWorkflow((current) => current);
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
    promise: ReturnType<typeof runtime.safetyInterlock.gateway.listInterlocks>;
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
      : runtime.safetyInterlock.gateway.listInterlocks();
    if (promise !== existing?.promise) {
      listFlight.current = { request: listRequest, scenarioId, promise };
    }
    void promise
      .then((response: SafetyInterlockGatewayResult) => {
        if (!active) return;
        if (!response.ok) {
          setListError({ errorCode: response.errorCode, message: response.message });
          return;
        }
        if (response.data.scenarioId !== scenarioId) {
          setListError({
            errorCode: 'DEMO-SCENARIO-001',
            message: 'Interlock response scenario does not match the active scenario.',
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

  let board: SafetyInterlockBoard | undefined;
  let projectionInvalid = false;
  try {
    board = visible ? selectSafetyInterlockBoard(state, context) : undefined;
  } catch {
    projectionInvalid = true;
  }

  const selectedItem = board?.items.find(
    ({ interlock }) => interlock.id === workflow.selectedInterlockId,
  ) ?? board?.items[0];
  const selectedInterlockId = selectedItem?.interlock.id;
  const audits = state.configAudit.commandAudit.filter(
    ({ record }) => record.objectId === selectedInterlockId && /^SI-0[1-5]$/.test(record.action),
  );
  const approvalUserId = workflow.approvalDraft.trim();
  const approvalUser = state.configAudit.userRoles.find(({ id }) => id === approvalUserId);
  const approvalUserValid = approvalUser?.status === 'ACTIVE'
    && actionPolicies['interlock:approve'].some((role) => role === approvalUser.roleCode);
  const resetRequestText = typeof workflow.resetRequestDraft.note === 'string'
    ? workflow.resetRequestDraft.note
    : '';
  const resetRequestReady = workflow.resetRequestDraft.requested === true
    && resetRequestText.trim().length > 0;

  const demoPlayback = useDemoSequence({
    sequence: interlockDemoSequence,
    autoplay: demoAutoplayEnabled(location.search),
    onReset: async () => {
      setFeedback(undefined);
      setCommandError(undefined);
      const result = await runtime.commands.resetScenario(state.scenario.activeScenarioId);
      if (!result.ok) throw new Error(result.message);
      runtime.safetyInterlock.workflow.selectInterlock('IL-001');
      runtime.safetyInterlock.workflow.setReason('演示：安全联锁闭环');
      runtime.safetyInterlock.workflow.setApprovalDraft('USER-004');
      runtime.safetyInterlock.workflow.setResetRequestDraft({
        requested: true,
        note: '现场清场完成；传感器复核正常',
      });
    },
    onStep: async (step) => {
      const interlockId = 'IL-001';
      runtime.safetyInterlock.workflow.selectInterlock(interlockId);
      if (step.id === 'detect') {
        setFeedback('人员侵入风险已被传感器识别。');
      }
      if (step.id === 'lock') {
        setFeedback('安全联锁已锁定，派工与异常写操作同步阻断。');
      }
      if (step.id === 'verifyLock') {
        const current = runtime.store.getState().interlock.interlocks.find(
          ({ id }) => id === interlockId,
        );
        if (current?.status !== 'LOCKED') throw new Error('演示联锁未处于锁定状态');
        setFeedback('联锁锁定状态与人员侵入风险输入已核验。');
      }
      if (step.id === 'request') {
        const result = await runtime.safetyInterlock.commands.requestReset({
          interlockId,
          reason: '演示：现场清场后申请复位',
          resetRequest: {
            requested: true,
            note: '现场清场完成；传感器复核正常',
          },
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('复位申请已提交，等待独立审批人复核。');
      }
      if (step.id === 'approve') {
        const result = await runtime.safetyInterlock.commands.approveInterlock({
          interlockId,
          reason: '演示：独立审批人复核通过',
          approvalUserId: 'USER-004',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('双人职责分离审批完成。');
      }
      if (step.id === 'restore') {
        const result = await runtime.safetyInterlock.commands.restoreInterlock({
          interlockId,
          reason: '演示：登记恢复记录',
        });
        if (!result.ok) throw new Error(result.message);
        setFeedback('恢复记录已登记；真实设备复位仍由现场控制系统完成。');
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

  const selectInterlock = (interlockId: string): void => {
    runtime.safetyInterlock.workflow.selectInterlock(interlockId);
    setCommandError(undefined);
    setFeedback(undefined);
  };

  const forSelected = (
    action: (interlockId: string) => Promise<CommandResult>,
  ): void => {
    if (selectedInterlockId) void execute(() => action(selectedInterlockId));
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
        {state.scenario.activeFault.type === 'INTERLOCK_FORCE_STOP' ? (
          <Alert
            type="error"
            showIcon
            title="SCN-05 强制停机安全边界"
            description="仅演示联锁审批与恢复记录；不会绕过安全联锁或执行真实设备控制。"
          />
        ) : null}
        {commandLoading ? <Alert type="info" showIcon title="正在提交联锁命令" /> : null}
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
        <InterlockContextHeader board={board} />
        <InterlockKpiStrip kpis={board.kpis} />
        <div className="safety-interlock-main">
          <InterlockLedger
            items={board.items}
            selectedInterlockId={selectedInterlockId}
            onSelect={selectInterlock}
          />
          <InterlockDetailPanel item={selectedItem} audits={audits} />
          <div className="interlock-action-column">
            <InterlockActionPanel
              item={selectedItem}
              loading={commandLoading}
              reason={workflow.reason}
              approvalUserId={workflow.approvalDraft}
              approvalUserValid={approvalUserValid}
              resetRequestText={resetRequestText}
              resetRequestReady={resetRequestReady}
              onReason={runtime.safetyInterlock.workflow.setReason}
              onApprovalUser={runtime.safetyInterlock.workflow.setApprovalDraft}
              onResetRequest={(value) => runtime.safetyInterlock.workflow.setResetRequestDraft(
                value.trim() ? { requested: true, note: value } : {},
              )}
              onTrigger={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.triggerInterlock({
                  interlockId,
                  reason: workflow.reason,
                }))}
              onReceipt={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.receiptInterlock({
                  interlockId,
                  reason: workflow.reason,
                }))}
              onRequestReset={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.requestReset({
                  interlockId,
                  reason: workflow.reason,
                  resetRequest: workflow.resetRequestDraft,
                }))}
              onApprove={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.approveInterlock({
                  interlockId,
                  reason: workflow.reason,
                  approvalUserId: workflow.approvalDraft,
                }))}
              onRestore={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.restoreInterlock({
                  interlockId,
                  reason: workflow.reason,
                }))}
              onRequestOverride={() => forSelected((interlockId) =>
                runtime.safetyInterlock.commands.requestOverride({
                  interlockId,
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
      className="safety-interlock-page"
      aria-label="UI-009 安全联锁页面"
      data-demo-active={demoPlayback.status === 'running'}
    >
      <div className="safety-interlock-header">
        <PageIdentity pageId="UI-009" name="安全联锁" path="/safety/interlocks" />
        <Space size={8} wrap>
          <Tag>/safety/interlocks</Tag>
          <Tag color="processing">{state.scenario.activeScenarioId}</Tag>
          {context.exceptionId ? <Tag>{context.exceptionId}</Tag> : null}
        </Space>
      </div>
      <Breadcrumb
        items={[
          { title: <Link to={board?.returnExceptionUrl ?? '/monitor/exceptions'}>UI-008 异常处置</Link> },
          { title: '安全联锁' },
        ]}
      />
      <DemoPlaybackControls
        title="安全联锁闭环演示"
        boundary="页面仅记录联锁、回执、复位申请、审批与恢复；不会绕过联锁，也不会直接控制 PLC/ECS。"
        playback={demoPlayback}
      />
      <DemoStageRail
        ariaLabel="安全联锁演示阶段"
        currentStepIndex={demoPlayback.currentStepIndex}
        stages={interlockDemoStages}
      />
      {renderWorkspace()}
    </div>
  );
}
