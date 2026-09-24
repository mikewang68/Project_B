/**
 * 表访问器集合。
 *
 * 每个对象对应一张物理表，暴露与迁移前一致的方法名（find / findById /
 * findByIdAndUpdate / insertMany / create / countDocuments / deleteMany），
 * 使业务侧（服务、路由、种子、脚本）在做「文档库 → 关系库」替换时，
 * 业务规则与状态机逻辑完全不用改。
 *
 * 原文档模型里内嵌的数组，在这里通过 hydrate / persistChildren 落到子表，
 * 对外仍以数组形式呈现 —— 存储规范化了，接口契约没有变。
 */
import type { PoolClient } from 'pg';
import {
  createTable,
  insertChildRows,
  makeHydrate,
  type ChildSpec,
  type TableSpec,
} from './table.js';

// ---------------------------------------------------------------------------
// 通用：子表读写（把声明式 ChildSpec 变成 persistChildren 实现）
// ---------------------------------------------------------------------------

/** 从父 doc 上取子表数组（不做形态转换，转换在写入前由调用方完成） */
function itemsOf(child: ChildSpec, doc: Record<string, unknown>): Record<string, unknown>[] {
  const raw = doc[child.key];
  if (!Array.isArray(raw) || raw.length === 0) return [];
  // 字符串数组（如 dataScope: ['AREA-A']）要包成对象，键名取该子表的第一个列名
  const scalarKey = Object.keys(child.columns)[0];
  return raw.map((item) =>
    typeof item === 'object' && item !== null
      ? (item as Record<string, unknown>)
      : { [scalarKey]: String(item) },
  );
}

/** 由一个或多个 ChildSpec 生成 persistChildren */
function persistOf(...children: ChildSpec[]) {
  return async (
    ids: string[],
    docs: Record<string, unknown>[],
    client: PoolClient,
  ): Promise<void> => {
    for (const [index, doc] of docs.entries()) {
      for (const child of children) {
        await insertChildRows(client, child, ids[index], itemsOf(child, doc));
      }
    }
  };
}

// ---------------------------------------------------------------------------
// 主键兜底：契约要求语义化 ID；调用方未给时生成一个稳定可读的
// ---------------------------------------------------------------------------

function withId(prefix: string, doc: Record<string, unknown>): Record<string, unknown> {
  if (doc._id) return doc;
  const random = Math.random().toString(36).slice(2, 8);
  return { ...doc, _id: `${prefix}-${Date.now().toString(36).toUpperCase()}${random}` };
}

// ---------------------------------------------------------------------------
// 子表声明
// ---------------------------------------------------------------------------

const userDataScopes: ChildSpec = {
  key: 'dataScope',
  table: 'user_data_scopes',
  foreignKey: 'actor_id',
  columns: { scope: 'scope' },
  keyColumns: ['actor_id', 'scope'],
  orderBy: 'scope',
};

const planCargoItems: ChildSpec = {
  key: 'cargoItems',
  table: 'plan_cargo_items',
  foreignKey: 'plan_id',
  columns: {
    itemNo: 'item_no',
    name: 'name',
    quantity: 'quantity',
    unit: 'unit',
    weight: 'weight',
    remarks: 'remarks',
  },
  keyColumns: ['plan_id', 'item_no'],
  seqColumn: 'item_no',
  orderBy: 'item_no',
  numericColumns: ['quantity', 'weight', 'itemNo'],
};

const workOrderCrew: ChildSpec = {
  key: 'assignedCrew',
  table: 'work_order_crew',
  foreignKey: 'work_order_id',
  columns: { crew: 'crew' },
  keyColumns: ['work_order_id', 'crew'],
  orderBy: 'crew',
};

const taskCrew: ChildSpec = {
  key: 'assignedCrew',
  table: 'task_crew',
  foreignKey: 'task_id',
  columns: { crew: 'crew' },
  keyColumns: ['task_id', 'crew'],
  orderBy: 'crew',
};

