import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { aiEventApi, confidenceParam, timeBucketParam } from './aiEvents'

type FetchCall = [string | URL, RequestInit?]

function lastCall(): FetchCall {
  const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls as FetchCall[]
  return calls.at(-1)!
}

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

describe('ai event api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('gets ai-events list at the exact backend path', async () => {
    mockFetchOnce({ list: [], total: 0, page: 1, pageSize: 100, metrics: {}, facets: {} })
    await aiEventApi.getAiEvents({ page: 1, pageSize: 100 })
    const [url, init] = lastCall()
    expect(String(url)).toContain('/api/v1/ai-events?')
    expect(init?.method ?? 'GET').toBe('GET')
  })

  it('maps confidence / time bucket filters and drops 全部', async () => {
    expect(confidenceParam('高（≥85%）')).toBe('high')
    expect(confidenceParam('全部')).toBeUndefined()
    expect(timeBucketParam('近1小时')).toBe('1h')
    expect(timeBucketParam('全部时段')).toBeUndefined()
    mockFetchOnce({ list: [], total: 0 })
    await aiEventApi.getAiEvents({ type: '全部', confidence: 'low' })
    const text = String(lastCall()[0])
    expect(text).toContain('confidence=low')
    expect(text).not.toContain('type=')
  })

  it('posts confirm and carries linked alert id through adapter', async () => {
    mockFetchOnce({ id: 'AI-1', status: '已确认违规', linkedAlertId: 'ALM-9', type: '未佩戴安全帽' })
    const event = await aiEventApi.confirmAiEvent('AI-1', '李娜')
    const [, init] = lastCall()
    expect(init?.method).toBe('POST')
    expect((init?.headers as Headers).get('Idempotency-Key')).toBe('idem-key-1')
    expect(event.linkedAlertId).toBe('ALM-9')
    expect(event.status).toBe('已确认违规')
  })

  it('false-positive never expects an alert id and posts reason', async () => {
    mockFetchOnce({ id: 'AI-1', status: '误报', type: '人员滞留' })
    const event = await aiEventApi.markFalsePositive('AI-1', '光照问题')
    expect(event.linkedAlertId).toBeUndefined()
    const [, init] = lastCall()
    expect(JSON.parse(String(init?.body))).toMatchObject({ reason: '光照问题' })
  })

  it('covers the remaining mutation / simulation endpoints', async () => {
    const cases: Array<[string, () => Promise<unknown>, object]> = [
      ['uncertain', () => aiEventApi.markUncertain('A'), {}],
      ['assign', () => aiEventApi.assignAiEvent('A', { assignee: '安全员 王建国', priority: '紧急' }), { assignee: '安全员 王建国' }],
      ['process', () => aiEventApi.processAiEvent('A'), {}],
      ['close', () => aiEventApi.closeAiEvent('A'), {}],
    ]
    for (const [suffix, call, bodyPart] of cases) {
      mockFetchOnce({ id: 'A', type: '摄像头异常' })
      await call()
      const [url, init] = lastCall()
      expect(String(url)).toContain(`/api/v1/ai-events/A/${suffix}`)
      expect(init?.method).toBe('POST')
      expect(JSON.parse(String(init?.body))).toMatchObject(bodyPart)
    }
    mockFetchOnce({ id: 'AI-NEW', type: '未佩戴安全帽' })
    await aiEventApi.simulate('new')
    const [url, init] = lastCall()
    expect(String(url)).toBe('/api/v1/ai-events/simulate')
    expect(JSON.parse(String(init?.body))).toEqual({ kind: 'new' })
  })
})
