/**
 * 权限判断工具
 *
 * 权限编码来自 menu-tree.ts，当前用户的权限列表由 auth store 提供。
 * 超级管理员（super_admin 角色）拥有全部权限，直接放行 —— 这样即使菜单树
 * 后续新增权限点、而 localStorage 中的角色快照未更新，超管依然拥有新权限。
 *
 * 用法：
 *   hasPerm('dt:twin:scene:view')
 *   hasAnyPerm(['dt:twin:scene:view', 'dt:screen:view:view'])
 *   hasAllPerm(['iam:user:list:view', 'iam:user:add:add'])
 */

import { useAuthStore } from '@/stores/auth'

/** 判断当前用户是否拥有指定权限 */
export function hasPerm(code: string): boolean {
  const auth = useAuthStore()
  if (auth.isSuperAdmin) return true
  return auth.permCodes.has(code)
}

/** 判断当前用户是否拥有列表中任意一个权限（空列表视为"无权限要求"，放行） */
export function hasAnyPerm(codes: string[]): boolean {
  if (!codes.length) return true
  const auth = useAuthStore()
  if (auth.isSuperAdmin) return true
  return codes.some((c) => auth.permCodes.has(c))
}

/** 判断当前用户是否拥有列表中全部权限（空列表视为"无权限要求"，放行） */
export function hasAllPerm(codes: string[]): boolean {
  if (!codes.length) return true
  const auth = useAuthStore()
  if (auth.isSuperAdmin) return true
  return codes.every((c) => auth.permCodes.has(c))
}
