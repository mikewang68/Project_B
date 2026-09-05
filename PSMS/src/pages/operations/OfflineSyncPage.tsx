import { Space, Tag } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

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
import OfflineCommandFeedback from '../../features/offline-sync/components/OfflineCommandFeedback';
import OfflineContextHeader from '../../features/offline-sync/components/OfflineContextHeader';
import OfflineKpiStrip from '../../features/offline-sync/components/OfflineKpiStrip';
import OfflinePacketActionPanel from '../../features/offline-sync/components/OfflinePacketActionPanel';
import OfflinePacketDetail from '../../features/offline-sync/components/OfflinePacketDetail';
import OfflinePacketLedger from '../../features/offline-sync/components/OfflinePacketLedger';
import {
  parseOfflinePacketQuery,
  selectOfflinePacketBoard,
  type OfflinePacketBoard,
  type OfflinePacketGatewayResult,
} from '../../features/offline-sync';
import '../../features/offline-sync/offline-sync.css';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import PageStatePanel from '../../features/plan-entry/components/PageStatePanel';
import {
  useDemoRuntime,
  useDemoSelector,
  useOfflinePacketWorkflow,
} from '../../runtime';

type PageError = Readonly<{ errorCode: PublicErrorCode; message: string }>;
type CommandAction = () => Promise<CommandResult>;

const offlineDemoStages = [
  { id: 'cache', label: '终端离线缓存', detail: '保留包版本与操作时间' },
  { id: 'reconnect', label: '网络恢复', detail: '发现待同步离线包' },
  { id: 'compare', label: '版本比对', detail: '识别包版本与服务端冲突' },
  { id: 'retry', label: '进入恢复队列', detail: '保留冲突证据后重试' },
  { id: 'upload', label: '上传离线包', detail: '提交包体与版本信息' },
  { id: 'validate', label: '联网校验', detail: '确认字段与版本策略' },
  { id: 'merge', label: '合并并回写', detail: '服务端版本更新并写入审计' },
] as const;

const offlineDemoSequence = createDemoSequence(offlineDemoStages.map((stage, index) => ({
  id: stage.id,
  durationMs: index === offlineDemoStages.length - 1
    ? 3_000
    : index < 2 ? 700 : index < 5 ? 900 : 1_200,
})));

function isNetworkError(error: PageError): boolean {
  return error.errorCode.startsWith('TOS-EXT-');
}

function hasAreaAVisibility(dataScope: readonly string[]): boolean {
  return dataScope.includes('*') || dataScope.includes('GLOBAL') || dataScope.includes('AREA-A');
}

function externalError(error: unknown): PageError {
  return {
    errorCode: 'TOS-EXT-001',
    message: error instanceof Error ? error.message : 'OfflinePacket API response was invalid.',
  };
}

