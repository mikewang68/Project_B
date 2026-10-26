import { env } from '../config/env.js';
import { executeBusinessCommand } from '../services/business-command.service.js';
import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as workOrderService from '../services/workOrder.service.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const workOrdersRouter = Router();

// API-008 工单列表
workOrdersRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const result = await workOrderService.listWorkOrders({
      workArea: q(req, 'workArea'),
      equipmentId: q(req, 'equipmentId'),
      statuses: qList(req, 'statuses'),
      planId: q(req, 'planId'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-008',
        operationId: 'GET_mock_work_orders',
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

// 工单详情
workOrdersRouter.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const wo = await workOrderService.getWorkOrderById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-008', items: [wo] } });
  } catch (err) {
    next(err);
  }
});

// API-008/API-009 工单命令：assign / accept / start / pause / complete / cancel
workOrdersRouter.post('/:id', authenticate, auditOperation('workOrderAction'), async (req, res, next) => {
  try {
    if(env.PSMS_SSO_ENABLED){
      if(typeof res.locals.platformSubject!=='string'){res.status(403).json({ok:false,message:'统一身份会话无效'});return;}
      const result=await executeBusinessCommand('work-orders',pathParam(req),req.body,{...req.user!,subject:res.locals.platformSubject});
      res.json({ok:true,data:result});return;
    }
    const { action, ...data } = req.body as Record<string, unknown> & { action?: string };
    const actorId = req.user!.actorId;
    let result: unknown;

    switch (action) {
      case 'assign':
        result = await workOrderService.assignWorkOrder(pathParam(req), actorId, data);
        break;
      case 'accept':
        result = await workOrderService.acceptWorkOrder(pathParam(req), actorId);
        break;
      case 'start':
        result = await workOrderService.startWorkOrder(pathParam(req));
        break;
      case 'pause':
        result = await workOrderService.pauseWorkOrder(pathParam(req), (data.reason as string) ?? '未说明原因');
        break;
      case 'complete':
        result = await workOrderService.completeWorkOrder(pathParam(req), data.feedback as never);
        break;
      case 'cancel':
        result = await workOrderService.cancelWorkOrder(pathParam(req), (data.reason as string) ?? '未说明原因');
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的工单动作：${String(action)}`,
        });
        return;
    }

    res.json({ ok: true, data: { apiId: 'API-009', items: [result] } });
  } catch (err) {
    next(err);
  }
});
