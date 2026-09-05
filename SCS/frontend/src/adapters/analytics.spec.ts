import { describe, expect, it } from 'vitest'
import { mapDataset, mapEventPage } from './analytics'

describe('analytics adapter', () => {
  it('maps dataset and coerces nullable team seconds to 0', () => {
    const ds = mapDataset({
      kpi: [{ key: 'total', label: '事件总数', value: '12', deltaPct: 0, direction: 'flat', goodWhenDown: true, hint: '', variant: 'hero' }],
      trend: [{ date: '09/04', total: 12, high: 3, demoBaseline: false }],
      riskTypes: [{ type: '人员越界', count: 5, high: 2 }],
      areas: [{ area: '装卸区 A', total: 5, high: 2, score: 9 }],
      teams: [{ team: '装卸一班', confirmSec: null, arriveSec: 90, closeSec: null, eventCount: 5 }],
      devices: [{ deviceId: 'G-CRANE-01', name: '龙门吊 01', count: 2, primaryRisk: '设备距离风险', primaryCount: 2, trend: 'flat', recentRisk: '', typeSplit: [], recentEvents: [] }],
      persons: [{ personId: 'P-1003', name: '赵磊', team: '装卸一班', count: 1, mainType: '人员越界', recent: [] }],
      levels: [{ level: '紧急', count: 1 }],
    })
    expect(ds.kpi[0]!.value).toBe('12')
    expect(ds.trend[0]!.total).toBe(12)
    expect(ds.areas[0]!.area).toBe('装卸区 A')
    expect((ds.areas[0] as unknown as Record<string, unknown>).score).toBeUndefined()
    expect(ds.teams[0]!.confirmSec).toBe(0)
    expect(ds.teams[0]!.arriveSec).toBe(90)
    expect(ds.devices[0]!.deviceId).toBe('G-CRANE-01')
    expect(ds.events).toEqual([])
  })

  it('defaults safely for empty payload', () => {
    const ds = mapDataset(undefined)
    expect(ds.kpi).toEqual([])
    expect(ds.levels).toEqual([])
  })

  it('maps an event page', () => {
    const page = mapEventPage({ page: 2, pageSize: 5, total: 12, list: [{ id: 'ALM-1' }] })
    expect(page.total).toBe(12)
    expect(page.list).toHaveLength(1)
    expect(mapEventPage(undefined).total).toBe(0)
  })
})
