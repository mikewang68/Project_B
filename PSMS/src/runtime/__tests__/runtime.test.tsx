import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DEMO_SESSION_STORAGE_KEY } from '../../auth';
import type { ApiSuccessEnvelope } from '../../contracts';
import {
  projectAuditTrail,
  type AuditGateway,
  type AuditTrailWorkflowState,
  type AuditTrailWorkflowStore,
} from '../../features/audit-trail';
import {
  selectDispatchBoard,
  type DispatchBoardCommandService,
  type DispatchBoardGateway,
  type DispatchBoardWorkflowState,
  type DispatchBoardWorkflowStore,
} from '../../features/dispatch-board';
import type {
  ExceptionHandlingCommandService,
  ExceptionHandlingGateway,
  ExceptionHandlingWorkflowState,
  ExceptionHandlingWorkflowStore,
} from '../../features/exception-handling';
import type {
  SafetyInterlockCommandService,
  SafetyInterlockGateway,
  SafetyInterlockWorkflowState,
  SafetyInterlockWorkflowStore,
} from '../../features/safety-interlock';
import type {
  ReportCommandService,
  ReportGateway,
  ReportWorkflowState,
  ReportWorkflowStore,
} from '../../features/reporting';
import type {
  SystemSettingsCommandService,
  SystemSettingsGateway,
  SystemSettingsWorkflowState,
  SystemSettingsWorkflowStore,
} from '../../features/system-settings';
import { selectConfirmedPlan, selectReceptionRecommendations } from '../../features/recommendation';
import {
  selectTaskTree,
  type TaskDecompositionCommandService,
  type TaskDecompositionGateway,
  type TaskDecompositionWorkflowState,
  type TaskDecompositionWorkflowStore,
} from '../../features/task-decomposition';
import { createFixtureSnapshot } from '../../mocks/fixtures';
import { selectPlans } from '../../stores';
import * as runtimeModule from '../DemoRuntimeContext';
import {
  createDemoRuntime,
  DemoRuntimeProvider,
  type DemoRuntime,
  useDemoRuntime,
  useDemoSelector,
  usePlanEntryWorkflow,
  useRecommendationWorkflow,
} from '../DemoRuntimeContext';

type BootstrapDemoRuntime = (
  search: string,
  fetcher: typeof fetch,
) => Promise<DemoRuntime>;

type TaskDecompositionRuntimeProbe = {
  gateway: TaskDecompositionGateway;
  workflow: TaskDecompositionWorkflowStore;
  commands: TaskDecompositionCommandService;
};

type DispatchBoardRuntimeProbe = {
  gateway: DispatchBoardGateway;
  workflow: DispatchBoardWorkflowStore;
  commands: DispatchBoardCommandService;
};

type ExceptionHandlingRuntimeProbe = {
  gateway: ExceptionHandlingGateway;
  workflow: ExceptionHandlingWorkflowStore;
  commands: ExceptionHandlingCommandService;
};

type SafetyInterlockRuntimeProbe = {
  gateway: SafetyInterlockGateway;
  workflow: SafetyInterlockWorkflowStore;
  commands: SafetyInterlockCommandService;
};

type ReportingRuntimeProbe = {
  gateway: ReportGateway;
  workflow: ReportWorkflowStore;
  commands: ReportCommandService;
};

type AuditTrailRuntimeProbe = {
  gateway: AuditGateway;
  workflow: AuditTrailWorkflowStore;
};

type SystemSettingsRuntimeProbe = {
  gateway: SystemSettingsGateway;
  workflow: SystemSettingsWorkflowStore;
  commands: SystemSettingsCommandService;
  timezone: string;
};

function taskRuntime(runtime: DemoRuntime): TaskDecompositionRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { taskDecomposition?: TaskDecompositionRuntimeProbe })
    .taskDecomposition;
}

function dispatchRuntime(runtime: DemoRuntime): DispatchBoardRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { dispatchBoard?: DispatchBoardRuntimeProbe }).dispatchBoard;
}

function exceptionRuntime(runtime: DemoRuntime): ExceptionHandlingRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { exceptionHandling?: ExceptionHandlingRuntimeProbe })
    .exceptionHandling;
}

function safetyInterlockRuntime(runtime: DemoRuntime): SafetyInterlockRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { safetyInterlock?: SafetyInterlockRuntimeProbe })
    .safetyInterlock;
}

