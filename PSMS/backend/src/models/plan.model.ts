import mongoose from 'mongoose';

/**
 * 外部到发计划（对应前端契约 DO-001 Plan 的调度侧视图）。
 *
 * `cargoItems` 与 `supplierInfo` 采用**内嵌文档**：它们只随计划读写，
 * 不会被独立查询，内嵌可避免额外集合与 $lookup。
 */
export interface CargoItem {
  itemNo: number;
  name: string;
  quantity: number;
  unit: string;
  weight: number;
  remarks?: string;
}

export interface SupplierInfo {
  name?: string;
  contactPerson?: string;
  contactPhone?: string;
}

export interface PlanDoc {
  _id: string;
  planBatchNo: string;
  trainNo: string;
  cargoType: string;
  cargoDescription: string;
  estimatedWeight: number;
  weightUnit: string;
  sourceStation: string;
  destinationStation: string;
  arriveTime: Date;
  trackNo: string;
  workArea: string;
  status: string;
  priority: string;
  confirmedBy?: string | null;
  confirmedAt?: Date | null;
  supplierInfo?: SupplierInfo;
  cargoItems?: CargoItem[];
  supplements?: Record<string, unknown> | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const planSchema = new mongoose.Schema<PlanDoc>(
  {
    _id: { type: String, required: true },
    planBatchNo: { type: String, required: true, unique: true },
    trainNo: { type: String, required: true },
    cargoType: { type: String, required: true },
    cargoDescription: { type: String, default: '' },
    estimatedWeight: { type: Number, default: 0 },
    weightUnit: { type: String, default: 'ton' },
    sourceStation: { type: String, required: true },
    destinationStation: { type: String, default: '' },
    arriveTime: { type: Date, required: true },
    trackNo: { type: String, default: '' },
    workArea: { type: String, required: true },
    status: {
      type: String,
      required: true,
      enum: ['PENDING_CONFIRM', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'],
      default: 'PENDING_CONFIRM',
    },
    priority: { type: String, enum: ['HIGH', 'MEDIUM', 'LOW'], default: 'MEDIUM' },
    confirmedBy: { type: String, default: null },
    confirmedAt: { type: Date, default: null },
    supplierInfo: {
      name: { type: String },
      contactPerson: { type: String },
      contactPhone: { type: String },
    },
    cargoItems: {
      type: [
        {
          itemNo: { type: Number },
          name: { type: String },
          quantity: { type: Number },
          unit: { type: String },
          weight: { type: Number },
          remarks: { type: String },
        },
      ],
      default: [],
    },
    supplements: { type: mongoose.Schema.Types.Mixed, default: null },
  },
  { timestamps: true, collection: 'plans', versionKey: false },
);

// 台账默认视图：按作业区 + 状态过滤，按到达时间倒序
planSchema.index({ workArea: 1, status: 1 });
planSchema.index({ arriveTime: -1 });
planSchema.index({ status: 1, updatedAt: -1 });
planSchema.index({ trainNo: 1 });

export const Plan = mongoose.model<PlanDoc>('Plan', planSchema);
