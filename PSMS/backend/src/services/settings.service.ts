import { ConfigVersion, type ConfigVersionDoc, applyAuditRetention } from '../models/index.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';
import { logger } from '../lib/logger.js';

/** 允许通过 edit 命令修改的字段白名单（对齐前端契约 DO-015） */
const EDITABLE_FIELDS = [
  'displayName',
  'defaultScenarioId',
  'ruleVersion',
  'dispatchStrategy',
  'recommendationEnabled',
  'offlineSyncEnabled',
  'reportPeriod',
  'auditRetentionDays',
] as const;

/** 配置状态机的合法迁移（与前端 commands/stateMachines.ts 的 DO-015 对齐） */
const STATUS_TRANSITIONS: Record<string, string[]> = {
  DRAFT: ['SUBMITTED'],
  SUBMITTED: ['APPROVED', 'DRAFT'],
  APPROVED: ['PUBLISHED', 'DRAFT'],
  PUBLISHED: ['ROLLED_BACK'],
  ROLLED_BACK: ['DRAFT'],
};

function checkTransition(current: string, next: string): void {
  const allowed = STATUS_TRANSITIONS[current];
  if (!allowed || !allowed.includes(next)) {
    throw new AppError(409, 'INVALID_STATE', `不能从 ${current} 转移到 ${next}`);
  }
}

function checkVersion(config: ConfigVersionDoc, expectedVersion?: number): void {
  if (expectedVersion === undefined || expectedVersion === null) return;
  const currentVersion = config.version ?? 0;
  if (currentVersion !== expectedVersion) {
    throw new AppError(
      409,
      'DEMO-VERSION-001',
      `配置版本已变化：期望 v${expectedVersion}，当前 v${currentVersion}`,
      { expectedVersion, actualVersion: currentVersion },
    );
  }
}

/** 取配置：优先按 _id，其次按 configId（兼容两种调用方式） */
async function requireConfig(configId: string, expectedVersion?: number): Promise<ConfigVersionDoc> {
  const config =
    (await ConfigVersion.findById(configId).lean<ConfigVersionDoc>()) ??
    (await ConfigVersion.findOne({ configId }).lean<ConfigVersionDoc>());

  if (!config) throw new AppError(404, 'CONFIG_NOT_FOUND', '配置不存在');
  checkVersion(config, expectedVersion);
  return config;
}

export async function getConfig(configId: string): Promise<ConfigVersionDoc> {
  const config =
    (await ConfigVersion.findById(configId).lean<ConfigVersionDoc>()) ??
    (await ConfigVersion.findOne({ configId }).lean<ConfigVersionDoc>());
  if (!config) throw new AppError(404, 'CONFIG_NOT_FOUND', '配置不存在');
  return config;
}

export async function updateConfig(
  configId: string,
  changes: Record<string, unknown>,
  actorId: string,
  reason?: string,
  expectedVersion?: number,
) {
  const config = await requireConfig(configId, expectedVersion);
  if (config.status !== 'DRAFT') {
    throw new AppError(409, 'INVALID_STATE', '只能修改草稿状态的配置');
  }

  const history = [...(config.changeHistory ?? [])];
  const update: Record<string, unknown> = {};
  const appliedChanges: Record<string, unknown> = {};

  for (const key of EDITABLE_FIELDS) {
    if (key in changes) {
      history.push({
        field: key,
        oldValue: config[key as keyof ConfigVersionDoc],
        newValue: changes[key],
        changedBy: actorId,
        changedAt: new Date(),
      });
      update[key] = changes[key];
      appliedChanges[key] = changes[key];
    }
  }

  if (Object.keys(appliedChanges).length === 0) {
    throw new AppError(400, 'DEMO-SCENARIO-001', 'changes 中不包含任何可编辑字段');
  }

  if (reason) {
    history.push({ field: '_reason', oldValue: null, newValue: reason, changedBy: actorId, changedAt: new Date() });
  }

  update.changeHistory = history;
  update.updatedBy = actorId;
  update.version = (config.version ?? 0) + 1;

  const updated = await ConfigVersion.findByIdAndUpdate(config._id, update, { new: true }).lean();

  await writeBusinessAudit({
    actorId,
    action: 'config:edit',
    objectType: 'DO-015',
    objectId: configId,
    before: { version: config.version, status: config.status },
    after: { version: update.version, status: config.status, changes: appliedChanges },
    reason: reason ?? '编辑配置草稿',
  });

  return updated;
}

async function applyStatusCommand(
  configId: string,
  nextStatus: string,
  actorId: string,
  action: string,
  expectedVersion?: number,
) {
  const config = await requireConfig(configId, expectedVersion);
  checkTransition(config.status, nextStatus);

  const update: Record<string, unknown> = {
    status: nextStatus,
    updatedBy: actorId,
    version: (config.version ?? 0) + 1,
  };
  if (nextStatus === 'PUBLISHED') {
    update.publishedBy = actorId;
    update.publishedAt = new Date();
  }

  const updated = await ConfigVersion.findByIdAndUpdate(config._id, update, { new: true }).lean();

  await writeBusinessAudit({
    actorId,
    action,
    objectType: 'DO-015',
    objectId: configId,
    before: { status: config.status, version: config.version },
    after: { status: nextStatus, version: update.version },
    reason: `配置状态流转至 ${nextStatus}`,
  });

  // 配置发布后，审计保留期随之生效
  if (nextStatus === 'PUBLISHED' && typeof updated?.auditRetentionDays === 'number') {
    await applyAuditRetention(updated.auditRetentionDays).catch((err: unknown) => {
      logger.warn({ err }, '审计保留期更新失败');
    });
  }

  return updated;
}

export async function submitConfig(configId: string, actorId: string, expectedVersion?: number) {
  return applyStatusCommand(configId, 'SUBMITTED', actorId, 'config:submit', expectedVersion);
}

export async function approveConfig(configId: string, actorId: string, expectedVersion?: number) {
  return applyStatusCommand(configId, 'APPROVED', actorId, 'config:approve', expectedVersion);
}

export async function publishConfig(configId: string, actorId: string, expectedVersion?: number) {
  return applyStatusCommand(configId, 'PUBLISHED', actorId, 'config:publish', expectedVersion);
}

export async function rollbackConfig(configId: string, actorId: string, expectedVersion?: number) {
  return applyStatusCommand(configId, 'ROLLED_BACK', actorId, 'config:rollback', expectedVersion);
}
