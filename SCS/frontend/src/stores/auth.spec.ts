import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { getCurrentUser } from '@/api/auth'
import type { CurrentUser } from '@/types/auth'
import { sharedLiveSocket } from '@/api/live'

vi.mock('@/api/auth', () => ({
  getCurrentUser: vi.fn(),
}))

const mockedGetCurrentUser = vi.mocked(getCurrentUser)

const demoUser: CurrentUser = {
  id: 'USR-001',
  name: '李娜',
  role: '安全员',
  team: '安全管理组',
  shift: 'night',
  online: true,
}

describe('useAuthStore', () => {
  it('enables V2 realtime only after an explicit server capability and stops on logout', () => {
    setActivePinia(createPinia())
    const enabled=vi.spyOn(sharedLiveSocket,'setEnabled')
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,menuOperations:{'SCS:rules':['VIEW']}})
    expect(enabled).toHaveBeenLastCalledWith(false)
    store.setCurrentUser({...demoUser,menuOperations:{'SCS:rules':['VIEW']},scopedRealtime:true})
    expect(enabled).toHaveBeenLastCalledWith(true)
    store.clear()
    expect(enabled).toHaveBeenLastCalledWith(false)
    enabled.mockRestore()
  })
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads current user from /auth/me', async () => {
    mockedGetCurrentUser.mockResolvedValue(demoUser)

    const store = useAuthStore()
    await store.ensureLoaded()

    expect(mockedGetCurrentUser).toHaveBeenCalledTimes(1)
    expect(store.user).toEqual(demoUser)
    expect(store.loaded).toBe(true)
    expect(store.error).toBeNull()
    expect(store.displayName).toBe('李娜')
    expect(store.shift).toBe('night')
  })

  it('deduplicates concurrent and repeated loads', async () => {
    mockedGetCurrentUser.mockImplementation(
      () => new Promise<CurrentUser>((resolve) => window.setTimeout(() => resolve(demoUser), 20)),
    )

    const store = useAuthStore()
    await Promise.all([store.ensureLoaded(), store.ensureLoaded()])
    await store.ensureLoaded()

    expect(mockedGetCurrentUser).toHaveBeenCalledTimes(1)
  })

  it('keeps the app usable and exposes an error when /auth/me fails', async () => {
    mockedGetCurrentUser.mockRejectedValue(new Error('HTTP 500'))

    const store = useAuthStore()
    const result = await store.ensureLoaded()

    expect(result).toBeNull()
    expect(store.user).toBeNull()
    expect(store.loaded).toBe(false)
    expect(store.error).toContain('HTTP 500')
  })

  it('force refresh requests current user again', async () => {
    mockedGetCurrentUser.mockResolvedValue(demoUser)

    const store = useAuthStore()
    await store.ensureLoaded()
    await store.refresh()

    expect(mockedGetCurrentUser).toHaveBeenCalledTimes(2)
  })

  it('keeps broad native permissions inside central menu and action limits', () => {
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,permissions:['safety:module:rule:edit','safety:module:rule:view','safety:module:ops:view'],
      menuOperations:{'SCS:rules':['VIEW','ADD']}})
    expect(store.can('safety:module:rule:view')).toBe(true)
    expect(store.can('safety:sys:view')).toBe(true)
    expect(store.can('safety:work:view')).toBe(false)
    expect(store.can('safety:module:ops:view')).toBe(false)
    expect(store.canAction('SCS:rules','ADD','safety:module:rule:edit')).toBe(true)
    expect(store.canAction('SCS:rules','EDIT','safety:module:rule:edit')).toBe(false)
  })

  it('does not enable a command without its native grant or for a readonly subject', () => {
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,readOnly:true,permissions:['safety:module:rule:edit'],
      menuOperations:{'SCS:rules':['ADD','VIEW']}})
    expect(store.canAction('SCS:rules','ADD','safety:module:rule:edit')).toBe(false)
    expect(store.canAction('SCS:rules','VIEW','safety:module:rule:view')).toBe(false)
  })

  it('keeps unsupported simulation disabled in V2 while preserving the old mode', () => {
    const store=useAuthStore()
    const permission='safety:module:rule:execute'
    store.setCurrentUser({...demoUser,permissions:[permission],menuOperations:{'SCS:rules':['CONFIRM']}})
    expect(store.canAction('SCS:rules','CONFIRM',permission,false)).toBe(false)
    store.setCurrentUser({...demoUser,permissions:[permission]})
    expect(store.canAction('SCS:rules','CONFIRM',permission,false)).toBe(true)
  })

  it('separates mobile home from incident pages despite their shared native permission', () => {
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,permissions:['safety:mobile:view'],menuOperations:{'SCS:mobile-home':['VIEW']}})
    expect(store.canPage('/mobile/home/')).toBe(true)
    expect(store.canPage('/mobile/alerts')).toBe(false)
    expect(store.canPage('/mobile/alert/event-1')).toBe(false)
    store.setCurrentUser({...demoUser,permissions:['safety:mobile:view'],menuOperations:{'SCS:mobile-alerts':['VIEW']}})
    expect(store.canPage('/mobile/alert/event-1')).toBe(true)
    expect(store.canPage('/mobile/home')).toBe(false)
    expect(store.canPage('/mobile/unknown')).toBe(false)
  })

  it('allows a group with an authorized child without requiring an unrelated rules menu', () => {
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,permissions:['safety:module:ops:view'],menuOperations:{'SCS:operations':['VIEW']}})
    expect(store.can('safety:sys:view')).toBe(true)
    expect(store.canPage('/operations')).toBe(true)
    expect(store.canPage('/rules')).toBe(false)
  })

  it('keeps global model editing at L1 even when a subsystem admin has the native ADMIN role', () => {
    const store=useAuthStore()
    const u={...demoUser,role:'ADMIN',centralAdminLevel:'L3_SUBSYSTEM_ADMIN',permissions:['safety:module:ai:model:edit'],menuOperations:{'SCS:ai-models':['EDIT','CONFIRM']}}
    store.setCurrentUser(u)
    expect(store.canAction('SCS:ai-models','EDIT','safety:module:ai:model:edit',true,true)).toBe(false)
    store.setCurrentUser({...u,centralAdminLevel:'L1_SUPER'})
    expect(store.canAction('SCS:ai-models','EDIT','safety:module:ai:model:edit',true,true)).toBe(true)
  })

  it('separates nonpersistent inference from escalation and keeps readonly inference usable', () => {
    const store=useAuthStore()
    store.setCurrentUser({...demoUser,readOnly:true,permissions:['safety:module:ai:model:execute'],menuOperations:{'SCS:ai-models':['VIEW','FAULT_ALARM']}})
    expect(store.canAction('SCS:ai-models','VIEW','safety:module:ai:model:execute')).toBe(true)
    expect(store.canAction('SCS:ai-models','FAULT_ALARM','safety:module:ai:model:execute')).toBe(false)
  })
})
