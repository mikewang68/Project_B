import { apiRequest } from './http'
import type { ZonePresence, ZoneState } from '@/types/zone'

/** 装卸区状态 API —— 对应后端 ZoneController（/api/v1/zones）。 */
export const zonesApi = {
  list: async (): Promise<ZoneState[]> => {
    const data = await apiRequest<{ list?: ZoneState[] }>('/zones')
    return data.list ?? []
  },
  /** 固定作业区 + 已生效临时围栏的实时在场人数 */
  presence: async (): Promise<ZonePresence[]> => {
    const data = await apiRequest<{ presence?: ZonePresence[] }>('/zones')
    return data.presence ?? []
  },
}
