import { watch, type ObjectDirective } from 'vue'
import { useAuthStore } from '@/stores/auth'

type State = { permission: string; businessDisabled: boolean; originalTitle: string | null; stop?: () => void }
const states = new WeakMap<HTMLElement, State>()
const disabledProp = (value: unknown): boolean => value === '' || Boolean(value)

function apply(el: HTMLElement): void {
  const state = states.get(el)
  if (!state) return
  const auth = useAuthStore()
  const allowed = auth.can(state.permission) && (!auth.user?.readOnly || state.permission.endsWith(':export'))
  if (!allowed) {
    el.setAttribute('disabled', '')
    el.setAttribute('aria-disabled', 'true')
    el.dataset.permissionBlocked = '1'
    el.title = '当前账号未获此操作权限'
  } else if (el.dataset.permissionBlocked) {
    // Restoring permission must not bypass a pending operation or required confirmation.
    if (!state.businessDisabled) el.removeAttribute('disabled')
    el.removeAttribute('aria-disabled')
    delete el.dataset.permissionBlocked
    if (state.originalTitle === null) el.removeAttribute('title')
    else el.title = state.originalTitle
  }
}

export const permissionDirective: ObjectDirective<HTMLElement, string> = {
  mounted(el, binding, vnode) {
    const state: State = { permission: String(binding.value), businessDisabled: disabledProp(vnode.props?.disabled), originalTitle: el.getAttribute('title') }
    states.set(el, state)
    const auth = useAuthStore()
    state.stop = watch(() => [auth.user, auth.can(state.permission), auth.user?.readOnly], () => apply(el), { immediate: true })
  },
  updated(el, binding, vnode) {
    const state = states.get(el)
    if (!state) return
    state.permission = String(binding.value)
    state.businessDisabled = disabledProp(vnode.props?.disabled)
    apply(el)
  },
  unmounted(el) {
    states.get(el)?.stop?.()
    states.delete(el)
  },
}
