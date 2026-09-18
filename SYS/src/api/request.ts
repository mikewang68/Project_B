/**
 * 统一 REST 请求客户端（fetch 实现，无额外依赖）。
 * - baseURL：VITE_API_BASE_URL，默认 /api/v1（dev 由 Vite 代理到后端）
 * - 鉴权：Bearer JWT（由 IAM 登录签发，SYS 本地验签），token 仅存 localStorage
 * - 响应约定：后端统一返回 { code, message, data }，code === 0 为成功
 * - 401：清除 token 并派发 app:unauthorized 事件，由路由/登录页处理
 */

export class ApiError extends Error {
  status: number
  code: number

  constructor(status: number, code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | undefined | null>
  /** 直接返回原始 Response（如下载文件），不走统一信封解析 */
  raw?: boolean
}

export interface ApiClientOptions {
  tokenKey: string
}

export interface ApiClient {
  request: <T>(path: string, options?: RequestOptions) => Promise<T>
  getToken: () => string | null
  setToken: (token: string) => void
  clearToken: () => void
}

export function createClient(options: ApiClientOptions): ApiClient {
  const { tokenKey } = options

  const getToken = () => localStorage.getItem(tokenKey)
  const setToken = (token: string) => localStorage.setItem(tokenKey, token)
  const clearToken = () => localStorage.removeItem(tokenKey)

  const baseUrl = () =>
    `${((import.meta.env.VITE_API_BASE_URL as string) || '/api/v1').replace(/\/$/, '')}`

  async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
    const method = opts.method || 'GET'
    let url = baseUrl() + path
    if (opts.query) {
      const usp = new URLSearchParams()
      for (const [k, v] of Object.entries(opts.query)) {
        if (v !== undefined && v !== null && v !== '') usp.append(k, String(v))
      }
      const qs = usp.toString()
      if (qs) url += `?${qs}`
    }

    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json'

    let resp: Response
    try {
      resp = await fetch(url, {
        method,
        headers,
        body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      })
    } catch {
      throw new ApiError(0, 0, '无法连接后端服务，请确认后端已启动')
    }

    if (opts.raw) {
      if (!resp.ok) {
        throw new ApiError(resp.status, resp.status, `请求失败（${resp.status}）`)
      }
      return resp as unknown as T
    }

    let payload: { code?: number; message?: string; data?: unknown } = {}
    const text = await resp.text()
    if (text) {
      try {
        payload = JSON.parse(text)
      } catch {
        payload = {}
      }
    }

    if (resp.status === 401) {
      clearToken()
      window.dispatchEvent(new CustomEvent('app:unauthorized'))
      throw new ApiError(401, 401, payload.message || '未登录或登录已过期')
    }
    if (!resp.ok || (payload.code !== undefined && payload.code !== 0)) {
      const status = resp.status
      throw new ApiError(status, payload.code ?? status, payload.message || `请求失败（${status}）`)
    }
    return payload.data as T
  }

  return { request, getToken, setToken, clearToken }
}
