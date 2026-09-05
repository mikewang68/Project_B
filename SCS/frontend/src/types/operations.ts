/** 运维监控 + 云边断网自治：类型定义（全部前端 Mock） */

/** 健康态：正常(绿) / 降级(橙) / 故障(红) / 离线(灰) */
export type HealthState = 'normal' | 'degraded' | 'fault' | 'offline'

/** 云边链路：在线 / 链路异常 / 中心连接中断 / 恢复中 */
export type CloudLink = 'online' | 'link-error' | 'disconnected' | 'recovering'

/** 边缘节点 */
export interface EdgeNode {
  id: string
  name: string
  ip: string
  online: boolean
  /** 自治模式（中心断网后边缘继续运行） */
  autonomy: boolean
  cpu: number
  memory: number
  /** 缓存占用 % */
  storage: number
  cacheCapacity: number
  /** 缓存事件数 */
  cacheEvents: number
  ruleVersion: string
  platformVersion: string
  /** 时间同步偏差 ms；null 表示正常 */
  timeOffsetMs: number | null
  lastHeartbeat: string
  localEventCount: number
  recentIssue: string
  /** 缓存分区：事件 / 视频证据 / 日志 */
  cacheParts: { label: string; percent: number }[]
}

/** 感知设备 */
export interface OpsDevice {
  id: string
  name: string
  kind: DeviceKind
  state: HealthState
  issue: string
  edgeId: string
}

export type DeviceKind = '摄像头' | '雷达' | '定位基站' | 'PLC接口' | '声光设备'

export interface DeviceCategory {
  kind: DeviceKind
  total: number
  devices: OpsDevice[]
}

/** 关键接口 */
export interface OpsInterface {
  id: string
  name: string
  state: HealthState
  latencyMs: number
  successRate: number
  lastCall: string
  errorCount: number
  /** 是否中心侧接口（断网后不可达） */
  cloudSide: boolean
}

/** 运维异常 Feed */
export interface OpsEvent {
  id: string
  time: string
  level: 'info' | 'warning' | 'degraded' | 'fault' | 'offline'
  target: string
  text: string
}

/** 本地缓存 / 待补传事件 */
export interface LocalEvent {
  id: string
  type: string
  node: string
  time: string
  risk: '一般' | '预警' | '严重' | '紧急'
  status: '待补传' | '补传中' | '已补传' | '重复'
  /** 唯一编号，用于恢复后去重 */
  dedupKey: string
  /** 边缘本地处置链路结果 */
  localActions: string[]
}

/** 恢复流程步骤 */
export interface RecoveryStep {
  key: string
  label: string
  state: 'wait' | 'running' | 'done' | 'fail'
  detail: string
}

export const HEALTH_LABEL: Record<HealthState, string> = {
  normal: '正常',
  degraded: '降级',
  fault: '故障',
  offline: '离线',
}

/** 断网期间能力可用性清单 */
export const AUTONOMY_CAPABILITIES: { name: string; available: boolean; note: string }[] = [
  { name: '人员定位', available: true, note: '本地可用' },
  { name: '电子围栏', available: true, note: '本地规则继续生效' },
  { name: '设备防碰撞', available: true, note: '本地可用' },
  { name: '紧急联动', available: true, note: '本地可用' },
  { name: '告警记录', available: true, note: '本地缓存' },
  { name: '规则修改', available: false, note: '暂停远程发布' },
  { name: '统计分析', available: false, note: '数据可能延迟' },
]

export const DEVICE_KIND_ORDER: DeviceKind[] = ['摄像头', '雷达', '定位基站', 'PLC接口', '声光设备']

/** 本地风险事件模板池 */
export const LOCAL_RISK_POOL: { type: string; risk: LocalEvent['risk']; actions: string[] }[] = [
  { type: '人员进入危险区域', risk: '紧急', actions: ['本地风险判定', '现场声光提醒', '设备禁动', '本地事件记录'] },
  { type: '设备距离风险', risk: '严重', actions: ['本地风险判定', '司机提醒', '减速请求', '本地事件记录'] },
  { type: '未佩戴安全帽', risk: '预警', actions: ['本地 AI 判定', '现场语音提醒', '本地事件记录'] },
  { type: '人员异常滞留', risk: '严重', actions: ['本地风险判定', '手环提醒', '通知现场安全员', '本地事件记录'] },
]
