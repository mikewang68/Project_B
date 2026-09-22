import { Router, type Request } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as auditService from '../services/audit.service.js';
import { q, qNum } from '../lib/http.js';

export const auditRouter = Router();

/** 把库内文档映射为前端 do013Schema 兼容的形状（去掉库内字段，补齐缺失字段） */
function toContractShape(item: Record<string, unknown>, now: string) {
  const occurredAt = item.occurredAt ?? item.createdAt ?? now;
  return {
    id: (item.id as string) ?? (item._id as string) ?? '',
    actorId: (item.actorId as string) ?? '',
    operatorTerminal: (item.operatorTerminal as string) ?? 'WEB-BACKEND',
    action: (item.action as string) ?? '',
    objectType: (item.objectType as string) ?? '',
    objectId: (item.objectId as string) ?? (item.resourceId as string) ?? '',
    before: (item.before as Record<string, unknown>) ?? {},
    after: (item.after as Record<string, unknown>) ?? {},
    reason: (item.reason as string) ?? (item.responseSummary as string) ?? '',
    traceId: (item.traceId as string) ?? '',
    occurredAt: occurredAt instanceof Date ? occurredAt.toISOString() : String(occurredAt),
  };
}

function buildQuery(req: Request) {
  return {
    actorId: q(req, 'actorId'),
    action: q(req, 'action'),
    objectId: q(req, 'objectId'),
    objectType: q(req, 'objectType'),
    traceId: q(req, 'traceId'),
    startDate: q(req, 'startDate'),
    endDate: q(req, 'endDate'),
    page: qNum(req, 'page'),
    pageSize: qNum(req, 'pageSize'),
  };
}

// API-024 审计日志查询
auditRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const query = buildQuery(req);
    const now = new Date().toISOString();

    const [items, total] = await Promise.all([
      auditService.listAuditLogs(query),
      auditService.countAuditLogs(query),
    ]);

    res.json({
      ok: true,
      data: {
        apiId: 'API-024',
        operationId: 'GET_mock_audit_logs',
        now,
        scenarioId: q(req, 'scenarioId') ?? 'SCN-01',
        total,
        items: items.map((item) => toContractShape(item as unknown as Record<string, unknown>, now)),
      },
    });
  } catch (err) {
    next(err);
  }
});

// 审计日志受控导出
auditRouter.post('/export', authenticate, auditOperation('exportAudit'), async (req, res, next) => {
  try {
    const now = new Date().toISOString();
    const query = {
      actorId: req.body?.actorId as string | undefined,
      action: req.body?.action as string | undefined,
      objectId: req.body?.objectId as string | undefined,
      objectType: req.body?.objectType as string | undefined,
      startDate: req.body?.startDate as string | undefined,
      endDate: req.body?.endDate as string | undefined,
      pageSize: 1000,
    };

    const items = await auditService.listAuditLogs(query);

    res.json({
      ok: true,
      data: {
        apiId: 'API-024',
        operationId: 'exportAuditLogs',
        now,
        scenarioId: (req.body?.scenarioId as string | undefined) ?? 'SCN-01',
        purpose: (req.body?.purpose as string | undefined) ?? '审计取证',
        total: items.length,
        items: items.map((item) => toContractShape(item as unknown as Record<string, unknown>, now)),
      },
    });
  } catch (err) {
    next(err);
  }
});
