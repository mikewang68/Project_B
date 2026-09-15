import bcrypt from 'bcryptjs';
import dotenv from 'dotenv';
import { connectDatabase, jsondb } from '../config/db.js';
import { logger } from '../lib/logger.js';

dotenv.config();

const passwordHash = await bcrypt.hash('password123', 10);

const users = [
  { _id: 'ACTOR-ADMIN', actorId: 'ACTOR-ADMIN', username: 'admin', passwordHash, displayName: '系统管理员', roleCode: 'super_admin', dataScope: ['*'], online: false },
  { _id: 'ACTOR-SCHEDULER', actorId: 'ACTOR-SCHEDULER', username: 'scheduler', passwordHash, displayName: '调度员张三', roleCode: 'scheduler', dataScope: ['AREA-A', 'AREA-B'], online: false },
  { _id: 'ACTOR-DISPATCHER', actorId: 'ACTOR-DISPATCHER', username: 'dispatcher', passwordHash, displayName: '派工员李四', roleCode: 'dispatcher', dataScope: ['AREA-A'], online: false },
  { _id: 'ACTOR-OPERATOR', actorId: 'ACTOR-OPERATOR', username: 'operator', passwordHash, displayName: '操作员王五', roleCode: 'operator', dataScope: ['AREA-A'], online: false },
  { _id: 'ACTOR-VIEWER', actorId: 'ACTOR-VIEWER', username: 'viewer', passwordHash, displayName: '浏览员赵六', roleCode: 'viewer', dataScope: ['AREA-A', 'AREA-B'], online: false },
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
  { _id: 'PLAN-001', planBatchNo: 'PB-2026-001', trainNo: 'T88001', cargoType: 'COAL', cargoDescription: '动力煤 5000kcal', estimatedWeight: 3500, weightUnit: 'ton', sourceStation: '大同站', destinationStation: 'B货场', arriveTime: '2026-08-27T08:00:00.000Z', trackNo: 'G1', workArea: 'AREA-A', status: 'PENDING_CONFIRM', priority: 'HIGH', supplierInfo: { name: '山西煤炭运销集团' } },
  { _id: 'PLAN-002', planBatchNo: 'PB-2026-002', trainNo: 'T88002', cargoType: 'IRON_ORE', cargoDescription: '铁矿石粉 62%Fe', estimatedWeight: 4200, weightUnit: 'ton', sourceStation: '日照港站', destinationStation: 'B货场', arriveTime: '2026-08-27T10:30:00.000Z', trackNo: 'G2', workArea: 'AREA-A', status: 'CONFIRMED', priority: 'HIGH', supplierInfo: { name: '日照钢铁原料公司' } },
  { _id: 'PLAN-003', planBatchNo: 'PB-2026-003', trainNo: 'T88003', cargoType: 'CEMENT', cargoDescription: '普通硅酸盐水泥 P.O42.5', estimatedWeight: 2800, weightUnit: 'ton', sourceStation: '唐山站', destinationStation: 'B货场', arriveTime: '2026-08-27T14:00:00.000Z', trackNo: 'G3', workArea: 'AREA-B', status: 'PENDING_CONFIRM', priority: 'MEDIUM', supplierInfo: { name: '唐山冀东水泥' } },
  { _id: 'PLAN-004', planBatchNo: 'PB-2026-004', trainNo: 'T88004', cargoType: 'STEEL', cargoDescription: '热轧卷板 Q235B 5.5mm', estimatedWeight: 1800, weightUnit: 'ton', sourceStation: '邯郸站', destinationStation: 'B货场', arriveTime: '2026-08-28T09:00:00.000Z', trackNo: 'G4', workArea: 'AREA-A', status: 'PENDING_CONFIRM', priority: 'MEDIUM', supplierInfo: { name: '邯郸钢铁集团' } },
  { _id: 'PLAN-005', planBatchNo: 'PB-2026-005', trainNo: 'T88005', cargoType: 'CONTAINER', cargoDescription: '40英尺标准集装箱 电子产品', estimatedWeight: 1200, weightUnit: 'ton', sourceStation: '郑州站', destinationStation: 'B货场', arriveTime: '2026-08-28T16:00:00.000Z', trackNo: 'G5', workArea: 'AREA-A', status: 'CONFIRMED', priority: 'HIGH', supplierInfo: { name: '富士康郑州' } },
];

const workOrders = [
  { planId: 'PLAN-002', planBatchNo: 'PB-2026-002', workArea: 'AREA-A', equipmentId: 'EQ-IMG-01', equipmentName: '智能化门式起重机 Q=40T S=30m', assignedCrew: ['CREW-A'], status: 'ASSIGNED', orderType: 'UNLOADING', priority: 'HIGH', description: '铁矿石卸车作业 - G2股道' },
  { planId: 'PLAN-002', planBatchNo: 'PB-2026-002', workArea: 'AREA-A', equipmentId: 'EQ-IMG-05', equipmentName: '智能化汽车衡', assignedCrew: ['CREW-B'], status: 'READY', orderType: 'TRANSFER', priority: 'MEDIUM', description: '铁矿石转运称重' },
  { planId: 'PLAN-001', planBatchNo: 'PB-2026-001', workArea: 'AREA-A', equipmentId: 'EQ-IMG-07', equipmentName: '智能化集装箱门式起重机', status: 'DRAFT', orderType: 'UNLOADING', priority: 'HIGH', description: '动力煤卸车 - G1股道' },
];

