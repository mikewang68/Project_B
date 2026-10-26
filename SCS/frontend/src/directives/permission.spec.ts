import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { permissionDirective } from './permission'
import type { CurrentUser } from '@/types/auth'

const permission = 'safety:module:rule:execute'
function identity(permissions: string[], readOnly = false): CurrentUser {
  return { id: 'test-subject', name: 'Test', role: 'OPERATOR', team: '', shift: '', online: true, permissions, readOnly, csrfToken: '', authorizationVersion: '1' }
}
const Button = defineComponent({
  props: { pending: Boolean },
  emits: ['execute'],
  template: '<button v-permission="permission" :disabled="pending" title="Required confirmation" @click="$emit(\'execute\')">Execute</button>',
  setup: () => ({ permission }),
})
describe('permission and business button state', () => {
  beforeEach(() => setActivePinia(createPinia()))
  const render = (pending = false) => mount(Button, { props: { pending }, global: { plugins: [createPinia()], directives: { permission: permissionDirective } } })
  it('blocks a read-only user even with an operation grant', async () => {
    const wrapper = render();useAuthStore().setCurrentUser(identity([permission], true));await nextTick()
    wrapper.get('button').element.click();expect(wrapper.emitted('execute')).toBeUndefined();expect(wrapper.get('button').element.disabled).toBe(true)
    wrapper.unmount()
  })
  it('revokes a previously enabled button without closing its dialog', async () => {
    const wrapper = render();const auth = useAuthStore();auth.setCurrentUser(identity([permission]));await nextTick()
    expect(wrapper.get('button').element.disabled).toBe(false);auth.setCurrentUser(identity([]));await nextTick()
    wrapper.get('button').element.click();expect(wrapper.emitted('execute')).toBeUndefined();expect(wrapper.get('button').element.disabled).toBe(true)
    wrapper.unmount()
  })
  it('keeps a pending operation disabled when authorization returns', async () => {
    const wrapper = render(true);useAuthStore().setCurrentUser(identity([permission]));await nextTick()
    expect(wrapper.get('button').element.disabled).toBe(true);expect(wrapper.attributes('title')).toBe('Required confirmation')
    wrapper.unmount()
  })
  it('uses the latest business condition after permission changes', async () => {
    const wrapper = render(true);await wrapper.setProps({ pending: false });useAuthStore().setCurrentUser(identity([permission]));await nextTick()
    expect(wrapper.get('button').element.disabled).toBe(false);wrapper.get('button').element.click();expect(wrapper.emitted('execute')).toHaveLength(1)
    wrapper.unmount()
  })
})
