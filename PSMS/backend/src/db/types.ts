/**
 * 领域对象类型（对外契约形态）。
 *
 * 这些类型描述的是**接口/契约层**的数据形状（camelCase、内嵌结构），
 * 与数据库的列名（snake_case、规范化子表）解耦 —— 映射由 db/tables.ts 完成。
 */

export interface CargoItem {
  itemNo: number;
  name: string;
  quantity: number;
  unit: string;
  weight: number;
  remarks?: string;
}

export interface SupplierInfo {
  name?: string;
  contactPerson?: string;
  contactPhone?: string;
}

export interface PlanDoc {
  _id: string;
  planBatchNo: string;
  trainNo: string;
  cargoType: string;
  cargoDescription: string;
  estimatedWeight: number;
  weightUnit: string;
  sourceStation: string;
  destinationStation: string;
  arriveTime: Date;
  trackNo: string;
  workArea: string;
  status: string;
  priority: string;
  confirmedBy?: string | null;
  confirmedAt?: Date | null;
  supplierInfo?: SupplierInfo;
  cargoItems?: CargoItem[];
  supplements?: Record<string, unknown> | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface ExecutionFeedback {
  quality?: string;
  comment?: string;
  reportedBy?: string;
  reportedAt?: Date;
}

export interface WorkOrderDoc {
  _id: string;
  planId: string;
  planBatchNo: string;
  taskId?: string | null;
  workArea: string;
  equipmentId?: string | null;
  equipmentName?: string;
  assignedCrew?: string[];
  assignedOperator?: string | null;
  status: string;
  orderType: string;
  priority: string;
  description: string;
  instructions?: string | null;
  estimatedDuration?: number | null;
  actualStartTime?: Date | null;
  actualEndTime?: Date | null;
  acceptedBy?: string | null;
  acceptedAt?: Date | null;
  pauseReason?: string | null;
  cancelReason?: string | null;
  executionFeedback?: ExecutionFeedback | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface TaskDoc {
  _id: string;
  planId: string;
  workOrderId?: string | null;
  planBatchNo: string;
  taskNo: string;
  name: string;
  description: string;
  workArea: string;
  equipmentId?: string | null;
  assignedCrew?: string[];
  status: string;
  order: number;
  parentTaskId?: string | null;
  dependsOn?: string[];
  estimatedDuration?: number | null;
  actualDuration?: number | null;
  startedAt?: Date | null;
  completedAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface EquipmentDoc {
  _id: string;
  equipmentId: string;
  name: string;
  type: string;
  model: string;
  specs?: Record<string, unknown> | null;
  workArea: string;
  status: string;
  lastHeartbeat?: Date | null;
  lastTelemetryAt?: Date | null;
  networkZone?: string | null;
  protocol?: string | null;
  ipAddress?: string | null;
  port?: number | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface TelemetryPointDoc {
  equipmentId: string;
  pointCode: string;
  value: number;
  quality: string;
  sourceTimestamp: Date;
  unit?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface ExceptionEvidence {
  type?: string;
  url?: string;
  description?: string;
}

export interface ExceptionDoc {
  _id: string;
  exceptionId: string;
  type: string;
  severity: string;
  sourceId: string;
  sourceType: string;
  title: string;
  description: string;
  equipmentId?: string | null;
  workArea: string;
  status: string;
  assignedTo?: string | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: Date | null;
  resolvedBy?: string | null;
  resolvedAt?: Date | null;
  closedBy?: string | null;
  closedAt?: Date | null;
  resolution?: string | null;
  rootCause?: string | null;
  evidence?: ExceptionEvidence[];
  createdAt?: Date;
  updatedAt?: Date;
}

export interface InterlockInputSignal {
  equipmentId?: string;
  pointCode?: string;
  expectedValue?: string;
  actualValue?: string;
}

export interface InterlockDoc {
  _id: string;
  interlockId: string;
  name: string;
  type: string;
  category: string;
  sourceId: string;
  equipmentId?: string | null;
  workArea: string;
  rule: string;
  description: string;
  inputSignals?: InterlockInputSignal[];
  status: string;
  triggeredAt?: Date | null;
  triggeredBy?: string | null;
  triggerReason?: string | null;
  overrideRequestedBy?: string | null;
  overrideApprovedBy?: string | null;
  overrideReason?: string | null;
  overrideExpiresAt?: Date | null;
  resetBy?: string | null;
  resetAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface AppointmentDocument {
  type?: string;
  url?: string;
  verified?: boolean;
}

export interface AppointmentDoc {
  _id: string;
  appointmentId: string;
  vehiclePlate: string;
  vehicleType: string;
  driverName: string;
  driverPhone: string;
  driverIdCard: string;
  company: string;
  cargoType: string;
  estimatedWeight: number;
  plannedArriveTime: Date;
  actualArriveTime?: Date | null;
  checkInTime?: Date | null;
  calledAt?: Date | null;
  enterTime?: Date | null;
  exitTime?: Date | null;
  queueNumber?: number | null;
  status: string;
  gateNo?: string | null;
  parkingBay?: string | null;
  route?: string | null;
  documents?: AppointmentDocument[];
  remarks?: string | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface OfflinePacketDoc {
  _id: string;
  packetId: string;
  terminalId: string;
  operatorId: string;
  workArea: string;
  status: string;
  version: number;
  serverVersion?: number | null;
  payload: Record<string, unknown>;
  syncAttempts: number;
  lastSyncAt?: Date | null;
  conflictFields?: string[];
  resolution?: string | null;
  resolvedBy?: string | null;
  resolvedAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

export interface ConfigChange {
  field?: string;
  oldValue?: unknown;
  newValue?: unknown;
  changedBy?: string;
  changedAt?: Date;
}

export interface ConfigVersionDoc {
  _id: string;
  configId: string;
  configVersion: string;
  displayName: string;
  defaultScenarioId: string;
  ruleVersion: string;
  dispatchStrategy: string;
  recommendationEnabled: boolean;
  offlineSyncEnabled: boolean;
  reportPeriod: string;
  auditRetentionDays: number;
  status: string;
  version: number;
  scenarioId: string;
  publishedAt?: Date | null;
  publishedBy?: string | null;
  createdBy: string;
  updatedBy?: string | null;
  changeHistory?: ConfigChange[];
  createdAt?: Date;
  updatedAt?: Date;
}

export interface AuditLogDoc {
  _id: string;
  id?: string;
  actorId: string;
  actorRole?: string | null;
  operatorTerminal?: string;
  action: string;
  objectType?: string;
  objectId?: string;
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
  reason?: string;
  traceId?: string;
  occurredAt?: Date | null;
  resource?: string;
  resourceId?: string | null;
  statusCode?: number | null;
  durationMs?: number | null;
  ip?: string | null;
  userAgent?: string | null;
  requestBody?: string | null;
  responseSummary?: string | null;
  createdAt: Date;
}

export interface UserDoc {
  _id: string;
  actorId: string;
  username: string;
  passwordHash: string;
  displayName: string;
  roleCode: string;
  dataScope: string[];
  online: boolean;
  lastLoginAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}
