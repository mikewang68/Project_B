import { describe, expect, it } from 'vitest'
import { mapLivePositions, mapPerson, mapPersonnelList, mapTrack } from './personnel'

describe('personnel adapter', () => {
  it('maps backend dto to PersonnelRecord, braceletId -> bracelet', () => {
    const p = mapPerson({
      id: 'P-ZHAO', jobNo: 'P-24018', name: '赵磊', team: '装卸一班', area: '装卸区 A',
      braceletId: 'WB-018', battery: 78, status: '在线', braceletStatus: '在线',
      positioningQuality: '优秀', risk: '高风险', state: 'danger', x: 53.5, y: 22.5,
      coordinate: 'A-03', distanceToday: 5.8, alertsToday: 1, lastUpdated: '刚刚', activeAlertIds: ['ALM-1'],
    })
    expect(p.bracelet).toBe('WB-018')
    expect(p.braceletId).toBe('WB-018')
    expect(p.risk).toBe('高风险')
    expect(p.state).toBe('danger')
    expect(p.activeAlertIds).toEqual(['ALM-1'])
  })

  it('fills safe defaults for malformed input', () => {
    const p = mapPerson(undefined)
    expect(p.battery).toBe(0)
    expect(p.risk).toBe('正常')
    expect(p.state).toBe('normal')
    expect(p.activeAlertIds).toBeUndefined()
  })

  it('maps list stats and track / live positions', () => {
    const list = mapPersonnelList({ stats: { online: 6, abnormal: 3, bandOffline: 1, lowBattery: 2 }, list: [{ id: 'a' }, { id: 'b' }] })
    expect(list.stats.online).toBe(6)
    expect(list.list).toHaveLength(2)
    expect(mapTrack({ points: [{ x: 1, y: 2, time: '22:00' }] })[0]!.time).toBe('22:00')
    expect(mapTrack(undefined)).toEqual([])
    const live = mapLivePositions({ list: [{ id: 'a', x: 1, y: 2, battery: 90 }] })
    expect(live[0]!.battery).toBe(90)
  })
})
