import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SafetyScreenView from './SafetyScreenView.vue'

const live = vi.hoisted(() => ({
  alert: undefined as undefined | ((event: { type: string }) => void),
}))
const incident = vi.hoisted(() => ({
  incidents: [] as unknown[],
  overview: null,
  breachActive: false,
  loadAll: vi.fn().mockResolvedValue(undefined),
  scheduleRefresh: vi.fn(),
  acknowledgeCritical: vi.fn(),
  spotlightCritical: vi.fn().mockResolvedValue(false),
}))

vi.mock('@/stores/incident', () => ({ useIncidentStore: () => incident }))
vi.mock('@/stores/live', () => ({
  useLiveStore: () => ({
    status: 'connected',
    onAlertEvent: (callback: (event: { type: string }) => void) => {
      live.alert = callback
      return () => { live.alert = undefined }
    },
    onReconnected: () => () => undefined,
  }),
}))
vi.mock('element-plus/es', () => ({
  ElMessage: { info: vi.fn(), error: vi.fn(), success: vi.fn() },
}))
vi.mock('@/components/screen/BigScreenHeader.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/BigScreenMetrics.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/BigScreenMap.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/BigScreenAlertFeed.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/BigScreenTrend.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/BigScreenDeviceHealth.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/screen/CriticalAlertPopup.vue', () => ({ default: { template: '<div />' } }))

describe('safety screen live refresh', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

  it('ignores non-alert live events', async () => {
    const wrapper = shallowMount(SafetyScreenView)
    await flushPromises()
    expect(incident.loadAll).toHaveBeenCalledTimes(1)

    live.alert?.({ type: 'person.moved' })
    expect(incident.scheduleRefresh).not.toHaveBeenCalled()

    live.alert?.({ type: 'alert.changed' })
    expect(incident.scheduleRefresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
