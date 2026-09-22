import { Equipment, Plan, Task, WorkOrder, type TaskDoc, type WorkOrderDoc } from '../models/index.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 200;

export interface WorkOrderQuery {
  workArea?: string;
  equipmentId?: string;
  statuses?: string[];
  planId?: string;
  page?: number;
  pageSize?: number;
}

export interface AssignInput {
  equipmentId?: string;
  assignedCrew?: string[];
  assignedOperator?: string;
}

export interface FeedbackInput {
  quality?: string;
  comment?: string;
  reportedBy?: string;
}

/** 作业单查看视图：附带设备名称，避免前端二次请求 */
export async function listWorkOrders(query: WorkOrderQuery) {
  const filter: Record<string, unknown> = {};
  if (query.workArea) filter.workArea = query.workArea;
  if (query.equipmentId) filter.equipmentId = query.equipmentId;
  if (query.statuses?.length) filter.status = { $in: query.statuses };
  if (query.planId) filter.planId = query.planId;

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [items, total] = await Promise.all([
    WorkOrder.find(filter)
      .sort({ updatedAt: -1 })
      .skip((page - 1) * pageSize)
      .limit(pageSize)
      .lean(),
    WorkOrder.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getWorkOrderById(id: string): Promise<WorkOrderDoc> {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');
  return wo;
}

export async function assignWorkOrder(id: string, operatorId: string, data: AssignInput) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');

  if (data.equipmentId) {
    const equipment = await Equipment.findOne({ equipmentId: data.equipmentId }).lean();
    if (!equipment) throw new AppError(400, 'EQUIPMENT_NOT_FOUND', '设备不存在');
    if (equipment.status === 'OFFLINE' || equipment.status === 'FAULT') {
      throw new AppError(409, 'EQUIPMENT_UNAVAILABLE', `设备当前状态不可用：${equipment.status}`);
    }
  }

  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    {
      ...(data.equipmentId ? { equipmentId: data.equipmentId } : {}),
      ...(data.assignedCrew ? { assignedCrew: data.assignedCrew } : {}),
      ...(data.assignedOperator ? { assignedOperator: data.assignedOperator } : {}),
      status: 'ASSIGNED',
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: operatorId,
    action: 'work_order:assign',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status, equipmentId: wo.equipmentId },
    after: { status: 'ASSIGNED', equipmentId: data.equipmentId ?? wo.equipmentId },
    reason: '派工',
  });

  return updated;
}

export async function acceptWorkOrder(id: string, operatorId: string) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');
  if (wo.status !== 'ASSIGNED') throw new AppError(409, 'INVALID_STATE', '只能接单已派发的工单');

  const now = new Date();
  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    { status: 'ACCEPTED', acceptedBy: operatorId, acceptedAt: now, actualStartTime: now },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: operatorId,
    action: 'work_order:accept',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status },
    after: { status: 'ACCEPTED' },
    reason: '接单',
  });

  return updated;
}

export async function startWorkOrder(id: string) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');

  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    { status: 'IN_PROGRESS', actualStartTime: wo.actualStartTime ?? new Date() },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: wo.assignedOperator ?? 'system',
    action: 'work_order:start',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status },
    after: { status: 'IN_PROGRESS' },
    reason: '开始作业',
  });

  return updated;
}

export async function pauseWorkOrder(id: string, reason: string) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');
  if (wo.status !== 'IN_PROGRESS') throw new AppError(409, 'INVALID_STATE', '只能暂停进行中的工单');

  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    { status: 'PAUSED', pauseReason: reason },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: 'system',
    action: 'work_order:pause',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status },
    after: { status: 'PAUSED' },
    reason,
  });

  return updated;
}

