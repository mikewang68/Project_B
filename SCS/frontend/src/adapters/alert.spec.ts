import { describe, expect, it } from 'vitest'
import {
  mapAlertEvent,
  mapAlertEvidence,
  mapAlertLevel,
  mapAlertMetricsDto,
  mapAlertPage,
  mapAlertStatus,
  nextRiskLevel,
  remainingSecByDeadline,
} from './alert'

describe('alert adapters', () => {
  it('maps metrics dto with numeric fallback', () => {
    expect(mapAlertMetricsDto({ total: 12, pending: 3, active: 6, severe: 4, urgent: 1, closed: 3 })).toEqual({
      total: 12, pending: 3, active: 6, severe: 4, urgent: 1, closed: 3,
    })
    expect(mapAlertMetricsDto(undefined)).toEqual({ total: 0, pending: 0, active: 0, severe: 0, urgent: 0, closed: 0 })
    expect(mapAlertMetricsDto({ total: 'x' })).toMatchObject({ total: 0 })
  })

  it('whitelists risk level and status', () => {
    expect(mapAlertLevel('紧急')).toBe('紧急')
    expect(mapAlertLevel('未知')).toBe('一般')
    expect(mapAlertStatus('待复核')).toBe('待复核')
    expect(mapAlertStatus(null)).toBe('待确认')
  })

  it('accepts the five evidence kinds and rejects unknown shapes', () => {
    expect(mapAlertEvidence({ kind: 'personnel', track: [] })?.kind).toBe('personnel')
    expect(mapAlertEvidence({ kind: 'system-metric', metrics: [] })?.kind).toBe('system-metric')
    expect(mapAlertEvidence({ kind: 'weird' })).toBeUndefined()
    expect(mapAlertEvidence(null)).toBeUndefined()
  })

  it('computes remaining seconds from absolute deadline', () => {
    const now = new Date('2026-09-05T00:00:00+08:00')
    expect(remainingSecByDeadline('2026-09-05T00:05:00+08:00', now)).toBe(300)
    expect(remainingSecByDeadline('2026-09-04T23:59:00+08:00', now)).toBe(-60)
    expect(remainingSecByDeadline(undefined, now)).toBeUndefined()
    expect(remainingSecByDeadline('not-a-date', now)).toBeUndefined()
  })

  it('maps a full alert dto: deadline wins over backend remainingSec, arrays normalized', () => {
    const dto = {
      id: 'ALM-1', title: 't', risk: '严重', eventType: 'e', time: '13:21:08', area: 'A',
      target: '赵磊', source: '人员安全', status: '处理中', assignee: '王建国',
      slaRemainingSec: 999, slaDeadline: '2026-09-05T00:05:00+08:00',
      ruleId: 'R1', ruleVersion: 'v1', durationSec: 3,
      evidence: { kind: 'collision', distance: 4.2, relSpeed: 1.8, trend: [1, 2], radar: 'ok', brakeDistance: 'b' },
      linkage: [{ id: 'plc', label: 'PLC', state: 'failed', detail: 'x' }, { id: 'bad', state: 'unknown' }],
      timeline: [{ time: '13:21:08', text: 'a', state: 'done' }, { text: 'b', state: 'active' }],
      linkageAvailable: true,
    }
    const event = mapAlertEvent(dto)
    expect(event.slaRemainingSec).toBe(remainingSecByDeadline(dto.slaDeadline))
    expect(event.linkage).toHaveLength(2)
    const secondStep = event.linkage[1]!
    expect(secondStep.state).toBe('wait')
    const secondNode = event.timeline[1]!
    expect(secondNode.time).toBe('')
    expect(secondNode.state).toBe('active')
    expect(event.evidence.kind).toBe('collision')
    expect(event.slaDeadline).toBe(dto.slaDeadline)
  })

  it('maps paginated payload list', () => {
    const page = mapAlertPage({ page: 2, pageSize: 10, total: 1, list: [{ id: 'A', risk: '预警', status: '已关闭' }] })
    expect(page.total).toBe(1)
    const first = page.list[0]!
    expect(first.id).toBe('A')
    expect(first.risk).toBe('预警')
  })

  it('escalates one level at a time and caps at 紧急', () => {
    expect(nextRiskLevel('一般')).toBe('预警')
    expect(nextRiskLevel('严重')).toBe('紧急')
    expect(nextRiskLevel('紧急')).toBe('紧急')
  })
})
