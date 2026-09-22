import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as planService from '../services/plan.service.js';
import * as workOrderService from '../services/workOrder.service.js';
import { Equipment } from '../models/index.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const plansRouter = Router();

// API-001/API-002 计划台账查询
plansRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const result = await planService.listPlans({
      workArea: q(req, 'workArea'),
      statuses: qList(req, 'statuses'),
      date: q(req, 'date'),
      planBatchNo: q(req, 'planBatchNo'),
      trainNo: q(req, 'trainNo'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
      sort: q(req, 'sort'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-002',
        operationId: 'GET_mock_plans',
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

// 计划详情
plansRouter.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const plan = await planService.getPlanById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-002', items: [plan] } });
  } catch (err) {
    next(err);
  }
});

// API-004 计划确认
plansRouter.post('/:id/confirm', authenticate, auditOperation('confirmPlan'), async (req, res, next) => {
  try {
    const plan = await planService.confirmPlan(
      pathParam(req),
      req.user!.actorId,
      req.body?.supplements,
    );
    res.json({
      ok: true,
      data: { apiId: 'API-004', operationId: 'POST_mock_plans_id_confirm', items: [plan] },
    });
  } catch (err) {
    next(err);
  }
});

// API-005 接车窗口推荐（按作业区内的设备/资源给出建议）
plansRouter.post('/:id/recommendation', authenticate, auditOperation('recommendation'), async (req, res, next) => {
  try {
    const plan = await planService.getPlanById(pathParam(req));
    const equipments = await Equipment.find({ workArea: plan.workArea })
      .sort({ status: 1, equipmentId: 1 })
      .lean();

    res.json({
      ok: true,
      data: {
        apiId: 'API-005',
        operationId: 'GET_mock_plans_id_recommendation',
        items: [
          {
            plan,
            suggestions: {
              waveNo: `WAVE-${plan.planBatchNo}`,
              estimatedDuration: 120,
              equipments: equipments.map((e) => ({
                equipmentId: e.equipmentId,
                name: e.name,
                type: e.type,
                status: e.status,
                workArea: e.workArea,
              })),
            },
          },
        ],
      },
    });
  } catch (err) {
    next(err);
  }
});

// API-007 任务拆解（写入任务集合）
plansRouter.post('/:id/tasks', authenticate, auditOperation('decomposeTasks'), async (req, res, next) => {
  try {
    if (!Array.isArray(req.body?.tasks)) {
      res.status(400).json({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: 'tasks 必须为数组',
      });
      return;
    }
    const tasks = await workOrderService.createTasksForPlan(pathParam(req), req.body.tasks);
    res.json({
      ok: true,
      data: { apiId: 'API-007', operationId: 'POST_mock_plans_id_decompose', items: tasks },
    });
  } catch (err) {
    next(err);
  }
});

// 读取某计划的任务列表
plansRouter.get('/:id/tasks', optionalAuth, async (req, res, next) => {
  try {
    const tasks = await workOrderService.listTasks({ planId: pathParam(req) });
    res.json({ ok: true, data: { apiId: 'API-007', items: tasks } });
  } catch (err) {
    next(err);
  }
});
