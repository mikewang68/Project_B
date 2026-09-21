import { apiRequest } from './http'
import type { CurrentUser } from '@/types/auth'

/** 获取当前演示用户。后端未启用登录，返回 DemoUserProperties 中的固定用户。 */
export function getCurrentUser(signal?: AbortSignal): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/auth/me', signal ? { signal } : undefined)
}
