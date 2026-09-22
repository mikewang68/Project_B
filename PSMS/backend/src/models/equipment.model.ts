import mongoose from 'mongoose';

/**
 * 设备台账与设备遥测。
 *
 * TelemetryPoint 使用 **MongoDB 时序集合（time series collection）**：
 * 以 sourceTimestamp 为 timeField、equipmentId 为 metaField，
 * 由 MongoDB 自动做按时间分桶压缩存储，适合高频测点写入。
 *
 * 注意：时序集合的二级索引只能建立在 timeField / metaField 及其组合上，
 * 因此这里使用 `{ equipmentId: 1, sourceTimestamp: -1 }`，
 * 不能加入 pointCode 这类普通测量字段（否则建索引会报错）。
 */

export interface EquipmentDoc {
  _id: string;
  equipmentId: string;
  name: string;
  type: string;
  model: string;
  specs?: Record<string, unknown> | null;
  workArea: string;
  status: string;
  lastHeartbeat?: Date | null;
  lastTelemetryAt?: Date | null;
  networkZone?: string | null;
  protocol?: string | null;
  ipAddress?: string | null;
  port?: number | null;
  createdAt?: Date;
  updatedAt?: Date;
}

const equipmentSchema = new mongoose.Schema<EquipmentDoc>(
  {
    _id: { type: String, required: true },
    equipmentId: { type: String, required: true, unique: true },
    name: { type: String, required: true },
    type: {
      type: String,
      required: true,
      enum: [
        'GANTRY_CRANE',
        'BRIDGE_CRANE',
        'CONTAINER_CRANE',
        'TRUCK_SCALE',
        'SILO',
        'CONTAINER_TILTER',
        'UNPACKING_STATION',
        'DUST_COLLECTOR',
        'ELEVATOR',
        'AIR_SLIDE',
        'CENTRIFUGAL_FAN',
        'UNLOADING_EQUIPMENT',
        'AIR_COMPRESSOR',
        'MAINTENANCE_EQUIPMENT',
        'ECS',
        'RAIL_SCALE',
        'ACCESS_CONTROL',
        'UWB_LOCATION',
        'OTHER',
      ],
    },
    model: { type: String, default: '' },
    specs: { type: mongoose.Schema.Types.Mixed, default: null },
    workArea: { type: String, required: true },
    status: {
      type: String,
      enum: ['ONLINE', 'OFFLINE', 'MAINTENANCE', 'FAULT'],
      default: 'OFFLINE',
    },
    lastHeartbeat: { type: Date, default: null },
    lastTelemetryAt: { type: Date, default: null },
    networkZone: { type: String, default: null },
    protocol: { type: String, default: null },
    ipAddress: { type: String, default: null },
    port: { type: Number, default: null },
  },
  { timestamps: true, collection: 'equipments', versionKey: false },
);

equipmentSchema.index({ workArea: 1, status: 1 });
equipmentSchema.index({ type: 1, status: 1 });

export const Equipment = mongoose.model<EquipmentDoc>('Equipment', equipmentSchema);

/** 遥测点（时序集合，_id 由 MongoDB 自动生成，不能自定义） */
export interface TelemetryPointDoc {
  equipmentId: string;
  pointCode: string;
  value: number;
  quality: string;
  sourceTimestamp: Date;
  unit?: string | null;
  metadata?: Record<string, unknown> | null;
}

const telemetrySchema = new mongoose.Schema<TelemetryPointDoc>(
  {
    equipmentId: { type: String, required: true },
    pointCode: { type: String, required: true },
    value: { type: Number, required: true },
    quality: {
      type: String,
      required: true,
      enum: ['GOOD', 'UNCERTAIN', 'BAD', 'OFFLINE', 'STALE', 'LATE', 'BACKFILLED'],
      default: 'GOOD',
    },
    sourceTimestamp: { type: Date, required: true },
    unit: { type: String, default: null },
    metadata: { type: mongoose.Schema.Types.Mixed, default: null },
  },
  {
    timestamps: true,
    timeseries: {
      timeField: 'sourceTimestamp',
      metaField: 'equipmentId',
      granularity: 'seconds',
    },
    collection: 'equipment_telemetry',
    versionKey: false,
  },
);

// 时序集合只允许建在 metaField + timeField 组合上的索引
telemetrySchema.index({ equipmentId: 1, sourceTimestamp: -1 });

export const TelemetryPoint = mongoose.model<TelemetryPointDoc>(
  'TelemetryPoint',
  telemetrySchema,
);
