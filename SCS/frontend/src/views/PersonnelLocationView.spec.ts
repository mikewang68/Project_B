import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PersonnelLocationView from './PersonnelLocationView.vue'
import { personnelApi } from '@/api/personnel'
import type { UwbStationHealth } from '@/types/personnel'
const live = vi.hoisted(() => ({ receive: undefined as undefined | ((value: Record<string, unknown>) => void) }))

vi.mock('@/api/personnel', () => ({
  personnelApi: { list: vi.fn(), getConfig: vi.fn(), uwbStations: vi.fn() },
}))
vi.mock('@/api/fences', () => ({ fenceApi: { list: vi.fn().mockResolvedValue([]) } }))
vi.mock('@/api/live', () => ({
  isDomainLiveEvent: () => true,
  sharedLiveSocket: { connect: vi.fn(), onMessage: (callback: (value: Record<string, unknown>) => void) => { live.receive = callback; return () => { live.receive = undefined } } },
}))

function health(): UwbStationHealth {
  return {
    summary: { total: 3, online: 2, minSnrDb: 18, allClockSynced: false },
    stations: [
      { stationId: 'BASE-OK', online: true, clockSynced: true, powerOk: true, snrDb: 30, tagCount: 2, lastSeen: '2026-11-02T12:00:00Z' },
      { stationId: 'BASE-WARN', online: true, clockSynced: false, powerOk: true, snrDb: 18, tagCount: 1, lastSeen: '2026-11-02T12:00:00Z' },
      { stationId: 'BASE-OFF', online: false, clockSynced: true, powerOk: true, snrDb: 20, tagCount: 0, lastSeen: '2026-11-02T11:00:00Z' },
    ],
  }
}

describe('personnel UWB health from the running station API', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(personnelApi.list).mockResolvedValue({ list: [], stats: { online: 0, abnormal: 0, bandOffline: 0, lowBattery: 0 } })
    vi.mocked(personnelApi.getConfig).mockRejectedValue(new Error('not configured'))
    vi.mocked(personnelApi.uwbStations).mockResolvedValue(health())
  })
  afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

  it('reloads authoritative configuration for a scoped refresh-only notification', async () => {
    const wrapper = shallowMount(PersonnelLocationView)
    await flushPromises()
    expect(personnelApi.getConfig).toHaveBeenCalledTimes(1)
    live.receive?.({ type: 'person.config.changed', data: { refresh: true } })
    await flushPromises()
    expect(personnelApi.getConfig).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('shows real summary and distinguishes offline, clock and power failures', async () => {
    const wrapper = shallowMount(PersonnelLocationView)
    await flushPromises()
    expect(wrapper.find('.module-stats').text()).toContain('2/3')
    const stations = wrapper.findAll('.uwb-station-chip')
    expect(stations[0]!.text()).toContain('30 dB')
    expect(stations[0]!.classes()).not.toContain('is-warn')
    expect(stations[1]!.classes()).toContain('is-warn')
    expect(stations[2]!.classes()).toContain('is-offline')
    expect(stations[2]!.text()).toContain('离线')
    expect(wrapper.find('.uwb-station-summary').text()).toContain('时钟异常')
    expect(wrapper.find('.uwb-station-summary').text()).toContain('18 dB')
    const recovered = health()
    recovered.stations[1]!.clockSynced = true
    recovered.stations[1]!.powerOk = false
    vi.mocked(personnelApi.uwbStations).mockImplementation(async () => structuredClone(recovered))
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(wrapper.findAll('.uwb-station-chip')[1]!.classes()).toContain('is-warn')
    recovered.stations[1]!.powerOk = true
    recovered.summary.allClockSynced = true
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(wrapper.findAll('.uwb-station-chip')[1]!.classes()).not.toContain('is-warn')
    expect(wrapper.find('.uwb-station-summary').text()).toContain('时钟同步')
    wrapper.unmount()
  })

  it('polls every five seconds, clears unavailable health, and stops on unmount', async () => {
    const wrapper = shallowMount(PersonnelLocationView)
    await flushPromises()
    expect(personnelApi.uwbStations).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(4999)
    expect(personnelApi.uwbStations).toHaveBeenCalledTimes(1)
    vi.mocked(personnelApi.uwbStations).mockRejectedValueOnce(new Error('offline'))
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(wrapper.find('.uwb-station-bar').exists()).toBe(false)
    expect(wrapper.find('.module-stats').text()).toContain('—')
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(wrapper.find('.uwb-station-bar').exists()).toBe(true)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(10000)
    expect(personnelApi.uwbStations).toHaveBeenCalledTimes(3)
  })

  it('renders no synthetic station or minimum signal when the service reports none', async () => {
    vi.mocked(personnelApi.uwbStations).mockResolvedValue({
      summary: { total: 0, online: 0, minSnrDb: null, allClockSynced: true }, stations: [],
    })
    const wrapper = shallowMount(PersonnelLocationView)
    await flushPromises()
    expect(wrapper.find('.module-stats').text()).toContain('0/0')
    expect(wrapper.find('.uwb-station-bar').exists()).toBe(false)
    wrapper.unmount()
  })
})
