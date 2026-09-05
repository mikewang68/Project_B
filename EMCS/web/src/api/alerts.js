/**
 * 第三幕异常告警真实 API｜REQ-039–044
 * 契约：docs/mock-contracts.md §3.1
 */
import request from '@/utils/request'

export function getAlerts(query = {}) {
  return request({ url: '/alerts', method: 'get', params: query })
}

export function getAlertDetail(eventId) {
  return request({ url: `/alerts/${eventId}`, method: 'get' })
}

export function transitionAlert(eventId, body) {
  return request({ url: `/alerts/${eventId}/transition`, method: 'post', data: body })
}
