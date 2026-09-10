/**
 * 认证与权限 Store（SYS 独立系统）
 * 负责登录/登出、会话、当前用户、权限编码集合、菜单树过滤。
 * 当前为 mock（localStorage），后续替换为统一认证中心 REST 接口，形态不变。
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthSession, LoginRequest, LoginResponse, SysUser, SysRole } from '@/sys/types'
import { getUsers, getRoles, getSession, saveSession, saveUsers, clearSession, addLog, uid, now } from '@/sys/mock-data'
import { MENU_TREE, filterMenuByPerms, collectAllPerms, type MenuNode } from '@/sys/sys-menu'

/** 登录落地页候选：取第一个有权限的页面，都没有则去 403 */
const LANDING_CANDIDATES = [
  { path: '/maintain/dict', perm: 'sys:dict:type:view' },
  { path: '/maintain/logs', perm: 'sys:log:list:view' },
  { path: '/maintain/config', perm: 'sys:config:list:view' },
]

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(getSession())
  const currentUser = ref<SysUser | null>(null)
  const userRoles = ref<SysRole[]>([])
  const permCodes = ref<Set<string>>(new Set())
  const menuTree = ref<MenuNode[]>([])

  const isLoggedIn = computed(() => session.value !== null && currentUser.value !== null)
  const isSuperAdmin = computed(() => userRoles.value.some((r) => r.code === 'super_admin' && r.status === 'active'))

  const landingPath = computed(() => {
    const hit = LANDING_CANDIDATES.find((c) => permCodes.value.has(c.perm))
    return hit ? hit.path : '/403'
  })

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
      for (const p of collectAllPerms()) codes.add(p.code)
    } else {
      for (const role of roles) {
        if (role.status !== 'active') continue
        role.permCodes.forEach((c) => codes.add(c))
      }
    }
    permCodes.value = codes
    menuTree.value = filterMenuByPerms(MENU_TREE, codes)
  }

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

  function login(req: LoginRequest): LoginResponse {
    const users = getUsers()
    const user = users.find((u) => u.username === req.username.trim())
    if (!user) {
      addLog({ kind: 'login', username: req.username.trim(), module: 'auth', action: 'login', detail: '用户名不存在', result: 'fail' })
      return { success: false, message: '用户名不存在' }
    }
    if (user.status !== 'active') return { success: false, message: '账号已被停用，请联系管理员' }
    if (user.password !== req.password) {
      addLog({ kind: 'login', username: user.username, module: 'auth', action: 'login', detail: '密码错误', result: 'fail' })
      return { success: false, message: '密码错误' }
    }
    const loginAt = now()
    const newSession: AuthSession = {
      token: `mock-jwt-${uid()}`,
      userId: user.id,
      username: user.username,
      name: user.name,
      loginAt,
      expireAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    }
    saveSession(newSession)
    session.value = newSession
    currentUser.value = { ...user, lastLoginAt: loginAt }
    saveUsers(users.map((u) => (u.id === user.id ? { ...u, lastLoginAt: loginAt } : u)))
    refreshPermissions()
    addLog({ kind: 'login', username: user.username, module: 'auth', action: 'login', detail: `用户 ${user.username} 登录成功`, result: 'success' })
    return { success: true, session: newSession, user: currentUser.value, permCodes: Array.from(permCodes.value) }
  }

  function logout() {
    if (currentUser.value) {
      addLog({ kind: 'login', username: currentUser.value.username, module: 'auth', action: 'logout', detail: `用户 ${currentUser.value.username} 退出登录`, result: 'success' })
    }
    clearSession()
    session.value = null
    currentUser.value = null
    userRoles.value = []
    permCodes.value = new Set()
    menuTree.value = []
  }

  restoreFromSession()

  return {
    session, currentUser, userRoles, permCodes, menuTree,
    isLoggedIn, isSuperAdmin, landingPath,
    login, logout, refreshPermissions, restoreFromSession,
  }
})
