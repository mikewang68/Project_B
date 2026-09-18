/**
 * IAM 后端 REST API 封装（context-path: /api/v1/iam）。
 */
import { createClient } from './request'
import type { Role, User } from '@/iam/types'
import type { MenuNode } from '@/iam/menu-tree'
import type { OrgNode } from '@/iam/org-tree'

export const TOKEN_KEY = 'b-iam-token'
const client = createClient({ tokenKey: TOKEN_KEY, prefix: 'iam' })

export interface LoginResult {
  token: string
  user: User
  roles: Role[]
  permissions: string[]
}

export interface MeResult {
  user: { id: string; username: string; name: string }
  roles: Array<Pick<Role, 'id' | 'name' | 'code'>>
  permissions: string[]
  superAdmin: boolean
}

export interface UserUpsertPayload {
  username?: string
  name: string
  phone?: string
  email?: string
  password?: string
  roleIds: string[]
  orgCodes?: string[]
  status: User['status']
}

export interface RoleUpsertPayload {
  name: string
  code?: string
  description?: string
  permCodes?: string[]
  status: Role['status']
}

/** 后端 User 不回传密码，前端表单统一按空串处理 */
function normalizeUser(u: User): User {
  return { ...u, password: '' }
}

export const iamApi = {
  token: client,

  // ---------------- 认证 ----------------
  login: (username: string, password: string) =>
    client.request<LoginResult>('/auth/login', { method: 'POST', body: { username, password } }),
  logout: () => client.request<void>('/auth/logout', { method: 'POST' }),
  me: () => client.request<MeResult>('/auth/me'),

  // ---------------- 用户 ----------------
  listUsers: () => client.request<User[]>('/users').then((list) => list.map(normalizeUser)),
  getUser: (id: string) => client.request<User>(`/users/${id}`).then(normalizeUser),
  createUser: (payload: UserUpsertPayload) =>
    client.request<User>('/users', { method: 'POST', body: payload }).then(normalizeUser),
  updateUser: (id: string, payload: Partial<UserUpsertPayload>) =>
    client.request<User>(`/users/${id}`, { method: 'PUT', body: payload }).then(normalizeUser),
  deleteUser: (id: string) => client.request<void>(`/users/${id}`, { method: 'DELETE' }),
  toggleUserStatus: (id: string, status: User['status']) =>
    client.request<User>(`/users/${id}/status`, { method: 'PUT', body: { status } }).then(normalizeUser),
  resetPassword: (id: string, newPassword: string) =>
    client.request<void>(`/users/${id}/password`, { method: 'PUT', body: { password: newPassword } }),
  assignUserRoles: (id: string, roleIds: string[]) =>
    client.request<void>(`/users/${id}/roles`, { method: 'PUT', body: { roleIds } }),

  // ---------------- 角色 ----------------
  listRoles: () => client.request<Role[]>('/roles'),
  getRole: (id: string) => client.request<Role>(`/roles/${id}`),
  createRole: (payload: RoleUpsertPayload) =>
    client.request<Role>('/roles', { method: 'POST', body: payload }),
  updateRole: (id: string, payload: Partial<RoleUpsertPayload>) =>
    client.request<Role>(`/roles/${id}`, { method: 'PUT', body: payload }),
  deleteRole: (id: string) => client.request<void>(`/roles/${id}`, { method: 'DELETE' }),
  toggleRoleStatus: (id: string, status: Role['status']) =>
    client.request<Role>(`/roles/${id}/status`, { method: 'PUT', body: { status } }),
  assignRolePermissions: (id: string, permCodes: string[]) =>
    client.request<Role>(`/roles/${id}/permissions`, { method: 'PUT', body: { permCodes } }),

  // ---------------- 权限目录 / 组织 ----------------
  permissionTree: () => client.request<MenuNode[]>('/permissions/tree'),
  orgTree: () => client.request<OrgNode[]>('/orgs/tree'),
}
