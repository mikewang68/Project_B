import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { overviewApi } from './overview'

type FetchCall = [string | URL, RequestInit?]
function lastUrl(): string {
  const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls as FetchCall[]
  return String(calls.at(-1)![0])
}
function lastCall(): FetchCall {
  const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls as FetchCall[]
  return calls.at(-1)!
}

function mockFetchOnce(payload: unknown): void {
  const response = {
    ok: true,
    status: 200,
    headers: new Headers(),
    json: vi.fn(async () => payload),
    text: vi.fn(async () => JSON.stringify(payload)),
  } as unknown as Response
  globalThis.fetch = vi.fn(async () => response) as unknown as typeof fetch
}

describe('overview api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('calls the six overview read endpoints at exact paths', async () => {
    mockFetchOnce({})
    await overviewApi.getSummary()
    expect(lastUrl()).toBe('/api/v1/overview/summary')
    await overviewApi.getMap()
    expect(lastUrl()).toBe('/api/v1/overview/map')
    await overviewApi.getFeed(8)
    expect(lastUrl()).toBe('/api/v1/overview/alerts/feed?limit=8')
    await overviewApi.getTrend('24h')
    expect(lastUrl()).toBe('/api/v1/overview/risk-trend?range=24h')
    await overviewApi.getDistribution()
    expect(lastUrl()).toBe('/api/v1/overview/risk-distribution')
    await overviewApi.getAlertDetail('ALM-1')
    expect(lastUrl()).toBe('/api/v1/overview/alerts/ALM-1')
  })

  it('posts simulate-risk with active flag and idempotency header', async () => {
    mockFetchOnce({ active: true })
    await overviewApi.simulateRisk(true)
    const [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/overview/simulate-risk')
    expect(init?.method).toBe('POST')
    expect((init?.headers as Headers).get('Idempotency-Key')).toBe('idem-key-1')
    expect(JSON.parse(String(init?.body))).toEqual({ active: true })
  })
})
