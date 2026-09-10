/**
 * 运维监控 Mock 数据工厂（FIXTURE，已退出主数据流）。
 * 运维页主数据已切换到 Backend（/api/v1/ops、/api/v1/edge），见 api/operations.ts + adapters/operations.ts；
 * 本文件仅保留离线 fixture 与纯工具函数，供测试 / 独立预览使用，不再被 OperationsView 引用。
 */
import type {
  DeviceCategory,
  DeviceKind,
  EdgeNode,
  OpsDevice,
  OpsEvent,
  OpsInterface,
} from '@/types/operations'

export const PLATFORM_RULE_VERSION = 'v3.3'

export function buildEdgeNodes(): EdgeNode[] {
  const base: EdgeNode[] = [
    {
      id: 'EDGE-01', name: '1 号边缘节点 · 装卸区 A', ip: '10.24.1.11',
      online: true, autonomy: false, cpu: 32, memory: 46, storage: 41, cacheCapacity: 5000,
      cacheEvents: 0, ruleVersion: 'v3.3', platformVersion: PLATFORM_RULE_VERSION,
      timeOffsetMs: null, lastHeartbeat: '刚刚', localEventCount: 1284, recentIssue: '近 24 小时无异常',
      cacheParts: [
        { label: '事件缓存', percent: 38 },
        { label: '视频证据缓存', percent: 45 },
        { label: '日志空间', percent: 32 },
      ],
    },
    {
      id: 'EDGE-02', name: '2 号边缘节点 · 车辆通道', ip: '10.24.1.12',
      online: true, autonomy: false, cpu: 38, memory: 52, storage: 47, cacheCapacity: 5000,
      cacheEvents: 0, ruleVersion: 'v3.3', platformVersion: PLATFORM_RULE_VERSION,
      timeOffsetMs: null, lastHeartbeat: '刚刚', localEventCount: 1036, recentIssue: '近 24 小时无异常',
      cacheParts: [
        { label: '事件缓存', percent: 44 },
        { label: '视频证据缓存', percent: 51 },
        { label: '日志空间', percent: 36 },
      ],
    },
    {
      id: 'EDGE-03', name: '3 号边缘节点 · 翻箱机区', ip: '10.24.1.13',
      online: true, autonomy: false, cpu: 44, memory: 58, storage: 68, cacheCapacity: 5000,
      cacheEvents: 0, ruleVersion: 'v3.3', platformVersion: PLATFORM_RULE_VERSION,
      timeOffsetMs: null, lastHeartbeat: '刚刚', localEventCount: 1512, recentIssue: '缓存占用超过 60%',
      cacheParts: [
        { label: '事件缓存', percent: 66 },
        { label: '视频证据缓存', percent: 72 },
        { label: '日志空间', percent: 58 },
      ],
    },
    {
      id: 'EDGE-04', name: '4 号边缘节点 · 龙门吊作业区', ip: '10.24.1.14',
      online: true, autonomy: false, cpu: 29, memory: 41, storage: 36, cacheCapacity: 5000,
      cacheEvents: 0, ruleVersion: 'v3.3', platformVersion: PLATFORM_RULE_VERSION,
      timeOffsetMs: null, lastHeartbeat: '刚刚', localEventCount: 902, recentIssue: '近 24 小时无异常',
      cacheParts: [
        { label: '事件缓存', percent: 33 },
        { label: '视频证据缓存', percent: 39 },
        { label: '日志空间', percent: 28 },
      ],
    },
  ]
  return base
}

function dev(id: string, name: string, kind: DeviceKind, issue = '', edgeId = 'EDGE-01'): OpsDevice {
  return { id, name, kind, state: issue ? 'degraded' : 'normal', issue, edgeId }
}

