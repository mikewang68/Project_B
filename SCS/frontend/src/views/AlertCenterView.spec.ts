import { flushPromises, shallowMount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AlertCenterView from './AlertCenterView.vue'
import { alertApi } from '@/api/alerts'
import type { AlertEvent } from '@/types/alert'

const live = vi.hoisted(() => ({
  alert: undefined as undefined | ((event: { type: string }) => void),
  reconnect: undefined as undefined | (() => unknown),
}))

vi.mock('@/api/alerts', () => ({
  alertApi: {
    getAlertMetrics: vi.fn(),
    getAlerts: vi.fn(),
    getAlertDetail: vi.fn(),
  },
}))

vi.mock('@/stores/live', () => ({
  useLiveStore: () => ({
    onAlertEvent: (callback: (event: { type: string }) => void) => {
      live.alert = callback
      return () => { live.alert = undefined }
    },
    onReconnected: (callback: () => unknown) => {
      live.reconnect = callback
      return () => { live.reconnect = undefined }
    },
  }),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('element-plus/es', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() },
  ElPagination: { name: 'ElPagination', template: '<div />' },
}))

const METRICS = { total: 1, pending: 0, active: 1, severe: 0, urgent: 0, closed: 0 }
const ROW: AlertEvent = {
  id: 'ALM-1', title: '事件', risk: '一般', eventType: 'AI违规', time: '2026-11-18 10:00:00',
  area: '装卸区 A', target: '王建国', source: 'AI违规', status: '处理中', assignee: '李娜',
  ruleId: 'RULE-01', ruleVersion: 'v1.0', durationSec: 0,
  evidence: { kind: 'system-metric', metrics: [], description: '' },
  linkageAvailable: false, linkage: [], timeline: [],
}

function mountView() {
  return shallowMount(AlertCenterView, {
    global: {
      directives: { loading: {} },
      stubs: { 'el-pagination': true },
    },
  })
}

describe('alert center live refresh', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.mocked(alertApi.getAlertMetrics).mockResolvedValue(METRICS)
    vi.mocked(alertApi.getAlerts).mockResolvedValue({ list: [ROW], total: 1, page: 1, pageSize: 10 })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('ignores non-alert live events so personnel position pushes do not refresh the page', async () => {
    const wrapper = mountView()
    await flushPromises()
    const metricsCalls = vi.mocked(alertApi.getAlertMetrics).mock.calls.length
    const listCalls = vi.mocked(alertApi.getAlerts).mock.calls.length

    live.alert?.({ type: 'person.moved' })
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    expect(vi.mocked(alertApi.getAlertMetrics)).toHaveBeenCalledTimes(metricsCalls)
    expect(vi.mocked(alertApi.getAlerts)).toHaveBeenCalledTimes(listCalls)

    live.alert?.({ type: 'alert.changed' })
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()
    expect(vi.mocked(alertApi.getAlertMetrics)).toHaveBeenCalledTimes(metricsCalls + 1)
    // one list request + one options request
    expect(vi.mocked(alertApi.getAlerts)).toHaveBeenCalledTimes(listCalls + 2)
    wrapper.unmount()
  })

  it('keeps current rows when the background refresh fails', async () => {
    const wrapper = mountView()
    await flushPromises()
    vi.mocked(alertApi.getAlerts).mockRejectedValue(new Error('network down'))

    live.alert?.({ type: 'alert.changed' })
    await vi.advanceTimersByTimeAsync(400)
    await flushPromises()

    const table = wrapper.findComponent({ name: 'AlertTable' })
    expect(table.props('alerts')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('告警列表加载失败')
    wrapper.unmount()
  })
})
