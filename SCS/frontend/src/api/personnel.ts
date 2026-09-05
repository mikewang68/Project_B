import { apiRequest, postJson } from './http'
import type { AlertEvent } from '@/types/alert'
import type { LivePosition, PersonnelRecord, TrackPoint } from '@/types/personnel'
import { mapLivePositions, mapPerson, mapPersonnelList, mapTrack } from '@/adapters/personnel'

/** 人员定位 API Client —— 对应后端 PersonnelController（/api/v1/personnel）。 */
export interface PersonnelQuery {
  [key: string]: unknown
  keyword?: string
  team?: string
  area?: string
  state?: string
  bracelet?: string
  onlyAbnormal?: boolean | undefined
}

function qs(params: Record<string, unknown>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    search.set(k, String(v))
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export const personnelApi = {
  list: async (query: PersonnelQuery = {}): Promise<ReturnType<typeof mapPersonnelList>> =>
    mapPersonnelList(await apiRequest(`/personnel${qs(query)}`)),

  detail: async (id: string): Promise<PersonnelRecord> =>
    mapPerson(await apiRequest(`/personnel/${encodeURIComponent(id)}`)),

  track: async (id: string): Promise<TrackPoint[]> =>
    mapTrack(await apiRequest(`/personnel/${encodeURIComponent(id)}/track`)),

  live: async (area?: string): Promise<LivePosition[]> =>
    mapLivePositions(await apiRequest(`/personnel/live${qs({ area })}`)),

  alerts: async (id: string, limit = 10): Promise<AlertEvent[]> => {
    const raw = (await apiRequest(`/personnel/${encodeURIComponent(id)}/alerts${qs({ limit })}`)) as { list?: AlertEvent[] }
    return Array.isArray(raw.list) ? raw.list : []
  },

  remind: async (id: string, message?: string): Promise<{ sent: boolean; time: string; message: string }> =>
    postJson(`/personnel/${encodeURIComponent(id)}/remind`, { message }),

  simulateAbnormal: async (id: string, kind: string): Promise<PersonnelRecord> =>
    mapPerson(await postJson(`/personnel/simulate-abnormal`, { id, kind })),
}
