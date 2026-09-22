import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { seedDatabase } from '../seeds/seed-data.js';
import { logger } from '../lib/logger.js';

export const demoRouter = Router();

/**
 * 演示数据重置。
 *
 * 接入 MongoDB 后这里不再是空壳：会真正清空集合并重新灌入
 * 固定种子数据（与 pnpm seed 同一份定义），用于把演示恢复到基线状态。
 */
demoRouter.post('/reset', authenticate, async (req, res, next) => {
  try {
    const scenarioId = (req.body?.scenarioId as string | undefined) ?? 'SCN-01';
    const result = await seedDatabase({ drop: true, withTelemetry: true });

    logger.info({ scenarioId, actorId: req.user!.actorId }, '演示数据已重置');

    res.json({
      ok: true,
      data: {
        apiId: 'API-025',
        operationId: 'POST_mock_demo_reset',
        scenario: { id: scenarioId, resetAt: new Date().toISOString() },
        dropped: result.dropped,
        counts: result.counts,
      },
    });
  } catch (err) {
    next(err);
  }
});

// 场景切换（演示用，仅回执当前场景标识）
demoRouter.post('/scenario', authenticate, async (req, res, next) => {
  try {
    const scenarioId = (req.body?.scenarioId as string | undefined) ?? 'SCN-01';
    res.json({
      ok: true,
      data: {
        apiId: 'API-013',
        operationId: 'POST_mock_scenarios_id_play',
        scenario: { id: scenarioId, switchedAt: new Date().toISOString() },
      },
    });
  } catch (err) {
    next(err);
  }
});
