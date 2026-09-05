import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ruleApi } from './rules'

type FetchCall = [string | URL, RequestInit?]
function calls(): FetchCall[] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls as FetchCall[]
}
function lastCall(): FetchCall {
  return calls().at(-1)!
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

describe('rule api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('uses exact paths and query string for reads', async () => {
    mockFetchOnce({ list: [] })
    await ruleApi.metrics()
    expect(String(lastCall()[0])).toBe('/api/v1/rules/metrics')
    await ruleApi.list({ category: '设备安全', status: '已生效' })
    expect(String(lastCall()[0])).toBe('/api/v1/rules?category=%E8%AE%BE%E5%A4%87%E5%AE%89%E5%85%A8&status=%E5%B7%B2%E7%94%9F%E6%95%88')
    await ruleApi.detail('RULE-DEV-003')
    expect(String(lastCall()[0])).toBe('/api/v1/rules/RULE-DEV-003')
    await ruleApi.versions('RULE-PER-001')
    expect(String(lastCall()[0])).toBe('/api/v1/rules/RULE-PER-001/versions')
  })

  it('posts lifecycle actions with body', async () => {
    mockFetchOnce({ id: 'RULE-DEV-003', status: '已生效' })
    await ruleApi.approve('RULE-DEV-003', { confirmHighRisk: true })
    let [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/rules/RULE-DEV-003/approve')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toEqual({ confirmHighRisk: true })

    await ruleApi.publish('RULE-DEV-003')
    expect(String(lastCall()[0])).toBe('/api/v1/rules/RULE-DEV-003/publish')

    await ruleApi.rollback('RULE-PER-001', 'v3.2')
    ;[url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/rules/RULE-PER-001/rollback')
    expect(JSON.parse(String(init?.body))).toEqual({ targetVersion: 'v3.2' })
  })

  it('posts simulation and conflict-check at static paths', async () => {
    mockFetchOnce({ level: '紧急风险', actions: ['设备停机'] })
    await ruleApi.simulate({ ruleId: 'RULE-DEV-003', distance: 2.8, relSpeed: 1.8, direction: '接近', radarQuality: 97, weather: '晴' })
    expect(String(lastCall()[0])).toBe('/api/v1/rules/simulate')
    await ruleApi.conflictCheck()
    expect(String(lastCall()[0])).toBe('/api/v1/rules/conflict-check')
  })
})
