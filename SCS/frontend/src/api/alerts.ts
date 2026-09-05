import { apiRequest, postJson } from './http'
import type { AlertEvent, AlertMetrics, AlertPageResult, AlertRisk } from '@/types/alert'

/**
 * 告警中心 API Client —— 严格对应后端 AlertController 的 13 个接口（/api/v1/alerts）。
 * 所有写操作经 http.ts 自动携带 X-Trace-Id / Idempotency-Key。
 */

/** 列表查询参数（空值 / “全部”由 buildAlertQuery 过滤） */
export interface AlertListParams {
  keyword?: string | undefined
  risk?: string | undefined
  status?: string | undefined
  area?: string | undefined
  eventType?: string | undefined
  source?: string | undefined
  assignee?: string | undefined
  timeRange?: string | undefined
  from?: string | undefined
  to?: string | undefined
  page?: number | undefined
  pageSize?: number | undefined
}

export interface AssignPayload {
  assignee: string
  priority: '普通' | '紧急'
  limitMin: number
  note?: string | undefined
}

export interface TreatmentPayload {
  measures: string[]
  result: string
  attachment?: string | undefined
  note?: string | undefined
  riskResolved?: boolean | undefined
}

export interface ReviewPayload {
  reviewer?: string | undefined
  note?: string | undefined
}

export interface TransferPayload {
  assignee: string
  note?: string | undefined
}

export interface EscalatePayload {
  level: AlertRisk
  reason: string
  targets?: string[] | undefined
}

export interface TakeoverPayload {
  reason?: string | undefined
  note?: string | undefined
  operator?: string | undefined
}

function queryString(params: AlertListParams): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '' || value === '全部') return
    search.set(key, String(value))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const alertApi = {
  getAlertMetrics: () => apiRequest<AlertMetrics>('/alerts/metrics'),

  getAlerts: (params: AlertListParams = {}) =>
    apiRequest<AlertPageResult>(`/alerts${queryString(params)}`),

  getAlertDetail: (id: string) => apiRequest<AlertEvent>(`/alerts/${encodeURIComponent(id)}`),

  confirmAlert: (id: string, operator?: string) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/confirm`, { operator }),

  assignAlert: (id: string, payload: AssignPayload) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/assign`, payload),

  startAlert: (id: string, handler?: string) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/start`, { handler }),

  submitTreatment: (id: string, payload: TreatmentPayload) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/treatment`, payload),

  reviewAlert: (id: string, payload: ReviewPayload = {}) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/review`, payload),

  rejectReview: (id: string, reason: string) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/review-reject`, { reason }),

  transferAlert: (id: string, payload: TransferPayload) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/transfer`, payload),

  escalateAlert: (id: string, payload: EscalatePayload) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/escalate`, payload),

  takeoverAlert: (id: string, payload: TakeoverPayload = {}) =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/takeover`, payload),

  triggerLinkage: (id: string, mode: 'success' | 'fail') =>
    postJson<AlertEvent>(`/alerts/${encodeURIComponent(id)}/linkage`, { mode }),
}

// 具名导出，便于单测与按需引用
export const {
  getAlertMetrics,
  getAlerts,
  getAlertDetail,
  confirmAlert,
  assignAlert,
  startAlert,
  submitTreatment,
  reviewAlert,
  rejectReview,
  transferAlert,
  escalateAlert,
  takeoverAlert,
  triggerLinkage,
} = alertApi
