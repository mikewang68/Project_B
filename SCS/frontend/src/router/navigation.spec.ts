import { describe, expect, it } from 'vitest'
import router from './index'

// Sidebar 九个业务菜单 → 路由（与 AppSidebar.vue 的 menuItems 一一对应）
const BUSINESS_ROUTES: Record<string, string> = {
  '/overview': 'overview',
  '/people': 'people',
  '/fences': 'fences',
  '/devices': 'devices',
  '/ai': 'ai-review',
  '/alarms': 'alert-center',
  '/analytics': 'analytics',
  '/rules': 'rules',
  '/operations': 'operations',
}

describe('router 路由表', () => {
  it('九个业务路径都能解析到唯一命名路由', () => {
    for (const [path, name] of Object.entries(BUSINESS_ROUTES)) {
      const resolved = router.resolve(path)
      expect(resolved.name).toBe(name)
      // 必须真正匹配到路由记录，而不是落到 catch-all
      expect(resolved.matched.length).toBeGreaterThan(0)
      expect(resolved.matched[0]?.name).toBe(name)
    }
  })

  it('路由 name 无重复', () => {
    const names = router
      .getRoutes()
      .map((r) => r.name)
      .filter((n): n is string | symbol => n != null)
      .map(String)
    expect(new Set(names).size).toBe(names.length)
  })

  function redirectOf(path: string): unknown {
    const record = router.options.routes.find((r) => r.path === path)
    return record?.redirect
  }

  it('根路径与 /demo 重定向到 /overview', () => {
    expect(redirectOf('/')).toBe('/overview')
    expect(redirectOf('/demo')).toBe('/overview')
  })

  it('未知路径兜底重定向到 /overview，不产生空白页', () => {
    expect(redirectOf('/:pathMatch(.*)*')).toBe('/overview')
  })

  it('安全大屏为 standalone 独立路由', () => {
    const resolved = router.resolve('/safety-screen')
    expect(resolved.name).toBe('safety-screen')
    expect(resolved.meta.standalone).toBe(true)
  })

  it('移动端 /mobile 重定向到 /mobile/home，子路由齐全且均为 standalone', () => {
    expect(redirectOf('/mobile')).toBe('/mobile/home')
    expect(router.resolve('/mobile/home').name).toBe('mobile-home')
    expect(router.resolve('/mobile/alerts').name).toBe('mobile-alerts')
    expect(router.resolve('/mobile/alert/ALM-1').name).toBe('mobile-alert-detail')
    expect(router.resolve('/mobile/home').meta.standalone).toBe(true)
  })
})
