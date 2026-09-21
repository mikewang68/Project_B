import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import AlertAssignDialog from './AlertAssignDialog.vue'
import { getDictionaries } from '@/api/meta'
import type { BackendDictionaryResponse } from '@/types/dictionary'

vi.mock('@/api/meta', () => ({ getDictionaries: vi.fn() }))
vi.mock('element-plus', () => ({
  ElDialog: defineComponent({
    props: ['modelValue'],
    template: '<div><slot name="header" /><slot /><slot name="footer" /></div>',
  }),
}))

const mockedGetDictionaries = vi.mocked(getDictionaries)

const sevenUsers = {
  areas: [
    { code: 'LOADING_AREA_A', name: '装卸区 A' },
    { code: 'VEHICLE_LANE', name: '车辆通道' },
  ],
  teams: [
    { code: 'LOADING_TEAM_1', name: '装卸一班' },
    { code: 'LOADING_TEAM_2', name: '装卸二班' },
    { code: 'SAFETY_MANAGEMENT', name: '安全管理组' },
    { code: 'EQUIPMENT_MAINTENANCE', name: '设备维保班' },
  ],
  assignees: [
    { id: 'USR-001', name: '李娜', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
    { id: 'USR-002', name: '王建国', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
    { id: 'USR-003', name: '赵明', teamCode: 'LOADING_TEAM_1', teamName: '装卸一班', demoUnverified: false },
    { id: 'USR-004', name: '陈静', teamCode: 'EQUIPMENT_MAINTENANCE', teamName: '设备维保班', demoUnverified: false },
    { id: 'USR-005', name: '刘志明', teamCode: 'LOADING_TEAM_2', teamName: '装卸二班', demoUnverified: true },
    { id: 'USR-006', name: '陈晓', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: true },
    { id: 'USR-007', name: '周海', teamCode: 'EQUIPMENT_MAINTENANCE', teamName: '设备维保班', demoUnverified: true },
  ],
} satisfies BackendDictionaryResponse

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

describe('AlertAssignDialog 责任人字典', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('渲染字典中的 7 名责任人及其班组，且不出现本地 fallback 名单', async () => {
    mockedGetDictionaries.mockResolvedValue(sevenUsers)
    const wrapper = mount(AlertAssignDialog, {
      props: { modelValue: true, mode: '派单' as const },
      ...globalStubs,
    })
    await flushPromises()

    const text = wrapper.text()
    for (const name of ['李娜', '王建国', '赵明', '陈静', '刘志明', '陈晓', '周海']) {
      expect(text).toContain(name)
    }
    expect(text).toContain('装卸二班')
    // 旧本地常量带头衔（安全员 王建国 / 班长 刘志明），字典只返回姓名，不应再出现。
    expect(text).not.toContain('安全员 王建国')
    expect(text).not.toContain('班长 刘志明')
  })

  it('确认派单提交姓名与 userId（assigneeId）', async () => {
    mockedGetDictionaries.mockResolvedValue(sevenUsers)
    const wrapper = mount(AlertAssignDialog, {
      props: { modelValue: true, mode: '派单' as const },
      ...globalStubs,
    })
    await flushPromises()

    const confirm = wrapper.findAll('button').find((b) => b.text().includes('确认派单'))!
    await confirm.trigger('click')

    const payload = wrapper.emitted('confirm')?.[0]?.[0] as Record<string, unknown>
    expect(payload.assignee).toBe('李娜')
    expect(payload.assigneeId).toBe('USR-001')
    expect(payload.assigneeName).toBe('李娜')
  })

  it('字典加载失败时显示错误与重试，不静默使用本地名单', async () => {
    mockedGetDictionaries.mockRejectedValueOnce(new Error('network down'))
    const wrapper = mount(AlertAssignDialog, {
      props: { modelValue: true, mode: '派单' as const },
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
    expect(wrapper.text()).toContain('刘志明')
    expect(mockedGetDictionaries).toHaveBeenCalledTimes(2)
  })
})
