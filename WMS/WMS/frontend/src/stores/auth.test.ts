import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/http'
import type { CurrentUser } from '@/api/auth'
import { SESSION_REFRESH_MS, useAuthStore } from './auth'

const api = vi.hoisted(() => ({ currentUser: vi.fn(), prepareCsrf: vi.fn(), login: vi.fn(), logout: vi.fn(), switchTenant: vi.fn(), changePassword: vi.fn() }))
vi.mock('@/api/auth', () => api)
const current = (): CurrentUser => ({ id: 7, username: 'operator', displayName: '操作员', companyCode: 'default', companyName: '公司',
  roles: ['OPERATOR'], permissions: ['stockin:read', 'stockin:write'], passwordChangeRequired: false,
  menus: [{ code: 'stockin', name: '入库管理', path: '/stockin', sortOrder: 1 }],
  tenant: { companyCode: 'default', companyName: '公司', currentWarehouse: { id: 1, code: 'WH1', name: '仓库' },
    currentOwner: { id: 1, code: 'OWN1', name: '货主' }, warehouses: [], owners: [] }, readOnly: false, authorizationVersion: 'v1' })

beforeEach(() => {
  vi.clearAllMocks()
  vi.useRealTimers()
  setActivePinia(createPinia())
  api.currentUser.mockResolvedValue(current())
  api.login.mockResolvedValue(current())
  api.prepareCsrf.mockResolvedValue(undefined)
  api.logout.mockResolvedValue(undefined)
})

