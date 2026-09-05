import { apiRequest, postJson } from './http'
import type { CollisionEquipment, CollisionPair, DistancePoint, LinkageStep } from '@/types/collision'
import { mapDevice, mapDeviceList, mapPair, mapSimulate, mapStepsResponse, mapTrend } from '@/adapters/collision'

/** 设备防碰撞 API Client —— 对应后端 CollisionController（/api/v1/collision）。 */
export const collisionApi = {
  devices: async (type?: string): Promise<CollisionEquipment[]> => {
    const qs = type ? `?type=${encodeURIComponent(type)}` : ''
    return mapDeviceList(await apiRequest(`/collision/devices${qs}`))
  },

  device: async (id: string): Promise<CollisionEquipment> =>
    mapDevice(await apiRequest(`/collision/devices/${encodeURIComponent(id)}`)),

  pair: async (currentId: string, relatedId?: string): Promise<CollisionPair> =>
    mapPair(await apiRequest(
      `/collision/pair?currentId=${encodeURIComponent(currentId)}${relatedId ? `&relatedId=${encodeURIComponent(relatedId)}` : ''}`,
    )),

  trend: async (id: string): Promise<DistancePoint[]> =>
    mapTrend(await apiRequest(`/collision/devices/${encodeURIComponent(id)}/distance-trend`)),

  simulate: async (deviceId: string, scenario: string) =>
    mapSimulate(await postJson(`/collision/simulate`, { deviceId, scenario })),

  linkage: async (deviceId: string, mode: 'success' | 'fail'): Promise<LinkageStep[]> =>
    mapStepsResponse(await postJson(`/collision/linkage`, { deviceId, mode })),

  takeover: async (deviceId: string, operator: string, note?: string) =>
    postJson<{ state: string; plcStatus: string; time: string; alertId: string }>(
      `/collision/takeover`,
      { deviceId, operator, note },
    ),

  releaseRequest: async (deviceId: string, operator: string, checks: string[]) =>
    postJson<{ requestId: string; status: string; createdAt: string }>(
      `/collision/release-request`,
      { deviceId, operator, checks },
    ),
}
