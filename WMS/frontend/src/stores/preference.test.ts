import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

async function load() {
  const module = await import('./preference')
  return { ...module, prefs: module.usePreferenceStore() }
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetModules()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  setActivePinia(createPinia())
})

describe('WMS appearance preferences', () => {
  it('applies the default theme at module load', async () => {
    const { prefs } = await load()
    expect(document.documentElement.dataset.theme).toBe('tech-blue')
    expect(prefs.layout).toBe('side')
  })
  it('keeps the four themes, three layouts and brand color aligned with the baseline', async () => {
    const { THEME_OPTIONS, LAYOUT_OPTIONS } = await load()
    expect(THEME_OPTIONS.map(t => t.id)).toEqual(['tech-blue', 'forest-green', 'purple-elegant', 'dark-pro'])
    expect(THEME_OPTIONS.every(t => t.colors[1] === '#409eff')).toBe(true)
    expect(LAYOUT_OPTIONS.map(l => l.id)).toEqual(['side', 'top', 'compact'])
  })
  it('persists and restores all twelve combinations using only the WMS key', async () => {
    const { prefs, THEME_OPTIONS, LAYOUT_OPTIONS } = await load()
    localStorage.setItem('b-iam-prefs', 'unchanged')
    for (const theme of THEME_OPTIONS) for (const layout of LAYOUT_OPTIONS) {
      prefs.setTheme(theme.id)
      prefs.setLayout(layout.id)
      expect(document.documentElement.dataset.theme).toBe(theme.id)
      expect(JSON.parse(localStorage.getItem('b-wms-prefs')!)).toEqual({ theme: theme.id, layout: layout.id })
      setActivePinia(createPinia())
      const restored = (await load()).prefs
      expect(restored.theme).toBe(theme.id)
      expect(restored.layout).toBe(layout.id)
    }
    expect(localStorage.getItem('b-iam-prefs')).toBe('unchanged')
  })
  it.each(['not-json', 'null', '{}', '{"theme":"unknown","layout":"invalid"}'])('falls back safely for stored %s', async raw => {
    localStorage.setItem('b-wms-prefs', raw)
    const { prefs } = await load()
    expect(prefs.theme).toBe('tech-blue')
    expect(prefs.layout).toBe('side')
  })
  it('restores the chosen theme before mounting', async () => {
    localStorage.setItem('b-wms-prefs', JSON.stringify({ theme: 'dark-pro', layout: 'compact' }))
    const { prefs } = await load()
    expect(document.documentElement.dataset.theme).toBe('dark-pro')
    expect(prefs.layout).toBe('compact')
  })
  it('resets theme and layout together', async () => {
    const { prefs } = await load()
    prefs.setTheme('dark-pro'); prefs.setLayout('top'); prefs.reset()
    expect(JSON.parse(localStorage.getItem('b-wms-prefs')!)).toEqual({ theme: 'tech-blue', layout: 'side' })
  })
  it('still switches appearance when storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    const { prefs } = await load()
    expect(() => { prefs.setTheme('dark-pro'); prefs.setLayout('top') }).not.toThrow()
    expect(document.documentElement.dataset.theme).toBe('dark-pro')
    expect(prefs.layout).toBe('top')
  })
})