describe('current effective authorization', () => {
  it('deduplicates concurrent restoration and throttles unchanged navigation polls', async () => {
    vi.useFakeTimers()
    const auth = useAuthStore()
    await Promise.all([auth.restoreSession(), auth.restoreSession(), auth.refreshSession({ force: false })])
    expect(api.currentUser).toHaveBeenCalledTimes(1)
    const previous = auth.user
    await auth.restoreSession()
    expect(api.currentUser).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(SESSION_REFRESH_MS)
    await auth.restoreSession()
    expect(api.currentUser).toHaveBeenCalledTimes(2)
    expect(auth.user).toBe(previous)
  })

  it('refreshes menus and effective readonly despite accidentally granted write permission', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    expect(auth.canWrite('stockin:write')).toBe(true)
    api.currentUser.mockResolvedValue({ ...current(), readOnly: true, authorizationVersion: 'v2', menus: [] })
    await auth.refreshSession()
    expect(auth.readOnly).toBe(true)
    expect(auth.canWrite('stockin:write')).toBe(false)
    expect(auth.user?.menus).toEqual([])
  })

  it.each(['AUDITOR', 'READONLY', 'ROLE_READONLY', 'READONLY_AUDITOR', 'PLATFORM_AUDITOR_READONLY'])('keeps the legacy %s role readonly', async role => {
    api.currentUser.mockResolvedValue({ ...current(), roles: [role.toLowerCase()], readOnly: false })
    const auth = useAuthStore()
    await auth.restoreSession()
    expect(auth.canWrite('stockin:write')).toBe(false)
    expect(auth.hasPermission('stockin:read')).toBe(true)
  })

  it.each([401, 403])('clears revoked sessions on /me %s without retry loops', async status => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.currentUser.mockRejectedValue(new ApiError(status, 'AUTH_INVALID', '失效'))
    await auth.refreshSession()
    expect(auth.authenticated).toBe(false)
    await auth.restoreSession()
    expect(api.currentUser).toHaveBeenCalledTimes(2)
  })

  it('does not pretend a service outage is logout; pauses writes and recovers explicitly', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.currentUser.mockRejectedValue(new ApiError(503, 'SSO_UNAVAILABLE', '不可用'))
    await expect(auth.refreshSession()).rejects.toMatchObject({ status: 503 })
    expect(auth.authenticated).toBe(true)
    expect(auth.canWrite('stockin:write')).toBe(false)
    expect(auth.sessionError).toContain('暂停写操作')
    api.currentUser.mockResolvedValue(current())
    await auth.refreshSession()
    expect(auth.canWrite('stockin:write')).toBe(true)
  })

  it('keeps an ordinary failed logout authenticated until the server confirms completion', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.logout.mockRejectedValue(new ApiError(503, 'SSO_UNAVAILABLE', '不可用'))
    await expect(auth.signOut()).rejects.toMatchObject({ status: 503 })
    expect(auth.authenticated).toBe(true)
  })

  it('clears only explicitly confirmed local logout while surfacing incomplete global logout', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.logout.mockRejectedValue(new ApiError(503, 'SSO_GLOBAL_LOGOUT_INCOMPLETE', '未完成', undefined, true))
    await expect(auth.signOut()).rejects.toMatchObject({ code: 'SSO_GLOBAL_LOGOUT_INCOMPLETE' })
    expect(auth.authenticated).toBe(false)
  })

  it('does not accept the logout error code alone as proof of local session destruction', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.logout.mockRejectedValue(new ApiError(503, 'SSO_GLOBAL_LOGOUT_INCOMPLETE', '未完成'))
    await expect(auth.signOut()).rejects.toBeInstanceOf(ApiError)
    expect(auth.authenticated).toBe(true)
  })

  it('does not resurrect a session when an older /me completes after logout', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    let complete!: (value: CurrentUser) => void
    api.currentUser.mockImplementation(() => new Promise<CurrentUser>(resolve => { complete = resolve }))
    const refresh = auth.refreshSession()
    await auth.signOut()
    complete(current())
    await refresh
    expect(auth.authenticated).toBe(false)
  })

  it('reloads complete authorization after changing business scope', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    api.currentUser.mockResolvedValue({ ...current(), menus: [], permissions: ['stockin:read'] })
    await auth.selectTenant('WH2', 'OWN2')
    expect(api.switchTenant).toHaveBeenCalledWith('WH2', 'OWN2')
    expect(auth.canWrite('stockin:write')).toBe(false)
    expect(auth.user?.menus).toEqual([])
  })

  it('allows readonly accounts to change their own password, not business data', async () => {
    api.currentUser.mockResolvedValue({ ...current(), readOnly: true, passwordChangeRequired: true })
    const auth = useAuthStore()
    await auth.restoreSession()
    await auth.updatePassword('old-password', 'new-password')
    expect(api.changePassword).toHaveBeenCalledWith('old-password', 'new-password')
    expect(auth.user?.passwordChangeRequired).toBe(false)
    expect(auth.canWrite()).toBe(false)
  })

  it('invalidates an older in-flight /me when switching tenant and loads the new scope', async () => {
    const auth = useAuthStore()
    await auth.restoreSession()
    let completeOld!: (value: CurrentUser) => void
    api.currentUser.mockImplementationOnce(() => new Promise<CurrentUser>(resolve => { completeOld = resolve }))
    const oldRefresh = auth.refreshSession()
    const next = current()
    next.tenant.currentWarehouse = { id: 2, code: 'WH2', name: '新仓库' }
    next.tenant.currentOwner = { id: 2, code: 'OWN2', name: '新货主' }
    next.permissions = ['stockin:read']
    next.authorizationVersion = 'v2'
    api.currentUser.mockResolvedValue(next)
    const switching = auth.selectTenant('WH2', 'OWN2')
    // Let CSRF preparation and the successful tenant response settle first.
    await vi.waitFor(() => expect(api.switchTenant).toHaveBeenCalledWith('WH2', 'OWN2'))
    completeOld(current())
    await Promise.all([oldRefresh, switching])
    expect(api.currentUser).toHaveBeenCalledTimes(3)
    expect(auth.user?.tenant.currentWarehouse.code).toBe('WH2')
    expect(auth.user?.tenant.currentOwner.code).toBe('OWN2')
    expect(auth.user?.authorizationVersion).toBe('v2')
    expect(auth.canWrite('stockin:write')).toBe(false)
  })
})
