import {
  createContext,
  type ReactNode,
  useContext,
  useRef,
  useSyncExternalStore,
} from 'react';
import { useStore } from 'zustand';

import { loadCurrentDemoSession } from '../auth';
import {
  createAuditGateway,
  createAuditTrailWorkflowStore,
  type AuditGateway,
  type AuditTrailWorkflowState,
  type AuditTrailWorkflowStore,
} from '../features/audit-trail';
import {
  createDispatchBoardCommandService,
  createDispatchBoardGateway,
  createDispatchBoardWorkflowStore,
  type DispatchBoardCommandService,
  type DispatchBoardGateway,
  type DispatchBoardWorkflowState,
  type DispatchBoardWorkflowStore,
} from '../features/dispatch-board';
import {
  createExceptionHandlingCommandService,
  createExceptionHandlingGateway,
  createExceptionHandlingWorkflowStore,
  type ExceptionHandlingCommandService,
  type ExceptionHandlingGateway,
  type ExceptionHandlingWorkflowState,
  type ExceptionHandlingWorkflowStore,
} from '../features/exception-handling';
import {
  createPlanEntryCommandService,
  type PlanEntryCommandService,
} from '../features/plan-entry/commands';
import { createPlanEntryGateway, type PlanEntryGateway } from '../features/plan-entry/gateway';
import {
  createPlanEntryWorkflowStore,
  parsePlanEntryQuery,
  type PlanEntryWorkflowState,
  type PlanEntryWorkflowStore,
} from '../features/plan-entry';
import {
  createSafetyInterlockCommandService,
  createSafetyInterlockGateway,
  createSafetyInterlockWorkflowStore,
  type SafetyInterlockCommandService,
  type SafetyInterlockGateway,
  type SafetyInterlockWorkflowState,
  type SafetyInterlockWorkflowStore,
} from '../features/safety-interlock';
import {
  createOfflinePacketCommandService,
  createOfflinePacketGateway,
  createOfflinePacketWorkflowStore,
  type OfflinePacketCommandService,
  type OfflinePacketGateway,
  type OfflinePacketWorkflowState,
  type OfflinePacketWorkflowStore,
} from '../features/offline-sync';
import {
  createReportCommandService,
  createReportGateway,
  createReportWorkflowStore,
  type ReportCommandService,
  type ReportGateway,
  type ReportWorkflowState,
  type ReportWorkflowStore,
} from '../features/reporting';
import {
  createRecommendationCommandService,
  createRecommendationGateway,
  createRecommendationWorkflowStore,
  type RecommendationCommandService,
  type RecommendationGateway,
  type RecommendationWorkflowState,
  type RecommendationWorkflowStore,
} from '../features/recommendation';
import {
  createTaskDecompositionCommandService,
  createTaskDecompositionGateway,
  createTaskDecompositionWorkflowStore,
  type TaskDecompositionCommandService,
  type TaskDecompositionGateway,
  type TaskDecompositionWorkflowState,
  type TaskDecompositionWorkflowStore,
} from '../features/task-decomposition';
import {
  createSystemSettingsCommandService,
  createSystemSettingsGateway,
  createSystemSettingsWorkflowStore,
  type SystemSettingsCommandService,
  type SystemSettingsGateway,
  type SystemSettingsWorkflowState,
  type SystemSettingsWorkflowStore,
} from '../features/system-settings';
import { createFixtureSnapshot } from '../mocks/fixtures';
import type { DemoScenario } from '../mocks/fixtures';
import { createDemoStore, type DemoRootState, type DemoStoreApi } from '../stores';
import { createAppFetch } from './appBasePath';

export type RecommendationRuntime = {
  gateway: RecommendationGateway;
  workflow: RecommendationWorkflowStore;
  commands: RecommendationCommandService;
};

export type TaskDecompositionRuntime = {
  gateway: TaskDecompositionGateway;
  workflow: TaskDecompositionWorkflowStore;
  commands: TaskDecompositionCommandService;
};

