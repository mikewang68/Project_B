/** 第五幕成本中心真实 API｜REQ-051–056、062、073/074 */
import request from '@/utils/request'

export function getCostMonthView(query = {}) {
  return request({ url: '/cost/month-view', method: 'get', params: query })
}

export function getCostTrace(query) {
  return request({ url: '/cost/trace', method: 'get', params: query })
}

export function getCostTariffs(query = {}) {
  return request({ url: '/cost/tariffs', method: 'get', params: query })
}

export function createCostTariff(data) {
  return request({ url: '/cost/tariffs', method: 'post', data })
}

export function getAllocationRules(query = {}) {
  return request({ url: '/cost/allocation-rules', method: 'get', params: query })
}

export function createAllocationRule(data) {
  return request({ url: '/cost/allocation-rules', method: 'post', data })
}

export function getCostRecomputations(query = {}) {
  return request({ url: '/cost/recomputations', method: 'get', params: query })
}

export function getCostRecomputation(recomputeId) {
  return request({ url: `/cost/recomputations/${recomputeId}`, method: 'get' })
}

export function createCostRecomputation(data) {
  return request({ url: '/cost/recomputations', method: 'post', data })
}

export function reviewCostRecomputation(recomputeId, data) {
  return request({ url: `/cost/recomputations/${recomputeId}/review`, method: 'post', data })
}

