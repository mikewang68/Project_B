import mongoose from 'mongoose';
import { logger } from '../lib/logger.js';

export { User } from './user.model.js';
export { Plan } from './plan.model.js';
export { WorkOrder } from './workOrder.model.js';
export { Task } from './task.model.js';
export { Equipment, TelemetryPoint } from './equipment.model.js';
export { Exception } from './exception.model.js';
export { Interlock } from './interlock.model.js';
export { Appointment } from './appointment.model.js';
export { OfflinePacket } from './offlinePacket.model.js';
export { ConfigVersion } from './config.model.js';
export { AuditLog, applyAuditRetention, AUDIT_TTL_INDEX } from './auditLog.model.js';

// ---- 文档类型再导出，便于 service / route 层引用 ----
export type { UserDoc } from './user.model.js';
export type { PlanDoc, CargoItem, SupplierInfo } from './plan.model.js';
export type { WorkOrderDoc, ExecutionFeedback } from './workOrder.model.js';
export type { TaskDoc } from './task.model.js';
export type { EquipmentDoc, TelemetryPointDoc } from './equipment.model.js';
export type { ExceptionDoc, ExceptionEvidence } from './exception.model.js';
export type { InterlockDoc, InterlockInputSignal } from './interlock.model.js';
export type { AppointmentDoc, AppointmentDocument } from './appointment.model.js';
export type { OfflinePacketDoc } from './offlinePacket.model.js';
export type { ConfigVersionDoc, ConfigChange } from './config.model.js';
export type { AuditLogDoc } from './auditLog.model.js';

import { User } from './user.model.js';
import { Plan } from './plan.model.js';
import { WorkOrder } from './workOrder.model.js';
import { Task } from './task.model.js';
import { Equipment, TelemetryPoint } from './equipment.model.js';
import { Exception } from './exception.model.js';
import { Interlock } from './interlock.model.js';
import { Appointment } from './appointment.model.js';
import { OfflinePacket } from './offlinePacket.model.js';
import { ConfigVersion } from './config.model.js';
import { AuditLog } from './auditLog.model.js';

/** 时序遥测集合的物理名 */
export const TELEMETRY_COLLECTION = 'equipment_telemetry';

/** 全部常规（非时序）模型 */
export const allModels = [
  User,
  Plan,
  WorkOrder,
  Task,
  Equipment,
  Exception,
  Interlock,
  Appointment,
  OfflinePacket,
  ConfigVersion,
  AuditLog,
] as const;

export const telemetryModel = TelemetryPoint;

/**
 * 确保时序集合存在。
 *
 * 时序集合无法依赖"首次写入自动建集合"（那样只会建成普通集合），
 * 也不能交给 mongoose 的 createIndexes —— 时序集合在 system.views 中
 * 以视图形式登记，mongoose 的 ensureIndexes 会因重复登记命名空间而报
 * NamespaceExists(48)。因此这里用底层 driver 显式创建。
 */
export async function ensureTelemetryCollection(): Promise<void> {
  const db = mongoose.connection.db;
  if (!db) throw new Error('MongoDB 尚未连接，无法创建时序集合');

  const existing = await db.listCollections({ name: TELEMETRY_COLLECTION }).hasNext();
  if (!existing) {
    await db.createCollection(TELEMETRY_COLLECTION, {
      timeseries: {
        timeField: 'sourceTimestamp',
        metaField: 'equipmentId',
        granularity: 'seconds',
      },
    });
    logger.info({ collection: TELEMETRY_COLLECTION }, '已创建时序集合');
  }

  // 时序集合只允许建在 metaField + timeField 组合上的二级索引
  const telemetryCollection = db.collection(TELEMETRY_COLLECTION);
  const desiredIndexName = 'equipmentId_1_sourceTimestamp_-1';

  await telemetryCollection
    .createIndex({ equipmentId: 1, sourceTimestamp: -1 })
    .catch((err: unknown) => {
      logger.warn({ err }, '时序集合索引创建失败');
    });

  // 自愈：清理该集合上不在预期内的其它二级索引，
  // 避免历次 schema 调整留下的冗余索引长期占据写入开销
  const existingIndexes = await telemetryCollection.indexes().catch(() => []);
  for (const idx of existingIndexes) {
    if (idx.name && idx.name !== '_id_' && idx.name !== desiredIndexName) {
      await telemetryCollection.dropIndex(idx.name).catch(() => undefined);
      logger.info({ index: idx.name }, '已清理时序集合的冗余索引');
    }
  }
}

/**
 * 同步全部集合与索引。
 *
 * 常规集合使用 createIndexes 而非 syncIndexes：syncIndexes 会删除
 * "不在 schema 中声明"的索引，而审计日志的 TTL 索引是按保留期动态
 * 管理的，必须避免被误删。
 *
 * 单个模型失败只记录告警，不阻断服务启动。
 */
export async function syncAllIndexes(): Promise<void> {
  const results = await Promise.allSettled(allModels.map((model) => model.createIndexes()));

  results.forEach((result, index) => {
    const name = allModels[index]?.modelName ?? `#${index}`;
    if (result.status === 'rejected') {
      logger.warn({ model: name, err: result.reason }, '索引创建失败');
    }
  });

  await ensureTelemetryCollection();

  logger.info({ count: allModels.length + 1 }, '集合索引同步完成');
}

/**
 * 删除全部业务集合。
 *
 * 仅供 seed 脚本与演示重置（POST /api/demo/reset）使用，
 * 会清空数据但保留数据库本身。
 *
 * @param withIndexes 是否在清空后重建集合与索引
 */
export async function dropAllCollections(withIndexes = true): Promise<string[]> {
  const db = mongoose.connection.db;
  if (!db) throw new Error('MongoDB 尚未连接，无法清空集合');

  const dropped: string[] = [];
  const collections = await db.listCollections().toArray();
  for (const info of collections) {
    if (info.name.startsWith('system.')) continue;
    await db.dropCollection(info.name).catch(() => undefined);
    dropped.push(info.name);
  }

  if (withIndexes) await syncAllIndexes();
  return dropped;
}
