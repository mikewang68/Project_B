/**
 * 运维监控集中 Adapter（任务书第六 / 四十三节）：后端 DTO → 前端既有视图类型。
 * 节点状态 / 健康态 / 队列态 / 恢复阶段的中文与颜色映射全部收敛在这里，
 * 组件内不再各写一套状态翻译；视觉组件 props 契约保持不变。
 */
import type {
  CloudLink,
  DeviceCategory,
  DeviceKind,
  EdgeNode,
  HealthState,
  LocalEvent,
  OpsDevice,
  OpsEvent,
  OpsInterface,
  RecoveryStep,
} from '@/types/operations'
import type {
  BackendEdgeNode,
  BackendOpsDevice,
  BackendOpsEvent,
  BackendOpsInterface,
  BackendPendingEvent,
  BackendRecoveryPhase,
  PhaseStatus,
} from '@/api/operations'

/** 后端节点状态 → 中文标签（集中映射，唯一权威） */
export const NODE_STATUS_LABEL: Record<string, string> = {
  ONLINE: '正常',
  DEGRADED: '降级',
  OFFLINE: '离线',
  RECOVERING: '恢复中',
  ERROR: '异常',
}

/** 后端节点状态 → 健康色（正常绿 / 降级橙黄 / 离线灰 / 异常红 / 恢复中蓝） */
export const NODE_STATUS_TONE: Record<string, string> = {
  ONLINE: 'success',
  DEGRADED: 'warning',
  OFFLINE: 'offline',
  RECOVERING: 'info',
  ERROR: 'danger',
}

const EVENT_TYPE_LABEL: Record<string, string> = {
  'person-intrusion': '人员进入危险区域',
  'collision-risk': '设备距离风险',
  'ppe-violation': '未佩戴安全帽',
  'person-stay': '人员异常滞留',
}

const QUEUE_STATUS_LABEL: Record<string, LocalEvent['status']> = {
  PENDING: '待补传',
  SYNCING: '补传中',
  SYNCED: '已补传',
  FAILED: '待补传',
  DUPLICATE: '重复',
}

const DEVICE_TYPE_TO_KIND: Record<string, DeviceKind> = {
  摄像头: '摄像头',
  雷达: '雷达',
  定位基站: '定位基站',
  'PLC 控制器': 'PLC接口',
  声光报警器: '声光设备',
}

const KIND_ORDER: DeviceKind[] = ['摄像头', '雷达', '定位基站', 'PLC接口', '声光设备']

