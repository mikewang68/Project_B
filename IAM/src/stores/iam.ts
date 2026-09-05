/**
 * 用户与角色管理 Store
 *
 * 负责：用户 CRUD、角色 CRUD、角色权限分配、用户角色分配、密码重置。
 * 当前为 mock 实现（localStorage），后续替换为 REST 接口。
 * 所有写操作记录操作日志（预留 blockchainTxHash 字段）。
 *
 * 内建保护：
 *  - 不能删除/停用当前登录用户、不能清空自己的角色（防止自锁）
 *  - super_admin 角色不可删除/停用/改权限（防止超管权限被篡改）
 *  - 被用户使用的角色不可删除
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { User, Role } from '@/iam/types'
import {
  getUsers,
  saveUsers,
  getRoles,
  saveRoles,
  addLog,
  uid,
  now,
} from '@/iam/mock-data'
import { resolveOrgPath } from '@/iam/org-tree'
import { useAuthStore } from './auth'

export const useIamStore = defineStore('iam', () => {
  const users = ref<User[]>([])
  const roles = ref<Role[]>([])
  const loading = ref(false)

  function logAction(module: string, action: string, target: string, detail: string) {
    const auth = useAuthStore()
    addLog({
      userId: auth.currentUser?.id || 'system',
      username: auth.currentUser?.username || 'system',
      module,
      action,
      target,
      detail,
    })
  }

  // ---- 用户管理 ----
  function fetchUsers() {
    loading.value = true
    users.value = getUsers()
    loading.value = false
  }

  function addUser(data: Omit<User, 'id' | 'createdAt' | 'updatedAt'>): { success: boolean; message?: string } {
    if (!data.roleIds || data.roleIds.length === 0) {
      return { success: false, message: '请至少为用户分配一个角色' }
    }
    const list = getUsers()
    if (list.some((u) => u.username === data.username.trim())) {
      return { success: false, message: '用户名已存在' }
    }
    const user: User = {
      ...data,
      // 组织层级名称统一由级联编码解析，避免手输造成口径不一致
      ...resolveOrgPath(data.orgCodes),
      username: data.username.trim(),
      roleIds: [...data.roleIds],
      id: `user-${uid()}`,
      createdAt: now(),
      updatedAt: now(),
    }
    list.push(user)
    saveUsers(list)
    users.value = list
    logAction('iam', 'add_user', user.id, `新增用户 ${user.username}（${user.name}）`)
    return { success: true }
  }

  function updateUser(id: string, data: Partial<User>): { success: boolean; message?: string } {
    const list = getUsers()
    const idx = list.findIndex((u) => u.id === id)
    if (idx < 0) return { success: false, message: '用户不存在' }
    // 用户名唯一性校验
    if (data.username && data.username !== list[idx].username) {
      if (list.some((u) => u.username === data.username!.trim() && u.id !== id)) {
        return { success: false, message: '用户名已存在' }
      }
    }
    const auth = useAuthStore()
    const isSelf = auth.currentUser?.id === id
    if (isSelf) {
      // 防止管理员把自己停用 / 清空自己的角色导致自锁
      if (data.status === 'disabled') {
        return { success: false, message: '不能停用当前登录用户' }
      }
      if (data.roleIds && data.roleIds.length === 0) {
        return { success: false, message: '不能清空当前登录用户的角色' }
      }
    }
    list[idx] = {
      ...list[idx],
      ...data,
      // 组织层级变更时，按级联编码重新解析名称
      ...(data.orgCodes ? resolveOrgPath(data.orgCodes) : {}),
      username: data.username?.trim() || list[idx].username,
      roleIds: data.roleIds ? [...data.roleIds] : list[idx].roleIds,
      updatedAt: now(),
    }
    saveUsers(list)
    users.value = list
    logAction('iam', 'update_user', id, `更新用户 ${list[idx].username}`)
    // 如果修改的是当前用户，刷新（若被停用则 refreshCurrentUser 会强制登出）
    if (isSelf) auth.refreshCurrentUser()
    return { success: true }
  }

  function deleteUser(id: string): { success: boolean; message?: string } {
    const auth = useAuthStore()
    if (auth.currentUser?.id === id) {
      return { success: false, message: '不能删除当前登录用户' }
    }
    const list = getUsers()
    const user = list.find((u) => u.id === id)
    if (!user) return { success: false, message: '用户不存在' }
    const filtered = list.filter((u) => u.id !== id)
    saveUsers(filtered)
    users.value = filtered
    logAction('iam', 'delete_user', id, `删除用户 ${user.username}`)
    return { success: true }
  }

  function toggleUserStatus(id: string): { success: boolean; message?: string } {
    const auth = useAuthStore()
    if (auth.currentUser?.id === id) {
      return { success: false, message: '不能停用当前登录用户' }
    }
    const list = getUsers()
    const idx = list.findIndex((u) => u.id === id)
    if (idx < 0) return { success: false, message: '用户不存在' }
    list[idx].status = list[idx].status === 'active' ? 'disabled' : 'active'
    list[idx].updatedAt = now()
    saveUsers(list)
    users.value = list
    logAction('iam', 'toggle_user_status', id, `${list[idx].status === 'active' ? '启用' : '停用'}用户 ${list[idx].username}`)
    return { success: true }
  }

  function resetPassword(id: string, newPassword: string): { success: boolean; message?: string } {
    if (newPassword.length < 6) return { success: false, message: '密码至少 6 位' }
    const list = getUsers()
    const idx = list.findIndex((u) => u.id === id)
    if (idx < 0) return { success: false, message: '用户不存在' }
    list[idx].password = newPassword
    list[idx].updatedAt = now()
    saveUsers(list)
    users.value = list
    logAction('iam', 'reset_password', id, `重置用户 ${list[idx].username} 密码`)
    return { success: true }
  }

  function assignUserRoles(id: string, roleIds: string[]): { success: boolean; message?: string } {
    const list = getUsers()
    const idx = list.findIndex((u) => u.id === id)
    if (idx < 0) return { success: false, message: '用户不存在' }
    if (!roleIds || roleIds.length === 0) {
      const auth = useAuthStore()
      if (auth.currentUser?.id === id) return { success: false, message: '不能清空当前登录用户的所有角色' }
    }
    list[idx].roleIds = [...roleIds]
    list[idx].updatedAt = now()
    saveUsers(list)
    users.value = list
    logAction('iam', 'assign_user_roles', id, `为用户 ${list[idx].username} 分配角色 [${roleIds.join(',')}]`)
    // 如果修改的是当前用户，刷新权限
    const auth = useAuthStore()
    if (auth.currentUser?.id === id) auth.refreshCurrentUser()
    return { success: true }
  }

  // ---- 角色管理 ----
  function fetchRoles() {
    loading.value = true
    roles.value = getRoles()
    loading.value = false
  }

  function addRole(data: Omit<Role, 'id' | 'createdAt' | 'updatedAt'>): { success: boolean; message?: string } {
    const list = getRoles()
    if (list.some((r) => r.code === data.code.trim())) {
      return { success: false, message: '角色编码已存在' }
    }
    const role: Role = {
      ...data,
      code: data.code.trim(),
      permCodes: [...(data.permCodes || [])],
      id: `role-${uid()}`,
      createdAt: now(),
      updatedAt: now(),
    }
    list.push(role)
    saveRoles(list)
    roles.value = list
    logAction('iam', 'add_role', role.id, `新增角色 ${role.name}（${role.code}）`)
    return { success: true }
  }

  function updateRole(id: string, data: Partial<Role>): { success: boolean; message?: string } {
    const list = getRoles()
    const idx = list.findIndex((r) => r.id === id)
    if (idx < 0) return { success: false, message: '角色不存在' }
    if (data.code && data.code !== list[idx].code) {
      if (list.some((r) => r.code === data.code!.trim() && r.id !== id)) {
        return { success: false, message: '角色编码已存在' }
      }
    }
    // super_admin 角色不允许停用
    if (list[idx].code === 'super_admin' && data.status === 'disabled') {
      return { success: false, message: '系统管理员角色不允许停用' }
    }
    list[idx] = {
      ...list[idx],
      ...data,
      code: data.code?.trim() || list[idx].code,
      permCodes: data.permCodes ? [...data.permCodes] : list[idx].permCodes,
      updatedAt: now(),
    }
    saveRoles(list)
    roles.value = list
    logAction('iam', 'update_role', id, `更新角色 ${list[idx].name}`)
    // 角色变更可能影响当前用户权限
    const auth = useAuthStore()
    if (auth.currentUser?.roleIds.includes(id)) auth.refreshCurrentUser()
    return { success: true }
  }

  function deleteRole(id: string): { success: boolean; message?: string } {
    const list = getRoles()
    const role = list.find((r) => r.id === id)
    if (!role) return { success: false, message: '角色不存在' }
    if (role.code === 'super_admin') {
      return { success: false, message: '不能删除系统管理员角色' }
    }
    // 检查是否有用户使用该角色
    const usersList = getUsers()
    const usedBy = usersList.filter((u) => u.roleIds.includes(id))
    if (usedBy.length > 0) {
      return { success: false, message: `该角色已被 ${usedBy.length} 个用户使用，无法删除` }
    }
    const filtered = list.filter((r) => r.id !== id)
    saveRoles(filtered)
    roles.value = filtered
    logAction('iam', 'delete_role', id, `删除角色 ${role.name}`)
    return { success: true }
  }

  function toggleRoleStatus(id: string): { success: boolean; message?: string } {
    const list = getRoles()
    const idx = list.findIndex((r) => r.id === id)
    if (idx < 0) return { success: false, message: '角色不存在' }
    // 内置超级管理员角色不允许停用
    if (list[idx].code === 'super_admin' && list[idx].status === 'active') {
      return { success: false, message: '系统管理员角色不允许停用' }
    }
    list[idx].status = list[idx].status === 'active' ? 'disabled' : 'active'
    list[idx].updatedAt = now()
    saveRoles(list)
    roles.value = list
    logAction('iam', 'toggle_role_status', id, `${list[idx].status === 'active' ? '启用' : '停用'}角色 ${list[idx].name}`)
    // 角色停用/启用会影响拥有该角色的在线用户权限，需即时刷新
    const auth = useAuthStore()
    if (auth.currentUser?.roleIds.includes(id)) auth.refreshCurrentUser()
    return { success: true }
  }

  function assignRolePerms(id: string, permCodes: string[]): { success: boolean; message?: string } {
    const list = getRoles()
    const idx = list.findIndex((r) => r.id === id)
    if (idx < 0) return { success: false, message: '角色不存在' }
    // super_admin 始终拥有全部权限，不允许通过分配权限篡改
    if (list[idx].code === 'super_admin') {
      return { success: false, message: '系统管理员角色自动拥有全部权限，无需手动分配' }
    }
    list[idx].permCodes = [...permCodes]
    list[idx].updatedAt = now()
    saveRoles(list)
    roles.value = list
    logAction('iam', 'assign_role_perms', id, `为角色 ${list[idx].name} 分配 ${permCodes.length} 项权限`)
    // 刷新当前用户权限（如果当前用户拥有该角色）
    const auth = useAuthStore()
    if (auth.currentUser?.roleIds.includes(id)) auth.refreshCurrentUser()
    return { success: true }
  }

  return {
    users,
    roles,
    loading,
    fetchUsers,
    addUser,
    updateUser,
    deleteUser,
    toggleUserStatus,
    resetPassword,
    assignUserRoles,
    fetchRoles,
    addRole,
    updateRole,
    deleteRole,
    toggleRoleStatus,
    assignRolePerms,
  }
})
