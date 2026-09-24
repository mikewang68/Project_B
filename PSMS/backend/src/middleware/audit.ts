import type { Request, Response, NextFunction } from 'express';
import { AuditLog } from '../db/tables.js';
import { logger } from '../lib/logger.js';
import { newAuditId, newTraceId } from '../lib/ids.js';

/**
 * HTTP 访问留痕中间件。
 *
 * 在响应结束时把本次请求的访问信息写入 openGauss 的 audit_logs 表。
 * 写入失败只记告警，绝不影响主流程（审计不能成为业务可用性的单点）。
 *
 * 注意：该中间件记录的是"访问维度"的留痕；各写命令的"业务维度"审计
 * （before/after 快照、变更原因）由服务层通过 writeBusinessAudit 记录，
 * 两者共用 audit_logs 表与同一套字段（超集模型）。
 */
export function auditOperation(action: string) {
  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    const startTime = Date.now();
    const traceId = newTraceId();

    // 让业务处理器可以复用同一个 traceId
    res.locals.traceId = traceId;

    const originalJson = res.json.bind(res);
    let responseBody = '';

    res.json = ((body?: unknown) => {
      responseBody = safeStringify(body) ?? '';
      return originalJson(body);
    }) as Response['json'];

    const originalEnd = res.end.bind(res);
    res.end = ((...args: unknown[]) => {
      const durationMs = Date.now() - startTime;
      const statusCode = res.statusCode;

      // 审计写入不阻塞响应返回
      void (async () => {
        try {
          await AuditLog.create({
            _id: newAuditId(),
            actorId: req.user?.actorId ?? 'anonymous',
            actorRole: req.user?.roleCode ?? 'guest',
            operatorTerminal: 'WEB-BACKEND',
            action,
            resource: `${req.method} ${req.originalUrl}`,
            resourceId: (req.params?.id as string | undefined) ?? null,
            statusCode,
            durationMs,
            ip: req.ip ?? req.socket.remoteAddress ?? 'unknown',
            userAgent: (req.headers['user-agent'] as string | undefined) ?? 'unknown',
            requestBody: req.method !== 'GET' ? safeStringify(req.body) : null,
            responseSummary: statusCode >= 400 ? responseBody.slice(0, 500) : null,
            traceId,
            occurredAt: new Date(),
          });
        } catch (err) {
          logger.warn({ err, action }, '审计留痕写入失败（已忽略，不影响业务）');
        }
      })();

      return (originalEnd as (...a: unknown[]) => Response)(...args);
    }) as Response['end'];

    next();
  };
}

/** 业务审计写入：记录命令的前后快照与原因（对应前端契约 DO-013） */
export async function writeBusinessAudit(input: {
  actorId: string;
  actorRole?: string;
  action: string;
  objectType: string;
  objectId: string;
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
  reason?: string;
  traceId?: string;
}): Promise<string> {
  const id = newAuditId();
  try {
    await AuditLog.create({
      _id: id,
      id,
      actorId: input.actorId,
      actorRole: input.actorRole ?? null,
      operatorTerminal: 'WEB-BACKEND',
      action: input.action,
      objectType: input.objectType,
      objectId: input.objectId,
      before: input.before ?? {},
      after: input.after ?? {},
      reason: input.reason ?? '',
      traceId: input.traceId ?? newTraceId(),
      occurredAt: new Date(),
    });
  } catch (err) {
    logger.warn({ err, action: input.action }, '业务审计写入失败');
  }
  return id;
}

function safeStringify(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  try {
    return JSON.stringify(value).slice(0, 2000);
  } catch {
    return null;
  }
}
