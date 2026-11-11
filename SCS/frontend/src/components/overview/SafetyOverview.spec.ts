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
  template: '<div data-test="map-stub" />',
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

  it('keeps the map free of demo risk controls', async () => {
    const wrapper = mountOverview()
    await flushPromises()
    expect(wrapper.find('[data-test="toggle-risk"]').exists()).toBe(false)
  })
})
