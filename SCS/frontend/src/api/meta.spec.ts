import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDictionaries } from './meta'

function mockFetchOnce(payload: unknown): void {
  const response = {
    ok: true,
    status: 200,
    headers: new Headers({ 'X-Trace-Id': 'trace-xyz' }),
    json: vi.fn(async () => payload),
    text: vi.fn(async () => JSON.stringify(payload)),
  } as unknown as Response
  globalThis.fetch = vi.fn(async () => response) as unknown as typeof fetch
}

describe('meta api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'trace-1') as unknown as typeof crypto.randomUUID
  })

  afterEach(() => vi.restoreAllMocks())

  it('gets all dictionaries from the exact backend path', async () => {
    mockFetchOnce({ areas: [], teams: [], assignees: [] })
    await getDictionaries()

    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    expect(String(call[0])).toBe('/api/v1/meta/dictionaries')
    expect(call[1]?.method ?? 'GET').toBe('GET')
  })

  it('passes comma-separated dictionary keys', async () => {
    mockFetchOnce({ assignees: [] })
    await getDictionaries(['teams', 'assignees'])

    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    expect(String(call[0])).toBe('/api/v1/meta/dictionaries?keys=teams%2Cassignees')
  })
})
