import { apiFetch } from './http'

export interface TenantOption { id: number; code: string; name: string }
export interface TenantContext {
  companyCode: string
  companyName: string
  currentWarehouse: TenantOption
  currentOwner: TenantOption
  warehouses: TenantOption[]
  owners: TenantOption[]
}
export interface MenuItem { code: string; parentCode?: string; name: string; path: string; icon?: string; sortOrder: number }
export interface CurrentUser {
  id: number
  username: string
  displayName: string
  companyCode: string
  companyName: string
  roles: string[]
  permissions: string[]
  passwordChangeRequired: boolean
  menus: MenuItem[]
  tenant: TenantContext
}
export interface LoginCredentials { company: string; username: string; password: string }
interface CsrfToken { headerName: string; parameterName: string; token: string }

let csrf: CsrfToken | null = null

export async function prepareCsrf(): Promise<void> {
  csrf = await apiFetch<CsrfToken>('/api/v1/auth/csrf')
}

function csrfHeaders(): Record<string, string> {
  if (!csrf) throw new Error('CSRF令牌尚未初始化')
  return { [csrf.headerName]: csrf.token }
}

export const login = (credentials: LoginCredentials): Promise<CurrentUser> => apiFetch('/api/v1/auth/login', { method: 'POST', data: credentials, headers: csrfHeaders() })
export const currentUser = (): Promise<CurrentUser> => apiFetch('/api/v1/auth/me')
export const logout = (): Promise<void> => apiFetch('/api/v1/auth/logout', { method: 'POST', headers: csrfHeaders() })
export const switchTenant = (warehouseCode: string, ownerCode: string): Promise<TenantContext> => apiFetch('/api/v1/auth/tenant', { method: 'PUT', data: { warehouseCode, ownerCode }, headers: csrfHeaders() })
export const changePassword = (currentPassword: string, newPassword: string): Promise<void> => apiFetch('/api/v1/auth/password', { method: 'PUT', data: { currentPassword, newPassword }, headers: csrfHeaders() })
