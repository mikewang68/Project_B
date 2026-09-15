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

export function errorHandler(err: Error, _req: Request, res: Response, _next: NextFunction): void {
  const auditLogId = `AUD-ERR-${Date.now()}`;
  const traceId = `TRACE-ERR-${Date.now()}`;
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
