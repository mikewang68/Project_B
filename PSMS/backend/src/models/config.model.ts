import mongoose from 'mongoose';

/**
 * 系统配置版本（对应前端契约 DO-015 ConfigVersion）。
 *
 * `changeHistory` 内嵌：每次 edit 追加一条，数据量可控且始终随配置整体读取。
 * `version` 是业务乐观锁版本（不是 mongoose 的 __v，此处已关闭 versionKey）。
 */
export interface ConfigChange {
  field?: string;
  oldValue?: unknown;
  newValue?: unknown;
  changedBy?: string;
  changedAt?: Date;
}

export interface ConfigVersionDoc {
  _id: string;
  configId: string;
  configVersion: string;
  displayName: string;
  defaultScenarioId: string;
  ruleVersion: string;
  dispatchStrategy: string;
  recommendationEnabled: boolean;
  offlineSyncEnabled: boolean;
  reportPeriod: string;
  auditRetentionDays: number;
  status: string;
  /** 业务乐观锁版本，每次写命令 +1 */
  version: number;
  scenarioId: string;
  publishedAt?: Date | null;
  publishedBy?: string | null;
  createdBy: string;
  updatedBy?: string | null;
  changeHistory?: ConfigChange[];
  createdAt?: Date;
  updatedAt?: Date;
}

const configSchema = new mongoose.Schema<ConfigVersionDoc>(
  {
    _id: { type: String, required: true },
    configId: { type: String, required: true, unique: true },
    configVersion: { type: String, required: true, default: 'CFG-1.0' },
    displayName: { type: String, default: 'B项目生产调度管理系统' },
    defaultScenarioId: { type: String, default: 'SCN-01' },
    ruleVersion: { type: String, default: 'RULE-1.0' },
    dispatchStrategy: {
      type: String,
      enum: ['BALANCED', 'PRIORITY_FIRST', 'RESOURCE_FIRST', 'OPTIMAL'],
      default: 'BALANCED',
    },
    recommendationEnabled: { type: Boolean, default: true },
    offlineSyncEnabled: { type: Boolean, default: true },
    reportPeriod: {
      type: String,
      enum: ['SHIFT', 'DAILY', 'MONTHLY'],
      default: 'DAILY',
    },
    auditRetentionDays: { type: Number, default: 365, min: 1, max: 3650 },
    status: {
      type: String,
      required: true,
      enum: ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED', 'ROLLED_BACK'],
      default: 'DRAFT',
    },
    version: { type: Number, required: true, default: 1 },
    scenarioId: { type: String, default: 'SCN-01' },
    publishedAt: { type: Date, default: null },
    publishedBy: { type: String, default: null },
    createdBy: { type: String, default: 'system' },
    updatedBy: { type: String, default: null },
    changeHistory: {
      type: [
        {
          field: { type: String },
          oldValue: { type: mongoose.Schema.Types.Mixed },
          newValue: { type: mongoose.Schema.Types.Mixed },
          changedBy: { type: String },
          changedAt: { type: Date },
        },
      ],
      default: [],
    },
  },
  { timestamps: true, collection: 'configs', versionKey: false },
);

configSchema.index({ status: 1 });

export const ConfigVersion = mongoose.model<ConfigVersionDoc>('ConfigVersion', configSchema);
