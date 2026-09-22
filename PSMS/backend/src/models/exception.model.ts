import mongoose from 'mongoose';

/**
 * 生产异常（对应前端契约 DO-009 的后端视角）。
 *
 * `evidence` 采用内嵌数组：证据条目体量小、且总是随异常一起读写。
 */
export interface ExceptionEvidence {
  type?: string;
  url?: string;
  description?: string;
}

export interface ExceptionDoc {
  _id: string;
  exceptionId: string;
  type: string;
  severity: string;
  sourceId: string;
  sourceType: string;
  title: string;
  description: string;
  equipmentId?: string | null;
  workArea: string;
  status: string;
  assignedTo?: string | null;
  acknowledgedBy?: string | null;
  acknowledgedAt?: Date | null;
  resolvedBy?: string | null;
  resolvedAt?: Date | null;
  closedBy?: string | null;
  closedAt?: Date | null;
  resolution?: string | null;
  rootCause?: string | null;
  evidence?: ExceptionEvidence[];
  createdAt?: Date;
  updatedAt?: Date;
}

const exceptionSchema = new mongoose.Schema<ExceptionDoc>(
  {
    _id: { type: String, required: true },
    exceptionId: { type: String, required: true, unique: true },
    type: {
      type: String,
      required: true,
      enum: ['FLOW', 'SAFETY', 'EQUIPMENT', 'INTERFACE', 'DATA'],
    },
    severity: {
      type: String,
      required: true,
      enum: ['CRITICAL', 'MAJOR', 'MINOR', 'INFO'],
      default: 'MAJOR',
    },
    sourceId: { type: String, required: true },
    sourceType: {
      type: String,
      required: true,
      enum: ['plan', 'workOrder', 'task', 'equipment', 'interlock'],
    },
    title: { type: String, required: true },
    description: { type: String, default: '' },
    equipmentId: { type: String, default: null },
    workArea: { type: String, required: true },
    status: {
      type: String,
      required: true,
      enum: ['OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'DISMISSED'],
      default: 'OPEN',
    },
    assignedTo: { type: String, default: null },
    acknowledgedBy: { type: String, default: null },
    acknowledgedAt: { type: Date, default: null },
    resolvedBy: { type: String, default: null },
    resolvedAt: { type: Date, default: null },
    closedBy: { type: String, default: null },
    closedAt: { type: Date, default: null },
    resolution: { type: String, default: null },
    rootCause: { type: String, default: null },
    evidence: {
      type: [
        {
          type: { type: String },
          url: { type: String },
          description: { type: String },
        },
      ],
      default: [],
    },
  },
  { timestamps: true, collection: 'exceptions', versionKey: false },
);

// 异常台账：按作业区 + 状态；以及按类型 + 等级做聚合分析
exceptionSchema.index({ workArea: 1, status: 1 });
exceptionSchema.index({ type: 1, severity: 1 });
exceptionSchema.index({ status: 1, createdAt: -1 });

export const Exception = mongoose.model<ExceptionDoc>('Exception', exceptionSchema);
