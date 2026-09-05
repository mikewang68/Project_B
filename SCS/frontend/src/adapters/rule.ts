import type {
  EdgeNodeState,
  EdgeSyncState,
  RuleCategory,
  RuleParam,
  RuleRisk,
  RuleStatus,
  RuleVersion,
  SafetyRule,
  SimulationResult,
  VersionDiff,
} from '@/types/rule'

/** 后端 DemoRule DTO（字段与前端 SafetyRule 基本同名，这里做防御性兜底） */
export interface RuleDto {
  id: string
  name: string
  category: RuleCategory
  type?: string
  scope?: string
  areas: string[]
  version: string
  platformVersion: string
  risk: RuleRisk
  status: RuleStatus
  updatedAt: string
  owner: string
  approver: string
  effectiveAt: string
  params: Array<{ label: string; value: string; hint?: string; danger?: boolean }>
  actions: string[]
  highRisk: boolean
  relatedModules?: string[]
  description?: string
  versions: Array<{
    version: string
    date: string
    note: string
    author: string
    state: RuleVersion['state']
    diffs?: VersionDiff[]
  }>
  edgeNodes: Array<{ node: string; version: string; state: EdgeSyncState }>
  sourceVersion?: string
}

export function mapRule(dto: unknown): SafetyRule {
  const d = (dto ?? {}) as Partial<RuleDto>
  const params: RuleParam[] = Array.isArray(d.params)
    ? d.params.map((p) => ({ label: p.label, value: p.value, hint: p.hint, danger: p.danger }))
    : []
  const versions: RuleVersion[] = Array.isArray(d.versions)
    ? d.versions.map((v) => ({
        version: v.version,
        date: v.date,
        note: v.note,
        author: v.author,
        state: v.state,
        diffs: Array.isArray(v.diffs) ? v.diffs : undefined,
      }))
    : []
  const edgeNodes: EdgeNodeState[] = Array.isArray(d.edgeNodes)
    ? d.edgeNodes.map((n) => ({ node: n.node, version: n.version, state: n.state }))
    : []
  return {
    id: d.id ?? '',
    name: d.name ?? '',
    category: d.category ?? '人员安全',
    areas: Array.isArray(d.areas) ? d.areas : [],
    version: d.version ?? 'v1.0',
    platformVersion: d.platformVersion ?? d.version ?? 'v1.0',
    risk: d.risk ?? '一般',
    status: d.status ?? '草稿',
    updatedAt: d.updatedAt ?? '',
    owner: d.owner ?? '',
    approver: d.approver ?? '待审批',
    effectiveAt: d.effectiveAt ?? '—',
    params,
    actions: Array.isArray(d.actions) ? d.actions : [],
    highRisk: !!d.highRisk,
    relatedModules: Array.isArray(d.relatedModules) ? d.relatedModules : ['告警中心'],
    description: d.description ?? '',
    versions,
    edgeNodes,
  }
}

export function mapRuleList(dto: unknown): SafetyRule[] {
  const d = (dto ?? {}) as { list?: unknown[] }
  return Array.isArray(d.list) ? d.list.map(mapRule) : []
}

export interface RuleMetricsDto {
  active: number
  review: number
  draft: number
  mismatch: number
  changedToday: number
}

export function mapRuleMetrics(dto: unknown): RuleMetricsDto {
  const d = (dto ?? {}) as Partial<RuleMetricsDto>
  return {
    active: d.active ?? 0,
    review: d.review ?? 0,
    draft: d.draft ?? 0,
    mismatch: d.mismatch ?? 0,
    changedToday: d.changedToday ?? 0,
  }
}

export function mapSimulation(dto: unknown): SimulationResult {
  const d = (dto ?? {}) as Partial<SimulationResult>
  return {
    level: d.level ?? '安全',
    matchedRule: d.matchedRule ?? '',
    thresholdNote: d.thresholdNote ?? '',
    actions: Array.isArray(d.actions) ? d.actions : [],
    escalation: d.escalation ?? '',
  }
}

export interface RuleConflictDto {
  ruleA: string
  ruleB: string
  ruleAName?: string
  ruleBName?: string
  area: string
  deviceKind: string
  paramLabel: string
  field?: string
  valueA: string
  valueB: string
  highRisk: boolean
  blocking?: boolean
  severity?: string
  desc?: string
}

export interface MappedRuleConflict {
  ruleA: string
  ruleB: string
  area: string
  deviceKind: string
  paramLabel: string
  valueA: string
  valueB: string
  highRisk: boolean
  desc?: string | undefined
}

export function mapConflicts(dto: unknown): MappedRuleConflict[] {
  const d = (dto ?? {}) as { conflicts?: RuleConflictDto[] }
  if (!Array.isArray(d.conflicts)) return []
  return d.conflicts.map((c) => ({
    ruleA: c.ruleA,
    ruleB: c.ruleB,
    area: c.area,
    deviceKind: c.deviceKind,
    paramLabel: c.paramLabel,
    valueA: c.valueA,
    valueB: c.valueB,
    highRisk: !!c.highRisk,
    desc: c.desc,
  }))
}