const taskDependencies: ChildSpec = {
  key: 'dependsOn',
  table: 'task_dependencies',
  foreignKey: 'task_id',
  columns: { dependsOnTaskId: 'depends_on_task_id' },
  keyColumns: ['task_id', 'depends_on_task_id'],
  orderBy: 'depends_on_task_id',
  // 契约里 dependsOn 是字符串数组，读回时摊平
  transform: (items) => items.map((i) => i.dependsOnTaskId),
};

const exceptionEvidence: ChildSpec = {
  key: 'evidence',
  table: 'exception_evidence',
  foreignKey: 'exception_id',
  columns: { type: 'type', url: 'url', description: 'description' },
  keyColumns: ['exception_id', 'seq'],
  seqColumn: 'seq',
  orderBy: 'seq',
};

const interlockSignals: ChildSpec = {
  key: 'inputSignals',
  table: 'interlock_input_signals',
  foreignKey: 'interlock_id',
  columns: {
    equipmentId: 'equipment_id',
    pointCode: 'point_code',
    expectedValue: 'expected_value',
    actualValue: 'actual_value',
  },
  keyColumns: ['interlock_id', 'seq'],
  seqColumn: 'seq',
  orderBy: 'seq',
};

const appointmentDocuments: ChildSpec = {
  key: 'documents',
  table: 'appointment_documents',
  foreignKey: 'appointment_id',
  columns: { type: 'type', url: 'url', verified: 'verified' },
  keyColumns: ['appointment_id', 'seq'],
  seqColumn: 'seq',
  orderBy: 'seq',
};

const offlineConflictFields: ChildSpec = {
  key: 'conflictFields',
  table: 'offline_packet_conflict_fields',
  foreignKey: 'packet_id',
  columns: { field: 'field' },
  keyColumns: ['packet_id', 'field'],
  orderBy: 'field',
  transform: (items) => items.map((i) => i.field),
};

const configChangeHistory: ChildSpec = {
  key: 'changeHistory',
  table: 'config_change_history',
  foreignKey: 'config_id',
  columns: {
    field: 'field',
    oldValue: 'old_value',
    newValue: 'new_value',
    changedBy: 'changed_by',
    changedAt: 'changed_at',
  },
  keyColumns: ['config_id', 'seq'],
  seqColumn: 'seq',
  orderBy: 'seq',
};

// ---------------------------------------------------------------------------
// 1. users
// ---------------------------------------------------------------------------

export const User = createTable({
  table: 'users',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    actorId: 'actor_id',
    username: 'username',
    passwordHash: 'password_hash',
    displayName: 'display_name',
    roleCode: 'role_code',
    online: 'online',
    lastLoginAt: 'last_login_at',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  children: [userDataScopes],
  beforeWrite: (doc) => {
    // 契约要求 users._id 与 actor_id 同值，二者缺一时互相补齐
    const flat = { ...doc };
    if (!flat._id && flat.actorId) flat._id = flat.actorId;
    if (!flat.actorId && flat._id) flat.actorId = flat._id;
    return withId('ACTOR', flat);
  },
  // users._id 与 actor_id 契约同值，故子表外键可直接用主键值
  hydrate: makeHydrate(userDataScopes, 'actorId'),
  persistChildren: persistOf(userDataScopes),
});

// ---------------------------------------------------------------------------
// 2. plans
// ---------------------------------------------------------------------------

