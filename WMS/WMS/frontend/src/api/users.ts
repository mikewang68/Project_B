import { apiFetch } from './http'

export interface UserView {
  id: number; username: string; mobile?: string; displayName: string; status: string
  loginEnabled: boolean; passwordChangeRequired: boolean; roles: string[]; warehouses: string[]; owners: string[]
}
export interface RoleOption { code: string; name: string }
export interface SaveUserRequest {
  username: string; mobile: string; displayName: string; password?: string; status: string
  loginEnabled: boolean; roleCodes: string[]; warehouseCodes: string[]; ownerCodes: string[]
}

export const listUsers = (): Promise<UserView[]> => apiFetch('/api/v1/system/users')
export const listRoles = (): Promise<RoleOption[]> => apiFetch('/api/v1/system/roles')
export async function saveUser(id: number | null, body: SaveUserRequest): Promise<UserView> {
  const csrf = await apiFetch<{ headerName: string; token: string }>('/api/v1/auth/csrf')
  return apiFetch(id ? `/api/v1/system/users/${id}` : '/api/v1/system/users', {
    method: id ? 'PUT' : 'POST', data: body, headers: { [csrf.headerName]: csrf.token },
  })
}
