import { z } from 'zod';
import {
  ackStatusSchema,
  actionLevelSchema,
  appointmentStatusSchema,
  cargoTypeSchema,
  configReportPeriodSchema,
  configStatusSchema,
  dispatchStrategySchema,
  exceptionLevelSchema,
  exceptionStatusSchema,
  exceptionTypeSchema,
  gateStatusSchema,
  generateStatusSchema,
  interlockStatusSchema,
  mergeStatusSchema,
  planStatusSchema,
  publicErrorCodeSchema,
  receiptStatusSchema,
  reportTypeSchema,
  resourceStatusSchema,
  resourceTypeSchema,
  roleCodeSchema,
  trackOccupyStatusSchema,
  userStatusSchema,
  workNodeStatusSchema,
  workOrderPrioritySchema,
  workOrderStatusSchema,
  workOrderTypeSchema,
} from './enums';

export const jsonObjectSchema = z.record(z.string(), z.unknown());

export const apiSuccessEnvelopeSchema = z
  .object({
    ok: z.literal(true),
    data: jsonObjectSchema,
    auditLogId: z.string(),
    traceId: z.string(),
  })
  .strict();

export const apiErrorEnvelopeSchema = z
  .object({
    ok: z.literal(false),
    errorCode: publicErrorCodeSchema,
    message: z.string(),
    details: jsonObjectSchema.optional(),
    auditLogId: z.string(),
    traceId: z.string(),
  })
  .strict();