export const Plan = createTable({
  table: 'plans',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    planBatchNo: 'plan_batch_no',
    trainNo: 'train_no',
    cargoType: 'cargo_type',
    cargoDescription: 'cargo_description',
    estimatedWeight: 'estimated_weight',
    weightUnit: 'weight_unit',
    sourceStation: 'source_station',
    destinationStation: 'destination_station',
    arriveTime: 'arrive_time',
    trackNo: 'track_no',
    workArea: 'work_area',
    status: 'status',
    priority: 'priority',
    confirmedBy: 'confirmed_by',
    confirmedAt: 'confirmed_at',
    supplierName: 'supplier_name',
    supplierContact: 'supplier_contact',
    supplierPhone: 'supplier_phone',
    supplements: 'supplements',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  jsonColumns: ['supplements'],
  numericColumns: ['estimatedWeight'],
  children: [planCargoItems],
  beforeWrite: (doc) => {
    const flat = withId('PLAN', doc);
    const supplier = flat.supplierInfo as Record<string, unknown> | undefined;
    if (supplier) {
      flat.supplierName = supplier.name ?? null;
      flat.supplierContact = supplier.contactPerson ?? null;
      flat.supplierPhone = supplier.contactPhone ?? null;
      // 摊平后必须删除嵌套键，否则会被当成未声明的列
      delete flat.supplierInfo;
    }
    return flat;
  },
  hydrate: async (rows) => {
    await makeHydrate(planCargoItems)(rows);
    for (const row of rows) {
      const info: Record<string, unknown> = {};
      if (row.supplierName) info.name = row.supplierName;
      if (row.supplierContact) info.contactPerson = row.supplierContact;
      if (row.supplierPhone) info.contactPhone = row.supplierPhone;
      if (Object.keys(info).length > 0) row.supplierInfo = info;
      delete row.supplierName;
      delete row.supplierContact;
      delete row.supplierPhone;
    }
  },
  persistChildren: persistOf(planCargoItems),
});

// ---------------------------------------------------------------------------
// 3. work_orders
// ---------------------------------------------------------------------------

export const WorkOrder = createTable({
  table: 'work_orders',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    planId: 'plan_id',
    planBatchNo: 'plan_batch_no',
    taskId: 'task_id',
    workArea: 'work_area',
    equipmentId: 'equipment_id',
    equipmentName: 'equipment_name',
    assignedOperator: 'assigned_operator',
    status: 'status',
    orderType: 'order_type',
    priority: 'priority',
    description: 'description',
    instructions: 'instructions',
    estimatedDuration: 'estimated_duration',
    actualStartTime: 'actual_start_time',
    actualEndTime: 'actual_end_time',
    acceptedBy: 'accepted_by',
    acceptedAt: 'accepted_at',
    pauseReason: 'pause_reason',
    cancelReason: 'cancel_reason',
    feedbackQuality: 'feedback_quality',
    feedbackComment: 'feedback_comment',
    feedbackBy: 'feedback_by',
    feedbackAt: 'feedback_at',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  numericColumns: ['estimatedDuration'],
  children: [workOrderCrew],
  beforeWrite: (doc) => {
    const flat = withId('WO', doc);
    const fb = flat.executionFeedback as Record<string, unknown> | undefined;
    if (fb) {
      flat.feedbackQuality = fb.quality ?? null;
      flat.feedbackComment = fb.comment ?? null;
      flat.feedbackBy = fb.reportedBy ?? null;
      flat.feedbackAt = fb.reportedAt ?? null;
      delete flat.executionFeedback;
    }
    return flat;
  },
  hydrate: async (rows) => {
    await makeHydrate(workOrderCrew)(rows);
    for (const row of rows) {
      if (row.feedbackQuality || row.feedbackComment || row.feedbackBy) {
        row.executionFeedback = {
          quality: row.feedbackQuality,
          comment: row.feedbackComment,
          reportedBy: row.feedbackBy,
          reportedAt: row.feedbackAt,
        };
      } else {
        row.executionFeedback = null;
      }
      delete row.feedbackQuality;
      delete row.feedbackComment;
      delete row.feedbackBy;
      delete row.feedbackAt;
    }
  },
  persistChildren: persistOf(workOrderCrew),
});

// ---------------------------------------------------------------------------
// 4. tasks
// ---------------------------------------------------------------------------

export const Task = createTable({
  table: 'tasks',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    planId: 'plan_id',
    workOrderId: 'work_order_id',
    planBatchNo: 'plan_batch_no',
    taskNo: 'task_no',
    name: 'name',
    description: 'description',
    workArea: 'work_area',
    equipmentId: 'equipment_id',
    status: 'status',
    order: 'order_no',
    parentTaskId: 'parent_task_id',
    estimatedDuration: 'estimated_duration',
    actualDuration: 'actual_duration',
    startedAt: 'started_at',
    completedAt: 'completed_at',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  numericColumns: ['order', 'estimatedDuration', 'actualDuration'],
  children: [taskCrew, taskDependencies],
  beforeWrite: (doc) => {
    const flat = withId('TASK', doc);
    // dependsOn 是字符串数组，落库前转成对象形态
    if (Array.isArray(flat.dependsOn)) {
      flat.dependsOn = (flat.dependsOn as unknown[]).map((id) =>
        typeof id === 'object' && id !== null ? id : { dependsOnTaskId: String(id) },
      );
    }
    return flat;
  },
  hydrate: async (rows) => {
    await makeHydrate(taskCrew)(rows);
    await makeHydrate(taskDependencies)(rows);
  },
  persistChildren: persistOf(taskCrew, taskDependencies),
});

