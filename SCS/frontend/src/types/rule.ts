// 规则配置中心类型定义（本阶段全部为前端 Mock）

export type RuleCategory = '人员安全' | '设备安全' | 'AI识别' | '告警策略' | '联动策略' | '通知策略'

export type RuleStatus = '草稿' | '待评审' | '已批准' | '发布中' | '已生效' | '版本异常' | '已停用'

export type RuleRisk = '一般' | '预警' | '严重' | '紧急'

/** 规则判定参数 */
export interface RuleParam {
  label: string
  value: string
  hint?: string | undefined
  /** 高危安全参数：修改 / 发布时需要二次确认 */
  danger?: boolean | undefined
}

/** 版本差异条目 */
export interface VersionDiff {
  label: string
  from: string
  to: string
}

export type VersionState = '当前' | '历史版本'

export interface RuleVersion {
  version: string
  date: string
  note: string
  author: string
  state: VersionState
  diffs?: VersionDiff[] | undefined
}

export type EdgeSyncState = 'synced' | 'syncing' | 'mismatch'

/** 规则冲突条目（冲突检查结果） */
export interface RuleConflict {
  ruleA: string
  ruleB: string
  area: string
  deviceKind: string
  paramLabel: string
  valueA: string
  valueB: string
  highRisk: boolean
}

export interface EdgeNodeState {
  node: string
  version: string
  state: EdgeSyncState
}

export interface SafetyRule {
  id: string
  name: string
  category: RuleCategory
  areas: string[]
  version: string
  /** 平台最新版本（版本异常时高于部分边缘节点版本） */
  platformVersion: string
  risk: RuleRisk
  status: RuleStatus
  updatedAt: string
  owner: string
  approver: string
  effectiveAt: string
  /** 判定参数（随规则类型不同） */
  params: RuleParam[]
  /** 联动 / 处置动作 */
  actions: string[]
  /** 涉及高危安全参数（紧急停机、设备禁动、紧急等级、PLC 联动等） */
  highRisk: boolean
  /** 关联的前序模块 */
  relatedModules: string[]
  /** 参数说明 */
  description: string
  versions: RuleVersion[]
  edgeNodes: EdgeNodeState[]
}

/** 规则仿真输入 */
export interface SimulationInput {
  distance: number
  relSpeed: number
  direction: '接近' | '远离' | '静止'
  radarQuality: number
  weather: '晴' | '小雨' | '雾天' | '夜间'
}

export type SimulationLevel = '安全' | '预警风险' | '严重风险' | '紧急风险'

export interface SimulationResult {
  level: SimulationLevel
  matchedRule: string
  thresholdNote: string
  actions: string[]
  escalation: string
}

export const RULE_CATEGORIES: RuleCategory[] = ['人员安全', '设备安全', 'AI识别', '告警策略', '联动策略', '通知策略']

export const RULE_STATUSES: RuleStatus[] = ['草稿', '待评审', '已批准', '发布中', '已生效', '版本异常', '已停用']

export const RULE_LEVELS: RuleRisk[] = ['一般', '预警', '严重', '紧急']

export const RULE_OWNERS = ['安全员 王建国', '安全员 李娜', '设备管理员 周海', '调度员 陈晓', '系统管理员'] as const

export const RULE_AREAS = ['全部区域', '装卸区 A', '装卸区 B', '龙门吊作业区', '翻箱机作业区', '车辆通道', '箱区 B', '维修通道'] as const

export const RULE_ACTIONS = [
  '现场声光提醒',
  '人员手环提醒',
  '通知安全员',
  '通知调度员',
  '通知司机',
  '减速请求',
  '设备禁动',
  '设备停机',
  'PLC 联动',
  '进入人工复核',
] as const

export const EDGE_NODE_IDS = ['EDGE-01', 'EDGE-02', 'EDGE-03', 'EDGE-04'] as const

/** 版本号递增：v3.2 -> v3.3 */
export function bumpVersion(version: string): string {
  const m = /^v(\d+)\.(\d+)$/.exec(version)
  if (!m) return 'v1.1'
  return `v${m[1]}.${Number(m[2]) + 1}`
}

/** 状态机：各状态下允许的操作 */
export function ruleActionsOf(status: RuleStatus): string[] {
  switch (status) {
    case '草稿': return ['编辑', '提交评审']
    case '待评审': return ['批准', '驳回']
    case '已批准': return ['发布', '驳回']
    case '发布中': return []
    case '已生效': return ['新建版本', '回滚', '停用']
    case '版本异常': return ['重新下发']
    case '已停用': return ['提交评审']
    default: return []
  }
}
