import { env } from '../config/env.js';
import { executeBusinessCommand } from '../services/business-command.service.js';
import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as exceptionService from '../services/exception.service.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const exceptionsRouter = Router();

// API-014 异常列表
exceptionsRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const result = await exceptionService.listExceptions({
      workArea: q(req, 'workArea'),
      statuses: qList(req, 'statuses'),
      types: qList(req, 'types'),
      severities: qList(req, 'severities'),
      equipmentId: q(req, 'equipmentId'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-014',
        operationId: 'GET_mock_exceptions',
        items: result.items,
        total: result.total,
        page: result.page,
        pageSize: result.pageSize,
      },
    });
  } catch (err) {
    next(err);
  }
});

// 异常详情
exceptionsRouter.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const ex = await exceptionService.getExceptionById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-014', items: [ex] } });
  } catch (err) {
    next(err);
  }
});

// API-015 异常处置：acknowledge / resolve / close
exceptionsRouter.post('/:id', authenticate, auditOperation('exceptionAction'), async (req, res, next) => {
  try {
    if(env.PSMS_SSO_ENABLED){
      if(typeof res.locals.platformSubject!=='string'){res.status(403).json({ok:false,message:'统一身份会话无效'});return;}
      const result=await executeBusinessCommand('exceptions',pathParam(req),req.body,{...req.user!,subject:res.locals.platformSubject});
      res.json({ok:true,data:result});return;
    }
    const { action, ...data } = req.body as Record<string, unknown> & { action?: string };
    const actorId = req.user!.actorId;
    let result: unknown;

    switch (action) {
      case 'acknowledge':
        result = await exceptionService.acknowledgeException(pathParam(req), actorId);
        break;
      case 'resolve':
        result = await exceptionService.resolveException(
          pathParam(req),
          actorId,
          (data.resolution as string) ?? '已处理',
          data.rootCause as string | undefined,
        );
        break;
      case 'close':
        result = await exceptionService.closeException(pathParam(req), actorId);
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的异常动作：${String(action)}`,
        });
        return;
    }

    res.json({ ok: true, data: { apiId: 'API-015', items: [result] } });
  } catch (err) {
    next(err);
  }
});