function reportingRuntime(runtime: DemoRuntime): ReportingRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { reporting?: ReportingRuntimeProbe }).reporting;
}

function auditTrailRuntime(runtime: DemoRuntime): AuditTrailRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { auditTrail?: AuditTrailRuntimeProbe }).auditTrail;
}

function systemSettingsRuntime(runtime: DemoRuntime): SystemSettingsRuntimeProbe | undefined {
  return (runtime as DemoRuntime & { systemSettings?: SystemSettingsRuntimeProbe }).systemSettings;
}

function createSharedFlowFetcher() {
  let fetchCall = 0;
  return vi.fn(async (input: string | URL | Request) => {
    fetchCall += 1;
    const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
    const rawPlan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
    const workOrderMatch = path.match(/\/mock\/work-orders\/([^/]+)\/(assign|dispatch)$/);
    const apiId = path.endsWith('/recommendation/confirm')
      ? 'API-006'
      : path.endsWith('/recommendation')
        ? 'API-005'
        : path.endsWith('/decompose')
          ? 'API-007'
          : path.endsWith('/assign')
            ? 'API-008'
            : path.endsWith('/dispatch')
              ? 'API-009'
          : undefined;
    const operationId = apiId === 'API-005'
      ? 'GET_mock_plans_id_recommendation'
      : apiId === 'API-006'
        ? 'POST_mock_plans_id_recommendation_confirm'
        : apiId === 'API-007'
          ? 'POST_mock_plans_id_decompose'
          : apiId === 'API-008'
            ? 'POST_mock_work_orders_id_assign'
            : apiId === 'API-009'
              ? 'POST_mock_work_orders_id_dispatch'
          : undefined;
    const data = apiId && operationId
      ? {
          apiId,
          operationId,
          now: '2026-07-16T09:00:00+08:00',
          scenarioId: 'SCN-01',
          items: workOrderMatch
            ? [{ id: decodeURIComponent(workOrderMatch[1]!) }]
            : rawPlan ? [rawPlan] : [],
        }
      : { status: 'ACCEPTED' };
    const envelope: ApiSuccessEnvelope = {
      ok: true,
      data,
      traceId: `TRACE-SHARED-${fetchCall}`,
      auditLogId: `AUD-SHARED-${fetchCall}`,
    };
    return new Response(JSON.stringify(envelope), {
      headers: { 'content-type': 'application/json' },
    });
  });
}

