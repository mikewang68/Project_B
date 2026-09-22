import mongoose from 'mongoose';
import { logger } from '../lib/logger.js';

/**
 * 审计日志。
 *
 * 该集合同时承载两类留痕，统一为「一个文档模型 + 一个集合」，
 * 避免同集合内两种文档形状混排导致的查询歧义：
 *
 *  A. 业务审计（对应前端契约 DO-013）
 *     id / actorId / operatorTerminal / action / objectType / objectId /
 *     before / after / reason / traceId / occurredAt
 *     由各写命令的服务层在提交后写入。
 *
 *  B. HTTP 访问留痕
 *     actorRole / resource / resourceId / statusCode / durationMs /
 *     ip / userAgent / requestBody / responseSummary
 *     由 middleware/audit.ts 在请求结束时写入。
 *
 * 两类字段均为可选，业务审计与访问留痕各写各的字段子集。
 */
export interface AuditLogDoc {
  _id: string;
  /** 业务审计主键（与 _id 同值，便于前端直接取 id） */
  id?: string;
  actorId: string;
  actorRole?: string | null;
  operatorTerminal?: string;
  action: string;
  objectType?: string;
  objectId?: string;
  before?: Record<string, unknown>;
  after?: Record<string, unknown>;
  reason?: string;
  traceId?: string;
  /** 业务事件发生时间（DO-013） */
  occurredAt?: Date | null;
  /** ---- HTTP 访问留痕字段 ---- */
  resource?: string;
  resourceId?: string | null;
  statusCode?: number | null;
  durationMs?: number | null;
  ip?: string | null;
  userAgent?: string | null;
  requestBody?: string | null;
  responseSummary?: string | null;
  createdAt: Date;
  updatedAt: Date;
}

const auditLogSchema = new mongoose.Schema<AuditLogDoc>(
  {
    _id: { type: String, required: true },
    id: { type: String },
    actorId: { type: String, required: true },
    actorRole: { type: String, default: null },
    operatorTerminal: { type: String, default: 'WEB-BACKEND' },
    action: { type: String, required: true },
    objectType: { type: String, default: '' },
    objectId: { type: String, default: '' },
    before: { type: mongoose.Schema.Types.Mixed, default: {} },
    after: { type: mongoose.Schema.Types.Mixed, default: {} },
    reason: { type: String, default: '' },
    traceId: { type: String, default: '' },
    occurredAt: { type: Date, default: null },
    resource: { type: String, default: null },
    resourceId: { type: String, default: null },
    statusCode: { type: Number, default: null },
    durationMs: { type: Number, default: null },
    ip: { type: String, default: null },
    userAgent: { type: String, default: null },
    requestBody: { type: String, default: null },
    responseSummary: { type: String, default: null },
  },
  {
    timestamps: { createdAt: true, updatedAt: false },
    collection: 'audit_logs',
    versionKey: false,
  },
);

// 业务审计访问模式
auditLogSchema.index({ occurredAt: -1 });
auditLogSchema.index({ actorId: 1, occurredAt: -1 });
auditLogSchema.index({ objectType: 1, objectId: 1, occurredAt: -1 });
auditLogSchema.index({ action: 1, occurredAt: -1 });
auditLogSchema.index({ traceId: 1 });
auditLogSchema.index({ actorId: 1, action: 1, objectType: 1, objectId: 1 });

export const AuditLog = mongoose.model<AuditLogDoc>('AuditLog', auditLogSchema);

/** 审计保留期 TTL 索引名 */
export const AUDIT_TTL_INDEX = 'audit_retention_ttl';

/**
 * 按保留天数配置审计日志的 TTL 索引。
 *
 * 对应前端契约 DO-015 的 `auditRetentionDays`（1~3650 天）：
 * 索引不存在则创建；已存在但保留期变化则通过 collMod 原地修改
 * （TTL 索引的 expireAfterSeconds 无法直接 createIndex 覆盖）。
 *
 * @returns 实际生效的保留秒数
 */
export async function applyAuditRetention(retentionDays: number): Promise<number> {
  const days = Math.min(3650, Math.max(1, Math.floor(retentionDays)));
  const seconds = days * 86_400;
  const collection = AuditLog.collection;

  const indexes = await collection.indexes().catch(() => [] as { name?: string; expireAfterSeconds?: number }[]);
  const existing = indexes.find((idx) => idx.name === AUDIT_TTL_INDEX);

  if (!existing) {
    await collection.createIndex(
      { createdAt: 1 },
      { name: AUDIT_TTL_INDEX, expireAfterSeconds: seconds },
    );
    logger.info({ retentionDays: days }, '已创建审计日志 TTL 索引');
    return seconds;
  }

  if ((existing.expireAfterSeconds ?? -1) !== seconds) {
    await collection.db.command({
      collMod: collection.collectionName,
      index: { name: AUDIT_TTL_INDEX, expireAfterSeconds: seconds },
    });
    logger.info({ retentionDays: days }, '已更新审计日志保留期');
  }
  return seconds;
}
