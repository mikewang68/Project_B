/**
 * 原生 Fetch 封装（对齐统一基线：trace_id/超时/错误码/鉴权；非幂等写不自动重试）
 * REST API：/api/v1，JSON UTF-8，统一分页/错误码
 */

export interface ApiError {
  code: number
  message: string
  traceId?: string
}

export interface RestOptions {
  /** 超时 ms，默认 10000 */
  timeout?: number
  /** 是否自动重试（幂等读可重试），默认 false */
  retry?: number
  /** 鉴权 token */
  token?: string
}

import { API_BASE_URL } from '@/config/runtime'

// 后端入口可经 VITE_API_BASE_URL 配置，默认同源 /api/v1（Nginx 反代 Easegress）。
const BASE_URL = API_BASE_URL

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  options: RestOptions = {},
): Promise<T> {
  const timeout = options.timeout ?? 10000
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)

  const traceId = crypto.randomUUID?.() ?? `${Date.now()}-${Math.random()}`

  try {
    const res = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        'X-Trace-Id': traceId,
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    })

    if (!res.ok) {
      let err: ApiError = { code: res.status, message: `HTTP ${res.status}` }
      try {
        const data = await res.json()
        err = { ...err, ...data }
      } catch {
        /* 非 JSON 响应 */
      }
      throw err
    }

    return (await res.json()) as T
  } finally {
    clearTimeout(timer)
  }
}

export const restClient = {
  get<T>(path: string, options?: RestOptions) {
    return request<T>('GET', path, undefined, options)
  },
  post<T>(path: string, body?: unknown, options?: RestOptions) {
    return request<T>('POST', path, body, options)
  },
  put<T>(path: string, body?: unknown, options?: RestOptions) {
    return request<T>('PUT', path, body, options)
  },
  delete<T>(path: string, options?: RestOptions) {
    return request<T>('DELETE', path, undefined, options)
  },
}
