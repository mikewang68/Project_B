/**
 * 第四幕节能建议闭环真实 API｜REQ-045–050、REQ-091/096
 * 契约：docs/mock-contracts.md §5
 */
import request from '@/utils/request'
import { assertSuggestionRequestSafe } from '@/views/energy/shared/act4'

function safeBody(body = {}) {
  return assertSuggestionRequestSafe(body)
}

function requireRowVersion(rowVersion) {
  if (rowVersion == null) throw new Error('rowVersion 为必填并发版本')
  return rowVersion
}

export function getSuggestions(query = {}) {
  return request({ url: '/suggestions', method: 'get', params: query })
}

export function getSuggestionDetail(suggestionId) {
  return request({ url: `/suggestions/${suggestionId}`, method: 'get' })
}

export function getSuggestionRetrospective(query = {}) {
  return request({ url: '/suggestions/retrospective', method: 'get', params: query })
}

export function createSuggestionFromAlert(eventId, body = {}) {
  return request({
    url: `/alerts/${eventId}/suggestions`, method: 'post', data: safeBody(body),
    headers: { repeatSubmit: false }
  })
}

export function createManualSuggestion(body) {
  return request({ url: '/suggestions', method: 'post', data: safeBody(body) })
}

// REQ-051~056：财务窄授权仅透传成本服务返回的 sourceContext 定位键。
export function createCostSuggestion(body, sourceContext) {
  return createManualSuggestion({ ...body, sourceContext })
}

export function getSuggestionTemplates(query = {}) {
  return request({ url: '/suggestion-templates', method: 'get', params: query })
}

export function createSuggestionTemplate(body) {
  return request({ url: '/suggestion-templates', method: 'post', data: safeBody(body) })
}

export function updateSuggestionTemplate(templateId, body) {
  return request({
    url: `/suggestion-templates/${templateId}`, method: 'put', data: safeBody(body)
  })
}

export function transitionSuggestion(suggestionId, body = {}) {
  const data = { ...body, rowVersion: requireRowVersion(body.rowVersion) }
  return request({
    url: `/suggestions/${suggestionId}/transition`, method: 'post', data: safeBody(data)
  })
}

export function addSuggestionActivity(suggestionId, { remark, attachments, rowVersion }) {
  return request({
    url: `/suggestions/${suggestionId}/activities`, method: 'post',
    data: safeBody({ remark, attachments, rowVersion: requireRowVersion(rowVersion) })
  })
}

export function generateSuggestionVerification(suggestionId, { rowVersion }) {
  return request({
    url: `/suggestions/${suggestionId}/verification/generate`, method: 'post',
    data: safeBody({ rowVersion: requireRowVersion(rowVersion) })
  })
}
