import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const getCurrentUserMock = vi.fn()
vi.mock('@/api/auth', () => ({ getCurrentUser: (...args: unknown[]) => getCurrentUserMock(...args) }))
// 守卫测试只关心导航结果，不让真实视图（echarts 等重依赖）进测试环境
vi.mock('@/views/OverviewView.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/ForbiddenView.vue', () => ({ default: { render: () => null } }))

import { ApiError } from '@/api/http'
import { createAppRouter } from './index'
import { useAuthStore } from '@/stores/auth'
import type { CurrentUser } from '@/types/auth'

function aUser(permissions: string[]): CurrentUser {
  return { id:'u-1', name:'测试员', role:'viewer', team:'安全组', shift:'day', online:true, permissions }
}

describe('router auth guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getCurrentUserMock.mockReset()
  })

  it('redirects an unauthenticated navigation to /forbidden', async () => {
    getCurrentUserMock.mockRejectedValue(new ApiError('会话失效', 401, 't'))
    const router = createAppRouter()
    await router.push('/overview')
    expect(router.currentRoute.value.path).toBe('/forbidden')
  })

  it('admits an authorized user whose deep link carries a trailing slash', async () => {
    getCurrentUserMock.mockResolvedValue(aUser(['safety:home:map:view']))
    const router = createAppRouter()
    await router.push('/overview/')
    expect(router.currentRoute.value.name).toBe('overview')
    expect(router.currentRoute.value.path).not.toBe('/forbidden')
  })

  it('bounces a user without the required page permission to /forbidden', async () => {
    getCurrentUserMock.mockResolvedValue(aUser(['safety:home:map:view']))
    const router = createAppRouter()
    await router.push('/operations')
    expect(router.currentRoute.value.path).toBe('/forbidden')
  })

  it('keeps /forbidden reachable even while unauthenticated', async () => {
    getCurrentUserMock.mockRejectedValue(new ApiError('会话失效', 401, 't'))
    const router = createAppRouter()
    await router.push('/forbidden')
    expect(router.currentRoute.value.path).toBe('/forbidden')
  })

  it('treats transient network failure on refresh as not-logged-in for the first visit', async () => {
    // 首次加载即网络故障（status 0）：无既有会话可保留，守卫按未认证处理
    getCurrentUserMock.mockRejectedValue(new ApiError('网络异常，无法连接服务', 0, 't', 'NETWORK_ERROR'))
    const router = createAppRouter()
    await router.push('/overview')
    expect(router.currentRoute.value.path).toBe('/forbidden')
    const auth = useAuthStore()
    expect(auth.error).toContain('网络异常')
  })
})
