import { timingSafeEqual, randomUUID } from 'node:crypto';
import { Router } from 'express';
import { AppError } from '../middleware/errorHandler.js';
import { authenticate } from '../middleware/auth.js';
import { env } from '../config/env.js';
import { WmsInbound } from '../services/wms-inbound.service.js';

export const wmsInbound = process.env.PSMS_WMS_ENABLED === 'true' && process.env.PSMS_WMS_DATABASE ? new WmsInbound({
  database: process.env.PSMS_WMS_DATABASE ?? '', baseUrl: process.env.PSMS_WMS_BASE_URL ?? '',
  sendToken: process.env.PSMS_WMS_SEND_TOKEN ?? '', receiveToken: process.env.PSMS_WMS_RECEIVE_TOKEN ?? '',
  companyCode: process.env.PSMS_WMS_COMPANY_CODE ?? '', ownerCode: process.env.PSMS_WMS_OWNER_CODE ?? '',
}) : null;

// Mounted before the browser SSO guard. This router has its own narrowly scoped service identity.
export const wmsEventRouter = Router();
wmsEventRouter.post('/inbound-events', async (req, res, next) => {
  try {
    if (!wmsInbound) throw new AppError(503, 'WMS_DISABLED', 'WMS 联调服务未启用');
    const expected = Buffer.from('Bearer ' + wmsInbound.config.receiveToken);
    const actual = Buffer.from(req.header('authorization') ?? '');
    if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
      throw new AppError(401, 'UNAUTHORIZED', '服务凭据无效');
    }
    res.json(await wmsInbound.receive(req.body));
  } catch (error) {
    if (error instanceof AppError) {
      res.status(error.statusCode).json({ code: error.errorCode, message: error.message, traceId: randomUUID() });
    } else next(error);
  }
});

export const wmsBusinessRouter = Router();
wmsBusinessRouter.use(authenticate);
wmsBusinessRouter.get('/work-orders/:id/inbound', async (req, res, next) => {
  try {
    res.json({ ok: true, data: wmsInbound ? await wmsInbound.detail(String(req.params.id), { ...req.user!, subject: res.locals.platformSubject ?? req.user!.actorId })
      : { enabled: false, task: null, pendingSequences: [] } });
  } catch (error) { next(error); }
});
wmsBusinessRouter.post('/work-orders/:id/inbound/:operation', async (req, res, next) => {
  if (!env.PSMS_SSO_ENABLED || typeof res.locals.platformSubject !== 'string') {
    res.status(403).json({ ok: false, message: '此操作需要当前统一身份会话' }); return;
  }
  try {
    if (!wmsInbound) throw new AppError(503, 'WMS_DISABLED', 'WMS 联调服务未启用');
    const actor = { ...req.user!, subject: res.locals.platformSubject };
    const operation = String(req.params.operation);
    if (!['dispatch', 'tasks', 'retry'].includes(operation)) throw new AppError(404, 'NOT_FOUND', '业务动作不存在');
    if (operation === 'retry') {
      res.json({ ok: true, data: await wmsInbound.retry(String(req.params.id), actor) }); return;
    }
    const data = operation === 'dispatch'
      ? await wmsInbound.dispatch(String(req.params.id), req.body, actor)
      : await wmsInbound.taskCommand(String(req.params.id), req.body, actor);
    res.json({ ok: true, data });
  } catch (error) { next(error); }
});
