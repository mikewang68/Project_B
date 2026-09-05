import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { alertApi } from './alerts'

function mockFetchOnce(payload: unknown, init?: { status?: number; body?: string }): Response {
  const response = {
    ok: (init?.status ?? 200) < 400,
    status: init?.status ?? 200,
    headers: new Headers({ 'X-Trace-Id': 'trace-xyz' }),
    json: vi.fn(async () => payload),
    text: vi.fn(async () => init?.body ?? JSON.stringify(payload)),
  } as unknown as Response
  globalThis.fetch = vi.fn(async () => response) as unknown as typeof fetch
  return response
}

describe('alert api client', () => {
  beforeEach(() => {
    globalThis.crypto.randomUUID = vi.fn(() => 'idem-key-1') as unknown as typeof crypto.randomUUID
  })
  afterEach(() => vi.restoreAllMocks())

  it('gets metrics from the exact backend path', async () => {
    mockFetchOnce({ total: 12 })
    await alertApi.getAlertMetrics()
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    const [url, init] = call
    expect(String(url)).toBe('/api/v1/alerts/metrics')
    expect(init.method ?? 'GET').toBe('GET')
  })

  it('builds list query and drops “全部” / empty params', async () => {
    mockFetchOnce({ list: [], total: 0 })
    await alertApi.getAlerts({ risk: '紧急', status: '全部', keyword: '', page: 2, pageSize: 10 })
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    const text = String(call[0])
    expect(text).toContain('/api/v1/alerts?')
    expect(text).toContain('risk=%E7%B4%A7%E6%80%A5')
    expect(text).not.toContain('status=')
    expect(text).not.toContain('keyword=')
    expect(text).toContain('page=2')
    expect(text).toContain('pageSize=10')
  })

  it('url-encodes the alert id in detail path', async () => {
    mockFetchOnce({ id: 'ALM-1' })
    await alertApi.getAlertDetail('ALM 2026 001')
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    expect(String(call[0])).toBe('/api/v1/alerts/ALM%202026%20001')
  })

  it('posts assign with payload and idempotency header', async () => {
    mockFetchOnce({ id: 'ALM-1', status: '待处理' })
    await alertApi.assignAlert('ALM-1', { assignee: '安全员 王建国', priority: '紧急', limitMin: 15 })
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]!
    const [, init] = call
    expect(init.method).toBe('POST')
    expect(init.headers.get('Idempotency-Key')).toBe('idem-key-1')
    expect(JSON.parse(init.body)).toMatchObject({ assignee: '安全员 王建国', limitMin: 15 })
  })

  it('covers the remaining mutation endpoints', async () => {
    const cases: Array<[string, () => Promise<unknown>, object]> = [
      ['confirm', () => alertApi.confirmAlert('A'), {}],
      ['start', () => alertApi.startAlert('A'), {}],
      ['treatment', () => alertApi.submitTreatment('A', { measures: ['x'], result: '风险已解除' }), { measures: ['x'] }],
      ['review', () => alertApi.reviewAlert('A'), {}],
      ['review-reject', () => alertApi.rejectReview('A', '原因'), { reason: '原因' }],
      ['transfer', () => alertApi.transferAlert('A', { assignee: '李娜' }), { assignee: '李娜' }],
      ['escalate', () => alertApi.escalateAlert('A', { level: '严重', reason: 'r' }), { level: '严重' }],
      ['takeover', () => alertApi.takeoverAlert('A', { reason: 'r' }), { reason: 'r' }],
      ['linkage', () => alertApi.triggerLinkage('A', 'fail'), { mode: 'fail' }],
    ]
    for (const [suffix, call, bodyPart] of cases) {
      mockFetchOnce({ ok: true })
      await call()
      const last = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.at(-1)!
      const [url, init] = last
      expect(String(url)).toContain(`/api/v1/alerts/A/${suffix}`)
      expect(init.method).toBe('POST')
      expect(JSON.parse(init.body)).toMatchObject(bodyPart)
    }
  })
})
