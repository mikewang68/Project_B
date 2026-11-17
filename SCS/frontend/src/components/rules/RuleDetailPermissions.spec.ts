import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import RuleDetailDrawer from './RuleDetailDrawer.vue'
import FenceDetailPanel from '../fence/FenceDetailPanel.vue'
import { useAuthStore } from '@/stores/auth'
import { permissionDirective } from '@/directives/permission'
import { mapRule } from '@/adapters/rule'
import type { CurrentUser } from '@/types/auth'
import type { FenceDraft, FenceRecord } from '@/types/fence'

const nativePermissions = ['safety:module:rule:edit', 'safety:module:rule:execute',
  'safety:module:rule:approve', 'safety:fence:edit']
const identity = (actions: string[], readOnly = false): CurrentUser => ({
  id: 'test-subject', name: 'Test', role: 'OPERATOR', team: '', shift: '', online: true,
  permissions: nativePermissions, readOnly, csrfToken: '', authorizationVersion: '1',
  menuOperations: { 'SCS:rules': actions, 'SCS:fences': actions },
})
function setup(actions: string[]) {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.setCurrentUser(identity(actions))
  return { auth, global: {
    plugins: [pinia], directives: { permission: permissionDirective },
    stubs: { ElDrawer: { template: '<section><slot /></section>' }, ElIcon: true,
      RiskBadge: true, RuleStatusBadge: true, StatusBadge: true,
      EdgeSyncPanel: true, RuleVersionHistory: true },
  } }
}