export type DispatchBoardRuntime = {
  gateway: DispatchBoardGateway;
  workflow: DispatchBoardWorkflowStore;
  commands: DispatchBoardCommandService;
};

export type ExceptionHandlingRuntime = {
  gateway: ExceptionHandlingGateway;
  workflow: ExceptionHandlingWorkflowStore;
  commands: ExceptionHandlingCommandService;
};

export type SafetyInterlockRuntime = {
  gateway: SafetyInterlockGateway;
  workflow: SafetyInterlockWorkflowStore;
  commands: SafetyInterlockCommandService;
};

export type OfflineSyncRuntime = {
  gateway: OfflinePacketGateway;
  workflow: OfflinePacketWorkflowStore;
  commands: OfflinePacketCommandService;
};

export type ReportingRuntime = {
  gateway: ReportGateway;
  workflow: ReportWorkflowStore;
  commands: ReportCommandService;
};

export type AuditTrailRuntime = {
  gateway: AuditGateway;
  workflow: AuditTrailWorkflowStore;
};

export type SystemSettingsRuntime = {
  gateway: SystemSettingsGateway;
  workflow: SystemSettingsWorkflowStore;
  commands: SystemSettingsCommandService;
  timezone: string;
};

export type DemoRuntime = {
  store: DemoStoreApi;
  gateway: PlanEntryGateway;
  workflow: PlanEntryWorkflowStore;
  commands: PlanEntryCommandService;
  recommendation: RecommendationRuntime;
  taskDecomposition: TaskDecompositionRuntime;
  dispatchBoard: DispatchBoardRuntime;
  exceptionHandling: ExceptionHandlingRuntime;
  safetyInterlock: SafetyInterlockRuntime;
  offlineSync: OfflineSyncRuntime;
  reporting: ReportingRuntime;
  auditTrail: AuditTrailRuntime;
  systemSettings: SystemSettingsRuntime;
};

export type CreateDemoRuntimeOptions = Readonly<{
  initialScenarioId?: DemoScenario['id'];
}>;

