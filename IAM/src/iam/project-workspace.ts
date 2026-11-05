import type { ScopeSelection, UserContext, WorkspaceProject } from '../api/scope'

export const LEVEL_LABELS: Record<UserContext['adminLevel'], string> = {
  L1_SUPER: 'L1 · 平台总管理员', L1_READONLY: 'L1 · 平台只读用户',
  L2_PROJECT: 'L2 · 项目管理员', L3_SUBSYSTEM: 'L3 · 子系统管理员', L4_OPERATOR: 'L4 · 业务操作员',
}
export type WorkspaceTarget = { mode: 'projects' | 'global' | 'project'; projectId: string | null }
type Query = Record<string, unknown>

/** URL values are hints only. The trusted catalog and identity determine available workspaces. */
export function resolveWorkspace(context: UserContext, projects: WorkspaceProject[], query: Query): WorkspaceTarget {
  const l1 = ['L1_SUPER', 'L1_READONLY'].includes(context.adminLevel)
  if (l1 && query.workspace === 'global') return { mode: 'global', projectId: null }
  if (l1 && query.workspace === 'projects') return { mode: 'projects', projectId: null }
  let projectId = context.projectId
  if (l1) {
    if (query.projectId !== undefined && (typeof query.projectId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(query.projectId))) {
      throw new Error('项目入口无效，请从项目列表重新进入')
    }
    const activeProjects = [...new Set(context.activeScope.map(scope => scope.projectId))]
    projectId = typeof query.projectId === 'string' ? query.projectId
      : context.scopeMode === 'SCOPED' && activeProjects.length === 1 ? activeProjects[0]! : null
    if (!projectId) return { mode: 'projects', projectId: null }
  }
  if (!projectId || !projects.some(project => project.projectId === projectId)) {
    throw new Error('项目不存在或不在当前管理范围内，请刷新权限后重试')
  }
  return { mode: 'project', projectId }
}

export function selectionForWorkspace(context: UserContext, target: WorkspaceTarget): ScopeSelection | null {
  if (target.mode === 'global') {
    if (!['L1_SUPER', 'L1_READONLY'].includes(context.adminLevel)) throw new Error('当前身份不能进入全局管理')
    return { global: true, scopes: [] }
  }
  if (target.mode === 'projects' || ['L3_SUBSYSTEM', 'L4_OPERATOR'].includes(context.adminLevel)) return null
  const key = (scope: ScopeSelection['scopes'][number]) => JSON.stringify([scope.projectId, scope.regionId, scope.subsystemCode])
  const allowed = new Set(context.allowedScopes.map(key))
  if (context.scopeMode === 'SCOPED' && context.activeScope.length && context.activeScope.every(scope => scope.projectId === target.projectId && allowed.has(key(scope)))) {
    // Opening management after SSO must preserve a region selected in the portal.
    return { global: false, scopes: context.activeScope.map(scope => ({ ...scope })) }
  }
  // A registered project may have no business grants yet. An empty selection must remain DENY.
  return { global: false, scopes: context.allowedScopes.filter(scope => scope.projectId === target.projectId).map(scope => ({ ...scope })) }
}

export function selectionMatches(context: UserContext, selection: ScopeSelection): boolean {
  if (selection.global) return context.scopeMode === 'GLOBAL'
  if (!selection.scopes.length) return context.scopeMode === 'DENY' && !context.activeScope.length
  if (context.scopeMode !== 'SCOPED') return false
  const key = (scope: ScopeSelection['scopes'][number]) => JSON.stringify([scope.projectId, scope.regionId, scope.subsystemCode])
  const active = new Set(context.activeScope.map(key))
  const desired = new Set(selection.scopes.map(key))
  return active.size === desired.size && [...desired].every(value => active.has(value))
}

export function rowsInWorkspace<T extends { project_id: string | null }>(rows: T[], target: WorkspaceTarget): T[] {
  return target.mode === 'global' ? rows : target.mode === 'project' ? rows.filter(row => row.project_id === target.projectId) : []
}

/** Build-time configuration only; never accept a return URL from the login query. */
export function portalEntry(config: string | undefined): string {
  const value = config?.trim() || '/portal/'
  if (/^\/(?!\/)/.test(value) && !/[\\\r\n]/.test(value)) return value
  try {
    const url = new URL(value)
    if (url.protocol === 'https:' && !url.username && !url.password) return url.toString()
  } catch { /* Invalid configuration falls back to the local portal. */ }
  return '/portal/'
}
