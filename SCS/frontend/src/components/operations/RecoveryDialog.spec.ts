import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecoveryDialog from './RecoveryDialog.vue'
import type { EdgeNode, LocalEvent } from '@/types/operations'

vi.mock('element-plus', () => ({
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: '<div class="dialog-stub"><slot name="header" /><slot /><slot name="footer" /></div>',
  }),
}))

vi.mock('@element-plus/icons-vue', () => ({
  CircleCheckFilled: defineComponent({ template: '<i />' }),
  Loading: defineComponent({ template: '<i />' }),
}))

function node(over: Partial<EdgeNode> = {}): EdgeNode {
  return {
    id: 'EDGE-03',
    name: '3 号边缘节点',
    ip: '10.24.1.13',
    online: true,
    autonomy: false,
    cpu: 40,
    memory: 50,
    storage: 60,
    cacheCapacity: 1000,
    cacheEvents: 4,
    ruleVersion: 'v3.2',
    platformVersion: 'v3.3',
    timeOffsetMs: null,
    lastHeartbeat: '2026-09-10T12:00:00+08:00',
    localEventCount: 4,
    recentIssue: '',
    cacheParts: [],
    ...over,
  }
}

function queueItem(index: number): LocalEvent {
  return {
    id: `EVT-EDGE-${String(index).padStart(3, '0')}`,
    type: '人员进入危险区域',
    node: 'EDGE-03',
    time: `12:00:0${index}`,
    risk: '紧急',
    status: '待补传',
    dedupKey: `EDGE-03-${index}`,
    localActions: ['本地风险判定', '现场声光提醒'],
  }
}

function mountDialog(options: { nodes?: EdgeNode[]; timeDrift?: boolean } = {}) {
  return mount(RecoveryDialog, {
    props: {
      modelValue: false,
      queue: [queueItem(1), queueItem(2), queueItem(3), queueItem(4)],
      nodes: options.nodes ?? [node()],
      timeDrift: options.timeDrift ?? false,
    },
    global: { stubs: { ElIcon: true } },
  })
}

async function advance(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms)
  await flushPromises()
}

describe('RecoveryDialog local recovery workflow', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('opens and blocks at mismatched rule version until redelivery', async () => {
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await advance(5000)

    expect(wrapper.text()).toContain('规则版本对账')
    expect(wrapper.text()).toContain('v3.2')
    expect(wrapper.text()).toContain('v3.3')
    const redeliver = wrapper.findAll('button').find((b) => b.text().includes('重新下发'))!
    expect(redeliver).toBeTruthy()
    expect(wrapper.findAll('button').some((b) => b.text().includes('保持当前版本'))).toBe(true)

    const finishBtn = wrapper.findAll('button').find((b) => b.text() === '完成')!
    expect(finishBtn.attributes('disabled')).toBeDefined()

    await redeliver.trigger('click')
    await advance(2500)

    expect(wrapper.emitted('ruleRedeliver')).toBeTruthy()
    expect(wrapper.emitted('finished')).toBeTruthy()
    expect(wrapper.text()).toContain('全部完成，系统恢复在线运行')
    expect(finishBtn.attributes('disabled')).toBeUndefined()
  })

  it('shows time resync action when time drift is pending', async () => {
    const wrapper = mountDialog({
      nodes: [node({ id: 'EDGE-02', ruleVersion: 'v3.3', timeOffsetMs: 3800 })],
      timeDrift: true,
    })
    await wrapper.setProps({ modelValue: true })
    await advance(5400)

    const resync = wrapper.findAll('button').find((b) => b.text().includes('重新同步'))!
    expect(resync).toBeTruthy()

    await resync.trigger('click')
    await advance(1800)

    expect(wrapper.emitted('timeResync')).toBeTruthy()
    expect(wrapper.emitted('finished')).toBeTruthy()
  })

  it('emits replay progress while draining the local queue', async () => {
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await advance(3400)

    const progressEvents = wrapper.emitted('progress') ?? []
    expect(progressEvents.length).toBeGreaterThan(0)
    expect(progressEvents.at(-1)?.[0]).toBe(4)
  })
})