export function createDemoRuntime(
  fetcher: typeof fetch = fetch,
  options: CreateDemoRuntimeOptions = {},
): DemoRuntime {
  const appFetcher = createAppFetch(fetcher);
  const snapshot = createFixtureSnapshot();
  const session = loadCurrentDemoSession();
  const store = createDemoStore(snapshot, {
    actorId: session.actorId,
    roleCode: session.roleCode,
    dataScope: [...session.dataScope],
    online: session.online,
    shiftId: 'SHIFT-001',
    scenarioId: options.initialScenarioId ?? 'SCN-01',
  });
  const gateway = createPlanEntryGateway(appFetcher);
  const workflow = createPlanEntryWorkflowStore();
  const recommendationGateway = createRecommendationGateway(appFetcher);
  const recommendationWorkflow = createRecommendationWorkflowStore();
  const recommendationCommands = createRecommendationCommandService({
    store,
    gateway: recommendationGateway,
    workflow: recommendationWorkflow,
  });
  const taskDecompositionGateway = createTaskDecompositionGateway(appFetcher);
  const taskDecompositionWorkflow = createTaskDecompositionWorkflowStore();
  const taskDecompositionCommands = createTaskDecompositionCommandService({
    store,
    gateway: taskDecompositionGateway,
    workflow: taskDecompositionWorkflow,
  });
  const dispatchBoardGateway = createDispatchBoardGateway(appFetcher);
  const dispatchBoardWorkflow = createDispatchBoardWorkflowStore();
  const dispatchBoardCommands = createDispatchBoardCommandService({
    store,
    gateway: dispatchBoardGateway,
    workflow: dispatchBoardWorkflow,
  });
  const exceptionHandlingGateway = createExceptionHandlingGateway(appFetcher);
  const exceptionHandlingWorkflow = createExceptionHandlingWorkflowStore();
  const exceptionHandlingCommands = createExceptionHandlingCommandService({
    store,
    gateway: exceptionHandlingGateway,
    workflow: exceptionHandlingWorkflow,
  });
  const safetyInterlockGateway = createSafetyInterlockGateway(appFetcher);
  const safetyInterlockWorkflow = createSafetyInterlockWorkflowStore();
  const safetyInterlockCommands = createSafetyInterlockCommandService({
    store,
    gateway: safetyInterlockGateway,
    workflow: safetyInterlockWorkflow,
  });
  const offlinePacketGateway = createOfflinePacketGateway(appFetcher);
  const offlinePacketWorkflow = createOfflinePacketWorkflowStore();
  const offlinePacketCommands = createOfflinePacketCommandService({
    store,
    gateway: offlinePacketGateway,
    workflow: offlinePacketWorkflow,
  });
  const reportGateway = createReportGateway(appFetcher);
  const reportWorkflow = createReportWorkflowStore();
  const reportCommands = createReportCommandService({
    store,
    workflow: reportWorkflow,
  });
  const auditGateway = createAuditGateway(appFetcher);
  const auditWorkflow = createAuditTrailWorkflowStore();
  const systemSettingsGateway = createSystemSettingsGateway(appFetcher);
  const systemSettingsWorkflow = createSystemSettingsWorkflowStore();
  const systemSettingsCommands = createSystemSettingsCommandService({
    store,
    gateway: systemSettingsGateway,
    workflow: systemSettingsWorkflow,
  });
  const storage = typeof window === 'undefined' ? undefined : window.localStorage;
  const commands = createPlanEntryCommandService({
    store,
    gateway,
    workflow,
    onSuccessfulReset: () => {
      recommendationWorkflow.reset();
      recommendationCommands.resetCommandState();
      taskDecompositionWorkflow.reset();
      taskDecompositionCommands.resetCommandState();
      dispatchBoardWorkflow.reset();
      dispatchBoardCommands.resetCommandState();
      exceptionHandlingWorkflow.reset();
      exceptionHandlingCommands.resetCommandState();
      safetyInterlockWorkflow.reset();
      safetyInterlockCommands.resetCommandState();
      offlinePacketWorkflow.reset();
      offlinePacketCommands.resetCommandState();
      reportWorkflow.reset();
      reportCommands.resetCommandState();
      auditWorkflow.reset();
      systemSettingsWorkflow.reset();
      systemSettingsCommands.resetCommandState();
    },
    ...(storage ? { storage } : {}),
  });
  return {
    store,
    gateway,
    workflow,
    commands,
    recommendation: {
      gateway: recommendationGateway,
      workflow: recommendationWorkflow,
      commands: recommendationCommands,
    },
    taskDecomposition: {
      gateway: taskDecompositionGateway,
      workflow: taskDecompositionWorkflow,
      commands: taskDecompositionCommands,
    },
    dispatchBoard: {
      gateway: dispatchBoardGateway,
      workflow: dispatchBoardWorkflow,
      commands: dispatchBoardCommands,
    },
    exceptionHandling: {
      gateway: exceptionHandlingGateway,
      workflow: exceptionHandlingWorkflow,
      commands: exceptionHandlingCommands,
    },
    safetyInterlock: {
      gateway: safetyInterlockGateway,
      workflow: safetyInterlockWorkflow,
      commands: safetyInterlockCommands,
    },
    offlineSync: {
      gateway: offlinePacketGateway,
      workflow: offlinePacketWorkflow,
      commands: offlinePacketCommands,
    },
    reporting: {
      gateway: reportGateway,
      workflow: reportWorkflow,
      commands: reportCommands,
    },
    auditTrail: {
      gateway: auditGateway,
      workflow: auditWorkflow,
    },
    systemSettings: {
      gateway: systemSettingsGateway,
      workflow: systemSettingsWorkflow,
      commands: systemSettingsCommands,
      timezone: snapshot.timezone,
    },
  };
}

