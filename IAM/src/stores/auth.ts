/**
 * 认证与权限 Store（IAM 独立系统）
 *
 * 负责：登录/登出、会话管理、当前用户、权限编码集合、菜单树过滤。
 * 当前为 mock 实现（localStorage），后续替换为 REST 接口，接口形态不变。
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthSession, LoginRequest, LoginResponse, User, Role } from '@/iam/types'
import {
  getUsers,
  getRoles,
  getSession,
  saveSession,
  saveUsers,
  clearSession,
  addLog,
  uid,
  now,
} from '@/iam/mock-data'
import { MENU_TREE, filterMenuByPerms, collectAllPerms, type MenuNode } from '@/iam/menu-tree'

/** IAM 后台可导航页面及其进入所需权限（登录落地页按此顺序选第一个有权限的） */
const LANDING_CANDIDATES = [
  { path: '/system/users', perm: 'iam:user:list:view' },
  { path: '/system/roles', perm: 'iam:role:list:view' },
  { path: '/system/menus', perm: 'iam:menu:tree:view' },
]

export const useAuthStore = defineStore('auth', () => {
  // ---- state ----
  const session = ref<AuthSession | null>(getSession())
  const currentUser = ref<User | null>(null)
  const userRoles = ref<Role[]>([])
  const permCodes = ref<Set<string>>(new Set())
  const menuTree = ref<MenuNode[]>([])

  // ---- getters ----
  const isLoggedIn = computed(() => session.value !== null && currentUser.value !== null)
  const isSuperAdmin = computed(() =>
    userRoles.value.some((r) => r.code === 'super_admin' && r.status === 'active'),
  )

  /** 登录后默认落地页：取第一个有权限的 IAM 页面，都没有则去 403 */
  const landingPath = computed(() => {
    const hit = LANDING_CANDIDATES.find((c) => permCodes.value.has(c.perm))
    return hit ? hit.path : '/403'
  })

  // ---- actions ----
  /** 根据用户角色重新计算权限集合与菜单树 */
  function refreshPermissions() {
    if (!currentUser.value) {
      permCodes.value = new Set()
      menuTree.value = []
      userRoles.value = []
      return
    }
    const roles = getRoles().filter((r) => currentUser.value!.roleIds.includes(r.id))
    userRoles.value = roles
    const codes = new Set<string>()
    const isSuper = roles.some((r) => r.status === 'active' && r.code === 'super_admin')
    if (isSuper) {
      // 超级管理员动态拥有菜单树中的全部权限，避免新增权限点后本地快照过期
      for (const p of collectAllPerms()) codes.add(p.code)
    } else {
      for (const role of roles) {
        if (role.status !== 'active') continue
        for (const code of role.permCodes) codes.add(code)
      }
    }
    permCodes.value = codes
    menuTree.value = filterMenuByPerms(MENU_TREE, codes)
  }

  /** 从会话恢复当前用户 */
  function restoreFromSession() {
    const s = getSession()
    if (!s) return
    session.value = s
    const user = getUsers().find((u) => u.id === s.userId)
    if (user && user.status === 'active') {
      currentUser.value = user
      refreshPermissions()
    } else {
      clearSession()
      session.value = null
    }
  }

  /** 登录 */
  function login(req: LoginRequest): LoginResponse {
    const users = getUsers()
    const user = users.find((u) => u.username === req.username.trim())
    if (!user) {
      return { success: false, message: '用户名不存在' }
    }
    if (user.status !== 'active') {
      return { success: false, message: '账号已被停用，请联系管理员' }
    }
    if (user.password !== req.password) {
      return { success: false, message: '密码错误' }
    }
    // 生成会话（模拟 JWT）
    const loginAt = now()
    const expireAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
    const newSession: AuthSession = {
      token: `mock-jwt-${uid()}`,
      userId: user.id,
      username: user.username,
      name: user.name,
      loginAt,
      expireAt,
    }
    saveSession(newSession)
    session.value = newSession
    currentUser.value = { ...user, lastLoginAt: loginAt }
    // 更新用户最后登录时间（静态 import，同步持久化）
    const updatedUsers = users.map((u) => (u.id === user.id ? { ...u, lastLoginAt: loginAt } : u))
    saveUsers(updatedUsers)

    refreshPermissions()
    addLog({
      userId: user.id,
      username: user.username,
      module: 'iam',
      action: 'login',
      detail: `用户 ${user.username} 登录成功`,
    })
    return {
      success: true,
      session: newSession,
      user: currentUser.value,
      permCodes: Array.from(permCodes.value),
    }
  }

  /** 登出 */
  function logout() {
    if (currentUser.value) {
      addLog({
        userId: currentUser.value.id,
        username: currentUser.value.username,
        module: 'iam',
        action: 'logout',
        detail: `用户 ${currentUser.value.username} 退出登录`,
      })
    }
    clearSession()
    session.value = null
    currentUser.value = null
    userRoles.value = []
    permCodes.value = new Set()
    menuTree.value = []
  }

  /** 刷新当前用户信息（如修改密码、变更角色后）；若用户已被停用则强制登出 */
  function refreshCurrentUser() {
    if (!session.value) return
    const user = getUsers().find((u) => u.id === session.value!.userId)
    if (!user || user.status !== 'active') {
      // 用户被删除或停用 → 当前会话立即失效
      logout()
      return
    }
    currentUser.value = user
    refreshPermissions()
  }

  // 初始化时尝试恢复会话
  restoreFromSession()

  return {
    session,
    currentUser,
    userRoles,
    permCodes,
    menuTree,
    isLoggedIn,
    isSuperAdmin,
    landingPath,
    login,
    logout,
    refreshPermissions,
    refreshCurrentUser,
    restoreFromSession,
  }
})
