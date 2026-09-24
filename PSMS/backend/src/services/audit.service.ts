import { AuditLog, type AuditLogDoc } from '../db/tables.js';

export interface AuditQuery {
  actorId?: string;
  action?: string;
  objectId?: string;
  objectType?: string;
  traceId?: string;
  startDate?: string;
  endDate?: string;
  page?: number;
  pageSize?: number;
}

const DEFAULT_PAGE_SIZE = 100;
const MAX_PAGE_SIZE = 1000;

/**
 * 查询审计日志。
 *
 * audit_logs 集合同时存放业务审计与 HTTP 访问留痕两类文档，
 * 因此这里对 `occurredAt` 做了兜底：访问留痕写入时也会填该字段，
 * 未填的旧数据按 createdAt 参与排序与过滤。
 */
export async function listAuditLogs(query: AuditQuery): Promise<AuditLogDoc[]> {
  const filter: Record<string, unknown> = {};
  if (query.actorId) filter.actorId = query.actorId;
  if (query.action) filter.action = query.action;
  if (query.objectId) filter.objectId = query.objectId;
  if (query.objectType) filter.objectType = query.objectType;
  if (query.traceId) filter.traceId = query.traceId;

  if (query.startDate || query.endDate) {
    const range: Record<string, Date> = {};
    if (query.startDate) range.$gte = new Date(query.startDate);
    if (query.endDate) {
      const end = new Date(query.endDate);
      end.setDate(end.getDate() + 1);
      range.$lte = end;
    }
    // 两类文档的时间字段统一收敛到 occurredAt，避免过滤时漏掉访问留痕
    filter.occurredAt = range;
  }

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  return AuditLog.find(filter)
    .sort({ occurredAt: -1, createdAt: -1 })
    .skip((page - 1) * pageSize)
    .limit(pageSize)
    .lean<AuditLogDoc[]>();
}

export async function countAuditLogs(query: AuditQuery): Promise<number> {
  const filter: Record<string, unknown> = {};
  if (query.actorId) filter.actorId = query.actorId;
  if (query.action) filter.action = query.action;
  if (query.objectId) filter.objectId = query.objectId;
  if (query.objectType) filter.objectType = query.objectType;
  if (query.traceId) filter.traceId = query.traceId;
  return AuditLog.countDocuments(filter);
}
