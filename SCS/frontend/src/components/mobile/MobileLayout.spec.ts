import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MobileLayout from './MobileLayout.vue'

const live = vi.hoisted(() => ({
  alert: undefined as undefined | ((event: { type: string }) => void),
}))
const incident = vi.hoisted(() => ({
  loadAll: vi.fn().mockResolvedValue(undefined),
  scheduleRefresh: vi.fn(),
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
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/mobile/home' }),
  RouterView: { template: '<div />' },
}))

describe('mobile layout live refresh', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

  it('ignores non-alert live events', async () => {
    const wrapper = shallowMount(MobileLayout, {
      global: { stubs: { RouterView: true, RouterLink: true, 'el-icon': true } },
    })
    await flushPromises()
    expect(incident.loadAll).toHaveBeenCalledTimes(1)

    live.alert?.({ type: 'person.moved' })
    expect(incident.scheduleRefresh).not.toHaveBeenCalled()

    live.alert?.({ type: 'alert.changed' })
    expect(incident.scheduleRefresh).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