async function prepareConfirmedRecommendation(runtime: DemoRuntime): Promise<void> {
  await expect(runtime.commands.confirmPlan('PLAN-001')).resolves.toMatchObject({ ok: true });
  await expect(runtime.recommendation.commands.calculateRecommendation('PLAN-001'))
    .resolves.toMatchObject({ ok: true });
  const draft = selectReceptionRecommendations(runtime.store.getState(), 'PLAN-001');
  const selected = draft?.candidates.find(({ recommended }) => recommended);
  if (!selected) throw new Error('Recommended C05 candidate missing.');
  await expect(runtime.recommendation.commands.confirmRecommendation({
    planId: 'PLAN-001',
    candidateId: selected.candidateId,
  })).resolves.toMatchObject({ ok: true });
}

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe('DemoRuntimeProvider', () => {
  it('shares one runtime and lets both consumers observe one Store update', () => {
    const runtime = createDemoRuntime();
    const observedRuntimes: DemoRuntime[] = [];

    function Consumer({ label }: { label: string }) {
      observedRuntimes.push(useDemoRuntime());
      const plans = useDemoSelector(selectPlans);
      return <output aria-label={label}>{plans.length}</output>;
    }

    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer label="first plans" />
        <Consumer label="second plans" />
      </DemoRuntimeProvider>,
    );

    expect(observedRuntimes).toHaveLength(2);
    expect(observedRuntimes[0]).toBe(runtime);
    expect(observedRuntimes[1]).toBe(runtime);
    expect(screen.getByLabelText('first plans')).toHaveTextContent('3');
    expect(screen.getByLabelText('second plans')).toHaveTextContent('3');

    act(() => {
      runtime.store.replaceDomainState((candidate) => {
        candidate.plan.plans = candidate.plan.plans.slice(0, 1);
      });
    });

    expect(screen.getByLabelText('first plans')).toHaveTextContent('1');
    expect(screen.getByLabelText('second plans')).toHaveTextContent('1');
  });

  it('shares one workflow and lets both consumers observe workflow snapshot updates', () => {
    const runtime = createDemoRuntime();
    const observedRuntimes: DemoRuntime[] = [];

    function Consumer({ label }: { label: string }) {
      observedRuntimes.push(useDemoRuntime());
      const selectedPlanId = usePlanEntryWorkflow((state) => state.selectedPlanId ?? 'none');
      const retryCount = usePlanEntryWorkflow((state) => state.retryCount);
      return <output aria-label={label}>{`${selectedPlanId}|${retryCount}`}</output>;
    }

    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer label="first workflow" />
        <Consumer label="second workflow" />
      </DemoRuntimeProvider>,
    );

    expect(observedRuntimes).toHaveLength(2);
    expect(observedRuntimes[0]).toBe(runtime);
    expect(observedRuntimes[1]).toBe(runtime);
    expect(observedRuntimes[0]?.workflow).toBe(runtime.workflow);
    expect(observedRuntimes[1]?.workflow).toBe(runtime.workflow);
    expect(screen.getByLabelText('first workflow')).toHaveTextContent('none|0');
    expect(screen.getByLabelText('second workflow')).toHaveTextContent('none|0');

    act(() => {
      runtime.workflow.selectPlan('PLAN-002');
      runtime.workflow.recordRetry();
    });

    expect(screen.getByLabelText('first workflow')).toHaveTextContent('PLAN-002|1');
    expect(screen.getByLabelText('second workflow')).toHaveTextContent('PLAN-002|1');
  });

  it('shares one Store with recommendation runtime and exposes one reactive recommendation workflow', () => {
    const runtime = createDemoRuntime();

    function Consumer() {
      const selectedCandidateId = useRecommendationWorkflow(
        (state) => state.selectedCandidateId ?? 'none',
      );
      return <output aria-label="recommendation selection">{selectedCandidateId}</output>;
    }

    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );

    expect(runtime.recommendation).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(selectConfirmedPlan(runtime.store.getState(), 'PLAN-001')).toBeUndefined();
    expect(screen.getByLabelText('recommendation selection')).toHaveTextContent('none');

    act(() => runtime.recommendation.workflow.selectCandidate('CANDIDATE-001'));
    expect(screen.getByLabelText('recommendation selection')).toHaveTextContent('CANDIDATE-001');
  });

  it('exposes one C06 runtime and one reactive task-decomposition workflow hook', () => {
    const runtime = createDemoRuntime(createSharedFlowFetcher() as typeof fetch);
    const c06 = taskRuntime(runtime);
    const useTaskWorkflow = (runtimeModule as Record<string, unknown>)
      .useTaskDecompositionWorkflow;

    expect(c06).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useTaskWorkflow).toBeTypeOf('function');
    if (!c06 || typeof useTaskWorkflow !== 'function') return;

    const observedSelections: string[] = [];
    function Consumer() {
      const selected = (useTaskWorkflow as <T>(
        selector: (state: TaskDecompositionWorkflowState) => T,
      ) => T)((state) => state.selectedNodeIds.join(',') || 'none');
      observedSelections.push(selected);
      return null;
    }

    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    expect(observedSelections.at(-1)).toBe('none');
    act(() => c06.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']));
    expect(observedSelections.at(-1)).toBe('C06-NODE-PLAN-001-G001-02');
  });

  it('shares C04, C05, and C06 truth through one Store without a reload', async () => {
    const runtime = createDemoRuntime(createSharedFlowFetcher() as typeof fetch);
    const c06 = taskRuntime(runtime);
    expect(c06).toBeDefined();
    if (!c06) return;

    await prepareConfirmedRecommendation(runtime);
    expect(selectConfirmedPlan(runtime.store.getState(), 'PLAN-001')).toMatchObject({
      status: 'CONFIRMED',
    });
    expect(selectReceptionRecommendations(runtime.store.getState(), 'PLAN-001')).toMatchObject({
      status: 'CONFIRMED',
    });

    await expect(c06.commands.generateTasks('PLAN-001')).resolves.toMatchObject({ ok: true });
    expect(selectTaskTree(runtime.store.getState(), 'PLAN-001')).toHaveLength(4);
    await expect(c06.commands.confirmTasks('PLAN-001')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().plan.plans.find(({ id }) => id === 'PLAN-001'))
      .toMatchObject({ status: 'DECOMPOSED' });
    expect(selectTaskTree(runtime.store.getState(), 'PLAN-001').every(({ status }) =>
      status === 'READY',
    )).toBe(true);
  });

  it('exposes one C07 runtime, reactive workflow, DB-01/DB-02 truth, and API-025 cleanup', async () => {
    const runtime = createDemoRuntime(createSharedFlowFetcher() as typeof fetch);
    const c06 = taskRuntime(runtime);
    const c07 = dispatchRuntime(runtime);
    const useDispatchWorkflow = (runtimeModule as Record<string, unknown>)
      .useDispatchBoardWorkflow;

    expect(c06).toBeDefined();
    expect(c07).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useDispatchWorkflow).toBeTypeOf('function');
    if (!c06 || !c07 || typeof useDispatchWorkflow !== 'function') return;

    const observedSelections: string[] = [];
    function Consumer() {
      const selected = (useDispatchWorkflow as <T>(
        selector: (state: DispatchBoardWorkflowState) => T,
      ) => T)((state) => `${state.selectedWorkOrderId ?? 'none'}|${state.selectedResourceId ?? 'none'}`);
      observedSelections.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    expect(observedSelections.at(-1)).toBe('none|none');

    await prepareConfirmedRecommendation(runtime);
    await c06.commands.generateTasks('PLAN-001');
    await c06.commands.confirmTasks('PLAN-001');
    const actionsBefore = runtime.store.getState().configAudit.commandAudit.map(({ record }) => record.action);
    act(() => {
      c07.workflow.selectWorkOrder('C06-WO-PLAN-001-G001-02');
      c07.workflow.selectResource('RESOURCE-001');
    });
    expect(observedSelections.at(-1)).toBe('C06-WO-PLAN-001-G001-02|RESOURCE-001');

    await expect(c07.commands.bindDispatchResource({
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      resourceId: 'RESOURCE-001',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-001' });
    await expect(c07.commands.dispatchWorkOrder('C06-WO-PLAN-001-G001-02'))
      .resolves.toMatchObject({ ok: true, commandId: 'CMD-C07-002' });
    expect(selectDispatchBoard(runtime.store.getState(), 'PLAN-001')?.orders[1]).toMatchObject({
      workOrder: { status: 'DISPATCHED', resourceId: 'RESOURCE-001' },
      workNode: { status: 'READY' },
    });
    expect(runtime.store.getState().configAudit.commandAudit.map(({ record }) => record.action))
      .toEqual([...actionsBefore, 'DB-01', 'DB-02']);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(c07.workflow.getState()).toEqual({
      resourceDrawerOpen: false,
      feedbackPanelOpen: false,
    });
    expect(selectDispatchBoard(runtime.store.getState(), 'PLAN-001')).toBeUndefined();

    await prepareConfirmedRecommendation(runtime);
    await c06.commands.generateTasks('PLAN-001');
    await c06.commands.confirmTasks('PLAN-001');
    await expect(c07.commands.bindDispatchResource({
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      resourceId: 'RESOURCE-001',
    })).resolves.toEqual({
      ok: true,
      commandId: 'CMD-C07-001',
      traceId: 'TRACE-C07-001',
      auditLogId: 'AUD-C07-001',
    });
  });

  it('exposes one C08 runtime and API-025 clears workflow, replay cache, and command sequences', async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
      const fixture = createFixtureSnapshot();
      const exception = fixture.objects['DO-009'].find(({ id }) => id === 'EX-001');
      const data = path === '/mock/demo/reset'
        ? { scenarioId: 'SCN-01' }
        : {
            apiId: 'API-015',
            operationId: 'POST_mock_exceptions_id_command',
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: 'SCN-01',
            items: exception ? [exception] : [],
          };
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data,
        traceId: 'TRACE-C08-RUNTIME',
        auditLogId: 'AUD-C08-RUNTIME',
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const c08 = exceptionRuntime(runtime);
    const useExceptionWorkflow = (runtimeModule as Record<string, unknown>)
      .useExceptionHandlingWorkflow;

    expect(c08).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useExceptionWorkflow).toBeTypeOf('function');
    if (!c08 || typeof useExceptionWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useExceptionWorkflow as <T>(
        selector: (state: ExceptionHandlingWorkflowState) => T,
      ) => T)((state) => state.selectedExceptionId ?? 'none');
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    act(() => {
      c08.workflow.selectException('EX-001');
      c08.workflow.setDrawerOpen(true);
      c08.workflow.setMode('ACK');
      c08.workflow.setReason('确认异常');
      c08.workflow.setOwnerDraft('TEAM-09');
      c08.workflow.setEvidenceDraft(['EVIDENCE-RUNTIME']);
    });
    expect(observed.at(-1)).toBe('EX-001');

    await expect(c08.commands.ackException({
      exceptionId: 'EX-001', reason: '确认异常',
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C08-001' });
    expect(runtime.store.getState().exception.exceptions.find(({ id }) => id === 'EX-001'))
      .toMatchObject({ status: 'ACKNOWLEDGED', version: 2 });

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().exception.exceptions.find(({ id }) => id === 'EX-001'))
      .toMatchObject({ status: 'OPEN', version: 1 });
    expect(c08.workflow.getState()).toEqual({
      drawerOpen: false,
      reason: '',
      ownerDraft: '',
      evidenceDraft: [],
    });

    await expect(c08.commands.ackException({
      exceptionId: 'EX-001', reason: '重置后确认',
    })).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C08-001',
      traceId: 'TRACE-C08-001',
      auditLogId: 'AUD-C08-001',
    });
    expect(fetcher.mock.calls.filter(([input]) =>
      new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname
        === '/mock/exceptions/EX-001/command',
    )).toHaveLength(2);
  });

  it('exposes one C09 runtime and API-025 clears workflow, replay cache, and command sequences', async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
      const fixture = createFixtureSnapshot();
      const interlock = fixture.objects['DO-010'].find(({ id }) => id === 'IL-001');
      const data = path === '/mock/demo/reset'
        ? { scenarioId: 'SCN-01' }
        : {
            apiId: 'API-017',
            operationId: 'POST_mock_interlocks_id_command',
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: 'SCN-01',
            items: interlock ? [interlock] : [],
          };
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data,
        traceId: 'TRACE-C09-RUNTIME',
        auditLogId: 'AUD-C09-RUNTIME',
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const c09 = safetyInterlockRuntime(runtime);
    const useSafetyWorkflow = (runtimeModule as Record<string, unknown>)
      .useSafetyInterlockWorkflow;

    expect(c09).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useSafetyWorkflow).toBeTypeOf('function');
    if (!c09 || typeof useSafetyWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useSafetyWorkflow as <T>(
        selector: (state: SafetyInterlockWorkflowState) => T,
      ) => T)((state) => state.selectedInterlockId ?? 'none');
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    act(() => {
      c09.workflow.selectInterlock('IL-001');
      c09.workflow.setDrawerOpen(true);
      c09.workflow.setMode('REQUEST_RESET');
      c09.workflow.setReason('申请复位');
      c09.workflow.setApprovalDraft('USER-004');
      c09.workflow.setResetRequestDraft({ requested: true, checklist: ['现场清场'] });
    });
    expect(observed.at(-1)).toBe('IL-001');
    const exceptionsBeforeCommand = structuredClone(
      runtime.store.getState().exception.exceptions,
    );

    await expect(c09.commands.requestReset({
      interlockId: 'IL-001',
      reason: '申请复位',
      resetRequest: { requested: true, checklist: ['现场清场'] },
    })).resolves.toMatchObject({ ok: true, commandId: 'CMD-C09-001' });
    expect(runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'RESET_REQUESTED', version: 2 });
    expect(runtime.store.getState().exception.exceptions).toEqual(exceptionsBeforeCommand);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(runtime.store.getState().interlock.interlocks.find(({ id }) => id === 'IL-001'))
      .toMatchObject({ status: 'LOCKED', version: 1 });
    expect(c09.workflow.getState()).toEqual({
      drawerOpen: false,
      reason: '',
      approvalDraft: '',
      resetRequestDraft: {},
    });

    await expect(c09.commands.requestReset({
      interlockId: 'IL-001',
      reason: '重置后申请',
      resetRequest: { requested: true },
    })).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C09-001',
      traceId: 'TRACE-C09-001',
      auditLogId: 'AUD-C09-001',
    });
    expect(fetcher.mock.calls.filter(([input]) =>
      new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname
        === '/mock/interlocks/IL-001/command',
    )).toHaveLength(2);
  });

  it('resets C05/C06 domain and workflow state only through API-025 and restarts C06 IDs at G001', async () => {
    const runtime = createDemoRuntime(createSharedFlowFetcher() as typeof fetch);
    const c06 = taskRuntime(runtime);
    expect(c06).toBeDefined();
    if (!c06) return;
    await prepareConfirmedRecommendation(runtime);
    await c06.commands.generateTasks('PLAN-001');
    c06.workflow.selectNodes(['C06-NODE-PLAN-001-G001-02']);
    c06.workflow.openEditor('SPLIT', 'C06-NODE-PLAN-001-G001-02');
    c06.workflow.setReason('API-025 前的编辑输入');
    c06.workflow.setRulePanelOpen(true);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });

    expect(runtime.store.getState().recommendation.drafts).toEqual({});
    expect(selectTaskTree(runtime.store.getState(), 'PLAN-001')).toEqual([]);
    expect(c06.workflow.getState()).toEqual({
      selectedNodeIds: [],
      editDrawerOpen: false,
      rulePanelOpen: false,
      reason: '',
    });

    await prepareConfirmedRecommendation(runtime);
    const regenerated = await c06.commands.generateTasks('PLAN-001');
    expect(regenerated).toEqual({
      ok: true,
      commandId: 'CMD-C06-001',
      traceId: 'TRACE-C06-001',
      auditLogId: 'AUD-C06-001',
    });
    expect(selectTaskTree(runtime.store.getState(), 'PLAN-001').map(({ workOrderId }) => workOrderId))
      .toEqual([
        'C06-WO-PLAN-001-G001-01',
        'C06-WO-PLAN-001-G001-02',
        'C06-WO-PLAN-001-G001-03',
        'C06-WO-PLAN-001-G001-04',
      ]);
  });

  it('makes a C04 confirmation immediately visible to C05 and resets all C05 state through API-025', async () => {
    let fetchCall = 0;
    const plan = createFixtureSnapshot().objects['DO-001'].find(({ id }) => id === 'PLAN-001');
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      fetchCall += 1;
      const path = new URL(input instanceof Request ? input.url : String(input), 'http://localhost').pathname;
      const apiId = path.endsWith('/recommendation/confirm')
        ? 'API-006'
        : path.endsWith('/recommendation')
          ? 'API-005'
          : undefined;
      const data = apiId
        ? {
            apiId,
            operationId:
              apiId === 'API-005'
                ? 'GET_mock_plans_id_recommendation'
                : 'POST_mock_plans_id_recommendation_confirm',
            now: '2026-07-16T09:00:00+08:00',
            scenarioId: 'SCN-01',
            items: plan ? [plan] : [],
          }
        : { status: 'ACCEPTED' };
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data,
        traceId: `TRACE-RUNTIME-${fetchCall}`,
        auditLogId: `AUD-RUNTIME-${fetchCall}`,
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const runtime = createDemoRuntime(fetcher as typeof fetch);

    await runtime.commands.confirmPlan('PLAN-001');
    expect(selectConfirmedPlan(runtime.store.getState(), 'PLAN-001')).toMatchObject({ version: 2 });
    const calculated = await runtime.recommendation.commands.calculateRecommendation('PLAN-001');
    expect(calculated).toMatchObject({ ok: true, commandId: 'CMD-C05-001' });
    expect(runtime.store.getState().configAudit.commandAudit.map(({ record }) => record.action)).toEqual([
      'confirm',
      'RC-01',
    ]);
    runtime.recommendation.workflow.selectCandidate('CANDIDATE-001');
    expect(selectReceptionRecommendations(runtime.store.getState(), 'PLAN-001')).toBeDefined();

    const reset = await runtime.commands.resetScenario('SCN-01');

    expect(reset).toMatchObject({ ok: true });
    expect(runtime.store.getState().recommendation.drafts).toEqual({});
    expect(runtime.recommendation.workflow.getState()).toEqual({
      adjustmentDrawerOpen: false,
      ruleDrawerOpen: false,
    });

    await runtime.commands.confirmPlan('PLAN-001');
    const recalculated = await runtime.recommendation.commands.calculateRecommendation('PLAN-001');
    expect(recalculated).toMatchObject({ ok: true, commandId: 'CMD-C05-001' });
  });

  it('hydrates SCN-02 from URL through strict API-025 before creating one session-aware runtime', async () => {
    localStorage.setItem(
      DEMO_SESSION_STORAGE_KEY,
      JSON.stringify({
        actorId: 'E2E-DISPATCHER',
        roleCode: 'DISPATCHER',
        dataScope: ['AREA-A'],
        online: true,
      }),
    );
    const fetcher = vi.fn(async (_input: string | URL | Request, _init?: RequestInit) => {
      const envelope: ApiSuccessEnvelope = {
        ok: true,
        data: { scenarioId: 'SCN-02' },
        traceId: 'TRACE-BOOTSTRAP-001',
        auditLogId: 'AUD-BOOTSTRAP-001',
      };
      return new Response(JSON.stringify(envelope), {
        headers: { 'content-type': 'application/json' },
      });
    });
    const bootstrap = (runtimeModule as Record<string, unknown>).bootstrapDemoRuntime;

    expect(bootstrap).toBeTypeOf('function');
    if (typeof bootstrap !== 'function') return;
    const runtime = await (bootstrap as BootstrapDemoRuntime)(
      '?date=2026-07-16&workArea=AREA-A&scenarioId=SCN-02',
      fetcher as typeof fetch,
    );

    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('/mock/demo/reset', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ scenarioId: 'SCN-02' }),
    });
    expect(runtime.store.getState()).toMatchObject({
      session: {
        actorId: 'E2E-DISPATCHER',
        roleCode: 'DISPATCHER',
        scenarioId: 'SCN-02',
      },
      scenario: { activeScenarioId: 'SCN-02' },
    });
    expect(runtime.workflow.getState()).toEqual({
      retryCount: 0,
      circuitOpen: false,
      resolvedFields: {},
    });
  });

  it('rejects malformed API-025 bootstrap envelopes instead of rendering a split scenario runtime', async () => {
    const fetcher = vi.fn(async () =>
      new Response(
        JSON.stringify({
          ok: true,
          data: {},
          traceId: 'TRACE-BOOTSTRAP-002',
          auditLogId: 'AUD-BOOTSTRAP-002',
          extra: true,
        }),
        { headers: { 'content-type': 'application/json' } },
      ),
    );
    const bootstrap = (runtimeModule as Record<string, unknown>).bootstrapDemoRuntime;

    expect(bootstrap).toBeTypeOf('function');
    if (typeof bootstrap !== 'function') return;
    await expect(
      (bootstrap as BootstrapDemoRuntime)('?scenarioId=SCN-03', fetcher as typeof fetch),
    ).rejects.toThrow();
  });

  it('exposes one C11 runtime and API-025 resets reports, workflow, cache, and IDs', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({
      ok: true,
      data: { scenarioId: 'SCN-01' },
      traceId: 'TRACE-C11-RESET',
      auditLogId: 'AUD-C11-RESET',
    }), { headers: { 'content-type': 'application/json' } }));
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const c11 = reportingRuntime(runtime);
    const useReportWorkflow = (runtimeModule as Record<string, unknown>).useReportWorkflow;

    expect(c11).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
    });
    expect(useReportWorkflow).toBeTypeOf('function');
    if (!c11 || typeof useReportWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useReportWorkflow as <T>(
        selector: (state: ReportWorkflowState) => T,
      ) => T)((state) => state.selectedReportId ?? 'none');
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    act(() => {
      c11.workflow.selectReport('RP-002');
      c11.workflow.setMetricsDrawerOpen(true);
      c11.workflow.setFilters({ reportType: 'DAILY', generateStatus: 'FAILED' });
    });
    expect(observed.at(-1)).toBe('RP-002');

    const report = runtime.store.getState().report.reports.find(({ id }) => id === 'RP-002');
    if (!report) throw new Error('RP-002 fixture missing.');
    await expect(c11.commands.refreshReport({
      reportId: 'RP-002',
      expectedGeneratedAt: report.generatedAt,
      reason: '运行时刷新',
    })).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });
    expect(c11.workflow.getState()).toEqual({
      filters: {},
      metricsDrawerOpen: false,
      snapshotSequence: 0,
    });
    const resetReport = runtime.store.getState().report.reports.find(({ id }) => id === 'RP-002');
    expect(resetReport).toEqual(createFixtureSnapshot().objects['DO-012']
      .find(({ id }) => id === 'RP-002'));
    if (!resetReport) throw new Error('RP-002 reset fixture missing.');

    await expect(c11.commands.refreshReport({
      reportId: 'RP-002',
      expectedGeneratedAt: resetReport.generatedAt,
      reason: '重置后刷新',
    })).resolves.toMatchObject({
      ok: true,
      commandId: 'CMD-C11-001',
      traceId: 'TRACE-C11-001',
      auditLogId: 'AUD-C11-001',
    });
  });

  it('exposes one C12 audit runtime and API-025 resets its local workflow only', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({
      ok: true,
      data: { scenarioId: 'SCN-01' },
      traceId: 'TRACE-C12-RESET',
      auditLogId: 'AUD-C12-RESET',
    }), { headers: { 'content-type': 'application/json' } }));
    const runtime = createDemoRuntime(fetcher as typeof fetch);
    const c12 = auditTrailRuntime(runtime);
    const useAuditTrailWorkflow = (runtimeModule as Record<string, unknown>)
      .useAuditTrailWorkflow;

    expect(c12).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
    });
    expect(useAuditTrailWorkflow).toBeTypeOf('function');
    if (!c12 || typeof useAuditTrailWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useAuditTrailWorkflow as <T>(
        selector: (state: AuditTrailWorkflowState) => T,
      ) => T)((state) => state.selectedAuditId ?? 'none');
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    act(() => {
      c12.workflow.setFilters({ module: 'BASELINE' });
      c12.workflow.openDetail('AUD-001');
      c12.workflow.setExpandedTrace('TRACE-001');
      c12.workflow.beginRead();
    });
    expect(observed.at(-1)).toBe('AUD-001');
    const auditBeforeReset = structuredClone(runtime.store.getState().configAudit.audit);

    await expect(runtime.commands.resetScenario('SCN-01')).resolves.toMatchObject({ ok: true });

    expect(c12.workflow.getState()).toEqual({
      filters: {},
      detailDrawerOpen: false,
      pending: false,
      readState: { kind: 'idle' },
    });
    expect(runtime.store.getState().configAudit.audit).toEqual(auditBeforeReset);
    expect(runtime.store.getState().configAudit.audit).toHaveLength(9);
    expect(runtime.store.getState().configAudit.commandAudit).toHaveLength(1);
    expect(runtime.store.getState().configAudit.commandAudit[0]).toMatchObject({
      record: { id: 'AUD-C04-001', action: 'execute', objectId: 'SCN-01' },
      metadata: { result: 'SUCCESS' },
    });
    expect(projectAuditTrail(runtime.store.getState()).kpis.idempotentHit).toBe(0);
  });

  it('exposes one C13 runtime with fixture timezone and one reactive workflow hook', () => {
    const runtime = createDemoRuntime();
    const c13 = systemSettingsRuntime(runtime);
    const useSettingsWorkflow = (runtimeModule as Record<string, unknown>)
      .useSystemSettingsWorkflow;

    expect(c13).toMatchObject({
      gateway: expect.any(Object),
      workflow: expect.any(Object),
      commands: expect.any(Object),
      timezone: 'Asia/Shanghai',
    });
    expect(useSettingsWorkflow).toBeTypeOf('function');
    if (!c13 || typeof useSettingsWorkflow !== 'function') return;

    const observed: string[] = [];
    function Consumer() {
      const selected = (useSettingsWorkflow as <T>(
        selector: (state: SystemSettingsWorkflowState) => T,
      ) => T)((state) => state.selectedGroup);
      observed.push(selected);
      return null;
    }
    render(
      <DemoRuntimeProvider runtime={runtime}>
        <Consumer />
      </DemoRuntimeProvider>,
    );
    expect(observed.at(-1)).toBe('overview');
    act(() => c13.workflow.selectGroup('dispatch'));
    expect(observed.at(-1)).toBe('dispatch');
  });
});
