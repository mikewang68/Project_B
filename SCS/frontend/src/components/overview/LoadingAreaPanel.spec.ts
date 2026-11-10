import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { zonesApi } from '@/api/zones'
import type { ZoneState } from '@/types/zone'
import LoadingAreaPanel from './LoadingAreaPanel.vue'

const live = vi.hoisted(() => ({
  onAlertEvent: vi.fn<(listener: (event: { type: string }) => void) => () => void>(),
  onReconnected: vi.fn<(listener: () => void) => () => void>(),
}))

vi.mock('@/api/zones', () => ({ zonesApi: { list: vi.fn() } }))
vi.mock('@/stores/live', () => ({ useLiveStore: () => live }))

function snapshots(
  personnelWorking: boolean | null,
  vehiclePresent: boolean | null,
  distanceMm: number | null,
): ZoneState[] {
  return [
    { code: 'LOADING-A', name: '装卸区 A', sensorType: '限位开关', personnelWorking,
      vehiclePresent: null, distanceMm: null, signal: '限位开关', updatedAt: null, online: true },
    { code: 'LOADING-B', name: '装卸区 B', sensorType: '位置传感器', personnelWorking: null,
      vehiclePresent, distanceMm, signal: '位置传感器', updatedAt: null, online: true },
  ]
}

function mountPanel() {
  return mount(LoadingAreaPanel, {
    global: { stubs: { ElIcon: { template: '<span><slot /></span>' } } },
  })
}

async function notifyZoneChanged() {
  live.onAlertEvent.mock.calls[0]![0]({ type: 'zone.changed' })
  await vi.advanceTimersByTimeAsync(300)
  await flushPromises()
}

describe('loading area signal states', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetAllMocks()
    live.onAlertEvent.mockReturnValue(() => undefined)
    live.onReconnected.mockReturnValue(() => undefined)
  })

  afterEach(() => vi.useRealTimers())

  it('renders online null signals as unknown for both areas regardless of signal text', async () => {
    const data = snapshots(null, null, null)
    data[0]!.signal = '空闲'
    data[1]!.signal = '无车'
    vi.mocked(zonesApi.list).mockResolvedValue(data)
    const wrapper = mountPanel()
    try {
      await flushPromises()
      const rows = wrapper.findAll('li')
      expect(rows).toHaveLength(2)
      for (const row of rows) {
        expect(row.attributes('data-tone')).toBe('unknown')
        expect(row.get('em').text()).toBe('状态未知')
        expect(row.get('em').text()).not.toMatch(/空闲|无车/)
      }
    } finally {
      wrapper.unmount()
    }
  })

  it('clears active signals and old distance on a fault update and renders recovered values', async () => {
    vi.mocked(zonesApi.list).mockResolvedValueOnce(snapshots(true, true, 2400))
    const wrapper = mountPanel()
    try {
      await flushPromises()
      let rows = wrapper.findAll('li')
      expect(rows[0]!.get('em').text()).toBe('作业中')
      expect(rows[1]!.get('em').text()).toBe('有车')
      expect(rows.every((row) => row.attributes('data-tone') === 'on')).toBe(true)
      expect(rows[1]!.text()).toContain('2.4m')

      vi.mocked(zonesApi.list).mockResolvedValueOnce(snapshots(null, null, null))
      await notifyZoneChanged()
      rows = wrapper.findAll('li')
      for (const row of rows) {
        expect(row.attributes('data-tone')).toBe('unknown')
        expect(row.get('em').text()).toBe('状态未知')
      }
      expect(rows[1]!.text()).not.toContain('2.4m')
      expect(rows[1]!.get('small').text()).not.toMatch(/\d+\.\d+m/)

      vi.mocked(zonesApi.list).mockResolvedValueOnce(snapshots(false, false, 1800))
      await notifyZoneChanged()
      rows = wrapper.findAll('li')
      expect(rows[0]!.get('em').text()).toBe('空闲')
      expect(rows[1]!.get('em').text()).toBe('无车')
      expect(rows.every((row) => row.attributes('data-tone') === 'off')).toBe(true)
      expect(rows[1]!.text()).toContain('1.8m')
      expect(rows[1]!.text()).not.toContain('2.4m')
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps zero distance as a valid reading', async () => {
    vi.mocked(zonesApi.list).mockResolvedValue(snapshots(false, false, 0))
    const wrapper = mountPanel()
    try {
      await flushPromises()
      expect(wrapper.findAll('li')[1]!.text()).toContain('0.0m')
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps offline areas in the existing not-connected state', async () => {
    vi.mocked(zonesApi.list).mockResolvedValue(
      snapshots(null, null, null).map((zone) => ({ ...zone, online: false })),
    )
    const wrapper = mountPanel()
    try {
      await flushPromises()
      for (const row of wrapper.findAll('li')) {
        expect(row.attributes('data-tone')).toBe('unknown')
        expect(row.get('em').text()).toBe('未接入')
      }
    } finally {
      wrapper.unmount()
    }
  })
})
