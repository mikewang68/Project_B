import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { getCurrentUser } from '@/api/auth'
import type { CurrentUser } from '@/types/auth'

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
})
