const API_BASE = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '')

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly traceId: string,
    /** 后端错误码，如 STATE_CONFLICT；无法解析时为空串 */
    readonly code: string = '',
    /** 后端错误体原文（开发排障用） */
    readonly details: unknown = undefined,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function traceId(): string {
  return crypto.randomUUID()
}

/** 解析后端统一错误体 { code, message, traceId, details, timestamp }，失败时回退通用文案 */
async function buildError(response: Response, requestTraceId: string): Promise<ApiError> {
  const headerTraceId = response.headers.get('X-Trace-Id') ?? requestTraceId
  const fallback = `请求失败：HTTP ${response.status}`
  try {
    const raw = await response.text()
    if (!raw) return new ApiError(fallback, response.status, headerTraceId)
    const body = JSON.parse(raw) as { code?: string; message?: string; traceId?: string; details?: unknown }
    const message = typeof body.message === 'string' && body.message.trim() ? body.message : fallback
    return new ApiError(message, response.status, body.traceId || headerTraceId, body.code || '', body.details)
  } catch {
    return new ApiError(fallback, response.status, headerTraceId)
  }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 8_000)
  const requestTraceId = traceId()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Trace-Id', requestTraceId)
  if (init.body) headers.set('Content-Type', 'application/json; charset=utf-8')
  if (init.method && init.method !== 'GET') headers.set('Idempotency-Key', traceId())

  try {
    const response = await fetch(`${API_BASE}${path}`, { ...init, headers, signal: controller.signal })
    if (!response.ok) throw await buildError(response, requestTraceId)
    return (await response.json()) as T
  } catch (error) {
    // AbortError（超时）与网络错误包装为统一 ApiError，调用方只需 catch ApiError
    if (error instanceof ApiError) {
      if (import.meta.env.DEV) console.warn(`[api] ${init.method || 'GET'} ${path} -> ${error.status} ${error.code || ''} traceId=${error.traceId}`)
      throw error
    }
    const aborted = error instanceof DOMException && error.name === 'AbortError'
    throw new ApiError(aborted ? '请求超时，请稍后重试' : '网络异常，无法连接服务', 0, requestTraceId, aborted ? 'TIMEOUT' : 'NETWORK_ERROR')
  } finally {
    window.clearTimeout(timeout)
  }
}

export const postJson = <T>(path: string, body: unknown = {}) =>
  apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body) })

export const putJson = <T>(path: string, body: unknown = {}) =>
  apiRequest<T>(path, { method: 'PUT', body: JSON.stringify(body) })
