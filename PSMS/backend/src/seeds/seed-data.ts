import bcrypt from 'bcryptjs';
import {
  Appointment,
  AuditLog,
  ConfigVersion,
  Equipment,
  Exception,
  Interlock,
  OfflinePacket,
  Plan,
  Task,
  TelemetryPoint,
  User,
  WorkOrder,
  applyAuditRetention,
  dropAllCollections,
  syncAllIndexes,
} from '../models/index.js';
import { logger } from '../lib/logger.js';

/**
 * 固定种子数据。
 *
 * 与前端契约的固定规模保持一致：15 类领域对象、13 个角色、7 个演示场景、
 * 9 个公开错误码。这里的 _id 一律使用语义化字符串，与前端 DO-xxx 的
 * 标识形式对齐，便于两端直接对账。
 */

export const DEMO_PASSWORD = 'password123';

const users = [
  { _id: 'ACTOR-ADMIN', actorId: 'ACTOR-ADMIN', username: 'admin', displayName: '系统管理员', roleCode: 'super_admin', dataScope: ['*'] },
  { _id: 'ACTOR-SCHEDULER', actorId: 'ACTOR-SCHEDULER', username: 'scheduler', displayName: '调度员张三', roleCode: 'scheduler', dataScope: ['AREA-A', 'AREA-B'] },
  { _id: 'ACTOR-DISPATCHER', actorId: 'ACTOR-DISPATCHER', username: 'dispatcher', displayName: '派工员李四', roleCode: 'dispatcher', dataScope: ['AREA-A'] },
  { _id: 'ACTOR-OPERATOR', actorId: 'ACTOR-OPERATOR', username: 'operator', displayName: '操作员王五', roleCode: 'operator', dataScope: ['AREA-A'] },
  { _id: 'ACTOR-VIEWER', actorId: 'ACTOR-VIEWER', username: 'viewer', displayName: '浏览员赵六', roleCode: 'viewer', dataScope: ['AREA-A', 'AREA-B'] },
];

