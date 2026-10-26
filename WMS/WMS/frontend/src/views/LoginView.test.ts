import { afterEach, describe, expect, it, vi } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn() }) }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ signIn: vi.fn() }) }))
afterEach(() => { vi.unstubAllEnvs(); vi.resetModules() })

describe('login demo hint', () => {
  it('does not publish a default password when not configured', async () => {
    vi.stubEnv('VITE_DEMO_LOGIN_HINT', '')
    const { default: LoginView } = await import('./LoginView.vue')
    const wrapper = shallowMount(LoginView, { global: { plugins: [ElementPlus] } })
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toBe('请使用管理员分配的账号和密码。')
    wrapper.unmount()
  })
  it('displays the explicitly configured public lab hint without filling the password input', async () => {
    vi.stubEnv('VITE_DEMO_LOGIN_HINT', '公司：default，账号：admin，密码：test-demo-only')
    const { default: LoginView } = await import('./LoginView.vue')
    const wrapper = shallowMount(LoginView, { global: { plugins: [ElementPlus] } })
    expect(wrapper.findComponent({ name: 'ElAlert' }).props('title')).toContain('test-demo-only')
    const state = wrapper.vm as unknown as { form: { password: string } }
    expect(state.form.password).toBe('')
    wrapper.unmount()
  })
})
