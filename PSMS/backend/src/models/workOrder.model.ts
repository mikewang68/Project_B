import mongoose from 'mongoose';

/**
 * 作业工单。
 *
 * 引用字段（planId / taskId）使用**字符串 ID**而非 ObjectId：
 * 模块的权威契约（前端 DO-005）以 `WO-001`、`PLAN-001` 这类语义化
 * 字符串作为标识，统一为字符串可直接对齐，且便于跨集合人工排查。
 */
export interface ExecutionFeedback {
  quality?: string;
  comment?: string;
  reportedBy?: string;
  reportedAt?: Date;
}

export interface WorkOrderDoc {
  _id: string;
  planId: string;
  planBatchNo: string;
  taskId?: string | null;
  workArea: string;
  equipmentId?: string | null;
  equipmentName?: string;
  assignedCrew?: string[];
  assignedOperator?: string | null;
  status: string;
  orderType: string;
  priority: string;
  description: string;
  instructions?: string | null;
  estimatedDuration?: number | null;
  actualStartTime?: Date | null;
  actualEndTime?: Date | null;
  acceptedBy?: string | null;
  acceptedAt?: Date | null;
  pauseReason?: string | null;
  cancelReason?: string | null;
  executionFeedback?: ExecutionFeedback | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const workOrderSchema = new mongoose.Schema<WorkOrderDoc>(
  {
    _id: { type: String, required: true },
    planId: { type: String, required: true, index: true },
    planBatchNo: { type: String, required: true },
    taskId: { type: String, default: null, index: true },
    workArea: { type: String, required: true },
    equipmentId: { type: String, default: null },
    equipmentName: { type: String, default: '' },
    assignedCrew: { type: [String], default: [] },
    assignedOperator: { type: String, default: null },
    status: {
      type: String,
      required: true,
      enum: [
        'DRAFT',
        'READY',
        'ASSIGNED',
        'ACCEPTED',
        'IN_PROGRESS',
        'PAUSED',
        'COMPLETED',
        'CANCELLED',
      ],
      default: 'DRAFT',
    },
    orderType: {
      type: String,
      enum: ['LOADING', 'UNLOADING', 'TRANSFER', 'MAINTENANCE', 'OTHER'],
      default: 'OTHER',
    },
    priority: { type: String, enum: ['HIGH', 'MEDIUM', 'LOW'], default: 'MEDIUM' },
    description: { type: String, default: '' },
    instructions: { type: String, default: null },
    estimatedDuration: { type: Number, default: null },
    actualStartTime: { type: Date, default: null },
    actualEndTime: { type: Date, default: null },
    acceptedBy: { type: String, default: null },
    acceptedAt: { type: Date, default: null },
    pauseReason: { type: String, default: null },
    cancelReason: { type: String, default: null },
    executionFeedback: {
      type: {
        quality: { type: String, enum: ['GOOD', 'FAIR', 'POOR'] },
        comment: { type: String },
        reportedBy: { type: String },
        reportedAt: { type: Date },
      },
      default: null,
    },
  },
  { timestamps: true, collection: 'work_orders', versionKey: false },
);

// 看板主查询：按作业区 + 状态；以及按设备查占用
workOrderSchema.index({ workArea: 1, status: 1 });
workOrderSchema.index({ equipmentId: 1, status: 1 });
workOrderSchema.index({ planId: 1, status: 1 });

export const WorkOrder = mongoose.model<WorkOrderDoc>('WorkOrder', workOrderSchema);
