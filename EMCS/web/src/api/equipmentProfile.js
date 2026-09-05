/**
 * 第三幕设备能耗画像真实 API｜REQ-031–035
 * 契约：docs/mock-contracts.md §3.2
 */
import request from '@/utils/request'

export function getEquipmentProfiles(query = {}) {
  return request({ url: '/equipment-profiles', method: 'get', params: query })
}

export function getEquipmentProfile(equipmentId, query = {}) {
  return request({
    url: `/equipment-profiles/${encodeURIComponent(equipmentId)}`,
    method: 'get',
    params: query
  })
}
