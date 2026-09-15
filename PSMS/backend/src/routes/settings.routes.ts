// @ts-nocheck
import { Router, type Request } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as settingsService from '../services/settings.service.js';

function q(req: Request, key: string): string | undefined {
  const v = req.query[key]; return Array.isArray(v) ? v[0] : (v as string | undefined);
}

export const settingsRouter = Router();

// API-022 GET /api/settings — 读取系统配置
settingsRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const configId = q(req, 'configId') ?? 'CFG-001';
    const config = await settingsService.getConfig(configId);
    const now = config.updatedAt || new Date().toISOString();
    const scenarioId = config.scenarioId || 'SCN-01';
    // Map backend model to frontend do015Schema-compatible format,
    // stripping db-internal keys (_id, __v, configId) and ensuring all required fields.
    const item = {
      id: config.id ?? config.configId ?? configId,
      configVersion: config.configVersion ?? 'CFG-1.0',
      displayName: config.displayName ?? 'B项目生产调度管理系统',
      defaultScenarioId: config.defaultScenarioId ?? 'SCN-01',
      ruleVersion: config.ruleVersion ?? 'RULE-1.0',
      dispatchStrategy: config.dispatchStrategy ?? 'BALANCED',
      recommendationEnabled: config.recommendationEnabled ?? true,
      offlineSyncEnabled: config.offlineSyncEnabled ?? true,
      reportPeriod: config.reportPeriod ?? 'DAILY',
      auditRetentionDays: config.auditRetentionDays ?? 180,
      status: config.status ?? 'DRAFT',
      version: typeof config.version === 'number' ? config.version : 1,
      createdAt: config.createdAt ?? now,
      updatedAt: config.updatedAt ?? now,
      updatedBy: config.updatedBy ?? 'ACTOR-ADMIN',
    };
    res.json({
      ok: true,
      data: {
        apiId: 'API-022',
        operationId: 'GET_mock_config',
        now,
        scenarioId,
        items: [item],
      },
      auditLogId: `AUD-READ-${Date.now()}`,
      traceId: `TRACE-READ-${Date.now()}`,
    });
  } catch (err) { next(err); }
});

// API-023 POST /api/settings/:id — 配置写命令 (edit/submit/approve/publish/rollback)
settingsRouter.post('/:id', optionalAuth, auditOperation('configAction'), async (req, res, next) => {
  try {
    // Accept both "command" (frontend convention) and "action" (backend convention)
    const action = req.body.command || req.body.action;
    const { changes, reason, expectedVersion } = req.body;
    const actorId = req.user?.actorId ?? 'ACTOR-DEMO';
    let result;
    switch (action) {
      case 'edit': result = await settingsService.updateConfig(req.params.id, changes ?? {}, actorId, reason, expectedVersion); break;
      case 'submit': result = await settingsService.submitConfig(req.params.id, actorId, expectedVersion); break;
      case 'approve': result = await settingsService.approveConfig(req.params.id, actorId, expectedVersion); break;
      case 'publish': result = await settingsService.publishConfig(req.params.id, actorId, expectedVersion); break;
      case 'rollback': result = await settingsService.rollbackConfig(req.params.id, actorId, expectedVersion); break;
      default: res.status(400).json({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: `Unknown action: ${action}`,
        auditLogId: `AUD-ERR-${Date.now()}`,
        traceId: `TRACE-ERR-${Date.now()}`,
      }); return;
    }
    const now = result.updatedAt || new Date().toISOString();
    const scenarioId = result.scenarioId || 'SCN-01';
    const item = {
      id: result.id ?? result.configId ?? req.params.id,
      configVersion: result.configVersion ?? 'CFG-1.0',
      displayName: result.displayName ?? 'B项目生产调度管理系统',
      defaultScenarioId: result.defaultScenarioId ?? 'SCN-01',
      ruleVersion: result.ruleVersion ?? 'RULE-1.0',
      dispatchStrategy: result.dispatchStrategy ?? 'BALANCED',
      recommendationEnabled: result.recommendationEnabled ?? true,
      offlineSyncEnabled: result.offlineSyncEnabled ?? true,
      reportPeriod: result.reportPeriod ?? 'DAILY',
      auditRetentionDays: result.auditRetentionDays ?? 180,
      status: result.status ?? 'DRAFT',
      version: typeof result.version === 'number' ? result.version : 1,
      createdAt: result.createdAt ?? now,
      updatedAt: result.updatedAt ?? now,
      updatedBy: result.updatedBy ?? actorId,
    };
    res.json({
      ok: true,
      data: {
        apiId: 'API-023',
        operationId: 'POST_mock_config_id_command',
        now,
        scenarioId,
        items: [item],
      },
      auditLogId: `AUD-CMD-${Date.now()}`,
      traceId: `TRACE-CMD-${Date.now()}`,
    });
  } catch (err) { next(err); }
});
