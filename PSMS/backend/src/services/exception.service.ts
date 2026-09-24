import { Exception, type ExceptionDoc } from '../db/tables.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 200;

export interface ExceptionQuery {
  workArea?: string;
  statuses?: string[];
  types?: string[];
  severities?: string[];
  equipmentId?: string;
  page?: number;
  pageSize?: number;
}

export async function listExceptions(query: ExceptionQuery) {
  const filter: Record<string, unknown> = {};
  if (query.workArea) filter.workArea = query.workArea;
  if (query.statuses?.length) filter.status = { $in: query.statuses };
  if (query.types?.length) filter.type = { $in: query.types };
  if (query.severities?.length) filter.severity = { $in: query.severities };
  if (query.equipmentId) filter.equipmentId = query.equipmentId;

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [items, total] = await Promise.all([
    Exception.find(filter).sort({ createdAt: -1 }).skip((page - 1) * pageSize).limit(pageSize).lean(),
    Exception.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getExceptionById(id: string): Promise<ExceptionDoc> {
  const ex = await Exception.findById(id).lean<ExceptionDoc>();
  if (!ex) throw new AppError(404, 'EXCEPTION_NOT_FOUND', '异常记录不存在');
  return ex;
}

async function requireException(id: string): Promise<ExceptionDoc> {
  const ex = await Exception.findById(id).lean<ExceptionDoc>();
  if (!ex) throw new AppError(404, 'EXCEPTION_NOT_FOUND', '异常记录不存在');
  return ex;
}

export async function acknowledgeException(id: string, actorId: string) {
  const ex = await requireException(id);
  if (ex.status !== 'OPEN') throw new AppError(409, 'INVALID_STATE', '只能确认未处理的异常');

  const updated = await Exception.findByIdAndUpdate(
    id,
    {
      status: 'ACKNOWLEDGED',
      acknowledgedBy: actorId,
      acknowledgedAt: new Date(),
      assignedTo: actorId,
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'exception:ack',
    objectType: 'DO-009',
    objectId: id,
    before: { status: ex.status },
    after: { status: 'ACKNOWLEDGED', assignedTo: actorId },
    reason: '认领异常',
  });

  return updated;
}

export async function resolveException(
  id: string,
  actorId: string,
  resolution: string,
  rootCause?: string,
) {
  const ex = await requireException(id);

  const updated = await Exception.findByIdAndUpdate(
    id,
    {
      status: 'RESOLVED',
      resolvedBy: actorId,
      resolvedAt: new Date(),
      resolution,
      ...(rootCause ? { rootCause } : {}),
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'exception:resolve',
    objectType: 'DO-009',
    objectId: id,
    before: { status: ex.status },
    after: { status: 'RESOLVED', resolution },
    reason: resolution,
  });

  return updated;
}

export async function closeException(id: string, actorId: string) {
  const ex = await requireException(id);
  if (ex.status !== 'RESOLVED') throw new AppError(409, 'INVALID_STATE', '只能关闭已解决的异常');

  const updated = await Exception.findByIdAndUpdate(
    id,
    { status: 'CLOSED', closedBy: actorId, closedAt: new Date() },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId,
    action: 'exception:close',
    objectType: 'DO-009',
    objectId: id,
    before: { status: ex.status },
    after: { status: 'CLOSED' },
    reason: '异常闭环',
  });

  return updated;
}
