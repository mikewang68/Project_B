import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screenApi } from './screen'
import { mobileApi } from './mobile'

/** 同一个 fetch mock 按调用次序返回不同 payload，保证 mock.calls 完整累积 */
function installFetch(payloads: unknown[]): ReturnType<typeof vi.fn> {
  let idx = 0
  const fetchMock = vi.fn(async () => {
    const payload = payloads[Math.min(idx++, payloads.length - 1)]
    return {
      ok: true,
      status: 200,
      headers: new Headers(),
      json: vi.fn(async () => payload),
      text: vi.fn(async () => JSON.stringify(payload)),
    } as unknown as Response
  })
  globalThis.fetch = fetchMock as unknown as typeof fetch
  return fetchMock
}

describe('screen projection api', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'trace-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('uses exact backend projection paths', async () => {
    const fetchMock = installFetch([{ todayAlerts: 0 }, [], null])
    await screenApi.getOverview()
    await screenApi.getFeed(8)
    await screenApi.getCritical()
    const urls = fetchMock.mock.calls.map((c) => String(c[0]))
    expect(urls[0]).toBe('/api/v1/screen/overview')
    expect(urls[1]).toBe('/api/v1/screen/alerts/feed?limit=8')
    expect(urls[2]).toBe('/api/v1/screen/critical')
  })
})

describe('mobile projection api', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('gets home and posts accept/arrive against the same alert root', async () => {
    const fetchMock = installFetch([{ pending: 1 }, { id: 'ALM-1' }, { id: 'ALM-1' }])
    await mobileApi.getHome()
    await mobileApi.accept('ALM-1')
    await mobileApi.arrive('ALM-1')
    const [homeCall, acceptCall, arriveCall] = fetchMock.mock.calls
    expect(String(homeCall![0])).toBe('/api/v1/mobile/home')
    expect(String(acceptCall![0])).toBe('/api/v1/mobile/incidents/ALM-1/accept')
    expect(String(arriveCall![0])).toBe('/api/v1/mobile/incidents/ALM-1/arrive')
    expect(acceptCall![1].method).toBe('POST')
  })
})