function hms(iso?: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 边缘节点：后端模型 → 前端 EdgeNode（组件契约不变） */
export function toEdgeNode(b: BackendEdgeNode): EdgeNode {
  const drift = b.clockOffsetMs !== null && Math.abs(b.clockOffsetMs) > 1000
  const online = b.status === 'ONLINE' || b.status === 'DEGRADED' || b.status === 'RECOVERING'
  const issue = issueOf(b)
  return {
    id: b.id,
    name: b.name,
    ip: b.ip,
    online,
    autonomy: b.autonomyActive,
    cpu: b.cpuUsage,
    memory: b.memoryUsage,
    storage: b.diskUsage,
    cacheCapacity: 5000,
    cacheEvents: b.queueDepth,
    ruleVersion: b.activeRuleVersion,
    platformVersion: b.expectedRuleVersion,
    // 仅超过 1000ms 阈值才在节点卡片显示时间异常（+120ms 属正常范围）
    timeOffsetMs: drift ? b.clockOffsetMs : null,
    lastHeartbeat: b.cloudConnected ? '刚刚' : '已中断',
    localEventCount: b.cachedEventCount,
    recentIssue: issue,
    cacheParts: b.cacheParts?.map((p) => ({ label: p.label, percent: p.percent })) ?? [],
  }
}

function issueOf(b: BackendEdgeNode): string {
  if (b.status === 'OFFLINE') return '云连接中断，边缘本地自治运行'
  if (b.clockOffsetMs !== null && Math.abs(b.clockOffsetMs) > 1000) {
    return b.status === 'RECOVERING' ? `时间偏差 ${b.clockOffsetMs}ms（恢复中）` : `时间偏差 ${b.clockOffsetMs}ms`
  }
  if (b.activeRuleVersion !== b.expectedRuleVersion) {
    return b.status === 'RECOVERING' ? '规则版本与平台不一致（恢复中）' : '规则版本与平台不一致'
  }
  if (b.status === 'RECOVERING') return '云边链路恢复中'
  if (b.diskUsage >= 85) return '缓存占用达到容量高风险阈值'
  if (b.diskUsage >= 60) return '缓存占用偏高'
  return '近 24 小时无异常'
}

/** 设备台账：后端扁平列表 → 前端分类结构 */
export function toDeviceCategories(list: BackendOpsDevice[]): DeviceCategory[] {
  const mapped: OpsDevice[] = list.map((d) => ({
    id: d.id,
    name: d.name,
    kind: DEVICE_TYPE_TO_KIND[d.type] ?? '摄像头',
    state: d.status === 'online' ? 'normal' : d.status === 'fault' ? 'fault' : 'offline',
    issue: d.issue ?? '',
    edgeId: d.edgeNodeId,
  }))
  return KIND_ORDER.map((kind) => {
    const devices = mapped.filter((d) => d.kind === kind)
    return { kind, total: devices.length, devices }
  })
}

/** 接口链路：后端 → 前端（IF-PLC 为现场侧接口，断网时仍可达） */
export function toOpsInterfaces(list: BackendOpsInterface[]): OpsInterface[] {
  return list.map((i) => ({
    id: i.id,
    name: i.name,
    state: (i.status === 'normal' ? 'normal' : i.status === 'degraded' ? 'degraded' : 'fault') as HealthState,
    latencyMs: i.latencyMs,
    successRate: i.successRate,
    lastCall: i.message ?? '刚刚',
    errorCount: i.status === 'normal' ? 0 : 1,
    cloudSide: i.id !== 'IF-PLC',
  }))
}

/** 运维日志：后端 OpsEventLog → 前端 OpsEvent，级别集中映射 */
export function toOpsEvents(list: BackendOpsEvent[]): OpsEvent[] {
  return list.map((e) => ({
    id: e.id,
    time: e.time || hms(e.ts),
    level: eventLevel(e.level, e.type),
    target: e.nodeId || e.type,
    text: e.text,
  }))
}

function eventLevel(level: string, type: string): OpsEvent['level'] {
  if (type === 'NODE_OFFLINE') return 'offline'
  if (level === 'error') return 'fault'
  if (level === 'warning') {
    return type === 'CLOCK_MISMATCH' || type === 'RULE_MISMATCH' ? 'degraded' : 'warning'
  }
  return 'info'
}

/** 离线事件队列：后端 EdgePendingEvent → 前端 LocalEvent */
export function toLocalEvents(list: BackendPendingEvent[]): LocalEvent[] {
  return list.map((e) => ({
    id: e.eventId,
    type: e.payloadSummary?.title || EVENT_TYPE_LABEL[e.eventType] || e.eventType,
    node: e.edgeNodeId,
    time: hms(e.edgeOccurredAt),
    risk: e.risk as LocalEvent['risk'],
    status: QUEUE_STATUS_LABEL[e.status] ?? '待补传',
    dedupKey: e.idempotencyKey,
    localActions: e.localLinkage?.actions ?? [],
  }))
}

/** 恢复阶段：后端 RecoveryPhase → 前端 RecoveryStep（后端 phase 驱动，唯一映射） */
export function toRecoverySteps(phases: BackendRecoveryPhase[] | undefined): RecoveryStep[] {
  if (!phases) return []
  return phases.map((p) => ({
    key: p.key,
    label: p.label,
    state: phaseState(p.status),
    detail: p.message || phaseDefaultDetail(p.key, p.status),
  }))
}

function phaseState(status: PhaseStatus): RecoveryStep['state'] {
  switch (status) {
    case 'RUNNING':
      return 'running'
    case 'DONE':
      return 'done'
    case 'BLOCKED':
    case 'FAILED':
      return 'fail'
    default:
      return 'wait'
  }
}

function phaseDefaultDetail(key: string, status: PhaseStatus): string {
  if (status === 'DONE') return '已完成'
  if (status === 'RUNNING') return '执行中…'
  if (status === 'BLOCKED') return '等待对账'
  const map: Record<string, string> = {
    CONNECTIVITY: '等待中心链路重新建立',
    CLOCK_RECONCILIATION: 'NTP 偏差检查（Demo 校时）',
    RULE_RECONCILIATION: '比对平台与边缘规则 / 围栏版本',
    EVENT_REPLAY: '按发生时间顺序幂等补传离线事件',
    FINAL_CHECK: '队列清空与一致性检查',
    ONLINE: '边缘节点切回在线模式',
  }
  return map[key] ?? '等待'
}

/** 后端云边链路 → 前端 CloudLink（直接同构，兜底 online） */
export function toCloudLink(state: string | undefined): CloudLink {
  if (state === 'disconnected' || state === 'recovering' || state === 'link-error') return state
  return 'online'
}

/** 在线设备数统计：仅正常计入在线（与原 mock 工具口径一致，供组件复用） */
export function onlineCount(devices: OpsDevice[]): number {
  return devices.filter((d) => d.state === 'normal').length
}