export function buildDeviceCategories(): DeviceCategory[] {
  const cameras: OpsDevice[] = []
  for (let i = 1; i <= 24; i += 1) {
    const id = `CAM-${String(i).padStart(2, '0')}`
    if (i === 12) cameras.push(dev(id, '装卸区 A 球机', '摄像头', '画面遮挡', 'EDGE-01'))
    else if (i === 18) cameras.push(dev(id, '车辆通道枪机', '摄像头', '网络波动', 'EDGE-02'))
    else cameras.push(dev(id, `现场摄像头 ${i}`, '摄像头'))
  }
  const radars: OpsDevice[] = []
  for (let i = 1; i <= 8; i += 1) radars.push(dev(`RADAR-${String(i).padStart(2, '0')}`, `毫米波雷达 ${i}`, '雷达', '', `EDGE-0${(i % 4) + 1}`))
  const stations: OpsDevice[] = []
  for (let i = 1; i <= 12; i += 1) stations.push(dev(`UWB-${String(i).padStart(2, '0')}`, `定位基站 ${i}`, '定位基站', '', `EDGE-0${(i % 4) + 1}`))
  const plcs: OpsDevice[] = []
  for (let i = 1; i <= 6; i += 1) plcs.push(dev(`PLC-${String(i).padStart(2, '0')}`, `PLC 控制单元 ${i}`, 'PLC接口', '', `EDGE-0${(i % 4) + 1}`))
  const alarms: OpsDevice[] = []
  for (let i = 1; i <= 10; i += 1) alarms.push(dev(`ALM-D${String(i).padStart(2, '0')}`, `声光报警器 ${i}`, '声光设备', '', `EDGE-0${(i % 4) + 1}`))
  return [
    { kind: '摄像头', total: 24, devices: cameras },
    { kind: '雷达', total: 8, devices: radars },
    { kind: '定位基站', total: 12, devices: stations },
    { kind: 'PLC接口', total: 6, devices: plcs },
    { kind: '声光设备', total: 10, devices: alarms },
  ]
}

export function buildInterfaces(): OpsInterface[] {
  return [
    { id: 'IF-SCHED', name: '生产调度系统', state: 'normal', latencyMs: 82, successRate: 99.98, lastCall: '12 秒前', errorCount: 0, cloudSide: true },
    { id: 'IF-DATA', name: '统一数据底座', state: 'normal', latencyMs: 64, successRate: 99.95, lastCall: '8 秒前', errorCount: 1, cloudSide: true },
    { id: 'IF-TWIN', name: '数字孪生', state: 'degraded', latencyMs: 136, successRate: 98.72, lastCall: '31 秒前', errorCount: 6, cloudSide: true },
    { id: 'IF-PLC', name: 'PLC 控制接口', state: 'normal', latencyMs: 21, successRate: 99.99, lastCall: '3 秒前', errorCount: 0, cloudSide: false },
    { id: 'IF-VIDEO', name: '视频证据存储', state: 'normal', latencyMs: 95, successRate: 99.91, lastCall: '18 秒前', errorCount: 2, cloudSide: true },
    { id: 'IF-AUTH', name: '人员权限系统', state: 'normal', latencyMs: 71, successRate: 99.96, lastCall: '44 秒前', errorCount: 0, cloudSide: true },
  ]
}

export function buildOpsEvents(): OpsEvent[] {
  return [
    { id: 'OPS-E-04', time: '11:36:20', level: 'warning', target: 'CAM-12', text: '画面遮挡，AI 识别能力受限' },
    { id: 'OPS-E-03', time: '10:58:41', level: 'warning', target: 'EDGE-03', text: '缓存占用超过 60%，请关注补传与清理' },
    { id: 'OPS-E-02', time: '10:22:07', level: 'degraded', target: '数字孪生接口', text: '接口延迟升高至 136ms' },
    { id: 'OPS-E-01', time: '09:14:52', level: 'degraded', target: 'EDGE-02', text: '时间同步偏差 260ms，已自动校正' },
  ]
}

/** 在线设备数统计：仅正常计入在线；降级 / 故障 / 离线均为异常未在线 */
export function onlineCount(devices: OpsDevice[]): number {
  return devices.filter((d) => d.state === 'normal').length
}
