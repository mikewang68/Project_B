import { createClient } from './request'
import { TOKEN_KEY } from './iam'

const client = createClient({ tokenKey: TOKEN_KEY, prefix: 'iam' })
export interface ScopeTriple { projectId: string; regionId: string; subsystemCode: string }
export interface ScopeSelection { scopes: ScopeTriple[]; global: boolean }
export interface UserContext {
  contextVersion: 2; userId: string; username: string; displayName: string; userCode: string
  adminLevel: 'L1_SUPER' | 'L1_READONLY' | 'L2_PROJECT' | 'L3_SUBSYSTEM' | 'L4_OPERATOR'
  readOnly: boolean; serviceAudience: string; projectId: string | null; regionId: string | null; subsystemCode: string | null
  scopeMode: 'GLOBAL' | 'SCOPED' | 'DENY'; allowedScopes: ScopeTriple[]; activeScope: ScopeTriple[]
  managementCapabilities: string[]; menuOperations: Record<string, string[]>; visibleMenuKeys: string[]
  authorizationVersion: string; my_info: string; my_project_zone_info: string
  my_info_template: string; my_project_zone_info_template: string; scope_params: Record<string, string>
}
export interface Project { project_id: string; project_name: string; company_name: string | null; status: string; authz_version: number }
export interface Region { project_id: string; region_id: string; region_name: string; region_type: string; status: string }
export interface Application { app_code: string; display_name: string; enabled: number; context_contract_max: number; iam_system_code: string; entry_base_path: string; row_version: number }
export interface Operation { op_code: string; op_name: string; op_category: string; effect_kind: string; allow_readonly: boolean; status: string }
export interface SystemGrant { project_id: string; region_id: string; subsystem_code: string; l1_allowed: boolean; l2_enabled: boolean }
export interface MenuGrant extends Omit<SystemGrant, 'l2_enabled'> { stable_menu_key: string; op_code: string; l2_checked: boolean }
export interface Menu { stable_menu_key: string; navigation_app_code: string; menu_name: string; feature_status: string; enabled: number; nav_eligible: number }
export interface Overview {
  userContext: UserContext; projects: Project[]; regions: Region[]; applications: Application[]
  operations: Operation[]; systemGrants: SystemGrant[]; menuGrants: MenuGrant[]; menus: Menu[]
  menuOperations: Array<Menu & { op_code: string; action_enabled: boolean }>
  organizationCodes: Array<{ org_code: string; org_name: string; org_type: string }>
}
export interface ManagedUser {
  id: string; username: string; display_name: string; status: string; scope_state: string; admin_level: UserContext['adminLevel'] | null
  read_only: boolean; project_id: string | null; region_id: string | null; subsystem_code: string | null; roleIds: string[]
}
export interface Action { menuKey: string; action: string }
export interface ManagedRole { role_scope: string; project_id: string | null; id: string; role_name: string; business_role_code: string; status: 'active' | 'inactive'; operations: Action[] }
export interface RoleOption extends Action { name: string }
export const scopeApi = {
  context: () => client.request<UserContext>('/auth/context'),
  selectContext: (body: ScopeSelection) => client.request<UserContext>('/auth/context/selection', { method: 'POST', body }),
  overview: () => client.request<Overview>('/management/overview'),
  users: () => client.request<ManagedUser[]>('/management/users'),
  roles: () => client.request<ManagedRole[]>('/management/roles'),
  roleOptions: () => client.request<RoleOption[]>('/management/role-options'),
  availability: (username: string) => client.request<{ available: boolean; normalized: string }>('/management/users/availability', { query: { username } }),
  createUser: (body: unknown, key: string) => client.request<{ id: string; fabricIdentity?: { state: string } }>('/management/users', { method: 'POST', body, headers: { 'Idempotency-Key': key } }),
  userStatus: (id: string, status: string) => client.request('/management/users/'+id+'/status', { method: 'PUT', body: { status } }),
  resetPassword: (id: string, password: string) => client.request('/management/users/'+id+'/password', { method: 'PUT', body: { password } }),
  assignRoles: (id: string, roleIds: string[]) => client.request('/management/users/'+id+'/roles', { method: 'PUT', body: { roleIds } }),
  deleteUser: (id: string) => client.request('/management/users/'+id, { method: 'DELETE' }),
  saveRole: (id: string | null, body: unknown) => client.request('/management/roles'+(id?'/'+id:''), { method: id?'PUT':'POST', body }),
  deleteRole: (id: string) => client.request('/management/roles/'+id, { method: 'DELETE' }),
  project: (body: unknown) => client.request('/management/projects', { method: 'PUT', body }),
  region: (body: unknown) => client.request('/management/regions', { method: 'PUT', body }),
  systemGrant: (body: unknown) => client.request('/management/system-grants', { method: 'PUT', body }),
  menuGrant: (body: unknown) => client.request('/management/menu-grants', { method: 'PUT', body }),
  menuAction: (body: unknown) => client.request('/management/menu-actions', { method: 'PUT', body }),
  application: (body: unknown) => client.request('/management/applications', { method: 'PUT', body }),
  operation: (body: unknown) => client.request('/management/operations', { method: 'PUT', body }),
}
