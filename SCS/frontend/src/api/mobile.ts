import { apiRequest, postJson } from './http'
import type { AlertEvent } from '@/types/alert'

/** 移动端首页投影（Alert 聚合，无独立数据源） */
export interface MobileHomeItem {
  id: string
  title: string
  risk: string
  area: string
  target: string
  time: string
  status: string
  mobileStage: string | null
  assignee: string
  occurredAt: string | null
}
export interface MobileHome {
  userName: string
  role: string
  team: string
  shift: string
  pending: number
  handling: number
  urgent: number
  closedToday: number
  pendingItems: MobileHomeItem[]
  handlingItems: MobileHomeItem[]
}

export const mobileApi = {
  getHome: () => apiRequest<MobileHome>('/mobile/home'),
  /** 移动端接单：mobileStage → ACCEPTED，主状态保持待处理 */
  accept: (id: string, handler = '王建国') =>
    postJson<AlertEvent>(`/mobile/incidents/${encodeURIComponent(id)}/accept`, { handler }),
  /** 移动端确认到场：ACCEPTED → ARRIVED */
  arrive: (id: string, handler = '王建国') =>
    postJson<AlertEvent>(`/mobile/incidents/${encodeURIComponent(id)}/arrive`, { handler }),
}
