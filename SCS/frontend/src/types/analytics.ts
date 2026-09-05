/** 统计分析模块类型定义（全部前端 Mock） */

export type AnalyticsPeriod = '今日' | '本周' | '本月' | '自定义'
export type TrendDirection = 'up' | 'down' | 'flat'

/** KPI 指标 */
export interface AnalyticsKpi {
  key: string
  label: string
  value: string
  /** 与上一周期对比（百分比，正数为上升）；direction 控制箭头语义，goodWhenDown 表示下降才是好事 */
  deltaPct: number
  direction: TrendDirection
  goodWhenDown: boolean
  hint: string
  variant: 'hero' | 'time' | 'default'
}

/** 趋势点 */
export interface TrendPoint {
  date: string
  total: number
  high: number
}

/** 风险类型计数 */
export interface RiskTypeCount {
  type: string
  count: number
  high: number
}

/** 区域风险 */
export interface AreaRisk {
  area: string
  total: number
  high: number
}

/** 班组处置效率（秒） */
export interface TeamEfficiency {
  team: string
  confirmSec: number
  arriveSec: number
  closeSec: number
  /** 事件量，用于下钻 */
  eventCount: number
}

/** 高频风险设备 */
export interface HotDevice {
  deviceId: string
  name: string
  count: number
  primaryRisk: string
  primaryCount: number
  trend: TrendDirection
  recentRisk: string
  typeSplit: { type: string; count: number }[]
  recentEvents: DeviceRecentEvent[]
}

export interface DeviceRecentEvent {
  time: string
  type: string
  level: string
}

/** 重复风险人员（安全管理视角，非黑名单） */
export interface RepeatPerson {
  personId: string
  name: string
  team: string
  count: number
  mainType: string
  recent: string[]
}

/** 等级分布 */
export interface LevelCount {
  level: string
  count: number
}

/** 事件明细行 */
export interface AnalyticsEventItem {
  id: string
  time: string
  type: string
  area: string
  target: string
  level: string
  status: string
  /** 处置时长（秒），未关闭为 undefined */
  durationSec: number | undefined
  team: string
  deviceId: string | undefined
}

export interface AnalyticsDataset {
  kpi: AnalyticsKpi[]
  trend: TrendPoint[]
  riskTypes: RiskTypeCount[]
  areas: AreaRisk[]
  teams: TeamEfficiency[]
  devices: HotDevice[]
  persons: RepeatPerson[]
  levels: LevelCount[]
  events: AnalyticsEventItem[]
}

/** 下钻维度 */
export interface DrillFilter {
  kind: 'type' | 'area' | 'level' | 'device' | 'team' | 'person'
  label: string
  value: string
}

export const ANALYTICS_AREAS = ['全部区域', '装卸区 A', '装卸区 B', '龙门吊作业区', '翻箱机作业区', '车辆通道', '箱区 B', '维修通道']
export const ANALYTICS_TEAMS = ['全部班组', '装卸一班', '装卸二班', '安全管理班', '设备保障班', '外协单位']
export const ANALYTICS_TYPES = ['全部类型', '人员越界', '设备距离风险', '未佩戴安全帽', '人员异常', '设备异常', '翻越护栏', '人员闯入', '视频设备异常']
export const ANALYTICS_LEVELS = ['全部等级', '一般', '预警', '严重', '紧急']
export const ANALYTICS_PERIODS: AnalyticsPeriod[] = ['今日', '本周', '本月', '自定义']

export function formatDuration(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}m ${String(s).padStart(2, '0')}s`
}
export function formatCnDuration(sec: number): string {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m} 分 ${String(s).padStart(2, '0')} 秒`
}
