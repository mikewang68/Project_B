/** 当前登录用户信息，对应 GET /api/v1/auth/me */
export interface CurrentUser {
  id: string
  name: string
  role: string
  team: string
  shift: 'day' | 'night' | (string & {})
  online: boolean
}
