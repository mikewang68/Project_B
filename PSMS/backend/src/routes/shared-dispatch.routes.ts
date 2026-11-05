import { Router } from 'express';
import { ContextError, IamContextClient } from '@bproject/iam-context-client';
import { env } from '../config/env.js';
import { AppError } from '../middleware/errorHandler.js';
import { readShared, sharedMenus, sharedResources, type SharedResource } from '../services/shared-dispatch.service.js';

/** Separate service-authenticated read boundary; never accepts a browser's UserContext or SQL. */
export const sharedDispatchRouter = Router();
sharedDispatchRouter.use((_req, res, next) => {
  res.setHeader('Cache-Control', 'no-store');
  if (!env.PSMS_SHARED_READ_ENABLED || !env.PSMS_SSO_ENABLED)
    return next(new AppError(503, 'SHARED_READ_DISABLED', '共享读取未启用'));
  next();
});
sharedDispatchRouter.get(['/:resource', '/:resource/:id'], async (req, res, next) => {
  try {
    const audience = req.header('X-Shared-Audience') ?? '';
    const token = req.header('X-SSO-Service-Token') ?? '';
    const subject = req.header('X-Shared-Subject') ?? '', sessionId = req.header('X-Shared-Session-Id') ?? '';
    if (!Object.hasOwn(sharedMenus, audience) || token.length < 32 || token.length > 4096
        || !/^[a-fA-F0-9-]{32,36}$/.test(subject) || !/^[-_a-zA-Z0-9]{1,256}$/.test(sessionId))
      throw new AppError(403, 'SHARED_SERVICE_DENIED', '共享服务身份无效');
    const resource = String(req.params.resource);
    if (!Object.hasOwn(sharedResources, resource))
      throw new AppError(404, 'SHARED_RESOURCE_NOT_FOUND', '共享资源不存在');
    // Reject extra query keys rather than silently honoring caller scope, SQL, menu or action.
    if (Object.keys(req.query).some(k => !['page', 'pageSize'].includes(k)))
      throw new AppError(400, 'SHARED_QUERY_INVALID', '仅允许分页参数');
    const page = Number(req.query.page ?? 1), pageSize = Number(req.query.pageSize ?? 50);
    if (!Number.isInteger(page) || page < 1 || page > 10000 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100)
      throw new AppError(400, 'SHARED_PAGE_INVALID', '分页参数无效');
    const client = new IamContextClient({ baseUrl: env.PSMS_IAM_BASE_URL.replace(/\/$/, '') + '/api/v1/iam',
      audience, serviceToken: token, allowLoopbackHttp: env.PSMS_SHARED_ALLOW_LOOPBACK_HTTP });
    const descriptor = sharedResources[resource as SharedResource];
    // IAM verifies service identity, current session/audience, current menu action,
    // upper project grants AND the explicit shared-resource grant for every request.
    const filter = await client.resourceFilter({ subject, sessionId }, {
      resourceKey: descriptor.key, menuKey: sharedMenus[audience as keyof typeof sharedMenus], action: 'VIEW' });
    const data = await readShared(resource as SharedResource, filter, page, pageSize,
      req.params.id === undefined ? undefined : String(req.params.id));
    res.json({ ok: true, data });
  } catch (error) {
    if (error instanceof ContextError)
      next(new AppError(error.status, 'SHARED_AUTHORIZATION_UNAVAILABLE', '共享授权不可用或未获授权'));
    else next(error);
  }
});
sharedDispatchRouter.use((_req, _res, next) => next(new AppError(405, 'SHARED_READ_ONLY', '共享入口仅支持读取')));