// ---------------------------------------------------------------------------
// 5. equipment
// ---------------------------------------------------------------------------

export const Equipment = createTable({
  table: 'equipment',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    equipmentId: 'equipment_id',
    name: 'name',
    type: 'type',
    model: 'model',
    specs: 'specs',
    workArea: 'work_area',
    status: 'status',
    lastHeartbeat: 'last_heartbeat',
    lastTelemetryAt: 'last_telemetry_at',
    networkZone: 'network_zone',
    protocol: 'protocol',
    ipAddress: 'ip_address',
    port: 'port',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  jsonColumns: ['specs'],
  numericColumns: ['port'],
  beforeWrite: (doc) => withId('EQ', doc),
});

// ---------------------------------------------------------------------------
// 6. exceptions
// ---------------------------------------------------------------------------

export const Exception = createTable({
  table: 'exceptions',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    exceptionId: 'exception_id',
    type: 'type',
    severity: 'severity',
    sourceId: 'source_id',
    sourceType: 'source_type',
    title: 'title',
    description: 'description',
    equipmentId: 'equipment_id',
    workArea: 'work_area',
    status: 'status',
    assignedTo: 'assigned_to',
    acknowledgedBy: 'acknowledged_by',
    acknowledgedAt: 'acknowledged_at',
    resolvedBy: 'resolved_by',
    resolvedAt: 'resolved_at',
    closedBy: 'closed_by',
    closedAt: 'closed_at',
    resolution: 'resolution',
    rootCause: 'root_cause',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  children: [exceptionEvidence],
  beforeWrite: (doc) => withId('EXC', doc),
  hydrate: makeHydrate(exceptionEvidence),
  persistChildren: persistOf(exceptionEvidence),
});

// ---------------------------------------------------------------------------
// 7. interlocks
// ---------------------------------------------------------------------------

export const Interlock = createTable({
  table: 'interlocks',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    interlockId: 'interlock_id',
    name: 'name',
    type: 'type',
    category: 'category',
    sourceId: 'source_id',
    equipmentId: 'equipment_id',
    workArea: 'work_area',
    rule: 'rule',
    description: 'description',
    status: 'status',
    triggeredAt: 'triggered_at',
    triggeredBy: 'triggered_by',
    triggerReason: 'trigger_reason',
    overrideRequestedBy: 'override_requested_by',
    overrideApprovedBy: 'override_approved_by',
    overrideReason: 'override_reason',
    overrideExpiresAt: 'override_expires_at',
    resetBy: 'reset_by',
    resetAt: 'reset_at',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  children: [interlockSignals],
  beforeWrite: (doc) => withId('ILK', doc),
  hydrate: makeHydrate(interlockSignals),
  persistChildren: persistOf(interlockSignals),
});

// ---------------------------------------------------------------------------
// 8. appointments
// ---------------------------------------------------------------------------

export const Appointment = createTable({
  table: 'appointments',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    appointmentId: 'appointment_id',
    vehiclePlate: 'vehicle_plate',
    vehicleType: 'vehicle_type',
    driverName: 'driver_name',
    driverPhone: 'driver_phone',
    driverIdCard: 'driver_id_card',
    company: 'company',
    cargoType: 'cargo_type',
    estimatedWeight: 'estimated_weight',
    plannedArriveTime: 'planned_arrive_time',
    actualArriveTime: 'actual_arrive_time',
    checkInTime: 'check_in_time',
    calledAt: 'called_at',
    enterTime: 'enter_time',
    exitTime: 'exit_time',
    queueNumber: 'queue_number',
    status: 'status',
    gateNo: 'gate_no',
    parkingBay: 'parking_bay',
    route: 'route',
    remarks: 'remarks',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  numericColumns: ['estimatedWeight', 'queueNumber'],
  children: [appointmentDocuments],
  beforeWrite: (doc) => withId('APT', doc),
  hydrate: makeHydrate(appointmentDocuments),
  persistChildren: persistOf(appointmentDocuments),
});