export const do001Schema = z
  .object({
    id: z.string(),
    planBatchNo: z.string(),
    trainNo: z.string(),
    arrivalDepartureTime: z.string(),
    trackNo: z.string(),
    cargoType: cargoTypeSchema,
    status: planStatusSchema,
    sourceSystem: z.string(),
    sourceTime: z.string(),
    missingFields: z.array(z.string()),
    conflicts: z.array(jsonObjectSchema),
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do002Schema = z
  .object({
    id: z.string(),
    waybillNo: z.string(),
    containerNo: z.string(),
    cargoType: cargoTypeSchema,
    quantity: z.number(),
    unit: z.string(),
    weight: z.number(),
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do003Schema = z
  .object({
    id: z.string(),
    trackNo: z.string(),
    occupyStatus: trackOccupyStatusSchema,
    estimateReleaseTime: z.string(),
    compatibleCargoTypes: z.array(cargoTypeSchema),
    version: z.number().int(),
    updatedAt: z.string(),
  })
  .strict();

export const do004Schema = z
  .object({
    id: z.string(),
    materialId: z.string(),
    containerNoRfid: z.string(),
    cargoType: cargoTypeSchema,
    quantityWeight: z.number(),
    locationCode: z.string(),
    version: z.number().int(),
    updatedAt: z.string(),
  })
  .strict();

export const do005Schema = z
  .object({
    id: z.string(),
    workOrderNo: z.string(),
    planId: z.string(),
    parentId: z.string(),
    type: workOrderTypeSchema,
    title: z.string(),
    priority: workOrderPrioritySchema,
    status: workOrderStatusSchema,
    ackStatus: ackStatusSchema,
    ruleVersion: z.string(),
    resourceId: z.string(),
    teamId: z.string(),
    blockReason: z.string(),
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do006Schema = z
  .object({
    id: z.string(),
    nodeNo: z.string(),
    workOrderNo: z.string(),
    sequence: z.number().int(),
    status: workNodeStatusSchema,
    plannedStartTime: z.string(),
    plannedFinishTime: z.string(),
    actualStartTime: z.string(),
    actualFinishTime: z.string(),
    version: z.number().int(),
    updatedAt: z.string(),
  })
  .strict();

export const do007Schema = z
  .object({
    id: z.string(),
    deviceId: z.string(),
    personId: z.string(),
    resourceType: resourceTypeSchema,
    capabilityTags: z.array(z.string()),
    status: resourceStatusSchema,
    workArea: z.string(),
    location: z.string(),
    version: z.number().int(),
    updatedAt: z.string(),
  })
  .strict();

export const do008Schema = z
  .object({
    id: z.string(),
    reservationNo: z.string(),
    vehicleNo: z.string(),
    driverId: z.string(),
    status: appointmentStatusSchema,
    queueNo: z.string(),
    gateStatus: gateStatusSchema,
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do009Schema = z
  .object({
    id: z.string(),
    exceptionNo: z.string(),
    type: exceptionTypeSchema,
    level: exceptionLevelSchema,
    status: exceptionStatusSchema,
    owner: z.string(),
    dueAt: z.string(),
    evidence: z.array(z.string()),
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do010Schema = z
  .object({
    id: z.string(),
    interlockNo: z.string(),
    riskType: z.string(),
    actionLevel: actionLevelSchema,
    status: interlockStatusSchema,
    inputSnapshot: jsonObjectSchema,
    receiptStatus: receiptStatusSchema,
    resetRequest: jsonObjectSchema,
    approvalChain: z.array(z.string()),
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do011Schema = z
  .object({
    id: z.string(),
    offlinePackageNo: z.string(),
    terminalId: z.string(),
    workOrderNo: z.string(),
    packageVersion: z.number().int(),
    serverVersion: z.number().int(),
    validation: jsonObjectSchema,
    mergeStatus: mergeStatusSchema,
    version: z.number().int(),
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do012Schema = z
  .object({
    id: z.string(),
    reportType: reportTypeSchema,
    period: z.string(),
    generateStatus: generateStatusSchema,
    metrics: jsonObjectSchema,
    generatedAt: z.string(),
  })
  .strict();

export const do013Schema = z
  .object({
    id: z.string(),
    actorId: z.string(),
    operatorTerminal: z.string(),
    action: z.string(),
    objectType: z.string(),
    objectId: z.string(),
    before: jsonObjectSchema,
    after: jsonObjectSchema,
    reason: z.string(),
    traceId: z.string(),
    occurredAt: z.string(),
  })
  .strict();

export const do014Schema = z
  .object({
    id: z.string(),
    roleCode: roleCodeSchema,
    dataScope: z.string(),
    status: userStatusSchema,
    createdAt: z.string(),
    updatedAt: z.string(),
  })
  .strict();

export const do015Schema = z
  .object({
    id: z.string().trim().min(1),
    configVersion: z.string().trim().min(1),
    displayName: z.string().trim().min(1).max(64),
    defaultScenarioId: z.enum([
      'SCN-01',
      'SCN-02',
      'SCN-03',
      'SCN-04',
      'SCN-05',
      'SCN-06',
      'SCN-07',
    ]),
    ruleVersion: z.string().trim().min(1).max(32),
    dispatchStrategy: dispatchStrategySchema,
    recommendationEnabled: z.boolean(),
    offlineSyncEnabled: z.boolean(),
    reportPeriod: configReportPeriodSchema,
    auditRetentionDays: z.number().int().min(1).max(3650),
    status: configStatusSchema,
    version: z.number().int().positive(),
    createdAt: z.string().min(1),
    updatedAt: z.string().min(1),
    updatedBy: z.string().trim().min(1),
  })
  .strict();

export const api022DataSchema = z
  .object({
    apiId: z.literal('API-022'),
    operationId: z.literal('GET_mock_config'),
    now: z.string().min(1),
    scenarioId: z.string().min(1),
    items: z.array(do015Schema),
  })
  .strict();

export const api023DataSchema = z
  .object({
    apiId: z.literal('API-023'),
    operationId: z.literal('POST_mock_config_id_command'),
    now: z.string().min(1),
    scenarioId: z.string().min(1),
    items: z.array(do015Schema).length(1),
  })
  .strict();

export const api022SuccessEnvelopeSchema = apiSuccessEnvelopeSchema
  .extend({ data: api022DataSchema })
  .strict();

export const api023SuccessEnvelopeSchema = apiSuccessEnvelopeSchema
  .extend({ data: api023DataSchema })
  .strict();

export const domainObjectIds = [
  'DO-001',
  'DO-002',
  'DO-003',
  'DO-004',
  'DO-005',
  'DO-006',
  'DO-007',
  'DO-008',
  'DO-009',
  'DO-010',
  'DO-011',
  'DO-012',
  'DO-013',
  'DO-014',
  'DO-015',
] as const;

export const domainSchemas = {
  'DO-001': do001Schema,
  'DO-002': do002Schema,
  'DO-003': do003Schema,
  'DO-004': do004Schema,
  'DO-005': do005Schema,
  'DO-006': do006Schema,
  'DO-007': do007Schema,
  'DO-008': do008Schema,
  'DO-009': do009Schema,
  'DO-010': do010Schema,
  'DO-011': do011Schema,
  'DO-012': do012Schema,
  'DO-013': do013Schema,
  'DO-014': do014Schema,
  'DO-015': do015Schema,
} as const;

export const domainObjectCounts = {
  'DO-001': 3,
  'DO-002': 8,
  'DO-003': 4,
  'DO-004': 8,
  'DO-005': 12,
  'DO-006': 12,
  'DO-007': 10,
  'DO-008': 6,
  'DO-009': 5,
  'DO-010': 4,
  'DO-011': 4,
  'DO-012': 3,
  'DO-013': 9,
  'DO-014': 13,
  'DO-015': 1,
} as const;

export type DomainObjectId = (typeof domainObjectIds)[number];
export type ApiSuccessEnvelope = z.infer<typeof apiSuccessEnvelopeSchema>;
export type ApiErrorEnvelope = z.infer<typeof apiErrorEnvelopeSchema>;
export type Plan = z.infer<typeof do001Schema>;
export type Waybill = z.infer<typeof do002Schema>;
export type Track = z.infer<typeof do003Schema>;
export type Material = z.infer<typeof do004Schema>;
export type WorkOrder = z.infer<typeof do005Schema>;
export type WorkNode = z.infer<typeof do006Schema>;
export type Resource = z.infer<typeof do007Schema>;
export type Appointment = z.infer<typeof do008Schema>;
export type DispatchException = z.infer<typeof do009Schema>;
export type Interlock = z.infer<typeof do010Schema>;
export type OfflinePacket = z.infer<typeof do011Schema>;
export type Report = z.infer<typeof do012Schema>;
export type AuditLog = z.infer<typeof do013Schema>;
export type UserRole = z.infer<typeof do014Schema>;
export type ConfigVersion = z.infer<typeof do015Schema>;
export type Api022SuccessEnvelope = z.infer<typeof api022SuccessEnvelopeSchema>;
export type Api023SuccessEnvelope = z.infer<typeof api023SuccessEnvelopeSchema>;
