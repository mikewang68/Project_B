import mongoose from 'mongoose';

/**
 * 任务拆解产物（工单的上游）。
 *
 * `dependsOn` 使用字符串数组表达任务间的前置依赖，
 * 相比内嵌文档更适合"多对多依赖 + 独立查询"的场景。
 */
export interface TaskDoc {
  _id: string;
  planId: string;
  workOrderId?: string | null;
  planBatchNo: string;
  taskNo: string;
  name: string;
  description: string;
  workArea: string;
  equipmentId?: string | null;
  assignedCrew?: string[];
  status: string;
  order: number;
  parentTaskId?: string | null;
  dependsOn?: string[];
  estimatedDuration?: number | null;
  actualDuration?: number | null;
  startedAt?: Date | null;
  completedAt?: Date | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const taskSchema = new mongoose.Schema<TaskDoc>(
  {
    _id: { type: String, required: true },
    planId: { type: String, required: true, index: true },
    workOrderId: { type: String, default: null, index: true },
    planBatchNo: { type: String, required: true },
    taskNo: { type: String, required: true },
    name: { type: String, required: true },
    description: { type: String, default: '' },
    workArea: { type: String, required: true },
    equipmentId: { type: String, default: null },
    assignedCrew: { type: [String], default: [] },
    status: {
      type: String,
      required: true,
      enum: ['PENDING', 'READY', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'],
      default: 'PENDING',
    },
    order: { type: Number, default: 1 },
    parentTaskId: { type: String, default: null },
    dependsOn: { type: [String], default: [] },
    estimatedDuration: { type: Number, default: null },
    actualDuration: { type: Number, default: null },
    startedAt: { type: Date, default: null },
    completedAt: { type: Date, default: null },
  },
  { timestamps: true, collection: 'tasks', versionKey: false },
);

// 任务树按计划 + 序号读取
taskSchema.index({ planId: 1, order: 1 });
taskSchema.index({ planId: 1, status: 1 });

export const Task = mongoose.model<TaskDoc>('Task', taskSchema);
