import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as offlineSyncService from '../services/offlineSync.service.js';
import { pathParam, q, qList, qNum } from '../lib/http.js';

export const offlineSyncRouter = Router();

// API-018 离线包列表
offlineSyncRouter.get('/packets', optionalAuth, async (req, res, next) => {
  try {
    const result = await offlineSyncService.listPackets({
      terminalId: q(req, 'terminalId'),
      statuses: qList(req, 'statuses'),
      page: qNum(req, 'page'),
      pageSize: qNum(req, 'pageSize'),
    });
    res.json({
      ok: true,
      data: {
        apiId: 'API-018',
        operationId: 'GET_mock_offline_packets',
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

// 离线包详情
offlineSyncRouter.get('/packets/:id', optionalAuth, async (req, res, next) => {
  try {
    const pkt = await offlineSyncService.getPacketById(pathParam(req));
    res.json({ ok: true, data: { apiId: 'API-018', items: [pkt] } });
  } catch (err) {
    next(err);
  }
});

// API-019 离线包处置：sync / resolve
offlineSyncRouter.post('/packets/:id', authenticate, auditOperation('offlineSync'), async (req, res, next) => {
  try {
    const { action, ...data } = req.body as Record<string, unknown> & { action?: string };
    const actorId = req.user!.actorId;
    let result: unknown;

    switch (action) {
      case 'sync':
        result = await offlineSyncService.syncPacket(
          pathParam(req),
          actorId,
          (data.payload as Record<string, unknown>) ?? {},
          (data.version as number) ?? 1,
        );
        break;
      case 'resolve':
        result = await offlineSyncService.resolvePacketConflict(
          pathParam(req),
          actorId,
          ((data.resolution as string) ?? 'DISCARD') as 'ACCEPT_LOCAL' | 'ACCEPT_SERVER' | 'MANUAL_MERGE' | 'DISCARD',
        );
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的离线包动作：${String(action)}`,
        });
        return;
    }

    res.json({ ok: true, data: { apiId: 'API-019', items: [result] } });
  } catch (err) {
    next(err);
  }
});
