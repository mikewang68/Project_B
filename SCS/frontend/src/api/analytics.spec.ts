import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { analyticsApi } from './analytics'

type FetchCall = [string | URL, RequestInit?]
function lastCall(): FetchCall {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1) as FetchCall
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

describe('analytics api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('requests dataset/events with filters', async () => {
    mockFetchOnce({ kpi: [], trend: [] })
    await analyticsApi.dataset({ period: '本周', area: '装卸区 A' })
    // URLSearchParams 按标准把空格编码为 “+”，后端可正常解析
    expect(String(lastCall()[0])).toBe('/api/v1/analytics/dataset?period=%E6%9C%AC%E5%91%A8&area=%E8%A3%85%E5%8D%B8%E5%8C%BA+A')
    mockFetchOnce({ list: [], total: 0 })
    await analyticsApi.events({ deviceId: 'G-CRANE-01', page: 1, pageSize: 200 })
    expect(String(lastCall()[0])).toContain('/analytics/events?')
    expect(String(lastCall()[0])).toContain('deviceId=G-CRANE-01')
  })

  it('posts simulate-surge and maps embedded dataset', async () => {
    mockFetchOnce({ active: true, area: '装卸区 A', dataset: { kpi: [], trend: [] } })
    const res = await analyticsApi.simulateSurge({ active: true, area: '装卸区 A' })
    expect(res.active).toBe(true)
    expect(String(lastCall()[0])).toBe('/api/v1/analytics/simulate-surge')
    expect(lastCall()[1]?.method).toBe('POST')
  })

  it('requests device/person detail by id', async () => {
    mockFetchOnce({})
    await analyticsApi.deviceDetail('G-CRANE-01')
    expect(String(lastCall()[0])).toBe('/api/v1/analytics/devices/G-CRANE-01')
    await analyticsApi.personDetail('P-1003')
    expect(String(lastCall()[0])).toBe('/api/v1/analytics/persons/P-1003')
  })
})
