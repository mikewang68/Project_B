import { Interlock, type InterlockDoc } from '../db/tables.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 200;

export interface InterlockQuery {
  workArea?: string;
  statuses?: string[];
  equipmentId?: string;
  page?: number;
  pageSize?: number;
}

export async function listInterlocks(query: InterlockQuery) {
  const filter: Record<string, unknown> = {};
  if (query.workArea) filter.workArea = query.workArea;
  if (query.statuses?.length) filter.status = { $in: query.statuses };
  if (query.equipmentId) filter.equipmentId = query.equipmentId;

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [items, total] = await Promise.all([
    Interlock.find(filter).sort({ updatedAt: -1 }).skip((page - 1) * pageSize).limit(pageSize).lean(),
    Interlock.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getInterlockById(id: string): Promise<InterlockDoc> {
  const il = await Interlock.findById(id).lean<InterlockDoc>();
  if (!il) throw new AppError(404, 'INTERLOCK_NOT_FOUND', '联锁记录不存在');
  return il;
}

async function requireInterlock(id: string): Promise<InterlockDoc> {
  const il = await Interlock.findById(id).lean<InterlockDoc>();
  if (!il) throw new AppError(404, 'INTERLOCK_NOT_FOUND', '联锁记录不存在');
  return il;
}

/**
 * 覆盖是否仍在有效期内。
 *
 * 覆盖有效期到期后的处理必须由应用层判定 —— 不能对 overrideExpiresAt 建
 * TTL 索引，因为 TTL 会删除整个联锁文档，而联锁记录本身必须保留用于追溯。
 */
export function isOverrideActive(interlock: InterlockDoc, now: Date = new Date()): boolean {
  if (interlock.status !== 'OVERRIDDEN') return false;
  if (!interlock.overrideExpiresAt) return false;
  return new Date(interlock.overrideExpiresAt).getTime() > now.getTime();
}

export async function requestOverride(
  id: string,
  actorId: string,
  reason: string,
  expiresInHours = 4,
) {
  const il = await requireInterlock(id);
  if (il.status !== 'TRIGGERED') throw new AppError(409, 'INVALID_STATE', '只能对已触发的联锁申请覆盖');

  const expiresAt = new Date(Date.now() + expiresInHours * 3_600_000);
  const updated = await Interlock.findByIdAndUpdate(
    id,
    { overrideRequestedBy: actorId, overrideReason: reason, overrideExpiresAt: expiresAt },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'interlock:request-override',
    objectType: 'DO-010',
    objectId: id,
    before: { status: il.status },
    after: { overrideRequestedBy: actorId, overrideExpiresAt: expiresAt.toISOString() },
    reason,
  });

  return updated;
}

export async function approveOverride(id: string, approverId: string) {
  const il = await requireInterlock(id);

  // 职责冲突硬约束：申请人不得审批本人的高风险动作
  if (il.overrideRequestedBy && il.overrideRequestedBy === approverId) {
    throw new AppError(403, 'TOS-AUTH-001', '职责冲突：不得审批本人发起的联锁覆盖申请');
  }

  const updated = await Interlock.findByIdAndUpdate(
    id,
    { status: 'OVERRIDDEN', overrideApprovedBy: approverId },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: approverId,
    action: 'interlock:approve-override',
    objectType: 'DO-010',
    objectId: id,
    before: { status: il.status, requestedBy: il.overrideRequestedBy ?? null },
    after: { status: 'OVERRIDDEN', approvedBy: approverId },
    reason: '覆盖审批通过',
  });

  return updated;
}

export async function resetInterlock(id: string, actorId: string) {
  const il = await requireInterlock(id);

  const updated = await Interlock.findByIdAndUpdate(
    id,
    { status: 'RESET', resetBy: actorId, resetAt: new Date() },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'interlock:reset',
    objectType: 'DO-010',
    objectId: id,
    before: { status: il.status },
    after: { status: 'RESET' },
    reason: '联锁复位',
  });

  return updated;
}

/** 触发某联锁（供监控/场景注入使用） */
export async function triggerInterlock(id: string, actorId: string, reason: string) {
  const il = await requireInterlock(id);

  const updated = await Interlock.findByIdAndUpdate(
    id,
    { status: 'TRIGGERED', triggeredAt: new Date(), triggeredBy: actorId, triggerReason: reason },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'interlock:trigger',
    objectType: 'DO-010',
    objectId: id,
    before: { status: il.status },
    after: { status: 'TRIGGERED' },
    reason,
  });

  return updated;
}
