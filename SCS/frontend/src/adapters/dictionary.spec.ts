import { describe, expect, it } from 'vitest'
import { adaptDictionaries, emptyDictionaries, isDictionaryKey } from './dictionary'

describe('dictionary adapter', () => {
  it('adapts the real backend dictionary response without changing codes or order', () => {
    const adapted = adaptDictionaries({
      areas: [
        { code: 'LOADING_AREA_A', name: '装卸区 A' },
        { code: 'VEHICLE_LANE', name: '车辆通道' },
      ],
      teams: [{ code: 'LOADING_TEAM_1', name: '装卸一班' }],
      assignees: [
        { id: 'USR-001', name: '李娜', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
        { id: 'USR-005', name: '刘志明', teamCode: 'LOADING_TEAM_2', teamName: '装卸二班', demoUnverified: true },
      ],
    })

    expect(adapted.areas).toEqual([
      { value: '装卸区 A', label: '装卸区 A', code: 'LOADING_AREA_A' },
      { value: '车辆通道', label: '车辆通道', code: 'VEHICLE_LANE' },
    ])
    expect(adapted.teams).toEqual([
      { value: '装卸一班', label: '装卸一班', code: 'LOADING_TEAM_1' },
    ])
    expect(adapted.assignees[0]).toEqual({
      value: 'USR-001',
      label: '李娜',
      team: '安全管理组',
      teamCode: 'SAFETY_MANAGEMENT',
      demoUnverified: false,
      code: 'USR-001',
    })
    expect(adapted.assignees[1]?.demoUnverified).toBe(true)
  })

  it('defaults missing demoUnverified to false', () => {
    const adapted = adaptDictionaries({
      assignees: [{ id: 'USR-002', name: '王建国', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组' }],
    })
    expect(adapted.assignees[0]?.demoUnverified).toBe(false)
  })

  it('treats omitted dictionaries as empty arrays', () => {
    expect(adaptDictionaries({})).toEqual(emptyDictionaries())
  })

  it('recognizes backend dictionary keys', () => {
    expect(isDictionaryKey('assignees')).toBe(true)
    expect(isDictionaryKey('alertStatus')).toBe(false)
  })
})
