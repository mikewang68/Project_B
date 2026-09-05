import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest } from './http'

function asApiError(p: Promise<unknown>): Promise<ApiError> {
  return p.then(
    () => {
      throw new Error('should have rejected')
    },
    (e) => e as ApiError,
  )
}

function mockErrorResponse(status: number, body: unknown): void {
  globalThis.fetch = vi.fn(async () => ({
    ok: false,
    status,
    headers: new Headers({ 'X-Trace-Id': 'header-trace' }),
    text: async () => JSON.stringify(body),
  })) as unknown as typeof fetch
}

describe('http client error mapping', () => {
  afterEach(() => vi.restoreAllMocks())

  it('surfaces backend message/code/traceId on HTTP 409 STATE_CONFLICT', async () => {
    mockErrorResponse(409, {
      code: 'STATE_CONFLICT',
      message: '当前状态不允许执行该操作（当前状态：已关闭）',
      traceId: 'body-trace',
    })
    const error = await asApiError(apiRequest('/alerts/A/start', { method: 'POST' }))
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(409)
    expect(error.code).toBe('STATE_CONFLICT')
    expect(error.message).toBe('当前状态不允许执行该操作（当前状态：已关闭）')
    // body.traceId 优先于响应头
    expect(error.traceId).toBe('body-trace')
  })

  it('falls back to generic text when error body is empty', async () => {
    globalThis.fetch = vi.fn(async () => ({
      ok: false,
      status: 500,
      headers: new Headers(),
      text: async () => '',
    })) as unknown as typeof fetch
    const error = await asApiError(apiRequest('/alerts/metrics'))
    expect(error.message).toBe('请求失败：HTTP 500')
    expect(error.traceId).toBeTruthy()
  })
})
