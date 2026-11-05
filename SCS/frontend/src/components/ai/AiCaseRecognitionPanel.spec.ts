import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { permissionDirective } from '@/directives/permission'
import { useAuthStore } from '@/stores/auth'
import type { CurrentUser } from '@/types/auth'
import type { AiInferenceResult } from '@/types/aiModel'
import AiCaseRecognitionPanel from './AiCaseRecognitionPanel.vue'

const api = vi.hoisted(() => ({ infer: vi.fn(), escalate: vi.fn() }))
vi.mock('@/api/aiModels', () => ({ aiModelApi: api }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const viewPermission = 'safety:module:ai:model:view'
const alarmPermission = 'safety:module:ai:model:execute'
const inference: AiInferenceResult = {
  inferenceId: 'test-inference', modelId: 'test-model', modelName: 'Test model',
  modelVersion: '1', engineUsed: 'fixture', latencyMs: 1,
  scenario: 'ppe_violation', scenarioName: 'Test case', imageSource: '',
  detectedObjects: [], violationCount: 1, riskLevel: 'WARNING', summary: 'Test result',
  suggestion: 'Review', timestamp: '2026-01-01T00:00:00Z',
}

function identity(actions: string[], readOnly = false, permissions = [viewPermission, alarmPermission]): CurrentUser {
  return {
    id: 'test-subject', name: 'Test', role: 'OPERATOR', team: '', shift: '', online: true,
    readOnly, permissions, menuOperations: { 'SCS:ai-models': actions },
  }
}

describe('explicit model execution', () => {
  let pinia: ReturnType<typeof createPinia>
  const wrappers: VueWrapper[] = []
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    vi.clearAllMocks()
    api.infer.mockResolvedValue(inference)
  })
  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    useAuthStore().clear()
  })
  function open(user: CurrentUser) {
    useAuthStore().setCurrentUser(user)
    const wrapper = mount(AiCaseRecognitionPanel, {
      global: { plugins: [pinia], directives: { permission: permissionDirective }, stubs: { 'el-icon': true, 'el-slider': true } },
    })
    wrappers.push(wrapper)
    return wrapper
  }

  it('does not infer when an authorized user only opens the page or changes a case', async () => {
    const wrapper = open(identity(['VIEW', 'FAULT_ALARM']))
    await wrapper.findAll('.scenario-chip')[1]!.trigger('click')
    expect(api.infer).not.toHaveBeenCalled()
    expect(api.escalate).not.toHaveBeenCalled()
  })

  it('requires both central VIEW and the native view permission', async () => {
    for (const user of [identity([]), identity(['VIEW'], false, [alarmPermission])]) {
      const wrapper = open(user)
      expect((wrapper.get('.primary-infer-btn').element as HTMLButtonElement).disabled).toBe(true)
      await wrapper.get('.primary-infer-btn').trigger('click')
      expect(api.infer).not.toHaveBeenCalled()
    }
  })

  it('allows explicitly authorized readonly inference but never readonly alarm submission', async () => {
    const wrapper = open(identity(['VIEW', 'FAULT_ALARM'], true))
    await wrapper.get('.primary-infer-btn').trigger('click')
    await flushPromises()
    expect(api.infer).toHaveBeenCalledTimes(1)
    expect(api.infer).toHaveBeenCalledWith({ scenario: 'ppe_violation', confidenceThreshold: 0.70 })
    expect((wrapper.get('.escalate-btn').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('.escalate-btn').trigger('click')
    expect(api.escalate).not.toHaveBeenCalled()
  })

  it('does not grant alarm submission merely because inference succeeded', async () => {
    const wrapper = open(identity(['VIEW']))
    await wrapper.get('.primary-infer-btn').trigger('click')
    await flushPromises()
    expect(api.infer).toHaveBeenCalledTimes(1)
    expect((wrapper.get('.escalate-btn').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('.escalate-btn').trigger('click')
    expect(api.escalate).not.toHaveBeenCalled()
  })

  it('disables an open page after the central VIEW action is revoked', async () => {
    const wrapper = open(identity(['VIEW']))
    useAuthStore().setCurrentUser(identity([]))
    await flushPromises()
    expect((wrapper.get('.primary-infer-btn').element as HTMLButtonElement).disabled).toBe(true)
    await wrapper.get('.primary-infer-btn').trigger('click')
    expect(api.infer).not.toHaveBeenCalled()
  })
})
