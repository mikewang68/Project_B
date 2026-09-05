import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SafetyOverview from './SafetyOverview.vue'
import { overviewApi } from '@/api/overview'

vi.mock('@/api/overview', () => ({
  overviewApi: {
    getSummary: vi.fn(),
    getMap: vi.fn(),
    getFeed: vi.fn(),
    getTrend: vi.fn(),
    getDistribution: vi.fn(),
    getAlertDetail: vi.fn(),
    simulateRisk: vi.fn(),
  },
}))

vi.mock('@/stores/live', () => ({
  useLiveStore: () => ({ onAlertEvent: () => () => undefined, onReconnected: () => () => undefined }),
}))

const statStub = {
  props: ['label', 'value'],
  template: '<div class="stat-stub" :data-label="label">{{ value }}</div>',
}
const mapStub = {
  props: ['riskActive'],
  emits: ['toggleRisk'],
  template: '<button data-test="toggle-risk" @click="$emit(\'toggleRisk\')">{{ riskActive }}</button>',
}
const feedStub = {
  props: ['alerts'],
  template: '<div data-test="top-alert">{{ alerts[0]?.title }}</div>',
}

function apiState(activeAlerts: number, urgent: number, severe: number): void {
  vi.mocked(overviewApi.getSummary).mockResolvedValue({
    onDuty: 128, deviceOnline: 36, deviceTotal: 38, activeAlerts, processingAlerts: 3,
    urgentAlerts: urgent, severeAlerts: severe, pendingAi: 5, riskyDevices: 2,
    onDutyDemo: true, deviceDemo: true, riskyDevicesDemo: true,
  })
  vi.mocked(overviewApi.getMap).mockResolvedValue({ baseDemo: true, liveOverlay: true, people: [], equipment: [], fences: [] })
  vi.mocked(overviewApi.getFeed).mockResolvedValue([
    { id: 'ALM-1', title: '人员进入龙门吊作业区域', level: '紧急', summary: 's', time: '22:14', area: '装卸区 A', objectName: '赵磊', status: '处理中', timeline: [] },
  ])
  vi.mocked(overviewApi.getTrend).mockResolvedValue({ demo: true, points: [] })
  vi.mocked(overviewApi.getDistribution).mockResolvedValue({ items: [] })
  vi.mocked(overviewApi.simulateRisk).mockResolvedValue({ active: true })
}

function mountOverview() {
  return mount(SafetyOverview, {
    global: {
      stubs: {
        SafetyStatCard: statStub,
        SafetyMap: mapStub,
        RealtimeAlertFeed: feedStub,
        RiskTrendChart: true,
        RiskDistributionChart: true,
        DeviceHealth: true,
        AlertDrawer: true,
      },
    },
  })
}

describe('safety overview backed by overview api', () => {
  beforeEach(() => apiState(9, 1, 1))
  afterEach(() => vi.clearAllMocks())

  it('renders KPI from backend summary and live alert feed', async () => {
    const wrapper = mountOverview()
    await flushPromises()
    expect(wrapper.get('[data-label="活动告警"]').text()).toBe('9')
    // 高风险事件 = 紧急 1 + 严重 1
    expect(wrapper.get('[data-label="高风险事件"]').text()).toBe('2')
    expect(wrapper.get('[data-test="top-alert"]').text()).toBe('人员进入龙门吊作业区域')
  })

  it('toggle risk calls backend simulate-risk and refreshes aggregates', async () => {
    const wrapper = mountOverview()
    await flushPromises()
    vi.mocked(overviewApi.getSummary).mockResolvedValueOnce({
      onDuty: 128, deviceOnline: 36, deviceTotal: 38, activeAlerts: 10, processingAlerts: 3,
      urgentAlerts: 2, severeAlerts: 1, pendingAi: 5, riskyDevices: 2,
      onDutyDemo: true, deviceDemo: true, riskyDevicesDemo: true,
    })
    await wrapper.get('[data-test="toggle-risk"]').trigger('click')
    await flushPromises()
    expect(overviewApi.simulateRisk).toHaveBeenCalledWith(true)
    expect(wrapper.get('[data-label="活动告警"]').text()).toBe('10')
    expect(wrapper.get('[data-label="高风险事件"]').text()).toBe('3')
  })
})
