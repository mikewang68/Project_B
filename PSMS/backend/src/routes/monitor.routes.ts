import { Router, type Request } from 'express';
import { optionalAuth } from '../middleware/auth.js';
import { Equipment, TelemetryPoint, WorkOrder } from '../db/tables.js';

export const monitorRouter = Router();

function q(req: Request, key: string): string | undefined {
  const v = req.query[key];
  return Array.isArray(v) ? (v[0] as string) : (v as string | undefined);
}

// API-012 全流程监控：工单 + 设备 + 最近遥测
monitorRouter.get('/operations', optionalAuth, async (req, res, next) => {
  try {
    const workArea = q(req, 'workArea');
    const filter = workArea ? { workArea } : {};

    const [workOrders, equipments] = await Promise.all([
      WorkOrder.find(filter).sort({ updatedAt: -1 }).limit(50).lean(),
      Equipment.find(filter).sort({ equipmentId: 1 }).lean(),
    ]);

    // 从时序集合取最近测点，验证时序写入链路
    const equipmentIds = equipments.map((e) => e.equipmentId);
    const telemetry = await TelemetryPoint.find({ equipmentId: { $in: equipmentIds } })
      .sort({ sourceTimestamp: -1 })
      .limit(200)
      .lean();

    res.json({
      ok: true,
      data: {
        apiId: 'API-012',
        operationId: 'GET_mock_operations',
        now: new Date().toISOString(),
        items: [{ workOrders, equipments, telemetry }],
      },
    });
  } catch (err) {
    next(err);
  }
});
