// @ts-nocheck
import { jsondb } from '../config/db.js';
import { AppError } from '../middleware/errorHandler.js';

const EDITABLE_FIELDS = ['displayName', 'defaultScenarioId', 'ruleVersion', 'dispatchStrategy', 'recommendationEnabled', 'offlineSyncEnabled', 'reportPeriod', 'auditRetentionDays'];

const STATUS: Record<string, string[]> = {
  DRAFT: ['SUBMITTED'],
  SUBMITTED: ['APPROVED', 'DRAFT'],
  APPROVED: ['PUBLISHED', 'DRAFT'],
  PUBLISHED: ['ROLLED_BACK'],
  ROLLED_BACK: ['DRAFT'],
};

function checkTransition(current: string, next: string): void {
  const allowed = STATUS[current];
  if (!allowed || !allowed.includes(next)) {
    throw new AppError(409, 'INVALID_STATE', `不能从 ${current} 转移到 ${next}`);
  }
}

function checkVersion(config: Record<string, unknown>, expectedVersion?: number): void {
  if (expectedVersion !== undefined && expectedVersion !== null) {
    const currentVersion = (config.version as number) ?? 0;
    if (currentVersion !== expectedVersion) {
      throw new AppError(409, 'DEMO-VERSION-001', `配置版本已变化：期望 v${expectedVersion}，当前 v${currentVersion}`);
    }
  }
}

async function findConfig(configId: string, expectedVersion?: number): Promise<Record<string, unknown>> {
  let configs = await jsondb.find<Record<string, unknown>>('configs', { id: configId }).lean();
  if (configs.length === 0) {
    configs = await jsondb.find<Record<string, unknown>>('configs', { configId }).lean();
  }
  if (configs.length === 0) throw new AppError(404, 'CONFIG_NOT_FOUND', '配置不存在');
  checkVersion(configs[0], expectedVersion);
  return configs[0];
}

export async function getConfig(configId: string) {
  let configs = await jsondb.find<Record<string, unknown>>('configs', { id: configId }).lean();
  if (configs.length === 0) {
    configs = await jsondb.find<Record<string, unknown>>('configs', { configId }).lean();
  }
  if (configs.length === 0) throw new AppError(404, 'CONFIG_NOT_FOUND', '配置不存在');
  return configs[0];
}

export async function updateConfig(configId: string, changes: Record<string, unknown>, actorId: string, reason?: string, expectedVersion?: number) {
  const config = await findConfig(configId, expectedVersion);
  if (config.status !== 'DRAFT') throw new AppError(409, 'INVALID_STATE', '只能修改草稿状态的配置');

  const history = (Array.isArray(config.changeHistory) ? [...config.changeHistory as Record<string, unknown>[]] : []) as Record<string, unknown>[];
  const update: Record<string, unknown> = {};
  for (const key of EDITABLE_FIELDS) {
    if (key in changes) {
      history.push({ field: key, oldValue: config[key], newValue: changes[key], changedBy: actorId, changedAt: new Date().toISOString() });
      update[key] = changes[key];
    }
  }
  if (reason) history.push({ field: '_reason', oldValue: null, newValue: reason, changedBy: actorId, changedAt: new Date().toISOString() });
  update.changeHistory = history;
  update.updatedAt = new Date().toISOString();
  update.updatedBy = actorId;
  update.version = ((config.version as number) ?? 0) + 1;
  return jsondb.findByIdAndUpdate('configs', config._id as string, update);
}

export async function submitConfig(configId: string, actorId: string, expectedVersion?: number) {
  const config = await findConfig(configId, expectedVersion);
  checkTransition(config.status as string, 'SUBMITTED');
  return jsondb.findByIdAndUpdate('configs', config._id as string, {
    status: 'SUBMITTED',
    updatedAt: new Date().toISOString(),
    updatedBy: actorId,
    version: ((config.version as number) ?? 0) + 1,
  });
}

export async function approveConfig(configId: string, actorId: string, expectedVersion?: number) {
  const config = await findConfig(configId, expectedVersion);
  checkTransition(config.status as string, 'APPROVED');
  return jsondb.findByIdAndUpdate('configs', config._id as string, {
    status: 'APPROVED',
    updatedAt: new Date().toISOString(),
    updatedBy: actorId,
    version: ((config.version as number) ?? 0) + 1,
  });
}

export async function publishConfig(configId: string, actorId: string, expectedVersion?: number) {
  const config = await findConfig(configId, expectedVersion);
  checkTransition(config.status as string, 'PUBLISHED');
  return jsondb.findByIdAndUpdate('configs', config._id as string, {
    status: 'PUBLISHED',
    publishedBy: actorId,
    publishedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    updatedBy: actorId,
    version: ((config.version as number) ?? 0) + 1,
  });
}

export async function rollbackConfig(configId: string, actorId: string, expectedVersion?: number) {
  const config = await findConfig(configId, expectedVersion);
  checkTransition(config.status as string, 'ROLLED_BACK');
  return jsondb.findByIdAndUpdate('configs', config._id as string, {
    status: 'ROLLED_BACK',
    updatedAt: new Date().toISOString(),
    updatedBy: actorId,
    version: ((config.version as number) ?? 0) + 1,
  });
}
