import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecoveryDialog from './RecoveryDialog.vue'
import { operationsApi, type BackendEdgeNode, type BackendRecoveryPhase } from '@/api/operations'

vi.mock('@/api/operations', () => ({
  operationsApi: {
    recover: vi.fn(),
    reconcileRules: vi.fn(),
    reconcileTime: vi.fn(),
  },
}))

vi.mock('element-plus', () => ({
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: '<div class="dialog-stub"><slot name="header" /><slot /><slot name="footer" /></div>',
  }),
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
}))

vi.mock('@element-plus/icons-vue', () => ({
  CircleCheckFilled: defineComponent({ template: '<i />' }),
  Loading: defineComponent({ template: '<i />' }),
}))

function phase(key: string, status: string, message?: string): BackendRecoveryPhase {
  const labels: Record<string, string> = {
    CONNECTIVITY: '恢复连接',
    CLOCK_RECONCILIATION: '时间对账',
    RULE_RECONCILIATION: '规则版本对账',
    EVENT_REPLAY: '缓存事件补传',
    FINAL_CHECK: '最终检查',
    ONLINE: '恢复在线',
  }
  const p: BackendRecoveryPhase = { key, label: labels[key] ?? key, status: status as BackendRecoveryPhase['status'] }
  if (message) p.message = message
  return p
}

function node(over: Partial<BackendEdgeNode> = {}): BackendEdgeNode {
  return {
    id: 'EDGE-03', name: '3 号边缘节点', area: '翻箱机区', ip: '10.24.1.13',
    status: 'RECOVERING', cloudConnected: true, autonomyActive: false,
    lastHeartbeat: '2026-09-10T12:00:00+08:00', latencyMs: 40, cpuUsage: 40, memoryUsage: 50,
    diskUsage: 60, temperature: 28, queueDepth: 1, cachedEventCount: 100,
    activeRuleVersion: 'v3.2', expectedRuleVersion: 'v3.3',
    activeFenceVersion: 'v3.2', expectedFenceVersion: 'v3.3',
    clockOffsetMs: 32, agentVersion: 'x', uptimeSec: 1, cacheParts: [],
    recoveryPhases: [
      phase('CONNECTIVITY', 'DONE'),
      phase('CLOCK_RECONCILIATION', 'DONE'),
      phase('RULE_RECONCILIATION', 'BLOCKED', '规则版本不一致'),
      phase('EVENT_REPLAY', 'WAIT'),
      phase('FINAL_CHECK', 'WAIT'),
    ],
    ...over,
  }
}

const onlineNode = () => node({
  status: 'ONLINE',
  activeRuleVersion: 'v3.3',
  activeFenceVersion: 'v3.3',
  queueDepth: 0,
  recoveryPhases: [
    phase('CONNECTIVITY', 'DONE'),
    phase('CLOCK_RECONCILIATION', 'DONE'),
    phase('RULE_RECONCILIATION', 'DONE'),
    phase('EVENT_REPLAY', 'DONE'),
    phase('FINAL_CHECK', 'DONE'),
    phase('ONLINE', 'DONE'),
  ],
})

function mountDialog() {
  return mount(RecoveryDialog, {
    props: { modelValue: false, nodeId: 'EDGE-03' },
    global: { stubs: { ElIcon: true } },
  })
}

describe('RecoveryDialog driven by backend recovery phases', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(() => vi.restoreAllMocks())

  it('opens, calls recover and renders blocked RULE reconciliation actions (reconnect UI)', async () => {
    vi.mocked(operationsApi.recover).mockResolvedValueOnce(node())
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(operationsApi.recover).toHaveBeenCalledWith('EDGE-03')
    expect(wrapper.text()).toContain('规则版本对账')
    expect(wrapper.text()).toContain('v3.2')
    expect(wrapper.text()).toContain('v3.3')
    // 阻塞在规则对账：出现重新下发 / 保持版本按钮
    const redeliver = wrapper.findAll('button').find((b) => b.text().includes('重新下发'))!
    expect(redeliver).toBeTruthy()
    expect(wrapper.findAll('button').some((b) => b.text().includes('保持当前版本'))).toBe(true)
    // 未完成时完成按钮禁用
    const finishBtn = wrapper.findAll('button').find((b) => b.text() === '完成')!
    expect(finishBtn.attributes('disabled')).toBeDefined()

    vi.mocked(operationsApi.reconcileRules).mockResolvedValueOnce(onlineNode())
    await redeliver.trigger('click')
    await flushPromises()
    expect(operationsApi.reconcileRules).toHaveBeenCalledWith('redeliver', 'EDGE-03')
    expect(wrapper.emitted('finished')).toBeTruthy()
    expect(wrapper.text()).toContain('全部完成，系统恢复在线运行')
    expect(finishBtn.attributes('disabled')).toBeUndefined()
  })

  it('shows time resync action when blocked at CLOCK_RECONCILIATION', async () => {
    vi.mocked(operationsApi.recover).mockResolvedValueOnce(node({
      activeRuleVersion: 'v3.3',
      recoveryPhases: [
        phase('CONNECTIVITY', 'DONE'),
        phase('CLOCK_RECONCILIATION', 'BLOCKED', '偏差 2800ms'),
        phase('RULE_RECONCILIATION', 'WAIT'),
        phase('EVENT_REPLAY', 'WAIT'),
        phase('FINAL_CHECK', 'WAIT'),
      ],
    }))
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    const btn = wrapper.findAll('button').find((b) => b.text().includes('重新同步时间'))!
    expect(btn).toBeTruthy()
    vi.mocked(operationsApi.reconcileTime).mockResolvedValueOnce(onlineNode())
    await btn.trigger('click')
    await flushPromises()
    expect(operationsApi.reconcileTime).toHaveBeenCalledWith('EDGE-03')
  })

  it('shows replay retry when EVENT_REPLAY failed (offline events pending re-delivery)', async () => {
    vi.mocked(operationsApi.recover).mockResolvedValueOnce(node({
      activeRuleVersion: 'v3.3',
      recoveryPhases: [
        phase('CONNECTIVITY', 'DONE'),
        phase('CLOCK_RECONCILIATION', 'DONE'),
        phase('RULE_RECONCILIATION', 'DONE'),
        phase('EVENT_REPLAY', 'FAILED', '存在补传失败事件，等待重试'),
        phase('FINAL_CHECK', 'WAIT'),
      ],
    }))
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试补传'))!
    expect(retry).toBeTruthy()
    vi.mocked(operationsApi.recover).mockResolvedValueOnce(onlineNode())
    await retry.trigger('click')
    await flushPromises()
    // 重试补传再次调用 recover
    expect(operationsApi.recover).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('finished')).toBeTruthy()
  })
})
