import { Plan, type PlanDoc } from '../db/tables.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

export interface PlanQuery {
  workArea?: string;
  statuses?: string[];
  date?: string;
  planBatchNo?: string;
  trainNo?: string;
  planId?: string;
  page?: number;
  pageSize?: number;
  sort?: string;
}

/** 列表页默认分页大小 */
const DEFAULT_PAGE_SIZE = 20;
/** 单页上限，防止一次拉全表 */
const MAX_PAGE_SIZE = 200;

function buildFilter(query: PlanQuery): Record<string, unknown> {
  const filter: Record<string, unknown> = {};
  if (query.workArea) filter.workArea = query.workArea;
  if (query.statuses?.length) filter.status = { $in: query.statuses };
  if (query.planBatchNo) filter.planBatchNo = { $regex: query.planBatchNo, $options: 'i' };
  if (query.trainNo) filter.trainNo = { $regex: query.trainNo, $options: 'i' };
  if (query.planId) filter._id = query.planId;
  if (query.date) {
    const dayStart = new Date(query.date);
    const dayEnd = new Date(query.date);
    dayEnd.setDate(dayEnd.getDate() + 1);
    filter.arriveTime = { $gte: dayStart, $lte: dayEnd };
  }
  return filter;
}

export async function listPlans(query: PlanQuery) {
  const filter = buildFilter(query);
  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [sortField, sortDir] = (query.sort ?? 'updatedAt:desc').split(':');
  const sort = { [sortField ?? 'updatedAt']: sortDir === 'asc' ? 1 : -1 } as Record<string, 1 | -1>;

  const [items, total] = await Promise.all([
    Plan.find(filter).sort(sort).skip((page - 1) * pageSize).limit(pageSize).lean(),
    Plan.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getPlanById(id: string): Promise<PlanDoc> {
  const plan = await Plan.findById(id).lean<PlanDoc>();
  if (!plan) throw new AppError(404, 'PLAN_NOT_FOUND', '计划不存在');
  return plan;
}

export async function confirmPlan(
  id: string,
  confirmedBy: string,
  supplements?: Record<string, unknown>,
) {
  const plan = await Plan.findById(id).lean<PlanDoc>();
  if (!plan) throw new AppError(404, 'PLAN_NOT_FOUND', '计划不存在');
  if (plan.status !== 'PENDING_CONFIRM') {
    throw new AppError(409, 'INVALID_STATE', `当前状态"${plan.status}"不允许确认操作`);
  }

  const updated = await Plan.findByIdAndUpdate(
    id,
    {
      status: 'CONFIRMED',
      confirmedBy,
      confirmedAt: new Date(),
      ...(supplements ? { supplements: { ...(plan.supplements ?? {}), ...supplements } } : {}),
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: confirmedBy,
    action: 'plan:confirm',
    objectType: 'DO-001',
    objectId: id,
    before: { status: plan.status },
    after: { status: 'CONFIRMED' },
    reason: supplements ? '确认计划并补充字段' : '确认计划',
  });

  return updated;
}

export async function updatePlanStatus(id: string, status: string) {
  const before = await Plan.findById(id).lean<PlanDoc>();
  if (!before) throw new AppError(404, 'PLAN_NOT_FOUND', '计划不存在');

  const updated = await Plan.findByIdAndUpdate(id, { status }, { new: true }).lean();

  await writeBusinessAudit({
    actorId: 'system',
    action: 'plan:update-status',
    objectType: 'DO-001',
    objectId: id,
    before: { status: before.status },
    after: { status },
    reason: '计划状态更新',
  });

  return updated;
}
