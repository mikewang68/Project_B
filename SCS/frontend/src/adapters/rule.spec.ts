import { describe, expect, it } from 'vitest'
import { mapConflicts, mapRule, mapRuleList, mapRuleMetrics, mapSimulation } from './rule'

describe('rule adapter', () => {
  it('maps a backend rule with params/versions/edge nodes', () => {
    const r = mapRule({
      id: 'RULE-DEV-003', name: '转运车辆距离预警', category: '设备安全', areas: ['翻箱机作业区'],
      version: 'v2.4', platformVersion: 'v2.4', risk: '严重', status: '已生效', updatedAt: '2026-09-04 10:00',
      owner: '设备管理员 周海', approver: '安全总监 赵民', effectiveAt: '2026-08-18 09:00', highRisk: true,
      params: [{ label: '预警距离', value: '10m', danger: false }], actions: ['减速请求'],
      versions: [{ version: 'v2.4', date: '2026-09-04', note: '当前', author: '周海', state: '当前' }],
      edgeNodes: [{ node: 'EDGE-01', version: 'v2.4', state: 'synced' }],
    })
    expect(r.id).toBe('RULE-DEV-003')
    expect(r.highRisk).toBe(true)
    expect(r.params[0]!.label).toBe('预警距离')
    expect(r.versions).toHaveLength(1)
    expect(r.edgeNodes[0]!.node).toBe('EDGE-01')
  })

  it('defaults safely for empty payload and maps list', () => {
    const d = mapRule(undefined)
    expect(d.status).toBe('草稿')
    expect(d.params).toEqual([])
    expect(d.relatedModules).toEqual(['告警中心'])
    expect(mapRuleList({ list: [{ id: 'a' }, { id: 'b' }] })).toHaveLength(2)
    expect(mapRuleList(undefined)).toEqual([])
  })

  it('maps metrics and simulation', () => {
    expect(mapRuleMetrics({ active: 8, review: 2, draft: 3, mismatch: 1, changedToday: 0 })).toEqual({
      active: 8, review: 2, draft: 3, mismatch: 1, changedToday: 0,
    })
    expect(mapRuleMetrics(undefined).active).toBe(0)
    const sim = mapSimulation({ level: '严重风险', matchedRule: 'RULE-DEV-003 v2.4', actions: ['减速请求'] })
    expect(sim.level).toBe('严重风险')
    expect(sim.actions).toEqual(['减速请求'])
    expect(mapSimulation(undefined).level).toBe('安全')
  })

  it('maps conflicts and keeps blocking/highRisk', () => {
    const list = mapConflicts({
      conflicts: [{
        ruleA: 'RULE-DEV-003', ruleB: 'RULE-DEV-009', area: '车辆通道', deviceKind: '转运车辆',
        paramLabel: '预警距离', valueA: '10m', valueB: '8m', highRisk: true, blocking: true, desc: 'x',
      }],
    })
    expect(list).toHaveLength(1)
    expect(list[0]!.highRisk).toBe(true)
    expect(mapConflicts(undefined)).toEqual([])
  })
})