// ---------------------------------------------------------------------------
// 9. offline_packets
// ---------------------------------------------------------------------------

export const OfflinePacket = createTable({
  table: 'offline_packets',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    packetId: 'packet_id',
    terminalId: 'terminal_id',
    operatorId: 'operator_id',
    workArea: 'work_area',
    status: 'status',
    version: 'version',
    serverVersion: 'server_version',
    payload: 'payload',
    syncAttempts: 'sync_attempts',
    lastSyncAt: 'last_sync_at',
    resolution: 'resolution',
    resolvedBy: 'resolved_by',
    resolvedAt: 'resolved_at',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  jsonColumns: ['payload'],
  numericColumns: ['version', 'serverVersion', 'syncAttempts'],
  children: [offlineConflictFields],
  beforeWrite: (doc) => {
    const flat = withId('PKT', doc);
    // conflictFields 是字符串数组，落库前转成对象形态
    if (Array.isArray(flat.conflictFields)) {
      flat.conflictFields = (flat.conflictFields as unknown[]).map((f) =>
        typeof f === 'object' && f !== null ? f : { field: String(f) },
      );
    }
    return flat;
  },
  hydrate: makeHydrate(offlineConflictFields),
  persistChildren: persistOf(offlineConflictFields),
});

// ---------------------------------------------------------------------------
// 10. config_versions
// ---------------------------------------------------------------------------

export const ConfigVersion = createTable({
  table: 'config_versions',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    configId: 'config_id',
    configVersion: 'config_version',
    displayName: 'display_name',
    defaultScenarioId: 'default_scenario_id',
    ruleVersion: 'rule_version',
    dispatchStrategy: 'dispatch_strategy',
    recommendationEnabled: 'recommendation_enabled',
    offlineSyncEnabled: 'offline_sync_enabled',
    reportPeriod: 'report_period',
    auditRetentionDays: 'audit_retention_days',
    status: 'status',
    version: 'version',
    scenarioId: 'scenario_id',
    publishedAt: 'published_at',
    publishedBy: 'published_by',
    createdBy: 'created_by',
    updatedBy: 'updated_by',
    createdAt: 'created_at',
    updatedAt: 'updated_at',
  },
  numericColumns: ['auditRetentionDays', 'version'],
  children: [configChangeHistory],
  beforeWrite: (doc) => withId('CFG', doc),
  hydrate: makeHydrate(configChangeHistory),
  persistChildren: persistOf(configChangeHistory),
});

// ---------------------------------------------------------------------------
// 11. audit_logs
// ---------------------------------------------------------------------------

export const AuditLog = createTable({
  table: 'audit_logs',
  primaryKey: 'id',
  columns: {
    _id: 'id',
    id: 'id',
    actorId: 'actor_id',
    actorRole: 'actor_role',
    operatorTerminal: 'operator_terminal',
    action: 'action',
    objectType: 'object_type',
    objectId: 'object_id',
    before: 'before_state',
    after: 'after_state',
    reason: 'reason',
    traceId: 'trace_id',
    occurredAt: 'occurred_at',
    resource: 'resource',
    resourceId: 'resource_id',
    statusCode: 'status_code',
    durationMs: 'duration_ms',
    ip: 'ip',
    userAgent: 'user_agent',
    requestBody: 'request_body',
    responseSummary: 'response_summary',
    createdAt: 'created_at',
  },
  jsonColumns: ['before', 'after'],
  numericColumns: ['statusCode', 'durationMs'],
  beforeWrite: (doc) => withId('AUD', doc),
});

export type { TableSpec };

// 统一出口：迁移后的调用方只需把 '../models/index.js' 换成 '../db/tables.js'
export { TelemetryPoint } from './telemetry.js';
export type * from './types.js';