export async function completeWorkOrder(id: string, feedback?: FeedbackInput) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');

  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    {
      status: 'COMPLETED',
      actualEndTime: new Date(),
      ...(feedback
        ? { executionFeedback: { ...feedback, reportedAt: new Date() } }
        : {}),
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: feedback?.reportedBy ?? 'system',
    action: 'work_order:complete',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status },
    after: { status: 'COMPLETED' },
    reason: '完成作业',
  });

  return updated;
}

export async function cancelWorkOrder(id: string, reason: string) {
  const wo = await WorkOrder.findById(id).lean<WorkOrderDoc>();
  if (!wo) throw new AppError(404, 'WO_NOT_FOUND', '工单不存在');

  const updated = await WorkOrder.findByIdAndUpdate(
    id,
    { status: 'CANCELLED', cancelReason: reason },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: 'system',
    action: 'work_order:cancel',
    objectType: 'DO-005',
    objectId: id,
    before: { status: wo.status },
    after: { status: 'CANCELLED' },
    reason,
  });

  return updated;
}

// --------------- 任务拆解 ---------------

export interface TaskInput {
  name?: string;
  description?: string;
  equipmentId?: string;
  assignedCrew?: string[];
  status?: string;
  order?: number;
  estimatedDuration?: number;
  dependsOn?: string[];
}

export async function listTasks(filter: { planId?: string; workOrderId?: string; statuses?: string[] }) {
  const query: Record<string, unknown> = {};
  if (filter.planId) query.planId = filter.planId;
  if (filter.workOrderId) query.workOrderId = filter.workOrderId;
  if (filter.statuses?.length) query.status = { $in: filter.statuses };
  return Task.find(query).sort({ order: 1 }).lean();
}

/**
 * 为指定计划重建任务列表。
 *
 * planBatchNo / workArea 从计划上取，避免调用方漏传导致校验失败；
 * 请求体中的同名字段可覆盖默认值。
 */
export async function createTasksForPlan(planId: string, tasks: TaskInput[]) {
  const plan = await Plan.findById(planId).lean();
  if (!plan) throw new AppError(404, 'PLAN_NOT_FOUND', '计划不存在');

  const docs = tasks.map((task, index) => {
    const taskNo = `TASK-${String(index + 1).padStart(3, '0')}`;
    return {
      _id: `${planId}-${taskNo}`,
      planId,
      planBatchNo: plan.planBatchNo,
      taskNo,
      name: task.name ?? `作业任务 ${index + 1}`,
      description: task.description ?? '',
      workArea: plan.workArea,
      equipmentId: task.equipmentId ?? null,
      assignedCrew: task.assignedCrew ?? [],
      status: task.status ?? 'PENDING',
      order: task.order ?? index + 1,
      dependsOn: task.dependsOn ?? [],
      estimatedDuration: task.estimatedDuration ?? null,
    };
  });

  // 先删后插：整体替换该计划的任务集合，避免残留旧节点
  await Task.deleteMany({ planId });
  const inserted = await Task.insertMany(docs);

  await writeBusinessAudit({
    actorId: 'system',
    action: 'plan:decompose',
    objectType: 'DO-001',
    objectId: planId,
    before: { planStatus: plan.status },
    after: { taskCount: inserted.length },
    reason: `任务拆解生成 ${inserted.length} 个任务`,
  });

  return inserted;
}

export async function updateTaskStatus(id: string, status: string): Promise<TaskDoc | null> {
  const task = await Task.findById(id).lean<TaskDoc>();
  if (!task) throw new AppError(404, 'TASK_NOT_FOUND', '任务不存在');

  const update: Record<string, unknown> = { status };
  if (status === 'IN_PROGRESS') update.startedAt = new Date();
  if (status === 'COMPLETED') update.completedAt = new Date();

  const updated = await Task.findByIdAndUpdate(id, update, { new: true }).lean<TaskDoc>();

  await writeBusinessAudit({
    actorId: 'system',
    action: 'task:update-status',
    objectType: 'DO-006',
    objectId: id,
    before: { status: task.status },
    after: { status },
    reason: '任务状态更新',
  });

  return updated;
}
