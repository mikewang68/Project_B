import { describe, expect, it } from 'vitest'
import { nativeEntryUrl } from './entry'
const paths = ['/', '/inventory', '/stockin', '/system/users', '/:pathMatch(.*)*']
describe('registered entry routing', () => {
  it('opens the requested function instead of the dashboard', () => {
    expect(nativeEntryUrl('/inventory', '?keyword=A', '', paths)).toBe('/#/inventory?keyword=A')
    expect(nativeEntryUrl('/system/users', '', '', paths)).toBe('/#/system/users')
  })
  it('preserves a database-configured module prefix', () => {
    expect(nativeEntryUrl('/warehouse/stockin', '', '', paths)).toBe('/warehouse/#/stockin')
    expect(nativeEntryUrl('/warehouse/', '', '', paths)).toBeNull()
  })
  it('retains old hash links and sends unknown routes to the native missing page', () => {
    expect(nativeEntryUrl('/', '', '#/stockin', paths)).toBeNull()
    expect(nativeEntryUrl('/unknown', '', '', paths)).toBe('/#/unknown')
  })
  it('cannot turn an unusual leading slash into a cross-origin destination', () => {
    const value = nativeEntryUrl('//untrusted.example/stockin', '', '', paths)
    expect(value).toBe('/untrusted.example/#/stockin')
  })
})
