import type {
  Material,
  Plan,
  PublicErrorCode,
  Resource,
  Waybill,
  WorkNode,
  WorkOrder,
} from '../../contracts';
import type { RecommendationDraft } from '../recommendation';
import type { TASK_DECOMPOSITION_RULE_VERSION, taskModes } from './constants';

export type TaskMode = (typeof taskModes)[number];
export type TaskStage = 'RECOGNITION' | 'UNLOAD' | 'TRANSFER' | 'STORAGE';

export type GenerateTaskDraftInput = Readonly<{
  plan: Plan;
  recommendation: RecommendationDraft;
  waybills: readonly Waybill[];
  materials: readonly Material[];
  resources: readonly Resource[];
  demoTime: string;
  generationVersion: number;
}>;

export type TaskGeneration = Readonly<{
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
}>;

export type TaskTreeNodeView = Readonly<{
  workOrderId: string;
  workOrderNo: string;
  nodeId: string;
  nodeNo: string;
  sequence: number;
  stage: TaskStage;
  taskType: WorkOrder['type'];
  title: string;
  objectId: string;
  dependencyIds: readonly string[];
  ruleVersion: typeof TASK_DECOMPOSITION_RULE_VERSION;
  status: WorkOrder['status'];
  nodeStatus: WorkNode['status'];
  requiredResourceType: Resource['resourceType'];
  resourceCandidateIds: readonly string[];
  sourceRefs: readonly string[];
  workOrderVersion: number;
  nodeVersion: number;
}>;

export type TaskRouteStep = Readonly<{
  stage: TaskStage;
  title: string;
  taskType: WorkOrder['type'];
  requiredResourceType: Resource['resourceType'];
}>;

export type TaskRouteExplanation = Readonly<{
  cargoType: Plan['cargoType'];
  ruleVersion: typeof TASK_DECOMPOSITION_RULE_VERSION;
  mappingMode: 'DEMO_STABLE_MAPPING';
  route: readonly TaskRouteStep[];
  source: string;
  limitations: readonly string[];
}>;

export type ProjectTaskGraphInput = Readonly<{
  plan: Plan;
  recommendation: RecommendationDraft;
  workOrders: readonly WorkOrder[];
  nodes: readonly WorkNode[];
  waybills: readonly Waybill[];
  materials: readonly Material[];
  resources: readonly Resource[];
}>;

export type CargoSummaryView = Readonly<{
  planId: string;
  planStatus: Plan['status'];
  cargoType: Plan['cargoType'];
  mappingMode: 'DEMO_STABLE_MAPPING';
  waybillIds: readonly string[];
  materialIds: readonly string[];
  sourceRefs: readonly string[];
  missingData: readonly string[];
  actualContainerCount: '数据未提供';
  acceptanceBenchmark: Readonly<{
    trains: 2;
    cars: 80;
    containers: 160;
    disclosure: '高峰验收基准，不是本计划实际箱量';
  }>;
}>;

export type ResourcePreviewItem = Readonly<{
  nodeId: string;
  stage: TaskStage;
  requiredResourceType: Resource['resourceType'];
  typeExists: true;
  allocationState: 'PREVIEW_ONLY';
  candidates: readonly Readonly<Pick<Resource, 'id' | 'workArea' | 'status'>>[];
}>;

export type TaskDecompositionWorkflowState = Readonly<{
  selectedNodeIds: readonly string[];
  editDrawerOpen: boolean;
  rulePanelOpen: boolean;
  mode?: Exclude<TaskMode, 'AUTO' | 'CONFIRM'>;
  targetNodeId?: string;
  reason: string;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type TaskDecompositionWorkflowStore = {
  getState: () => TaskDecompositionWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectNodes: (nodeIds: readonly string[]) => void;
  openEditor: (
    mode: Exclude<TaskMode, 'AUTO' | 'CONFIRM'>,
    targetNodeId?: string,
  ) => void;
  closeEditor: () => void;
  setReason: (reason: string) => void;
  setRulePanelOpen: (open: boolean) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
};
