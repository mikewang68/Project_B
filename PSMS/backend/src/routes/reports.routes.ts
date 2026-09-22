import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as reportService from '../services/report.service.js';
import { q } from '../lib/http.js';

export const reportsRouter = Router();

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

// API-020 统计报表（数据库侧聚合）
reportsRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const report = await reportService.generateReport({
      startDate: q(req, 'startDate') ?? today(),
      endDate: q(req, 'endDate') ?? today(),
      workArea: q(req, 'workArea'),
      type: q(req, 'type') ?? 'daily',
    });
    res.json({
      ok: true,
      data: { apiId: 'API-020', operationId: 'GET_mock_reports', items: [report] },
    });
  } catch (err) {
    next(err);
  }
});

// API-021 报表受控导出
reportsRouter.post('/export', authenticate, auditOperation('exportReport'), async (req, res, next) => {
  try {
    const report = await reportService.generateReport({
      startDate: (req.body?.startDate as string) ?? today(),
      endDate: (req.body?.endDate as string) ?? today(),
      workArea: req.body?.workArea as string | undefined,
      type: (req.body?.type as string) ?? 'daily',
    });

    // 导出用途为合规留痕必填项（前端契约 API-021 的 purpose 字段）
    const purpose = req.body?.purpose as string | undefined;
    if (!purpose) {
      res.status(400).json({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: '导出用途（purpose）为必填项',
      });
      return;
    }

    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', `attachment; filename="report-${Date.now()}.json"`);
    res.json({
      ok: true,
      data: {
        apiId: 'API-021',
        operationId: 'POST_mock_reports_export',
        format: req.body?.format ?? 'JSON',
        scope: req.body?.scope ?? 'OPERATION',
        purpose,
        items: [report],
      },
    });
  } catch (err) {
    next(err);
  }
});
