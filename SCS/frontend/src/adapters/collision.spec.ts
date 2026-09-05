import { describe, expect, it } from 'vitest'
import { mapDevice, mapDeviceList, mapPair, mapSimulate, mapTrend } from './collision'

describe('collision adapter', () => {
  it('maps a device with optional ids and coordinates', () => {
    const d = mapDevice({
      id: 'VEH-07', name: '转运车辆 07', type: '转运车辆', area: '车辆通道', status: '运行中',
      speed: 6.2, risk: '严重', relatedEquipment: '翻箱机 02', relatedEquipmentId: 'TIP-02',
      latestAlertId: 'ALM-1', x: 39, y: 53,
    })
    expect(d.risk).toBe('严重')
    expect(d.relatedEquipmentId).toBe('TIP-02')
    expect(d.x).toBe(39)
    expect(mapDevice(undefined).risk).toBe('安全')
    expect(mapDeviceList({ list: [{}, {}] })).toHaveLength(2)
  })

  it('maps pair risk, radar flag and steps', () => {
    const pair = mapPair({ distance: 2.8, risk: '紧急', radarDown: false, steps: [{ id: 'plc', label: 'PLC', state: 'failed', detail: '超时' }] })
    expect(pair.distance).toBe(2.8)
    expect(pair.risk).toBe('紧急')
    expect(pair.steps[0]!.state).toBe('failed')
    expect(mapPair(undefined).distance).toBe(12.6)
  })

  it('maps simulate result with nested device pair', () => {
    const r = mapSimulate({
      distance: 5.4, risk: '严重', deviceStopped: false, controlFailure: false, plcStatus: '待命',
      device: { id: 'VEH-07', type: '转运车辆' }, related: { id: 'TIP-02', type: '翻箱机' },
      steps: [],
    })
    expect(r.device.id).toBe('VEH-07')
    expect(r.related.id).toBe('TIP-02')
    expect(mapTrend({ points: [{ label: '现在', value: 5.4 }] })[0]!.value).toBe(5.4)
  })
})
