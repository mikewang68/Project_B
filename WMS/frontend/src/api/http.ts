import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

export interface ApiEnvelope<T> {
  code: string
  message: string
  requestId: string
  data: T
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
  ) {
    super(message)
  }
}

const http = axios.create({
  baseURL: '/',
  timeout: 10_000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
})

export function unwrapApiEnvelope<T>(body: ApiEnvelope<T>, status = 200): T {
  if (body.code !== 'OK') {
    throw new ApiError(status, body.code, body.message, body.requestId)
  }
  return body.data
}

export async function apiFetch<T>(path: string, config: AxiosRequestConfig = {}): Promise<T> {
  try {
    const response = await http.request<ApiEnvelope<T>>({ ...config, url: path })
    return unwrapApiEnvelope(response.data, response.status)
  } catch (reason) {
    if (reason instanceof ApiError) throw reason
    if (reason instanceof AxiosError) {
      const body = reason.response?.data as Partial<ApiEnvelope<unknown>> | undefined
      throw new ApiError(
        reason.response?.status ?? 0,
        body?.code ?? 'COMMON_NETWORK_ERROR',
        body?.message ?? '网络请求失败',
        body?.requestId,
      )
    }
    throw reason
  }
}
