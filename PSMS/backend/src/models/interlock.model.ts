import mongoose from 'mongoose';

/**
 * 安全联锁。
 *
 * `inputSignals` 内嵌：联锁的输入信号集合小、随联锁整体读写；
 * 覆盖（override）相关字段平铺在主文档上，便于按状态与有效期查询。
 */
export interface InterlockInputSignal {
  equipmentId?: string;
  pointCode?: string;
  expectedValue?: string;
  actualValue?: string;
}

export interface InterlockDoc {
  _id: string;
  interlockId: string;
  name: string;
  type: string;
  category: string;
  sourceId: string;
  equipmentId?: string | null;
  workArea: string;
  rule: string;
  description: string;
  inputSignals?: InterlockInputSignal[];
  status: string;
  triggeredAt?: Date | null;
  triggeredBy?: string | null;
  triggerReason?: string | null;
  overrideRequestedBy?: string | null;
  overrideApprovedBy?: string | null;
  overrideReason?: string | null;
  overrideExpiresAt?: Date | null;
  resetBy?: string | null;
  resetAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const interlockSchema = new mongoose.Schema<InterlockDoc>(
  {
    _id: { type: String, required: true },
    interlockId: { type: String, required: true, unique: true },
    name: { type: String, required: true },
    type: { type: String, required: true, enum: ['HARD', 'SOFT', 'PROCEDURAL'] },
    category: { type: String, required: true, enum: ['ACCESS', 'EQUIPMENT', 'AREA', 'ENVIRONMENT'] },
    sourceId: { type: String, required: true },
    equipmentId: { type: String, default: null },
    workArea: { type: String, required: true },
    rule: { type: String, required: true },
    description: { type: String, default: '' },
    inputSignals: {
      type: [
        {
          equipmentId: { type: String },
          pointCode: { type: String },
          expectedValue: { type: String },
          actualValue: { type: String },
        },
      ],
      default: [],
    },
    status: {
      type: String,
      required: true,
      enum: ['ARMED', 'TRIGGERED', 'OVERRIDDEN', 'RESET', 'DISABLED'],
      default: 'ARMED',
    },
    triggeredAt: { type: Date, default: null },
    triggeredBy: { type: String, default: null },
    triggerReason: { type: String, default: null },
    overrideRequestedBy: { type: String, default: null },
    overrideApprovedBy: { type: String, default: null },
    overrideReason: { type: String, default: null },
    overrideExpiresAt: { type: Date, default: null },
    resetBy: { type: String, default: null },
    resetAt: { type: Date, default: null },
  },
  { timestamps: true, collection: 'interlocks', versionKey: false },
);

interlockSchema.index({ workArea: 1, status: 1 });
interlockSchema.index({ status: 1, triggeredAt: -1 });
// 覆盖有效期到期后的处理由应用层判定（见 interlock.service.ts 的 isOverrideActive）。
// 这里不能对 overrideExpiresAt 建 TTL 索引：TTL 会删除**整个文档**，
// 而业务上只需要让"覆盖"失效，联锁记录本身必须保留用于追溯。
interlockSchema.index({ overrideExpiresAt: 1 }, { sparse: true });

export const Interlock = mongoose.model<InterlockDoc>('Interlock', interlockSchema);
