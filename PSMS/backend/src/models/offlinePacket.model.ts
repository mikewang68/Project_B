import mongoose from 'mongoose';

/**
 * PDA 离线数据包。
 *
 * `payload` 使用 Mixed：离线交账的业务载荷结构随终端版本演进，
 * 后端不做结构约束，仅做版本比对与冲突字段比对。
 */
export interface OfflinePacketDoc {
  _id: string;
  packetId: string;
  terminalId: string;
  operatorId: string;
  workArea: string;
  status: string;
  version: number;
  serverVersion?: number | null;
  payload: Record<string, unknown>;
  syncAttempts: number;
  lastSyncAt?: Date | null;
  conflictFields?: string[];
  resolution?: string | null;
  resolvedBy?: string | null;
  resolvedAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const offlinePacketSchema = new mongoose.Schema<OfflinePacketDoc>(
  {
    _id: { type: String, required: true },
    packetId: { type: String, required: true, unique: true },
    terminalId: { type: String, required: true },
    operatorId: { type: String, required: true },
    workArea: { type: String, required: true },
    status: {
      type: String,
      required: true,
      enum: ['DRAFT', 'SYNCED', 'CONFLICT', 'RESOLVED', 'FAILED'],
      default: 'DRAFT',
    },
    version: { type: Number, default: 1 },
    serverVersion: { type: Number, default: null },
    payload: { type: mongoose.Schema.Types.Mixed, required: true },
    syncAttempts: { type: Number, default: 0 },
    lastSyncAt: { type: Date, default: null },
    conflictFields: { type: [String], default: [] },
    resolution: {
      type: String,
      enum: ['ACCEPT_LOCAL', 'ACCEPT_SERVER', 'MANUAL_MERGE', 'DISCARD'],
      default: null,
    },
    resolvedBy: { type: String, default: null },
    resolvedAt: { type: Date, default: null },
  },
  { timestamps: true, collection: 'offline_packets', versionKey: false },
);

offlinePacketSchema.index({ terminalId: 1, status: 1 });
offlinePacketSchema.index({ status: 1, updatedAt: -1 });

export const OfflinePacket = mongoose.model<OfflinePacketDoc>(
  'OfflinePacket',
  offlinePacketSchema,
);
