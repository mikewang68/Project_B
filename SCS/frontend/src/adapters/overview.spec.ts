import { describe, expect, it } from 'vitest'
import {
  mapDistribution,
  mapFeed,
  mapFences,
  mapOverviewMap,
  mapSummary,
  mapTrend,
} from './overview'

describe('overview adapter', () => {
  it('maps summary with demo flags', () => {
    const s = mapSummary({ onDuty: 128, activeAlerts: 9, urgentAlerts: 1, unknown: 1 })
    expect(s.onDuty).toBe(128)
    expect(s.activeAlerts).toBe(9)
    expect(s.pendingAi).toBe(0)
    expect(s.onDutyDemo).toBe(false)
  })

  it('maps map people/equipment with live overlay', () => {
    const map = mapOverviewMap({
      baseDemo: true, liveOverlay: true,
      people: [{ id: 'P-ZHAO', name: '赵磊', team: '装卸一班', status: '在线', battery: 78, area: '装卸区 A', risk: '高风险', state: 'danger', x: 57, y: 26, liveOverlay: true }],
      equipment: [{ id: 'E-CRANE', name: '1#龙门吊', type: 'crane', state: 'normal', x: 60, y: 22 }],
      fences: [{ id: 'F-1', name: '龙门吊动态禁区', tone: 'danger', area: '龙门吊作业区', polygon: [{ x: 38, y: 5 }] }],
    })
    expect(map.people[0]!.liveOverlay).toBe(true)
    expect(map.people[0]!.state).toBe('danger')
    expect(map.equipment[0]!.type).toBe('crane')
    const fences = mapFences(map)
    expect(fences[0]!.polygon).toEqual([{ x: 38, y: 5 }])
    expect(fences[0]!.tone).toBe('danger')
    expect(fences[0]!.status).toBe('已生效')
  })

  it('converts ISO feed time to HH:mm:ss and keeps timeline', () => {
    const feed = mapFeed({
      list: [{
        id: 'ALM-1', title: '人员越界', level: '紧急', summary: 's',
        time: '2026-09-05T22:14:08+08:00', area: '装卸区 A', objectName: '赵磊', status: '处理中',
        timeline: [{ time: '22:14:08', title: '检测', detail: '进行中' }],
      }],
    })
    expect(feed[0]!.time).toBe('22:14:08')
    expect(feed[0]!.timeline[0]!.title).toBe('检测')
  })

  it('maps trend / distribution defensively', () => {
    expect(mapTrend({ demo: true, points: [{ label: '周一', total: 9, high: 2 }] }).points[0]!.total).toBe(9)
    expect(mapDistribution({ items: [{ type: '人员越界', count: 32 }] }).items[0]!.count).toBe(32)
    expect(mapTrend(null).points).toEqual([])
    expect(mapDistribution(null).items).toEqual([])
  })
})