export async function bootstrapDemoRuntime(
  search: string,
  fetcher: typeof fetch = fetch,
): Promise<DemoRuntime> {
  const scenarioId = parsePlanEntryQuery(search).scenarioId;
  const gateway = createPlanEntryGateway(createAppFetch(fetcher));
  const response = await gateway.resetDemo({ scenarioId });
  if (!response.ok) {
    throw new Error(`${response.errorCode}: ${response.message}`);
  }
  return createDemoRuntime(fetcher, { initialScenarioId: scenarioId });
}

const DemoRuntimeContext = createContext<DemoRuntime | undefined>(undefined);

type DemoRuntimeProviderProps = {
  children: ReactNode;
  runtime?: DemoRuntime;
};

export function DemoRuntimeProvider({
  children,
  runtime,
}: DemoRuntimeProviderProps) {
  const fallbackRuntime = useRef<DemoRuntime | undefined>(undefined);
  if (!runtime && !fallbackRuntime.current) fallbackRuntime.current = createDemoRuntime();
  return (
    <DemoRuntimeContext value={runtime ?? fallbackRuntime.current}>
      {children}
    </DemoRuntimeContext>
  );
}

export function useDemoRuntime(): DemoRuntime {
  const runtime = useContext(DemoRuntimeContext);
  if (!runtime) throw new Error('DemoRuntimeProvider is required.');
  return runtime;
}

export function useDemoSelector<T>(selector: (state: DemoRootState) => T): T {
  const runtime = useDemoRuntime();
  return useStore(runtime.store, selector);
}

export function usePlanEntryWorkflow<T>(
  selector: (state: PlanEntryWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.workflow.subscribe,
    runtime.workflow.getState,
    runtime.workflow.getState,
  );
  return selector(state);
}

export function useRecommendationWorkflow<T>(
  selector: (state: RecommendationWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.recommendation.workflow.subscribe,
    runtime.recommendation.workflow.getState,
    runtime.recommendation.workflow.getState,
  );
  return selector(state);
}

export function useTaskDecompositionWorkflow<T>(
  selector: (state: TaskDecompositionWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.taskDecomposition.workflow.subscribe,
    runtime.taskDecomposition.workflow.getState,
    runtime.taskDecomposition.workflow.getState,
  );
  return selector(state);
}

export function useDispatchBoardWorkflow<T>(
  selector: (state: DispatchBoardWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.dispatchBoard.workflow.subscribe,
    runtime.dispatchBoard.workflow.getState,
    runtime.dispatchBoard.workflow.getState,
  );
  return selector(state);
}

export function useExceptionHandlingWorkflow<T>(
  selector: (state: ExceptionHandlingWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.exceptionHandling.workflow.subscribe,
    runtime.exceptionHandling.workflow.getState,
    runtime.exceptionHandling.workflow.getState,
  );
  return selector(state);
}

export function useSafetyInterlockWorkflow<T>(
  selector: (state: SafetyInterlockWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.safetyInterlock.workflow.subscribe,
    runtime.safetyInterlock.workflow.getState,
    runtime.safetyInterlock.workflow.getState,
  );
  return selector(state);
}

export function useOfflinePacketWorkflow<T>(
  selector: (state: OfflinePacketWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.offlineSync.workflow.subscribe,
    runtime.offlineSync.workflow.getState,
    runtime.offlineSync.workflow.getState,
  );
  return selector(state);
}

export function useReportWorkflow<T>(
  selector: (state: ReportWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.reporting.workflow.subscribe,
    runtime.reporting.workflow.getState,
    runtime.reporting.workflow.getState,
  );
  return selector(state);
}

export function useAuditTrailWorkflow<T>(
  selector: (state: AuditTrailWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.auditTrail.workflow.subscribe,
    runtime.auditTrail.workflow.getState,
    runtime.auditTrail.workflow.getState,
  );
  return selector(state);
}

export function useSystemSettingsWorkflow<T>(
  selector: (state: SystemSettingsWorkflowState) => T,
): T {
  const runtime = useDemoRuntime();
  const state = useSyncExternalStore(
    runtime.systemSettings.workflow.subscribe,
    runtime.systemSettings.workflow.getState,
    runtime.systemSettings.workflow.getState,
  );
  return selector(state);
}