export default function OfflineSyncPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const context = parseOfflinePacketQuery(location.search);
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const workflow = useOfflinePacketWorkflow((current) => current);
  const visible = hasAreaAVisibility(state.session.dataScope);
  const [listLoading, setListLoading] = useState(visible);
  const [listError, setListError] = useState<PageError>();
  const [listRequest, setListRequest] = useState(0);
  const [resetting, setResetting] = useState(false);
  const listFlight = useRef<Readonly<{
    request: number;
    scenarioId: string;
    promise: ReturnType<typeof runtime.offlineSync.gateway.listPackets>;
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
      : runtime.offlineSync.gateway.listPackets();
    if (promise !== existing?.promise) {
      listFlight.current = { request: listRequest, scenarioId, promise };
    }
    void promise
      .then((response: OfflinePacketGatewayResult) => {
        if (!active) return;
        if (!response.ok) {
          setListError({ errorCode: response.errorCode, message: response.message });
          return;
        }
        if (response.data.scenarioId !== scenarioId) {
          setListError({
            errorCode: 'DEMO-SCENARIO-001',
            message: 'OfflinePacket response scenario does not match the active scenario.',
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

  let board: OfflinePacketBoard | undefined;
  let projectionInvalid = false;
  try {
    board = visible ? selectOfflinePacketBoard(state, context) : undefined;
  } catch {
    projectionInvalid = true;
  }

  const selectedItem = board?.items.find(
    ({ packet }) => packet.id === workflow.selectedPacketId,
  ) ?? board?.items[0];
  const selectedPacketId = selectedItem?.packet.id;
  const audits = state.configAudit.commandAudit.filter(
    ({ record }) => record.objectId === selectedPacketId && /^OS-0[1-5]$/.test(record.action),
  );
  const commandError = workflow.lastFeedback && !workflow.lastFeedback.ok
    ? {
        errorCode: workflow.lastFeedback.errorCode ?? 'DEMO-SCENARIO-001',
        message: workflow.lastFeedback.message,
      }
    : undefined;

  const demoPlayback = useDemoSequence({
    sequence: offlineDemoSequence,
    autoplay: demoAutoplayEnabled(location.search),
    onReset: async () => {
      setListError(undefined);
      const result = await runtime.commands.resetScenario('SCN-01');
      if (!result.ok) throw new Error(result.message);
      runtime.offlineSync.workflow.selectPacket('OFF-001');
      runtime.offlineSync.workflow.setReason('演示：离线包冲突恢复与合并');
      runtime.offlineSync.workflow.setValidationDraft({ valid: true, issues: [] });
      runtime.offlineSync.workflow.recordFeedback(undefined);
    },
    onStep: async (step) => {
      const packetId = 'OFF-001';
      runtime.offlineSync.workflow.selectPacket(packetId);
      if (step.id === 'cache') {
        runtime.offlineSync.workflow.recordFeedback({
          ok: true,
          traceId: '本地缓存',
          auditLogId: '待联网写入',
          commandId: '离线模式',
          message: '终端已保留离线包和本地版本。',
          idempotent: false,
        });
      }
      if (step.id === 'compare') {
        runtime.offlineSync.workflow.recordFeedback({
          ok: true,
          traceId: '版本比对',
          auditLogId: '待合并写入',
          commandId: '冲突识别',
          message: '检测到包版本 2 与服务端版本 1 不一致。',
          idempotent: false,
        });
      }
      if (step.id === 'retry') {
        const result = await runtime.offlineSync.commands.retryPacket({
          packetId,
          reason: '演示：版本冲突进入恢复队列',
        });
        if (!result.ok) throw new Error(result.message);
      }
      if (step.id === 'upload') {
        const result = await runtime.offlineSync.commands.uploadPacket({
          packetId,
          reason: '演示：网络恢复后上传离线包',
        });
        if (!result.ok) throw new Error(result.message);
      }
      if (step.id === 'validate') {
        const result = await runtime.offlineSync.commands.validatePacket({
          packetId,
          reason: '演示：联网校验通过',
          validation: { valid: true, issues: [] },
        });
        if (!result.ok) throw new Error(result.message);
      }
      if (step.id === 'merge') {
        const result = await runtime.offlineSync.commands.mergePacket({
          packetId,
          reason: '演示：合并并回写服务端版本',
        });
        if (!result.ok) throw new Error(result.message);
      }
    },
  });

  const execute = (action: CommandAction): Promise<CommandResult> => {
    if (inFlightCommand.current) return inFlightCommand.current;
    retryAction.current = action;
    const operation = action();
    inFlightCommand.current = operation;
    void operation.finally(() => {
      if (inFlightCommand.current === operation) inFlightCommand.current = undefined;
    });
    return operation;
  };

  const retryCommand = (): void => {
    if (retryAction.current) void execute(retryAction.current);
  };

  const selectPacket = (packetId: string): void => {
    runtime.offlineSync.workflow.selectPacket(packetId);
    runtime.offlineSync.workflow.recordFeedback(undefined);
  };

  const forSelected = (
    action: (packetId: string) => Promise<CommandResult>,
  ): void => {
    if (selectedPacketId) void execute(() => action(selectedPacketId));
  };

  const resetToScenarioOne = (): void => {
    if (resetting) return;
    setResetting(true);
    void runtime.commands.resetScenario('SCN-01')
      .then((result) => {
        if (!result.ok) {
          setListError({ errorCode: result.errorCode, message: result.message });
          return;
        }
        retryAction.current = undefined;
        const query = new URLSearchParams(location.search);
        query.set('scenarioId', 'SCN-01');
        navigate(`${location.pathname}?${query.toString()}`, { replace: true });
      })
      .finally(() => setResetting(false));
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
    if (board.items.length === 0) {
      return context.packetId
        ? <PageStatePanel state="not-found" />
        : <PageStatePanel state="empty" />;
    }

    return (
      <>
        {commandError ? (
          <PageStatePanel
            state={isNetworkError(commandError) ? 'network-error' : 'business-error'}
            errorCode={commandError.errorCode}
            recoveryLabel="重试原动作"
            onRecover={retryCommand}
          />
        ) : null}
        <div className="offline-sync-workspace">
          <OfflinePacketLedger
            items={board.items}
            selectedPacketId={selectedPacketId}
            onSelect={selectPacket}
          />
          <OfflinePacketDetail item={selectedItem} audits={audits} />
          <div className="offline-action-panel">
            <OfflinePacketActionPanel
              item={selectedItem}
              pendingAction={workflow.pendingAction}
              reason={workflow.reason}
              validationDraft={workflow.validationDraft}
              onReason={runtime.offlineSync.workflow.setReason}
              onValidationDraft={runtime.offlineSync.workflow.setValidationDraft}
              onUpload={() => forSelected((packetId) =>
                runtime.offlineSync.commands.uploadPacket({
                  packetId,
                  reason: workflow.reason,
                }))}
              onValidate={() => forSelected((packetId) =>
                runtime.offlineSync.commands.validatePacket({
                  packetId,
                  reason: workflow.reason,
                  validation: workflow.validationDraft,
                }))}
              onMerge={() => forSelected((packetId) =>
                runtime.offlineSync.commands.mergePacket({
                  packetId,
                  reason: workflow.reason,
                }))}
              onReject={() => forSelected((packetId) =>
                runtime.offlineSync.commands.rejectPacket({
                  packetId,
                  reason: workflow.reason,
                }))}
              onRetry={() => forSelected((packetId) =>
                runtime.offlineSync.commands.retryPacket({
                  packetId,
                  reason: workflow.reason,
                }))}
            />
            <OfflineCommandFeedback
              feedback={workflow.lastFeedback}
              pendingAction={workflow.pendingAction}
              audits={audits}
            />
          </div>
        </div>
      </>
    );
  };

  return (
    <div
      className="offline-sync-page"
      aria-label="UI-010 离线同步页面"
      data-demo-active={demoPlayback.status === 'running'}
    >
      <div className="offline-sync-header">
        <PageIdentity pageId="UI-010" name="离线同步" path="/operations/offline-sync" />
        <Space size={8} wrap>
          <Tag>/operations/offline-sync</Tag>
          <Tag color="processing">{state.scenario.activeScenarioId}</Tag>
          {selectedItem ? <Tag>{selectedItem.packet.offlinePackageNo}</Tag> : null}
        </Space>
      </div>
      {board ? (
        <OfflineContextHeader
          board={board}
          activeScenarioId={state.scenario.activeScenarioId}
          resetting={resetting}
          onReset={resetToScenarioOne}
        />
      ) : null}
      {board ? <OfflineKpiStrip kpis={board.kpis} /> : null}
      <DemoPlaybackControls
        title="离线缓存与冲突合并演示"
        boundary="仅推进离线包状态机并回写服务端版本；不会改动作业单、异常、联锁、计划或资源数据。"
        playback={demoPlayback}
      />
      <DemoStageRail
        ariaLabel="离线同步演示阶段"
        currentStepIndex={demoPlayback.currentStepIndex}
        stages={offlineDemoStages}
      />
      {renderWorkspace()}
    </div>
  );
}
