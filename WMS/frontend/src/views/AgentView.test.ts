import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AgentView from './AgentView.vue'

const mocks = vi.hoisted(() => ({ status: vi.fn(), chat: vi.fn(), utilization: vi.fn(), history: vi.fn(), alerts: vi.fn(), schedules: vi.fn(), mails: vi.fn(), actions: new Set<string>() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { tab: 'alerts' } }) }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ hasPermission: () => true, canWrite: () => true, canAction: (_menu: string, action: string) => mocks.actions.has(action), centralAuthorization: true, user: { centralAdminLevel: 'L3_SUBSYSTEM', tenant: { currentWarehouse: { name: '测试仓库' }, currentOwner: { name: '测试货主' } } } }) }))
vi.mock('@/api/agent', () => ({ agentStatus: mocks.status, agentChat: mocks.chat, agentUtilization: mocks.utilization,
  agentHistory: mocks.history, agentAlerts: mocks.alerts, agentSchedules: mocks.schedules, agentMails: mocks.mails,
  readAgentAlert: vi.fn(), saveAgentSchedule: vi.fn(), runAgentSchedule: vi.fn(), retryAgentMail: vi.fn(), agentWeights: vi.fn(), saveAgentWeight: vi.fn() }))

let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks()
  mocks.actions = new Set(['VIEW', 'ADD', 'EDIT', 'FAULT_ALARM', 'RECEIVE_EMAIL'])
  mocks.status.mockResolvedValue({ modelConfigured: true, model: 'local-test', mailConfigured: false })
  for (const mock of [mocks.history, mocks.alerts, mocks.schedules, mocks.mails]) mock.mockResolvedValue([])
  mocks.utilization.mockResolvedValue({ total_locations: 14, occupied_locations: 12, occupancy_percent: 85.71,
    known_weight_kg: 683300, missing_weight_rows: 5, note: '未知重量不推算承重', locations: [], history: [] })
})
afterEach(() => wrapper?.unmount())
const render = () => shallowMount(AgentView, { global: { plugins: [ElementPlus], renderStubDefaultSlot: true,
  stubs: { ElTableColumn: { template: '<div />' } } } })

describe('warehouse agent page', () => {
  it('does not submit a persistent chat when only VIEW is granted', async () => {
    mocks.actions = new Set(['VIEW'])
    wrapper = render(); await flushPromises()
    const state = wrapper.vm as unknown as { question: string; send: () => Promise<void>; canChat: boolean; manage: boolean }
    state.question = '查看库存'; await state.send()
    expect(state.canChat).toBe(false)
    expect(state.manage).toBe(false)
    expect(mocks.chat).not.toHaveBeenCalled()
  })
  it('does not turn ADD into EDIT, alarm execution or mail permission', async () => {
    mocks.actions = new Set(['VIEW', 'ADD'])
    wrapper = render(); await flushPromises()
    const state = wrapper.vm as unknown as { canChat: boolean; canCreate: boolean; canEdit: boolean; canRun: boolean; canEmail: boolean; canSave: boolean; editWeights: boolean }
    expect(state.canChat).toBe(true)
    expect(state.canCreate).toBe(true)
    expect(state.canEdit).toBe(false)
    expect(state.canRun).toBe(false)
    expect(state.canEmail).toBe(false)
    expect(state.canSave).toBe(false)
    expect(state.editWeights).toBe(false)
  })

  it('opens the station alerts tab from the top navigation and shows disabled mail', async () => {
    wrapper = render(); await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTabs' }).props('modelValue')).toBe('alerts')
    expect(wrapper.text()).toContain('邮件待配置')
    expect(wrapper.text()).toContain('测试仓库 / 测试货主')
  })
  it('loads remaining panels when one optional request fails', async () => {
    mocks.mails.mockRejectedValue(new Error('邮件记录暂时不可用'))
    wrapper = render(); await flushPromises()
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toBe('邮件记录暂时不可用')
    expect(wrapper.text()).toContain('85.71%')
    expect(mocks.utilization).toHaveBeenCalledOnce()
  })
  it('renders model content as text rather than executable HTML', async () => {
    mocks.chat.mockResolvedValue({ id: 1, answer: '<script>window.agentInjection=true</script>', state: 'COMPLETED', events: [
      { tool: 'search_inventory', label: '当前库存', arguments: {}, result: [{ good_code: 'FD-REBAR12', relevance: 95, order_code: 'R-DATABASE' }] }
    ], model: 'local-test' })
    wrapper = render(); await flushPromises()
    const state = wrapper.vm as unknown as { question: string; send: () => Promise<void> }
    state.question = '查钢材'; await state.send(); await flushPromises()
    expect(wrapper.find('.agent-answer').text()).toContain('<script>')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ElCollapse' }).props('modelValue')).toEqual([0])
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')[0].order_code).toBe('R-DATABASE')
    expect(mocks.chat).toHaveBeenCalledWith('查钢材', [])
  })
})
