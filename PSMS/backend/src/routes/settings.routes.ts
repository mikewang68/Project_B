import { Router } from 'express';
import { authenticate, optionalAuth } from '../middleware/auth.js';
import { auditOperation } from '../middleware/audit.js';
import * as settingsService from '../services/settings.service.js';
import type { ConfigVersionDoc } from '../models/index.js';
import { pathParam, q } from '../lib/http.js';

export const settingsRouter = Router();

/** 把配置文档映射为前端 do015Schema 兼容的形状（隐藏库内字段） */
function toContractShape(config: ConfigVersionDoc, fallbackId: string, now: string) {
  return {
    id: config._id ?? config.configId ?? fallbackId,
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
    createdAt: (config.createdAt ?? new Date(now)).toISOString?.() ?? now,
    updatedAt: (config.updatedAt ?? new Date(now)).toISOString?.() ?? now,
    updatedBy: config.updatedBy ?? 'ACTOR-ADMIN',
  };
}

// API-022 读取系统配置
settingsRouter.get('/', optionalAuth, async (req, res, next) => {
  try {
    const configId = q(req, 'configId') ?? 'CFG-001';
    const config = await settingsService.getConfig(configId);
    const now = new Date().toISOString();

    res.json({
      ok: true,
      data: {
        apiId: 'API-022',
        operationId: 'GET_mock_config',
        now,
        scenarioId: config.scenarioId ?? 'SCN-01',
        items: [toContractShape(config, configId, now)],
      },
    });
  } catch (err) {
    next(err);
  }
});

// API-023 配置写命令：edit / submit / approve / publish / rollback
settingsRouter.post('/:id', authenticate, auditOperation('configAction'), async (req, res, next) => {
  try {
    // 同时接受前端约定的 command 与后端的 action 两种字段名
    const action = (req.body?.command ?? req.body?.action) as string | undefined;
    const { changes, reason, expectedVersion } = req.body ?? {};
    const actorId = req.user!.actorId;
    let result: ConfigVersionDoc | null;

    switch (action) {
      case 'edit':
        result = await settingsService.updateConfig(pathParam(req), changes ?? {}, actorId, reason, expectedVersion);
        break;
      case 'submit':
        result = await settingsService.submitConfig(pathParam(req), actorId, expectedVersion);
        break;
      case 'approve':
        result = await settingsService.approveConfig(pathParam(req), actorId, expectedVersion);
        break;
      case 'publish':
        result = await settingsService.publishConfig(pathParam(req), actorId, expectedVersion);
        break;
      case 'rollback':
        result = await settingsService.rollbackConfig(pathParam(req), actorId, expectedVersion);
        break;
      default:
        res.status(400).json({
          ok: false,
          errorCode: 'DEMO-SCENARIO-001',
          message: `未知的配置命令：${String(action)}`,
        });
        return;
    }

    if (!result) {
      res.status(404).json({ ok: false, errorCode: 'CONFIG_NOT_FOUND', message: '配置不存在' });
      return;
    }

    const now = new Date().toISOString();
    res.json({
      ok: true,
      data: {
        apiId: 'API-023',
        operationId: 'POST_mock_config_id_command',
        now,
        scenarioId: result.scenarioId ?? 'SCN-01',
        items: [toContractShape(result, pathParam(req), now)],
      },
    });
  } catch (err) {
    next(err);
  }
});
