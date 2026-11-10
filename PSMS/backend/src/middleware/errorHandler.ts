import type { Request, Response, NextFunction } from 'express';
import { logger } from '../lib/logger.js';

export class AppError extends Error {
  constructor(
    public statusCode: number,
    public errorCode: string,
    message: string,
    public details?: unknown,
  ) {
    super(message);
    this.name = 'AppError';
  }
}

export function errorHandler(err: Error, req: Request, res: Response, _next: NextFunction): void {
  const auditLogId = `AUD-ERR-${Date.now()}`;
  const traceId = `TRACE-ERR-${Date.now()}`;
  if (req.path === '/api/integrations/wms/inbound-events') {
    const parserStatus = (err as Error & { status?: number }).status;
    const status = err instanceof AppError ? err.statusCode : parserStatus === 400 || parserStatus === 413 ? parserStatus : 500;
    if (status === 500) logger.error({ err }, 'WMS 回执处理失败');
    res.status(status).json({ code: err instanceof AppError ? err.errorCode : status === 400 ? 'INVALID_JSON'
      : status === 413 ? 'PAYLOAD_TOO_LARGE' : 'INTERNAL_ERROR',
      message: err instanceof AppError ? err.message : status === 400 ? '回执 JSON 格式无效'
        : status === 413 ? '回执体超过允许大小' : '回执处理暂时失败，请稍后按原事件重试', traceId });
    return;
  }
  if (err instanceof AppError) {
    res.status(err.statusCode).json({
      ok: false,
      errorCode: err.errorCode,
      message: err.message,
      auditLogId,
      traceId,
      ...(err.details ? { details: err.details } : {}),
    });
    return;
  }

  logger.error({ err }, 'Unhandled error');
  res.status(500).json({
    ok: false,
    errorCode: 'INTERNAL_ERROR',
    message: '服务器内部错误',
    auditLogId,
    traceId,
  });
}
