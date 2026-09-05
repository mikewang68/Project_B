import type { StoreApi } from 'zustand/vanilla';

import {
  roleCodes,
  type Appointment,
  type AuditLog,
  type ConfigVersion,
  type DispatchException,
  type Interlock,
  type Material,
  type OfflinePacket,
  type Plan,
  type Report,
  type Resource,
  type Track,
  type UserRole,
  type Waybill,
  type WorkNode,
  type WorkOrder,
} from '../contracts';
import type { DemoScenario, FixtureSnapshot } from '../mocks/fixtures';
import type { CommandAuditEntry } from '../governance/audit';

export type StoreRoleCode = (typeof roleCodes)[number];

export type DemoSessionSeed = {
  actorId: string;
  roleCode: StoreRoleCode;
  dataScope: string[];
  shiftId: string;
  online: boolean;
  scenarioId: DemoScenario['id'];
};

export type SessionSlice = DemoSessionSeed & {
  demoTime: string;
};

export type ScenarioSlice = {
  activeScenarioId: DemoScenario['id'];
  scenarios: DemoScenario[];
  activeFault: DemoScenario['fault'];
  resetPoint: string;
};

export type PlanSlice = {
  plans: Plan[];
  waybills: Waybill[];
};

export type RecommendationSlice = {
  drafts: Record<string, unknown>;
};

export type WorkOrderSlice = {
  workOrders: WorkOrder[];
  nodes: WorkNode[];
};

export type ResourceSlice = {
  tracks: Track[];
  materials: Material[];
  resources: Resource[];
};

export type VehicleSlice = {
  appointments: Appointment[];
};

export type ExceptionSlice = {
  exceptions: DispatchException[];
};

export type InterlockSlice = {
  interlocks: Interlock[];
};

export type OfflineSlice = {
  packets: OfflinePacket[];
};

export type ReportSlice = {
  reports: Report[];
};

export type ConfigAuditSlice = {
  userRoles: UserRole[];
  audit: AuditLog[];
  commandAudit: CommandAuditEntry[];
};

export type SystemConfigSlice = {
  configVersions: ConfigVersion[];
};

export type DemoRootState = {
  session: SessionSlice;
  scenario: ScenarioSlice;
  plan: PlanSlice;
  recommendation: RecommendationSlice;
  workOrder: WorkOrderSlice;
  resource: ResourceSlice;
  vehicle: VehicleSlice;
  exception: ExceptionSlice;
  interlock: InterlockSlice;
  offline: OfflineSlice;
  report: ReportSlice;
  systemConfig: SystemConfigSlice;
  configAudit: ConfigAuditSlice;
};

export type DomainStateMutator = (candidate: DemoRootState) => void;

export type DemoStoreApi = StoreApi<DemoRootState> & {
  replaceDomainState: (mutator: DomainStateMutator) => void;
  resetFromSnapshot: (snapshot: FixtureSnapshot, session: DemoSessionSeed) => void;
};