const equipments = [
  { equipmentId: 'EQ-IMG-01', name: '智能化门式起重机 Q=40T S=30m（磁吸式C型吊具）', type: 'GANTRY_CRANE', model: 'MG40-30', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-02', name: '智能化双梁桥式起重机 Q=20T S=28.5m（磁吸式吊具）', type: 'BRIDGE_CRANE', model: 'QD20-28.5', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-03', name: '智能化双梁桥式起重机 Q=32T S=28.5m（C型吊具）', type: 'BRIDGE_CRANE', model: 'QD32-28.5', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-04', name: '梁桥式起重机 Q=50t S=16.5m', type: 'GANTRY_CRANE', model: 'LD50-16.5', workArea: 'AREA-B', status: 'ONLINE', protocol: 'Modbus TCP' },
  { equipmentId: 'EQ-IMG-05', name: '智能化汽车衡', type: 'TRUCK_SCALE', model: 'SCS-100', workArea: 'AREA-A', status: 'ONLINE', protocol: 'Modbus TCP' },
  { equipmentId: 'EQ-IMG-06', name: '粉煤灰（水泥）3000t筒仓', type: 'SILO', model: 'SN-3000', workArea: 'AREA-B', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-07', name: '智能化集装箱门式起重机 Q=40.5T S=30m', type: 'CONTAINER_CRANE', model: 'RMG40.5-30', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-08', name: '移动式翻箱机', type: 'CONTAINER_TILTER', model: 'MT-50', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-09', name: '拆箱房设备', type: 'UNPACKING_STATION', model: 'US-01', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-IMG-10', name: '除尘器', type: 'DUST_COLLECTOR', model: 'DC-200', workArea: 'AREA-B', status: 'ONLINE', protocol: 'Modbus TCP' },
  { equipmentId: 'EQ-IMG-11', name: '提升机', type: 'ELEVATOR', model: 'EL-500', workArea: 'AREA-B', status: 'ONLINE', protocol: 'Modbus TCP' },
  { equipmentId: 'EQ-IMG-13', name: '高压离心风机', type: 'CENTRIFUGAL_FAN', model: 'CF-1000', workArea: 'AREA-B', status: 'ONLINE', protocol: 'Modbus TCP' },
  { equipmentId: 'EQ-IMG-17', name: '智能化装卸管控系统/ECS', type: 'ECS', model: 'ECS-v2', workArea: 'AREA-A', status: 'ONLINE', protocol: 'REST/MQTT' },
  { equipmentId: 'EQ-EXT-01', name: '轨道衡', type: 'RAIL_SCALE', model: 'RS-200', workArea: 'AREA-A', status: 'ONLINE', protocol: 'OPC UA' },
  { equipmentId: 'EQ-EXT-02', name: '进出车辆识别/道闸及场内定位系统', type: 'ACCESS_CONTROL', model: 'AC-01', workArea: 'AREA-A', status: 'ONLINE', protocol: 'REST' },
];

const plans = [
  { _id: 'PLAN-001', planBatchNo: 'PB-2026-001', trainNo: 'T88001', cargoType: 'COAL', cargoDescription: '动力煤 5000kcal', estimatedWeight: 3500, weightUnit: 'ton', sourceStation: '大同站', destinationStation: 'B货场', arriveTime: new Date('2026-08-27T08:00:00.000Z'), trackNo: 'G1', workArea: 'AREA-A', status: 'PENDING_CONFIRM', priority: 'HIGH', supplierInfo: { name: '山西煤炭运销集团' } },
  { _id: 'PLAN-002', planBatchNo: 'PB-2026-002', trainNo: 'T88002', cargoType: 'IRON_ORE', cargoDescription: '铁矿石粉 62%Fe', estimatedWeight: 4200, weightUnit: 'ton', sourceStation: '日照港站', destinationStation: 'B货场', arriveTime: new Date('2026-08-27T10:30:00.000Z'), trackNo: 'G2', workArea: 'AREA-A', status: 'CONFIRMED', priority: 'HIGH', supplierInfo: { name: '日照钢铁原料公司' } },
  { _id: 'PLAN-003', planBatchNo: 'PB-2026-003', trainNo: 'T88003', cargoType: 'CEMENT', cargoDescription: '普通硅酸盐水泥 P.O42.5', estimatedWeight: 2800, weightUnit: 'ton', sourceStation: '唐山站', destinationStation: 'B货场', arriveTime: new Date('2026-08-27T14:00:00.000Z'), trackNo: 'G3', workArea: 'AREA-B', status: 'PENDING_CONFIRM', priority: 'MEDIUM', supplierInfo: { name: '唐山冀东水泥' } },
  { _id: 'PLAN-004', planBatchNo: 'PB-2026-004', trainNo: 'T88004', cargoType: 'STEEL', cargoDescription: '热轧卷板 Q235B 5.5mm', estimatedWeight: 1800, weightUnit: 'ton', sourceStation: '邯郸站', destinationStation: 'B货场', arriveTime: new Date('2026-08-28T09:00:00.000Z'), trackNo: 'G4', workArea: 'AREA-A', status: 'PENDING_CONFIRM', priority: 'MEDIUM', supplierInfo: { name: '邯郸钢铁集团' } },
  { _id: 'PLAN-005', planBatchNo: 'PB-2026-005', trainNo: 'T88005', cargoType: 'CONTAINER', cargoDescription: '40英尺标准集装箱 电子产品', estimatedWeight: 1200, weightUnit: 'ton', sourceStation: '郑州站', destinationStation: 'B货场', arriveTime: new Date('2026-08-28T16:00:00.000Z'), trackNo: 'G5', workArea: 'AREA-A', status: 'CONFIRMED', priority: 'HIGH', supplierInfo: { name: '富士康郑州' } },
];

const workOrders = [
  { _id: 'WO-001', planId: 'PLAN-002', planBatchNo: 'PB-2026-002', workArea: 'AREA-A', equipmentId: 'EQ-IMG-01', equipmentName: '智能化门式起重机 Q=40T S=30m', assignedCrew: ['CREW-A'], status: 'ASSIGNED', orderType: 'UNLOADING', priority: 'HIGH', description: '铁矿石卸车作业 - G2股道' },
  { _id: 'WO-002', planId: 'PLAN-002', planBatchNo: 'PB-2026-002', workArea: 'AREA-A', equipmentId: 'EQ-IMG-05', equipmentName: '智能化汽车衡', assignedCrew: ['CREW-B'], status: 'READY', orderType: 'TRANSFER', priority: 'MEDIUM', description: '铁矿石转运称重' },
  { _id: 'WO-003', planId: 'PLAN-001', planBatchNo: 'PB-2026-001', workArea: 'AREA-A', equipmentId: 'EQ-IMG-07', equipmentName: '智能化集装箱门式起重机', status: 'DRAFT', orderType: 'UNLOADING', priority: 'HIGH', description: '动力煤卸车 - G1股道' },
];

const tasks = [
  { _id: 'PLAN-002-TASK-001', planId: 'PLAN-002', workOrderId: 'WO-001', planBatchNo: 'PB-2026-002', taskNo: 'TASK-001', name: '铁矿石卸车', description: '使用门式起重机卸车至堆场A', workArea: 'AREA-A', equipmentId: 'EQ-IMG-01', status: 'READY', order: 1 },
  { _id: 'PLAN-002-TASK-002', planId: 'PLAN-002', workOrderId: 'WO-002', planBatchNo: 'PB-2026-002', taskNo: 'TASK-002', name: '铁矿石转运', description: '转运至筒仓区域', workArea: 'AREA-A', equipmentId: 'EQ-IMG-05', status: 'PENDING', order: 2, dependsOn: ['PLAN-002-TASK-001'] },
];

const exceptions = [
  { exceptionId: 'EX-001', type: 'EQUIPMENT', severity: 'MAJOR', sourceId: 'EQ-IMG-04', sourceType: 'equipment', title: '梁桥式起重机通讯中断', description: '设备心跳超过30秒无响应', equipmentId: 'EQ-IMG-04', workArea: 'AREA-B', status: 'OPEN' },
  { exceptionId: 'EX-002', type: 'FLOW', severity: 'MINOR', sourceId: 'PLAN-001', sourceType: 'plan', title: '计划超时未确认', description: '计划PB-2026-001到达后2小时仍未确认', workArea: 'AREA-A', status: 'OPEN' },
];

const interlocks = [
  { interlockId: 'IL-001', name: 'G1股道门禁联锁', type: 'HARD', category: 'ACCESS', sourceId: 'G1', equipmentId: 'EQ-IMG-01', workArea: 'AREA-A', rule: '起重机作业时禁止人员进入G1区域', description: '防止起重机作业时人员进入危险区域', inputSignals: [{ equipmentId: 'EQ-IMG-01', pointCode: 'STATUS', expectedValue: 'RUNNING', actualValue: 'STANDBY' }], status: 'ARMED' },
  { interlockId: 'IL-002', name: '筒仓料位联锁', type: 'SOFT', category: 'EQUIPMENT', sourceId: 'EQ-IMG-06', equipmentId: 'EQ-IMG-06', workArea: 'AREA-B', rule: '料位>90%时停止进料', description: '防止筒仓溢出', inputSignals: [{ equipmentId: 'EQ-IMG-06', pointCode: 'LEVEL', expectedValue: 'NORMAL', actualValue: 'NORMAL' }], status: 'ARMED' },
];

const appointments = [
  { appointmentId: 'APT-001', vehiclePlate: '鲁A12345', vehicleType: 'HEAVY_TRUCK', driverName: '刘大', driverPhone: '13800001001', driverIdCard: '370100199001011234', company: '山东物流有限公司', cargoType: 'STEEL', estimatedWeight: 35, plannedArriveTime: new Date('2026-08-27T09:00:00.000Z'), status: 'APPROVED' },
  { appointmentId: 'APT-002', vehiclePlate: '鲁B67890', vehicleType: 'HEAVY_TRUCK', driverName: '陈二', driverPhone: '13800001002', driverIdCard: '370200199002021234', company: '青岛运输公司', cargoType: 'CEMENT', estimatedWeight: 40, plannedArriveTime: new Date('2026-08-27T10:00:00.000Z'), status: 'PENDING' },
  { appointmentId: 'APT-003', vehiclePlate: '鲁C11223', vehicleType: 'HEAVY_TRUCK', driverName: '张三', driverPhone: '13800001003', driverIdCard: '370300199003031234', company: '淄博货运有限公司', cargoType: 'COAL', estimatedWeight: 38, plannedArriveTime: new Date('2026-08-27T11:00:00.000Z'), status: 'QUEUED', queueNumber: 1 },
];

const offlinePackets = [
  { packetId: 'OFF-PKG-001', terminalId: 'PDA-01', operatorId: 'ACTOR-OPERATOR', workArea: 'AREA-A', status: 'DRAFT', version: 1, serverVersion: null, payload: { workOrderNo: 'WO-001', nodes: [] }, syncAttempts: 0 },
];

const configDoc = {
  _id: 'CFG-001',
  configId: 'CFG-001',
  configVersion: 'CFG-1.0',
  displayName: 'B项目生产调度管理系统',
  defaultScenarioId: 'SCN-01',
  ruleVersion: 'RULE-1.0',
  dispatchStrategy: 'BALANCED',
  recommendationEnabled: true,
  offlineSyncEnabled: true,
  reportPeriod: 'DAILY',
  auditRetentionDays: 180,
  status: 'DRAFT',
  version: 1,
  scenarioId: 'SCN-01',
  createdBy: 'ACTOR-ADMIN',
  updatedBy: 'ACTOR-ADMIN',
};

const auditLogs = [
  { _id: 'AUD-001', id: 'AUD-001', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'login', objectType: 'session', objectId: 'ACTOR-ADMIN', before: {}, after: { online: true }, reason: '登录系统', traceId: 'TRACE-001', occurredAt: new Date('2026-08-27T08:00:00.000Z') },
  { _id: 'AUD-002', id: 'AUD-002', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'plan:confirm', objectType: 'DO-001', objectId: 'PLAN-001', before: { status: 'PENDING_CONFIRM' }, after: { status: 'CONFIRMED' }, reason: '确认计划', traceId: 'TRACE-002', occurredAt: new Date('2026-08-27T08:05:00.000Z') },
  { _id: 'AUD-003', id: 'AUD-003', actorId: 'ACTOR-DISPATCHER', operatorTerminal: 'TERM-02', action: 'work_order:dispatch', objectType: 'DO-005', objectId: 'WO-001', before: { status: 'READY' }, after: { status: 'DISPATCHED' }, reason: '下发工单', traceId: 'TRACE-003', occurredAt: new Date('2026-08-27T08:30:00.000Z') },
  { _id: 'AUD-004', id: 'AUD-004', actorId: 'ACTOR-DISPATCHER', operatorTerminal: 'TERM-02', action: 'exception:ack', objectType: 'DO-009', objectId: 'EX-001', before: { status: 'OPEN' }, after: { status: 'ACKNOWLEDGED' }, reason: '确认异常', traceId: 'TRACE-004', occurredAt: new Date('2026-08-27T09:00:00.000Z') },
  { _id: 'AUD-005', id: 'AUD-005', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'config:edit', objectType: 'DO-015', objectId: 'CFG-001', before: { status: 'PUBLISHED' }, after: { status: 'PUBLISHED' }, reason: '尝试修改配置失败', traceId: 'TRACE-005', occurredAt: new Date('2026-08-27T09:30:00.000Z') },
];

/** 生成最近 5 分钟的遥测点（写入时序集合） */
function buildTelemetry(now: Date) {
  const points: Array<Record<string, unknown>> = [];
  const specs = [
    { equipmentId: 'EQ-IMG-01', pointCode: 'LOAD', base: 38, unit: 't' },
    { equipmentId: 'EQ-IMG-01', pointCode: 'TROLLEY_POS', base: 12.5, unit: 'm' },
    { equipmentId: 'EQ-IMG-05', pointCode: 'WEIGHT', base: 34.2, unit: 't' },
    { equipmentId: 'EQ-IMG-06', pointCode: 'LEVEL', base: 62, unit: '%' },
  ];
  for (let step = 0; step < 10; step += 1) {
    const ts = new Date(now.getTime() - (10 - step) * 30_000);
    for (const spec of specs) {
      points.push({
        equipmentId: spec.equipmentId,
        pointCode: spec.pointCode,
        value: Number((spec.base + Math.sin(step / 2) * 1.5).toFixed(3)),
        quality: 'GOOD',
        sourceTimestamp: ts,
        unit: spec.unit,
      });
    }
  }
  return points;
}

export interface SeedOptions {
  /** 是否先清空全部集合（默认 true） */
  drop?: boolean;
  /** 是否写入最近遥测点（默认 true） */
  withTelemetry?: boolean;
}

export interface SeedResult {
  dropped: string[];
  counts: Record<string, number>;
}

/**
 * 写入固定种子数据。
 *
 * 供 seed 脚本（pnpm seed）与演示重置接口（POST /api/demo/reset）共用，
 * 保证两条路径产生完全一致的数据基线。
 */
export async function seedDatabase(options: SeedOptions = {}): Promise<SeedResult> {
  const { drop = true, withTelemetry = true } = options;

  const dropped = drop ? await dropAllCollections(false) : [];
  if (drop) await syncAllIndexes();

  const passwordHash = await bcrypt.hash(DEMO_PASSWORD, 10);

  await User.insertMany(users.map((u) => ({ ...u, passwordHash, online: false })));
  await Equipment.insertMany(equipments.map((e) => ({ ...e, _id: e.equipmentId })));
  await Plan.insertMany(plans);
  await WorkOrder.insertMany(workOrders);
  await Task.insertMany(tasks);
  await Exception.insertMany(exceptions.map((e) => ({ ...e, _id: e.exceptionId })));
  await Interlock.insertMany(interlocks.map((i) => ({ ...i, _id: i.interlockId })));
  await Appointment.insertMany(appointments.map((a) => ({ ...a, _id: a.appointmentId })));
  await OfflinePacket.insertMany(offlinePackets.map((p) => ({ ...p, _id: p.packetId })));
  await ConfigVersion.create(configDoc);
  await AuditLog.insertMany(auditLogs);

  if (withTelemetry) {
    await TelemetryPoint.insertMany(buildTelemetry(new Date()));
  }

  // 让配置里的审计保留期真正作用到 TTL 索引上
  await applyAuditRetention(configDoc.auditRetentionDays);

  const counts = {
    users: users.length,
    equipments: equipments.length,
    plans: plans.length,
    workOrders: workOrders.length,
    tasks: tasks.length,
    exceptions: exceptions.length,
    interlocks: interlocks.length,
    appointments: appointments.length,
    offlinePackets: offlinePackets.length,
    configs: 1,
    auditLogs: auditLogs.length,
    telemetry: withTelemetry ? buildTelemetry(new Date()).length : 0,
  };

  logger.info({ counts, dropped: dropped.length }, '种子数据写入完成');
  return { dropped, counts };
}
