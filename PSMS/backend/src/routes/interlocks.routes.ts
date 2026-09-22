import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as interlockService from '../services/interlock.service.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const interlocksRouter = Router();

// API-016 联锁列表
interlocksRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const result = await interlockService.listInterlocks({
      workArea: q(req, 'workArea'),
      statuses: qList(req, 'statuses'),
      equipmentId: q(req, 'equipmentId'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-016',
        operationId: 'GET_mock_interlocks',
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

// 联锁详情
interlocksRouter.get('/:id', optionalAuth, async (req, res, next) => {
  try {
    const il = await interlockService.getInterlockById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-016', items: [il] } });
  } catch (err) {
    next(err);
  }
});

// API-017 联锁处置：trigger / override / approveOverride / reset
interlocksRouter.post('/:id', authenticate, auditOperation('interlockAction'), async (req, res, next) => {
  try {
    const { action, ...data } = req.body as Record<string, unknown> & { action?: string };
    const actorId = req.user!.actorId;
    let result: unknown;

    switch (action) {
      case 'trigger':
        result = await interlockService.triggerInterlock(
          pathParam(req),
          actorId,
          (data.reason as string) ?? '联锁触发',
        );
        break;
      case 'override':
        result = await interlockService.requestOverride(
          pathParam(req),
          actorId,
          (data.reason as string) ?? '未说明原因',
          (data.expiresInHours as number) ?? 4,
        );
        break;
      case 'approveOverride':
        result = await interlockService.approveOverride(pathParam(req), actorId);
        break;
      case 'reset':
        result = await interlockService.resetInterlock(pathParam(req), actorId);
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的联锁动作：${String(action)}`,
        });
        return;
    }

    res.json({ ok: true, data: { apiId: 'API-017', items: [result] } });
  } catch (err) {
    next(err);
  }
});
