/**
 * 有效权限 / 权限健康诊断 —— 前端数据结构（与后端 DTO 对齐）。
 */

export interface RoleBrief {
  id: string
  code: string
  name: string
}

export interface EffectivePermission {
  permissionCode: string
  permissionName: string
  sourceRoles: RoleBrief[]
}

export interface EffectivePermissionView {
  userId: string
  username: string
  displayName: string
  roles: RoleBrief[]
  permissions: EffectivePermission[]
}

export interface PermissionExplanation {
  permissionCode: string
  owned: boolean
  sourceRoles: RoleBrief[]
  reason?: string
}

export type Severity = 'HIGH' | 'MEDIUM' | 'LOW'

export interface HealthFinding {
  ruleId: string
  severity: Severity
  type: string
  title: string
  description: string
  evidence: string[]
  recommendation: string
}

export interface HealthSummary {
  roleCount: number
  permissionCount: number
  userCount: number
  findingCount: number
  high: number
  medium: number
  low: number
}

export interface PermissionHealthReport {
  summary: HealthSummary
  findings: HealthFinding[]
}
