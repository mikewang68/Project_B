import { z } from 'zod';

export const cargoTypes = ['FLY_ASH', 'STEEL', 'CEMENT', 'GENERAL_CARGO'] as const;
export const planStatuses = [
  'RECEIVED',
  'VALIDATING',
  'PENDING_CONFIRM',
  'CONFIRMED',
  'DECOMPOSED',
  'BLOCKED',
  'CANCELLED',
  'ADJUSTED',
] as const;
export const trackOccupyStatuses = ['FREE', 'OCCUPIED', 'RELEASING', 'BLOCKED'] as const;
export const workOrderTypes = ['UNLOAD', 'TRANSFER', 'LOAD', 'INSPECT'] as const;
export const workOrderPriorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'] as const;
export const workOrderStatuses = [
  'DRAFT',
  'READY',
  'DISPATCHED',
  'ACKNOWLEDGED',
  'IN_PROGRESS',
  'COMPLETED',
  'PAUSED',
  'BLOCKED',
  'FAILED',
  'CANCELLED',
] as const;
export const ackStatuses = ['PENDING', 'ACKNOWLEDGED', 'REJECTED', 'TIMEOUT'] as const;
export const workNodeStatuses = [
  'WAITING',
  'READY',
  'IN_PROGRESS',
  'COMPLETED',
  'SKIPPED',
  'BLOCKED',
  'FAILED',
] as const;
export const resourceTypes = ['TIPPER', 'CRANE', 'CONVEYOR', 'SILO', 'AGV', 'TEAM'] as const;
export const resourceStatuses = ['AVAILABLE', 'BUSY', 'OFFLINE', 'MAINTENANCE', 'LOCKED'] as const;
export const appointmentStatuses = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'QUEUED',
  'CALLED',
  'ENTERED',
  'OPERATING',
  'RELEASED',
  'EXITED',
  'REJECTED',
  'NEED_FIX',
  'EXCEPTION',
] as const;
export const gateStatuses = ['WAITING', 'OPEN', 'PASSED', 'CLOSED', 'REJECTED'] as const;
export const exceptionTypes = ['DEVICE_OFFLINE', 'DATA_CONFLICT', 'INTERLOCK', 'TIMEOUT', 'QUALITY'] as const;
export const exceptionLevels = ['INFO', 'MINOR', 'MAJOR', 'CRITICAL'] as const;
export const exceptionStatuses = [
  'OPEN',
  'ACKNOWLEDGED',
  'HANDLING',
  'PENDING_REVIEW',
  'CLOSED',
  'ESCALATED',
  'REOPENED',
] as const;
export const actionLevels = ['WARN', 'PAUSE', 'FORCE_STOP'] as const;
export const interlockStatuses = [
  'TRIGGERED',
  'ACTION_ISSUED',
  'WAITING_RECEIPT',
  'LOCKED',
  'RESET_REQUESTED',
  'APPROVED',
  'RESTORED',
  'FAILED',
  'OVERRIDE_PENDING',
  'OVERRIDDEN',
] as const;
export const receiptStatuses = ['PENDING', 'RECEIVED', 'FAILED'] as const;
export const mergeStatuses = ['CACHED', 'PENDING_UPLOAD', 'VALIDATING', 'MERGED', 'CONFLICT', 'REJECTED', 'RETRY'] as const;
export const reportTypes = ['SHIFT', 'DAILY', 'MONTHLY', 'CUSTOM'] as const;
export const generateStatuses = ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED'] as const;
export const roleCodes = [
  'DISPATCHER',
  'SHIFT_LEADER',
  'OPERATOR',
  'MAINTAINER',
  'WAREHOUSE',
  'GATE_GUARD',
  'DRIVER',
  'SAFETY',
  'BUSINESS',
  'REGULATOR',
  'SYS_ADMIN',
  'INTERFACE_OPS',
  'AUDITOR',
] as const;
export const userStatuses = ['ACTIVE', 'DISABLED', 'LOCKED'] as const;
export const configStatuses = ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED', 'ROLLED_BACK'] as const;
export const dispatchStrategies = ['BALANCED', 'PRIORITY_FIRST', 'RESOURCE_FIRST'] as const;
export const configReportPeriods = ['SHIFT', 'DAILY', 'MONTHLY'] as const;
export const publicErrorCodes = [
  'TOS-EXT-001',
  'TOS-EXT-002',
  'TOS-EXT-003',
  'TOS-WO-001',
  'TOS-IL-001',
  'TOS-OFF-001',
  'TOS-AUTH-001',
  'DEMO-VERSION-001',
  'DEMO-SCENARIO-001',
] as const;

export const enumValuesByComponent = {
  CargoType: cargoTypes,
  PlanStatus: planStatuses,
  TrackOccupyStatus: trackOccupyStatuses,
  WorkOrderType: workOrderTypes,
  WorkOrderPriority: workOrderPriorities,
  WorkOrderStatus: workOrderStatuses,
  AckStatus: ackStatuses,
  WorkNodeStatus: workNodeStatuses,
  ResourceType: resourceTypes,
  ResourceStatus: resourceStatuses,
  AppointmentStatus: appointmentStatuses,
  GateStatus: gateStatuses,
  ExceptionType: exceptionTypes,
  ExceptionLevel: exceptionLevels,
  ExceptionStatus: exceptionStatuses,
  ActionLevel: actionLevels,
  InterlockStatus: interlockStatuses,
  ReceiptStatus: receiptStatuses,
  MergeStatus: mergeStatuses,
  ReportType: reportTypes,
  GenerateStatus: generateStatuses,
  RoleCode: roleCodes,
  UserStatus: userStatuses,
  ConfigStatus: configStatuses,
  DispatchStrategy: dispatchStrategies,
  ConfigReportPeriod: configReportPeriods,
  PublicErrorCode: publicErrorCodes,
} as const;

export const cargoTypeSchema = z.enum(cargoTypes);
export const planStatusSchema = z.enum(planStatuses);
export const trackOccupyStatusSchema = z.enum(trackOccupyStatuses);
export const workOrderTypeSchema = z.enum(workOrderTypes);
export const workOrderPrioritySchema = z.enum(workOrderPriorities);
export const workOrderStatusSchema = z.enum(workOrderStatuses);
export const ackStatusSchema = z.enum(ackStatuses);
export const workNodeStatusSchema = z.enum(workNodeStatuses);
export const resourceTypeSchema = z.enum(resourceTypes);
export const resourceStatusSchema = z.enum(resourceStatuses);
export const appointmentStatusSchema = z.enum(appointmentStatuses);
export const gateStatusSchema = z.enum(gateStatuses);
export const exceptionTypeSchema = z.enum(exceptionTypes);
export const exceptionLevelSchema = z.enum(exceptionLevels);
export const exceptionStatusSchema = z.enum(exceptionStatuses);
export const actionLevelSchema = z.enum(actionLevels);
export const interlockStatusSchema = z.enum(interlockStatuses);
export const receiptStatusSchema = z.enum(receiptStatuses);
export const mergeStatusSchema = z.enum(mergeStatuses);
export const reportTypeSchema = z.enum(reportTypes);
export const generateStatusSchema = z.enum(generateStatuses);
export const roleCodeSchema = z.enum(roleCodes);
export const userStatusSchema = z.enum(userStatuses);
export const configStatusSchema = z.enum(configStatuses);
export const dispatchStrategySchema = z.enum(dispatchStrategies);
export const configReportPeriodSchema = z.enum(configReportPeriods);
export const publicErrorCodeSchema = z.enum(publicErrorCodes);

export type PublicErrorCode = z.infer<typeof publicErrorCodeSchema>;
