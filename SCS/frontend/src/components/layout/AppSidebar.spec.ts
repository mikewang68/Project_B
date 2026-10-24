import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { pagePermissions } from '@/permissions'
import AppSidebar from './AppSidebar.vue'
import { createAppRouter } from '@/router'
let router:ReturnType<typeof createAppRouter>

const EXPECTED_PATHS = [
  '/overview',
  '/people',
  '/fences',
  '/devices',
  '/ai',
  '/ai/models',
  '/alarms',
  '/analytics',
  '/rules',
  '/operations',
]

async function mountSidebar() {
  router=createAppRouter()
  const pinia=createPinia(); setActivePinia(pinia)
  const user={id:'scs-admin',name:'SCS Admin',role:'ADMIN',team:'',shift:'day',online:true,permissions:Object.values(pagePermissions)}
  const auth=useAuthStore(); auth.setCurrentUser(user)
  vi.spyOn(auth,'ensureLoaded').mockResolvedValue(user)
  await router.push('/overview')
  await router.isReady()
  return mount(AppSidebar, {
    props: { open: false },
    global: { plugins: [pinia, router], stubs: { 'el-icon': { template: '<span><slot /></span>' } } },
  })
}

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('AppSidebar 导航', { timeout: 20000 }, () => {
  it('渲染全部 10 个业务菜单，且每个都是指向真实路由的链接', async () => {
    const wrapper = await mountSidebar()
    const links = wrapper.findAll('.nav-link:not(.nav-link--external)')
    expect(links).toHaveLength(10)
    const hrefs = links.map((l) => l.attributes('href'))
    for (const path of EXPECTED_PATHS) {
      expect(hrefs).toContain(path)
    }
  })

  it('不存在没有目标的占位菜单（无 href 的可点击项）', async () => {
    const wrapper = await mountSidebar()
    const empty = wrapper.findAll('.nav-link:not(.nav-link--external)').filter((l) => !l.attributes('href'))
    expect(empty).toHaveLength(0)
  })

  it('大屏 / 移动端入口在新窗口打开 standalone 页面', async () => {
    const wrapper = await mountSidebar()
    const display = wrapper.findAll('.nav-link--external')
    expect(display).toHaveLength(2)
    for (const a of display) {
      expect(a.attributes('target')).toBe('_blank')
      expect(['/safety-screen', '/mobile/home']).toContain(a.attributes('href'))
    }
  })

  it('当前路由变化时 active 项由 Router 驱动同步', async () => {
    const wrapper = await mountSidebar()
    await router.push('/alarms')
    await wrapper.vm.$nextTick()
    const active = wrapper.findAll('.nav-link.router-link-active')
    expect(active).toHaveLength(1)
    expect(active.at(0)?.attributes('href')).toBe('/alarms')
  })
})