describe('central actions on existing rule and fence details', () => {
  it('requires EDIT for both draft actions even when ADD has the same native permission', async () => {
    const { auth, global } = setup(['VIEW', 'ADD'])
    const wrapper = mount(RuleDetailDrawer, {
      props: { modelValue: true, rule: mapRule({ id: 'test-rule', name: 'Draft', status: '草稿' }) }, global,
    })
    const actions = () => wrapper.findAll<HTMLButtonElement>('button')
      .filter(button => ['编辑', '提交评审'].includes(button.text()))
    expect(actions()).toHaveLength(2)
    for (const button of actions()) {
      expect(button.element.disabled).toBe(true)
      button.element.click()
    }
    expect(wrapper.emitted('action')).toBeUndefined()
    auth.setCurrentUser(identity(['VIEW', 'EDIT']))
    await nextTick()
    for (const button of actions()) expect(button.element.disabled).toBe(false)
    actions()[0]!.element.click()
    expect(wrapper.emitted('action')?.[0]?.[0]).toBe('编辑')
    auth.setCurrentUser(identity(['VIEW', 'ADD']))
    await nextTick()
    for (const button of actions()) expect(button.element.disabled).toBe(true)
    wrapper.unmount()
  })

  it('uses CONFIRM for approval and still applies read-only denial', async () => {
    const { auth, global } = setup(['VIEW', 'EDIT'])
    const wrapper = mount(RuleDetailDrawer, {
      props: { modelValue: true, rule: mapRule({ id: 'test-rule', status: '待评审' }) }, global,
    })
    const approval = () => wrapper.findAll<HTMLButtonElement>('button').find(button => button.text() === '批准')!
    expect(approval().element.disabled).toBe(true)
    auth.setCurrentUser(identity(['VIEW', 'CONFIRM']))
    await nextTick()
    expect(approval().element.disabled).toBe(false)
    auth.setCurrentUser(identity(['VIEW', 'CONFIRM'], true))
    await nextTick()
    expect(approval().element.disabled).toBe(true)
    wrapper.unmount()
  })

  it('exposes fence disable for an effective fence and gates it by EDIT', async () => {
    const { auth, global } = setup(['VIEW', 'EDIT'])
    const draft: FenceDraft = { name: '', kind: '危险区域', riskLevel: '一般',
      teams: '', startsAt: '', endsAt: '' }
    const fence: FenceRecord = { id: 'FENCE-001', name: '作业警戒红线', kind: '危险区域',
      tone: 'danger', area: '装卸区 A', version: 'v1.0', status: '已生效', effectiveAt: '', expiresAt: '',
      teams: '', approver: '', edgeSynced: 4, edgeTotal: 4, polygon: [], nodes: [] }
    const wrapper = mount(FenceDetailPanel, {
      props: { fence, draft, editing: false, pointCount: 0 }, global,
    })
    const disable = () => wrapper.findAll<HTMLButtonElement>('.fence-panel-actions button')
      .find(button => button.text() === '停用围栏')
    expect(disable()).toBeTruthy()
    expect(disable()!.element.disabled).toBe(false)
    disable()!.element.click()
    expect(wrapper.emitted('disable')).toHaveLength(1)
    auth.setCurrentUser(identity(['VIEW', 'ADD']))
    await nextTick()
    expect(disable()!.element.disabled).toBe(true)
    disable()!.element.click()
    expect(wrapper.emitted('disable')).toHaveLength(1)
    wrapper.unmount()
  })

  it('shows re-enable instead of edit for a disabled fence and gates it by EDIT', async () => {
    const { auth, global } = setup(['VIEW', 'EDIT'])
    const draft: FenceDraft = { name: '', kind: '危险区域', riskLevel: '一般',
      teams: '', startsAt: '', endsAt: '' }
    const fence: FenceRecord = { id: 'FENCE-001', name: '已停用围栏', kind: '危险区域',
      tone: 'danger', area: '装卸区 A', version: 'v1.0', status: '已停用', effectiveAt: '', expiresAt: '',
      teams: '', approver: '', edgeSynced: 0, edgeTotal: 4, polygon: [], nodes: [] }
    const wrapper = mount(FenceDetailPanel, {
      props: { fence, draft, editing: false, pointCount: 0 }, global,
    })
    const actions = () => wrapper.findAll<HTMLButtonElement>('.fence-panel-actions button')
    expect(actions().map(button => button.text())).toEqual(['重新启用'])
    expect(actions()[0]!.element.disabled).toBe(false)
    actions()[0]!.element.click()
    expect(wrapper.emitted('enable')).toHaveLength(1)
    auth.setCurrentUser(identity(['VIEW', 'ADD']))
    await nextTick()
    expect(actions()[0]!.element.disabled).toBe(true)
    actions()[0]!.element.click()
    expect(wrapper.emitted('enable')).toHaveLength(1)
    wrapper.unmount()
  })

  it('requires EDIT for existing fence editing and review without disabling a permitted new draft', async () => {
    const { auth, global } = setup(['VIEW', 'ADD'])
    const draft: FenceDraft = { name: '', kind: '危险区域', riskLevel: '一般',
      teams: '', startsAt: '', endsAt: '' }
    const fence: FenceRecord = { id: 'test-fence', name: 'Draft fence', kind: '危险区域',
      tone: 'danger', area: '', version: 'v1.0', status: '草稿', effectiveAt: '', expiresAt: '',
      teams: '', approver: '', edgeSynced: 0, edgeTotal: 0, polygon: [], nodes: [] }
    const wrapper = mount(FenceDetailPanel, {
      props: { fence, draft, editing: false, pointCount: 0 }, global,
    })
    const actions = () => wrapper.findAll<HTMLButtonElement>('.fence-panel-actions button')
    expect(actions()).toHaveLength(2)
    for (const button of actions()) expect(button.element.disabled).toBe(true)
    auth.setCurrentUser(identity(['VIEW', 'EDIT']))
    await nextTick()
    for (const button of actions()) expect(button.element.disabled).toBe(false)
    auth.setCurrentUser(identity(['VIEW', 'ADD']))
    await wrapper.setProps({ editing: true, isEditingExisting: false })
    for (const button of actions()) expect(button.element.disabled).toBe(false)
    wrapper.unmount()
  })
})
