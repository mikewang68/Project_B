import type {
  AnalyticsDataset,
  AnalyticsEventItem,
  AnalyticsKpi,
  AreaRisk,
  HotDevice,
  LevelCount,
  RepeatPerson,
  RiskTypeCount,
  TeamEfficiency,
  TrendDirection,
  TrendPoint,
} from '@/types/analytics'

/** 后端聚合 DTO（部分字段为后端附加，如 score / closeRate / demoBaseline，前端按需忽略） */
interface DatasetDto {
  kpi: Array<Omit<AnalyticsKpi, never> & { rawSec?: number | null }>
  trend: Array<TrendPoint & { demoBaseline?: boolean }>
  riskTypes: RiskTypeCount[]
  areas: Array<AreaRisk & { score?: number }>
  teams: Array<Omit<TeamEfficiency, 'confirmSec' | 'arriveSec' | 'closeSec'> & {
    confirmSec: number | null
    arriveSec: number | null
    closeSec: number | null
    closeRate?: number
  }>
  devices: HotDevice[]
  persons: RepeatPerson[]
  levels: LevelCount[]
  trendDemoBaseline?: boolean
}

interface EventPageDto {
  page: number
  pageSize: number
  total: number
  list: AnalyticsEventItem[]
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}

export function mapDataset(dto: unknown): AnalyticsDataset {
  const d = (dto ?? {}) as Partial<DatasetDto>
  const kpi: AnalyticsKpi[] = Array.isArray(d.kpi)
    ? d.kpi.map((k) => ({
        key: k.key,
        label: k.label,
        value: k.value,
        deltaPct: num(k.deltaPct),
        direction: (k.direction as TrendDirection) ?? 'flat',
        goodWhenDown: !!k.goodWhenDown,
        hint: k.hint,
        variant: k.variant ?? 'default',
      }))
    : []
  const trend: TrendPoint[] = Array.isArray(d.trend)
    ? d.trend.map((t) => ({ date: t.date, total: num(t.total), high: num(t.high) }))
    : []
  const riskTypes = Array.isArray(d.riskTypes) ? d.riskTypes : []
  const areas: AreaRisk[] = Array.isArray(d.areas)
    ? d.areas.map((a) => ({ area: a.area, total: num(a.total), high: num(a.high) }))
    : []
  const teams: TeamEfficiency[] = Array.isArray(d.teams)
    ? d.teams.map((t) => ({
        team: t.team,
        confirmSec: num(t.confirmSec),
        arriveSec: num(t.arriveSec),
        closeSec: num(t.closeSec),
        eventCount: num(t.eventCount),
      }))
    : []
  const devices: HotDevice[] = Array.isArray(d.devices)
    ? d.devices.map((dv) => ({
        deviceId: dv.deviceId,
        name: dv.name,
        count: num(dv.count),
        primaryRisk: dv.primaryRisk,
        primaryCount: num(dv.primaryCount),
        trend: dv.trend ?? 'flat',
        recentRisk: dv.recentRisk ?? '',
        typeSplit: Array.isArray(dv.typeSplit) ? dv.typeSplit : [],
        recentEvents: Array.isArray(dv.recentEvents) ? dv.recentEvents : [],
      }))
    : []
  const persons: RepeatPerson[] = Array.isArray(d.persons)
    ? d.persons.map((p) => ({ ...p, count: num(p.count) }))
    : []
  const levels: LevelCount[] = Array.isArray(d.levels)
    ? d.levels.map((l) => ({ level: l.level, count: num(l.count) }))
    : []
  // 明细由 /analytics/events 单独分页加载，dataset 不携带 events
  return { kpi, trend, riskTypes, areas, teams, devices, persons, levels, events: [] }
}

export interface AnalyticsEventPage {
  page: number
  pageSize: number
  total: number
  list: AnalyticsEventItem[]
}

export function mapEventPage(dto: unknown): AnalyticsEventPage {
  const d = (dto ?? {}) as Partial<EventPageDto>
  return {
    page: num(d.page) || 1,
    pageSize: num(d.pageSize) || 10,
    total: num(d.total),
    list: Array.isArray(d.list) ? d.list : [],
  }
}
