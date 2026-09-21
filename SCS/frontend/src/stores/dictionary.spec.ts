import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDictionaryStore } from './dictionary'
import { getDictionaries } from '@/api/meta'
import type { BackendDictionaryResponse } from '@/types/dictionary'

vi.mock('@/api/meta', () => ({
  getDictionaries: vi.fn(),
}))

const mockedGetDictionaries = vi.mocked(getDictionaries)

const dictionaries: BackendDictionaryResponse = {
  areas: [{ code: 'LOADING_AREA_A', name: '装卸区 A' }],
  teams: [{ code: 'LOADING_TEAM_1', name: '装卸一班' }],
  assignees: [
    { id: 'USR-001', name: '李娜', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
    { id: 'USR-002', name: '王建国', teamCode: 'SAFETY_MANAGEMENT', teamName: '安全管理组', demoUnverified: false },
  ],
}

describe('useDictionaryStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads and exposes options and labels', async () => {
    mockedGetDictionaries.mockResolvedValue(dictionaries)

    const store = useDictionaryStore()
    await store.ensureLoaded()

    expect(mockedGetDictionaries).toHaveBeenCalledTimes(1)
    expect(store.loaded).toBe(true)
    expect(store.hasDictionary('assignees')).toBe(true)
    expect(store.getOptions('assignees').map((option) => option.value)).toEqual(['USR-001', 'USR-002'])
    expect(store.getLabel('assignees', 'USR-001')).toBe('李娜')
    // 区域/班组 value 为规范中文名，code 随行携带且可用 code 反查名称。
    expect(store.getOptions('areas')).toEqual([
      { value: '装卸区 A', label: '装卸区 A', code: 'LOADING_AREA_A' },
    ])
    expect(store.getLabel('areas', 'LOADING_AREA_A')).toBe('装卸区 A')
    expect(store.getLabel('areas', '装卸区 A')).toBe('装卸区 A')
  })

  it('exposes user team metadata from the same dictionary', async () => {
    mockedGetDictionaries.mockResolvedValue(dictionaries)
    const store = useDictionaryStore()
    await store.ensureLoaded()

    const liNa = store.getOption('assignees', 'USR-001')
    expect(liNa?.team).toBe('安全管理组')
    expect(liNa?.teamCode).toBe('SAFETY_MANAGEMENT')
  })

  it('deduplicates concurrent and repeated ensureLoaded calls', async () => {
    mockedGetDictionaries.mockImplementation(
      () => new Promise<BackendDictionaryResponse>((resolve) => window.setTimeout(() => resolve(dictionaries), 20)),
    )

    const store = useDictionaryStore()
    await Promise.all([store.ensureLoaded(), store.ensureLoaded()])
    await store.ensureLoaded()

    expect(mockedGetDictionaries).toHaveBeenCalledTimes(1)
  })

  it('keeps existing dictionaries and records refresh failure', async () => {
    mockedGetDictionaries.mockResolvedValueOnce(dictionaries)
    const store = useDictionaryStore()
    await store.ensureLoaded()

    mockedGetDictionaries.mockRejectedValueOnce(new Error('HTTP 500'))
    const result = await store.refresh()

    expect(result).toBeNull()
    expect(store.error).toContain('HTTP 500')
    expect(store.getLabel('assignees', 'USR-002')).toBe('王建国')
  })

  it('starts usable with empty dictionaries when initial load fails', async () => {
    mockedGetDictionaries.mockRejectedValue(new Error('network down'))

    const store = useDictionaryStore()
    const result = await store.ensureLoaded()

    expect(result).toBeNull()
    expect(store.loaded).toBe(false)
    expect(store.getOptions('assignees')).toEqual([])
    expect(store.hasDictionary('assignees')).toBe(false)
  })

  it('does not guess unknown codes: returns the raw value and warns', async () => {
    mockedGetDictionaries.mockResolvedValue(dictionaries)
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const store = useDictionaryStore()
    await store.ensureLoaded()

    expect(store.getLabel('areas', 'NO_SUCH_AREA')).toBe('NO_SUCH_AREA')
    expect(warnSpy).toHaveBeenCalled()
  })
})
