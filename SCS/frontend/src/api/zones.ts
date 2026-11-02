import { apiRequest } from './http'
import type { ZoneState } from '@/types/zone'

/** 装卸区状态 API —— 对应后端 ZoneController（/api/v1/zones）。 */
export const zonesApi = {
  list: async (): Promise<ZoneState[]> => {
    const data = await apiRequest<{ list?: ZoneState[] }>('/zones')
    return data.list ?? []
  },
}
