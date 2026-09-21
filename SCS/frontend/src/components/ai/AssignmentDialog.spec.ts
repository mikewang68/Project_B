import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import AssignmentDialog from './AssignmentDialog.vue'
import { getDictionaries } from '@/api/meta'
import type { AiEvent } from '@/types/ai'
import type { BackendDictionaryResponse } from '@/types/dictionary'

vi.mock('@/api/meta', () => ({ getDictionaries: vi.fn() }))
vi.mock('element-plus', () => ({
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: '<div><slot name="header" /><slot /><slot name="footer" /></div>',
  }),
}))

const mockedGetDictionaries = vi.mocked(getDictionaries)

const sevenUsers: BackendDictionaryResponse = {
  assignees: [
    { id: 'USR-001', name: '李娜', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
    { id: 'USR-002', name: '王建国', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
    { id: 'USR-003', name: '赵明', teamCode: 'LOADING_TEAM_1', teamName: '装卸一班', demoUnverified: false },
    { id: 'USR-004', name: '陈静', teamCode: 'EQUIPMENT_MAINTENANCE', teamName: '设备维保班', demoUnverified: false },
    { id: 'USR-005', name: '刘志明', teamCode: 'LOADING_TEAM_2', teamName: '装卸二班', demoUnverified: true },
    { id: 'USR-006', name: '陈晓', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: true },
    { id: 'USR-007', name: '周海', teamCode: 'EQUIPMENT_MAINTENANCE', teamName: '设备维保班', demoUnverified: true },
  ],
}

const globalStubs = {
  global: {
    stubs: {
      ElSelect: defineComponent({ template: '<select><slot /></select>' }),
      ElOption: defineComponent({ props: ['label', 'value'], template: '<option><slot /></option>' }),
      ElInput: defineComponent({ template: '<input />' }),
      ElIcon: true,
    },
  },
}

const event = { id: 'AI-E-1', type: '闯入危险区域', area: '装卸区 A', risk: '高' } as AiEvent

describe('AI AssignmentDialog 责任人字典', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('与 Alert 派单渲染同一份 7 人名单（含班组），无本地 fallback', async () => {
    mockedGetDictionaries.mockResolvedValue(sevenUsers)
    const wrapper = mount(AssignmentDialog, {
      props: { modelValue: true, event },
      ...globalStubs,
    })
    await flushPromises()

    const text = wrapper.text()
    for (const name of ['李娜', '王建国', '赵明', '陈静', '刘志明', '陈晓', '周海']) {
      expect(text).toContain(name)
    }
    expect(text).toContain('设备维保班')
    expect(text).not.toContain('安全员 王建国')
    expect(text).not.toContain('值班员 陈晓')
  })

  it('确认派单提交责任人姓名', async () => {
    mockedGetDictionaries.mockResolvedValue(sevenUsers)
    const wrapper = mount(AssignmentDialog, {
      props: { modelValue: true, event },
      ...globalStubs,
    })
    await flushPromises()

    const confirm = wrapper.findAll('button').find((b) => b.text().includes('确认派单'))!
    await confirm.trigger('click')

    const payload = wrapper.emitted('confirm')?.[0]?.[0] as Record<string, unknown>
    expect(payload.assignee).toBe('李娜')
  })

  it('字典加载失败时显示错误与重试，不静默使用本地名单', async () => {
    mockedGetDictionaries.mockRejectedValueOnce(new Error('network down'))
    const wrapper = mount(AssignmentDialog, {
      props: { modelValue: true, event },
      ...globalStubs,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('人员字典加载失败')
    expect(wrapper.text()).not.toContain('王建国')
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试'))!
    expect(retry).toBeTruthy()

    mockedGetDictionaries.mockResolvedValueOnce(sevenUsers)
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('周海')
    expect(mockedGetDictionaries).toHaveBeenCalledTimes(2)
  })
})
