import { watchEffect } from 'vue'
import useUserStore from '@/store/modules/user'

const controls = new WeakMap()
function apply(el, state, blocked) {
  if (blocked) {
    if (!state.blocked) {
      state.disabled = el.disabled
      state.title = el.getAttribute('title')
      state.classDisabled = el.classList.contains('is-disabled')
    }
    el.disabled = true
    el.classList.add('is-disabled')
    el.setAttribute('aria-disabled', 'true')
    el.setAttribute('title', '当前可信身份为只读，写入和敏感导出已禁用')
  } else if (state.blocked) {
    el.disabled = state.disabled
    el.classList.toggle('is-disabled', state.classDisabled)
    el.removeAttribute('aria-disabled')
    if (state.title === null) el.removeAttribute('title')
    else el.setAttribute('title', state.title)
  }
  state.blocked = blocked
}
export default {
  mounted(el) {
    const store = useUserStore()
    const state = { blocked: false }
    controls.set(el, state)
    state.stop = watchEffect(() => apply(el, state, store.readOnly))
    state.guard = event => { if (store.readOnly) { event.preventDefault(); event.stopImmediatePropagation() } }
    el.addEventListener('click', state.guard, true)
  },
  updated(el) { const state = controls.get(el); if (state) apply(el, state, useUserStore().readOnly) },
  unmounted(el) { const state = controls.get(el); state?.stop(); if (state) el.removeEventListener('click', state.guard, true); controls.delete(el) }
}
