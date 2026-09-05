import type { SafetySnapshot } from '@/types/safety'

const now = new Date().toISOString()

export const demoSnapshot: SafetySnapshot = {
  schemaVersion: '1.0.0',
  updatedAt: now,
  dataQuality: 'valid',
  workPlan: { id: 'WO-20260826-018', name: 'B区集装箱卸车作业', shift: '白班 08:00-16:00', team: '装卸一班', area: 'B区 1-4 号泊位', progress: 68, status: '作业中' },
  people: [
    ['P-001', '张伟', '装卸一班', '司索工', 'WB-001', 18, 25, 94, -56, '优', 'normal'],
    ['P-002', '李娜', '安全管理', '安全员', 'WB-002', 34, 54, 82, -61, '优', 'normal'],
    ['P-003', '王强', '设备保障', '维修工', 'WB-003', 71, 24, 67, -65, '良', 'warning'],
    ['P-004', '赵敏', '装卸二班', '装卸工', 'WB-004', 79, 73, 76, -58, '优', 'danger'],
    ['P-005', '陈晨', '外协单位', '外协人员', 'WB-005', 53, 68, 28, -74, '一般', 'warning'],
    ['P-006', '刘洋', '调度中心', '调度员', 'WB-006', 45, 35, 91, -55, '优', 'normal'],
    ['P-007', '孙磊', '装卸一班', '装卸工', 'WB-007', 22, 78, 73, -63, '良', 'normal'],
    ['P-008', '周静', '质量管理', '质检员', 'WB-008', 89, 52, 58, -69, '良', 'normal'],
  ].map(([id, name, team, role, bracelet, x, y, battery, signal, quality, status]) => ({ id, name, team, role, bracelet, x, y, battery, signal, quality, status, lastSeen: now })) as SafetySnapshot['people'],
  fences: [
    { id: 'FENCE-001', name: '龙门吊运行禁区', type: '禁入区', x: 64, y: 12, width: 27, height: 22, level: '严重', enabled: true, version: 'V3.3', appliesTo: '地面作业人员', edgeSynced: true },
    { id: 'FENCE-002', name: '翻箱机作业半径', type: '动态围栏', x: 62, y: 66, width: 28, height: 22, level: '紧急', enabled: true, version: 'V2.5', appliesTo: '全部人员', edgeSynced: true },
    { id: 'FENCE-003', name: '临时检修预警区', type: '预警区', x: 48, y: 49, width: 18, height: 16, level: '一般', enabled: true, version: 'V1.8', appliesTo: '外协人员', edgeSynced: true },
  ],
  devices: [
    { id: 'DEV-CRANE-01', name: '1#龙门吊', type: '龙门吊', x: 78, y: 23, speed: 1.8, direction: '东向', distance: 24.6, threshold: 12, status: 'normal', sensorQuality: 98, controlState: '运行', linkedPerson: '张伟' },
    { id: 'DEV-TIP-02', name: '2#翻箱机', type: '翻箱机', x: 73, y: 72, speed: 0, direction: '固定', distance: 5.7, threshold: 10, status: 'danger', sensorQuality: 96, controlState: '减速待确认', linkedPerson: '赵敏' },
    { id: 'DEV-TRUCK-07', name: '7#转运车', type: '转运车辆', x: 43, y: 48, speed: 8.4, direction: '西北', distance: 16.2, threshold: 15, status: 'warning', sensorQuality: 93, controlState: '运行', linkedPerson: '陈晨' },
    { id: 'DEV-FORK-03', name: '3#叉车', type: '叉车', x: 24, y: 75, speed: 5.1, direction: '南向', distance: 31.8, threshold: 8, status: 'normal', sensorQuality: 97, controlState: '运行', linkedPerson: '孙磊' },
  ],
  aiEvents: [
    { id: 'AI-20260826-031', type: '未佩戴安全帽', camera: 'CAM-07', area: '装卸作业区 A', confidence: 0.96, status: 'pending', occurredAt: now, modelVersion: 'PPE-2.4.1' },
    { id: 'AI-20260826-030', type: '跨越警戒线', camera: 'CAM-12', area: '翻箱机南侧', confidence: 0.88, status: 'pending', occurredAt: now, modelVersion: 'BOUNDARY-1.8.2' },
    { id: 'AI-20260826-029', type: '人员闯入禁区', camera: 'CAM-03', area: '龙门吊运行区', confidence: 0.94, status: 'confirmed', occurredAt: now, modelVersion: 'INTRUSION-3.1.0' },
  ],
  alarms: [
    { id: 'ALM-20260826-1008', title: '翻箱机碰撞距离低于停机阈值', objectName: '2#翻箱机', area: '翻箱机南侧', level: '紧急', source: '设备防碰撞', status: '待接单', owner: 'B区安全员', occurredAt: now, durationSeconds: 82, traceId: 'tr-8f2f01' },
    { id: 'ALM-20260826-1007', title: '人员进入龙门吊运行禁区', objectName: '赵敏', area: '龙门吊运行区', level: '严重', source: '电子围栏', status: '处理中', owner: '李娜', occurredAt: now, durationSeconds: 226, traceId: 'tr-8f2d92' },
    { id: 'ALM-20260826-1006', title: '外协手环电量低于 30%', objectName: '陈晨', area: '装卸作业线 B', level: '一般', source: '人员定位', status: '已派单', owner: '王强', occurredAt: now, durationSeconds: 416, traceId: 'tr-8f2c43' },
  ],
  rules: [
    { id: 'RULE-PER-001', name: '禁入区人员越界', domain: '人员安全', level: '严重', version: 'V3.2', enabled: true, edgeSynced: true },
    { id: 'RULE-DEV-004', name: '设备红色碰撞风险', domain: '设备安全', level: '紧急', version: 'V2.6', enabled: true, edgeSynced: true },
    { id: 'RULE-AI-003', name: '未佩戴安全帽识别', domain: 'AI违规', level: '一般', version: 'V1.9', enabled: true, edgeSynced: true },
    { id: 'RULE-NET-002', name: '边缘网关断网自治', domain: '集成运维', level: '提示', version: 'V2.1', enabled: true, edgeSynced: true },
  ],
  scenarios: [
    { id: 'person_intrusion', name: '外协人员闯入禁区', domain: '人员安全', level: '严重', duration: '约 10 秒', description: '触发围栏、声光、大屏与派单，展示人员安全闭环。' },
    { id: 'group_intrusion', name: '多人同时进入危险区', domain: '人员安全', level: '紧急', duration: '约 15 秒', description: '展示多人事件聚合、并行通知与升级处置。' },
    { id: 'device_collision', name: '设备红色碰撞风险', domain: '设备安全', level: '紧急', duration: '约 12 秒', description: '距离降至 3.2m，触发停机接口与回执。' },
    { id: 'ai_ppe', name: 'AI 未佩戴安全帽', domain: 'AI违规', level: '一般', duration: '约 8 秒', description: '生成高置信度事件并进入人工复核。' },
    { id: 'linkage_failure', name: '紧急联动失败', domain: '告警联动', level: '紧急', duration: '约 15 秒', description: '模拟接口回执超时、自动升级和人工接管。' },
    { id: 'network_offline', name: '云边断网自治', domain: '运维', level: '提示', duration: '约 10 秒', description: '边缘规则继续运行，恢复后补传缓存事件。' },
  ],
  auditLogs: [
    { id: 'AUD-001', time: now, action: '规则版本对账', operator: '边缘网关', result: '一致', traceId: 'tr-8f1a01' },
    { id: 'AUD-002', time: now, action: '作业计划同步', operator: '调度接口', result: '成功', traceId: 'tr-8f1a02' },
  ],
  system: {
    cloudConnected: true,
    edgeVersion: 'EDGE-2.6.1',
    cachedEvents: 0,
    availability: 99.96,
    services: [
      { name: '定位引擎', state: 'healthy', latencyMs: 42, detail: '8 个手环在线' },
      { name: '边缘规则引擎', state: 'healthy', latencyMs: 18, detail: '规则已同步' },
      { name: 'AI 推理服务', state: 'healthy', latencyMs: 168, detail: '12 路视频' },
      { name: '消息服务', state: 'healthy', latencyMs: 31, detail: '无积压' },
    ],
  },
}

