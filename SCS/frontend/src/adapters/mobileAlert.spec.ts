import { describe, expect, it } from 'vitest'
import { mapAlertToMobileIncident, mapMobileStatus } from './mobileAlert'

function baseDto(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'ALM-20260904-005',
    title: '人员翻越护栏',
    risk: '严重',
    eventType: '人员越界',
    time: '22:14:08',
    occurredAt: '2026-09-04T22:14:08+08:00',
    area: '装卸区 A',
    target: '赵磊',
    source: '人员安全',
    status: '待处理',
    assignee: '王建国',
    ruleId: 'RULE-PER-001',
    ruleVersion: 'v3.2',
    durationSec: 8,
    linkageAvailable: false,
    linkage: [],
    timeline: [{ time: '22:14:08', text: '检测到人员进入危险区域', state: 'done' }],
    ...overrides,
  }
}

describe('mapMobileStatus', () => {
  it('maps main status + mobile stage to the six mobile statuses', () => {
    expect(mapMobileStatus('待处理')).toBe('待接单')
    expect(mapMobileStatus('待处理', 'PENDING')).toBe('待接单')
    expect(mapMobileStatus('待处理', 'ACCEPTED')).toBe('已接单')
    expect(mapMobileStatus('待处理', 'ARRIVED')).toBe('已到场')
    expect(mapMobileStatus('处理中')).toBe('处理中')
    expect(mapMobileStatus('待复核')).toBe('待复核')
    expect(mapMobileStatus('已关闭')).toBe('已关闭')
    expect(mapMobileStatus('待确认')).toBe('待接单')
  })
})

describe('mapAlertToMobileIncident', () => {
  it('keeps the same alert id across the three clients and formats HH:mm', () => {
    const m = mapAlertToMobileIncident(baseDto())
    expect(m.id).toBe('ALM-20260904-005')
    expect(m.alertStatus).toBe('待处理')
    expect(m.status).toBe('待接单')
    expect(m.time).toBe('22:14')
  })

  it('derives stable demo distance/position for the same id', () => {
    const a = mapAlertToMobileIncident(baseDto())
    const b = mapAlertToMobileIncident(baseDto())
    expect(a.distanceM).toBe(b.distanceM)
    expect(a.pos).toEqual(b.pos)
    expect(a.pos.x).toBeGreaterThanOrEqual(0)
    expect(a.pos.x).toBeLessThanOrEqual(100)
  })

  it('projects accepted/arrived timestamps and mobile stage', () => {
    const m = mapAlertToMobileIncident(baseDto({
      status: '待处理',
      mobileStage: 'ARRIVED',
      acceptedAt: '2026-09-04T22:15:00+08:00',
      arrivedAt: '2026-09-04T22:18:30+08:00',
    }))
    expect(m.status).toBe('已到场')
    expect(m.acceptTime).toBe('22:15')
    expect(m.arriveTime).toBe('22:18')
  })

  it('projects the 7-step linkage into the mobile three-state card', () => {
    const m = mapAlertToMobileIncident(baseDto({
      linkageAvailable: true,
      linkage: [
        { id: 'sound-light', label: '现场声光', state: 'success' },
        { id: 'shutdown', label: '设备停机', state: 'failed' },
        { id: 'plc', label: 'PLC回执', state: 'wait' },
      ],
    }))
    expect(m.linkage?.soundLight).toBe('done')
    expect(m.linkage?.shutdown).toBe('failed')
    expect(m.linkage?.plc).toBe('idle')
    expect(m.linkage?.requested).toBe(true)
  })

  it('projects treatment measures and mock photo count from attachment', () => {
    const m = mapAlertToMobileIncident(baseDto({
      status: '待复核',
      treatment: {
        measures: ['人员已撤离', '设备已停止'],
        result: '风险已解除',
        attachment: '现场照片 2 张（Mock）',
        note: '',
        submitTime: '22:30:00',
        handler: '王建国',
      },
    }))
    expect(m.measures).toEqual(['人员已撤离', '设备已停止'])
    expect(m.riskCleared).toBe(true)
    expect(m.photos).toHaveLength(1)
    expect(m.submitTime).toBe('22:30')
  })
})
