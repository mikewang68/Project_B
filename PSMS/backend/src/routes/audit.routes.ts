// @ts-nocheck
import { Router, type Request } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as auditService from '../services/audit.service.js';

function q(req: Request, key: string): string | undefined {
  const v = req.query[key];
  return Array.isArray(v) ? v[0] : (v as string | undefined);
}

export const auditRouter = Router();

// API-024 GET /api/audit-logs — 查询审计日志
auditRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const items = await auditService.listAuditLogs({
      actorId: q(req, 'actorId'),
      action: q(req, 'action'),
      objectId: q(req, 'objectId'),
      startDate: q(req, 'startDate'),
      endDate: q(req, 'endDate'),
    });
    const now = new Date().toISOString();
    // Map backend models to frontend do013Schema-compatible format,
    // stripping db-internal keys (_id, _v) and ensuring all required fields.
    const mappedItems = items.map((item: Record<string, unknown>) => ({
      id: (item.id as string) ?? (item._id as string) ?? '',
      actorId: (item.actorId as string) ?? '',
      operatorTerminal: (item.operatorTerminal as string) ?? 'WEB-BACKEND',
      action: (item.action as string) ?? '',
      objectType: (item.objectType as string) ?? '',
      objectId: (item.objectId as string) ?? '',
      before: (item.before as Record<string, unknown>) ?? {},
      after: (item.after as Record<string, unknown>) ?? {},
      reason: (item.reason as string) ?? '',
      traceId: (item.traceId as string) ?? '',
      occurredAt: (item.occurredAt as string) ?? now,
    }));
    const traceId = `TRACE-READ-${Date.now()}`;
    const auditLogId = `AUD-READ-${Date.now()}`;
    res.json({
      ok: true,
      data: {
        apiId: 'API-024',
        operationId: 'GET_mock_audit_logs',
        now,
        scenarioId: q(req, 'scenarioId') ?? 'SCN-01',
        items: mappedItems,
      },
      auditLogId,
      traceId,
    });
  } catch (err) {
    next(err);
  }
});

// 审计导出
auditRouter.post('/export', authenticate, auditOperation('exportAudit'), async (req, res, next) => {
  try {
    const items = await auditService.listAuditLogs({
      actorId: req.body.actorId,
      action: req.body.action,
      objectId: req.body.objectId,
      startDate: req.body.startDate,
      endDate: req.body.endDate,
    });
    const now = new Date().toISOString();
    const mappedItems = items.map((item: Record<string, unknown>) => ({
      id: (item.id as string) ?? (item._id as string) ?? '',
      actorId: (item.actorId as string) ?? '',
      operatorTerminal: (item.operatorTerminal as string) ?? 'WEB-BACKEND',
      action: (item.action as string) ?? '',
      objectType: (item.objectType as string) ?? '',
      objectId: (item.objectId as string) ?? '',
      before: (item.before as Record<string, unknown>) ?? {},
      after: (item.after as Record<string, unknown>) ?? {},
      reason: (item.reason as string) ?? '',
      traceId: (item.traceId as string) ?? '',
      occurredAt: (item.occurredAt as string) ?? now,
    }));
    res.json({
      ok: true,
      data: { apiId: 'API-024', operationId: 'exportAuditLogs', now, scenarioId: q(req, 'scenarioId') ?? 'SCN-01', items: mappedItems },
      auditLogId: `AUD-EXPORT-${Date.now()}`,
      traceId: `TRACE-EXPORT-${Date.now()}`,
    });
  } catch (err) {
    next(err);
  }
});