const tasks = [
  { planId: 'PLAN-002', planBatchNo: 'PB-2026-002', taskNo: 'TASK-001', name: '铁矿石卸车', description: '使用门式起重机卸车至堆场A', workArea: 'AREA-A', equipmentId: 'EQ-IMG-01', status: 'READY', order: 1 },
  { planId: 'PLAN-002', planBatchNo: 'PB-2026-002', taskNo: 'TASK-002', name: '铁矿石转运', description: '转运至筒仓区域', workArea: 'AREA-A', equipmentId: 'EQ-IMG-05', status: 'PENDING', order: 2 },
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
  { appointmentId: 'APT-001', vehiclePlate: '鲁A12345', vehicleType: 'HEAVY_TRUCK', driverName: '刘大', driverPhone: '13800001001', driverIdCard: '370100199001011234', company: '山东物流有限公司', cargoType: 'STEEL', estimatedWeight: 35, plannedArriveTime: '2026-08-27T09:00:00.000Z', status: 'APPROVED' },
  { appointmentId: 'APT-002', vehiclePlate: '鲁B67890', vehicleType: 'HEAVY_TRUCK', driverName: '陈二', driverPhone: '13800001002', driverIdCard: '370200199002021234', company: '青岛运输公司', cargoType: 'CEMENT', estimatedWeight: 40, plannedArriveTime: '2026-08-27T10:00:00.000Z', status: 'PENDING' },
  { appointmentId: 'APT-003', vehiclePlate: '鲁C11223', vehicleType: 'HEAVY_TRUCK', driverName: '张三', driverPhone: '13800001003', driverIdCard: '370300199003031234', company: '淄博货运有限公司', cargoType: 'COAL', estimatedWeight: 38, plannedArriveTime: '2026-08-27T11:00:00.000Z', status: 'QUEUED', queueNumber: 1 },
];

const config = {
  configId: 'CFG-001',
  id: 'CFG-001',
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
  createdAt: '2026-08-01T00:00:00.000Z',
  updatedAt: '2026-08-01T00:00:00.000Z',
  updatedBy: 'ACTOR-ADMIN',
  scenarioId: 'SCN-01',
};

const auditLogs = [
  { _id: 'AUD-001', id: 'AUD-001', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'login', objectType: 'session', objectId: 'ACTOR-ADMIN', before: {}, after: { online: true }, reason: '登录系统', traceId: 'TRACE-001', occurredAt: '2026-08-27T08:00:00.000Z' },
  { _id: 'AUD-002', id: 'AUD-002', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'plan:confirm', objectType: 'DO-001', objectId: 'PLAN-001', before: { status: 'PENDING_CONFIRM' }, after: { status: 'CONFIRMED' }, reason: '确认计划', traceId: 'TRACE-002', occurredAt: '2026-08-27T08:05:00.000Z' },
  { _id: 'AUD-003', id: 'AUD-003', actorId: 'ACTOR-DISPATCHER', operatorTerminal: 'TERM-02', action: 'work_order:dispatch', objectType: 'DO-005', objectId: 'WO-001', before: { status: 'READY' }, after: { status: 'DISPATCHED' }, reason: '下发工单', traceId: 'TRACE-003', occurredAt: '2026-08-27T08:30:00.000Z' },
  { _id: 'AUD-004', id: 'AUD-004', actorId: 'ACTOR-DISPATCHER', operatorTerminal: 'TERM-02', action: 'exception:ack', objectType: 'DO-009', objectId: 'EX-001', before: { status: 'OPEN' }, after: { status: 'ACKNOWLEDGED' }, reason: '确认异常', traceId: 'TRACE-004', occurredAt: '2026-08-27T09:00:00.000Z' },
  { _id: 'AUD-005', id: 'AUD-005', actorId: 'ACTOR-ADMIN', operatorTerminal: 'WEB-01', action: 'config:edit', objectType: 'DO-015', objectId: 'CFG-001', before: { status: 'PUBLISHED' }, after: { status: 'PUBLISHED' }, reason: '尝试修改配置失败', traceId: 'TRACE-005', occurredAt: '2026-08-27T09:30:00.000Z' },
];

async function seed(): Promise<void> {
  await connectDatabase();

  // Reset
  const collections = ['users', 'plans', 'work_orders', 'tasks', 'equipments', 'exceptions', 'interlocks', 'appointments', 'offline_packets', 'configs', 'audit_logs'];
  for (const name of collections) jsondb._dropCollection(name);

  jsondb.insertMany('users', users);
  jsondb.insertMany('equipments', equipments);
  jsondb.insertMany('plans', plans);
  jsondb.insertMany('work_orders', workOrders);
  jsondb.insertMany('tasks', tasks);
  jsondb.insertMany('exceptions', exceptions);
  jsondb.insertMany('interlocks', interlocks);
  jsondb.insertMany('appointments', appointments);
  jsondb.create('configs', config);
  jsondb.insertMany('audit_logs', auditLogs);

  logger.info(`Seed complete! ${users.length} users, ${plans.length} plans, ${equipments.length} equipments, ${auditLogs.length} audit entries`);
  logger.info('Logins: admin / scheduler / dispatcher / operator / viewer (password: password123)');
  process.exit(0);
}

seed().catch((err) => {
  logger.fatal({ err }, 'Seed failed');
  process.exit(1);
});
