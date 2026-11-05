/** 当前登录用户信息，对应 GET /api/v1/auth/me */
export interface CurrentUser {
  id: string
  name: string
  role: string
  team: string
  shift: 'day' | 'night' | (string & {})
  online: boolean
  permissions?: string[]
  readOnly?: boolean
  authorizationVersion?: string
  csrfToken?: string
  menuOperations?: Record<string, string[]> | null
  centralAdminLevel?: string | null
  scopedRealtime?: boolean
}
