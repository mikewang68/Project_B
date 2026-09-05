import { apiRequest, postJson, putJson } from './http'
import type { RuleCategory, RuleRisk, SafetyRule, SimulationResult } from '@/types/rule'
import {
  mapConflicts,
  mapRule,
  mapRuleList,
  mapRuleMetrics,
  mapSimulation,
  type MappedRuleConflict,
  type RuleMetricsDto,
} from '@/adapters/rule'

/** 规则配置 API Client —— 对应后端 RuleController（/api/v1/rules）。 */
export interface RuleQuery {
  keyword?: string | undefined
  category?: string | undefined
  status?: string | undefined
  risk?: string | undefined
}

export interface RuleSavePayload {
  name: string
  category: RuleCategory
  areas: string[]
  risk: RuleRisk
  owner: string
  params: Array<{ label: string; value: string; danger?: boolean; hint?: string }>
  actions: string[]
  highRisk: boolean
  submitReview?: boolean
  asNewVersion?: boolean
}

function qs<T extends object>(params: T): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    search.set(k, String(v))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export interface RuleRollbackResult {
  newVersion: string
  sourceVersion: string
  status: string
  rule: SafetyRule
}

export const ruleApi = {
  metrics: async (): Promise<RuleMetricsDto> =>
    mapRuleMetrics(await apiRequest('/rules/metrics')),

  list: async (query: RuleQuery = {}): Promise<SafetyRule[]> =>
    mapRuleList(await apiRequest(`/rules${qs(query)}`)),

  detail: async (id: string): Promise<SafetyRule> =>
    mapRule(await apiRequest(`/rules/${encodeURIComponent(id)}`)),

  create: async (payload: RuleSavePayload): Promise<SafetyRule> =>
    mapRule(await postJson('/rules', payload)),

  update: async (id: string, payload: Partial<RuleSavePayload>): Promise<SafetyRule> =>
    mapRule(await putJson(`/rules/${encodeURIComponent(id)}`, payload)),

  submit: async (
    id: string,
    body: { operator?: string; comment?: string; confirmHighRisk?: boolean } = {},
  ): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/submit`, body)),

  approve: async (
    id: string,
    body: { operator?: string; comment?: string; confirmHighRisk?: boolean } = {},
  ): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/approve`, body)),

  reject: async (id: string, body: { operator?: string; comment?: string } = {}): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/reject`, body)),

  publish: async (id: string, body: { operator?: string; nodeIds?: string[] } = {}): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/publish`, body)),

  redeliver: async (id: string, body: { nodeId?: string; operator?: string } = {}): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/redeliver`, body)),

  disable: async (id: string, body: { operator?: string } = {}): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/disable`, body)),

  versions: async (id: string): Promise<{ current: string; versions: SafetyRule['versions'] }> => {
    const dto = await apiRequest<{ current: string; versions: SafetyRule['versions'] }>(
      `/rules/${encodeURIComponent(id)}/versions`,
    )
    return { current: dto.current, versions: dto.versions ?? [] }
  },

  rollback: async (id: string, targetVersion: string): Promise<RuleRollbackResult> => {
    const dto = await postJson<{ newVersion: string; sourceVersion: string; status: string; rule: unknown }>(
      `/rules/${encodeURIComponent(id)}/rollback`,
      { targetVersion },
    )
    return {
      newVersion: dto.newVersion,
      sourceVersion: dto.sourceVersion,
      status: dto.status,
      rule: mapRule(dto.rule),
    }
  },

  simulateMismatch: async (id: string): Promise<SafetyRule> =>
    mapRule(await postJson(`/rules/${encodeURIComponent(id)}/simulate-mismatch`, {})),

  simulate: async (input: {
    ruleId?: string
    distance: number
    relSpeed: number
    direction: string
    radarQuality: number
    weather: string
  }): Promise<SimulationResult> =>
    mapSimulation(await postJson('/rules/simulate', input)),

  conflictCheck: async (ruleId?: string): Promise<MappedRuleConflict[]> =>
    mapConflicts(await postJson('/rules/conflict-check', { ruleId })),
}
