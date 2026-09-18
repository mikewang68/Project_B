/**
 * 用户与角色管理 Store（已接入 IAM 后端 REST API）
 *
 * 负责：用户 CRUD、角色 CRUD、角色权限分配、用户角色分配、密码重置。
 * 操作日志、唯一性校验、自锁保护、逻辑删除均由后端落库与强制；
 * 前端保留即时校验以改善交互，返回 { success, message } 供视图提示。
 */

import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { User, Role } from '@/iam/types'
import { iamApi, type UserUpsertPayload } from '@/api/iam'
import { useAuthStore } from './auth'

interface OpResult {
  success: boolean
  message?: string
}

function fail(e: unknown): OpResult {
  return { success: false, message: e instanceof Error ? e.message : '操作失败' }
}

export const useIamStore = defineStore('iam', () => {
  const users = ref<User[]>([])
  const roles = ref<Role[]>([])
  const loading = ref(false)

  // ---- 用户管理 ----
  async function fetchUsers() {
    loading.value = true
    try {
      users.value = await iamApi.listUsers()
    } finally {
      loading.value = false
    }
  }

  async function addUser(data: Omit<User, 'id' | 'createdAt' | 'updatedAt'>): Promise<OpResult> {
    if (!data.roleIds || data.roleIds.length === 0) {
      return { success: false, message: '请至少为用户分配一个角色' }
    }
    if (users.value.some((u) => u.username === data.username.trim())) {
      return { success: false, message: '用户名已存在' }
    }
    try {
      const payload: UserUpsertPayload = {
        username: data.username.trim(),
        name: data.name,
        phone: data.phone,
        email: data.email,
        password: data.password,
        roleIds: [...data.roleIds],
        orgCodes: data.orgCodes,
        status: data.status,
      }
      const created = await iamApi.createUser(payload)
      users.value.push(created)
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function updateUser(id: string, data: Partial<User>): Promise<OpResult> {
    const auth = useAuthStore()
    const isSelf = auth.currentUser?.id === id
    if (isSelf) {
      if (data.status === 'disabled') return { success: false, message: '不能停用当前登录用户' }
      if (data.roleIds && data.roleIds.length === 0) {
        return { success: false, message: '不能清空当前登录用户的角色' }
      }
    }
    if (data.username && users.value.some((u) => u.username === data.username!.trim() && u.id !== id)) {
      return { success: false, message: '用户名已存在' }
    }
    try {
      const updated = await iamApi.updateUser(id, {
        name: data.name,
        phone: data.phone,
        email: data.email,
        password: data.password,
        roleIds: data.roleIds ? [...data.roleIds] : undefined,
        orgCodes: data.orgCodes,
        status: data.status,
      })
      const idx = users.value.findIndex((u) => u.id === id)
      if (idx >= 0) users.value[idx] = updated
      if (isSelf) await auth.refreshCurrentUser()
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function deleteUser(id: string): Promise<OpResult> {
    const auth = useAuthStore()
    if (auth.currentUser?.id === id) return { success: false, message: '不能删除当前登录用户' }
    try {
      await iamApi.deleteUser(id)
      users.value = users.value.filter((u) => u.id !== id)
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function toggleUserStatus(id: string): Promise<OpResult> {
    const auth = useAuthStore()
    if (auth.currentUser?.id === id) return { success: false, message: '不能停用当前登录用户' }
    const current = users.value.find((u) => u.id === id)
    if (!current) return { success: false, message: '用户不存在' }
    const next = current.status === 'active' ? 'disabled' : 'active'
    try {
      const updated = await iamApi.toggleUserStatus(id, next)
      const idx = users.value.findIndex((u) => u.id === id)
      if (idx >= 0) users.value[idx] = updated
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function resetPassword(id: string, newPassword: string): Promise<OpResult> {
    if (newPassword.length < 6) return { success: false, message: '密码至少 6 位' }
    try {
      await iamApi.resetPassword(id, newPassword)
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function assignUserRoles(id: string, roleIds: string[]): Promise<OpResult> {
    const auth = useAuthStore()
    if (!roleIds || roleIds.length === 0) {
      if (auth.currentUser?.id === id) return { success: false, message: '不能清空当前登录用户的所有角色' }
    }
    try {
      await iamApi.assignUserRoles(id, roleIds)
      const idx = users.value.findIndex((u) => u.id === id)
      if (idx >= 0) users.value[idx] = { ...users.value[idx], roleIds: [...roleIds] }
      if (auth.currentUser?.id === id) await auth.refreshCurrentUser()
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  // ---- 角色管理 ----
  async function fetchRoles() {
    loading.value = true
    try {
      roles.value = await iamApi.listRoles()
    } finally {
      loading.value = false
    }
  }

  async function addRole(data: Omit<Role, 'id' | 'createdAt' | 'updatedAt'>): Promise<OpResult> {
    if (roles.value.some((r) => r.code === data.code.trim())) {
      return { success: false, message: '角色编码已存在' }
    }
    try {
      const created = await iamApi.createRole({
        name: data.name,
        code: data.code.trim(),
        description: data.description,
        permCodes: [...(data.permCodes || [])],
        status: data.status,
      })
      roles.value.push(created)
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function updateRole(id: string, data: Partial<Role>): Promise<OpResult> {
    if (data.code && roles.value.some((r) => r.code === data.code!.trim() && r.id !== id)) {
      return { success: false, message: '角色编码已存在' }
    }
    const current = roles.value.find((r) => r.id === id)
    if (current?.code === 'super_admin' && data.status === 'inactive') {
      return { success: false, message: '系统管理员角色不允许停用' }
    }
    try {
      const updated = await iamApi.updateRole(id, {
        name: data.name,
        description: data.description,
        status: data.status,
      })
      const idx = roles.value.findIndex((r) => r.id === id)
      if (idx >= 0) roles.value[idx] = updated
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function deleteRole(id: string): Promise<OpResult> {
    const role = roles.value.find((r) => r.id === id)
    if (!role) return { success: false, message: '角色不存在' }
    if (role.code === 'super_admin') return { success: false, message: '不能删除系统管理员角色' }
    const usedBy = users.value.filter((u) => u.roleIds.includes(id))
    if (usedBy.length > 0) {
      return { success: false, message: `该角色已被 ${usedBy.length} 个用户使用，无法删除` }
    }
    try {
      await iamApi.deleteRole(id)
      roles.value = roles.value.filter((r) => r.id !== id)
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function toggleRoleStatus(id: string): Promise<OpResult> {
    const current = roles.value.find((r) => r.id === id)
    if (!current) return { success: false, message: '角色不存在' }
    if (current.code === 'super_admin' && current.status === 'active') {
      return { success: false, message: '系统管理员角色不允许停用' }
    }
    const next = current.status === 'active' ? 'inactive' : 'active'
    try {
      const updated = await iamApi.toggleRoleStatus(id, next)
      const idx = roles.value.findIndex((r) => r.id === id)
      if (idx >= 0) roles.value[idx] = updated
      return { success: true }
    } catch (e) {
      return fail(e)
    }
  }

  async function assignRolePerms(id: string, permCodesToAssign: string[]): Promise<OpResult> {
    const current = roles.value.find((r) => r.id === id)
    if (!current) return { success: false, message: '角色不存在' }
    if (current.code === 'super_admin') {
      return { success: false, message: '系统管理员角色自动拥有全部权限，无需手动分配' }
    }
    try {
      const updated = await iamApi.assignRolePermissions(id, permCodesToAssign)
      const idx = roles.value.findIndex((r) => r.id === id)
      if (idx >= 0) roles.value[idx] = updated
      return { success: true }
    } catch (e) {
      return fail(e)
    }
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
