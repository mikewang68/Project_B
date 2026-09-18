/**
 * 认证与权限 Store（IAM 独立系统）
 *
 * 负责：登录/登出、会话恢复、当前用户、权限编码集合、菜单树过滤。
 * 已接入真实后端 REST API（/api/v1/iam/auth/*），JWT 存 localStorage，
 * 业务数据不再使用 localStorage；刷新页面通过 /auth/me 恢复会话。
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthSession, LoginRequest, LoginResponse, User, Role } from '@/iam/types'
import { MENU_TREE, filterMenuByPerms, collectAllPerms, type MenuNode } from '@/iam/menu-tree'
import { iamApi, TOKEN_KEY, type LoginResult, type MeResult } from '@/api/iam'

/** IAM 后台可导航页面及其进入所需权限（登录落地页按此顺序选第一个有权限的） */
const LANDING_CANDIDATES = [
  { path: '/system/users', perm: 'iam:user:list:view' },
  { path: '/system/roles', perm: 'iam:role:list:view' },
  { path: '/system/menus', perm: 'iam:menu:tree:view' },
]

export const useAuthStore = defineStore('auth', () => {
  // ---- state ----
  const session = ref<AuthSession | null>(null)
  const currentUser = ref<User | null>(null)
  const userRoles = ref<Role[]>([])
  const permCodes = ref<Set<string>>(new Set())
  const menuTree = ref<MenuNode[]>([])
  /** 是否已完成 /auth/me 恢复（路由守卫等待） */
  const hydrated = ref(!localStorage.getItem(TOKEN_KEY))

  // ---- getters ----
  const isLoggedIn = computed(() => !!session.value && !!currentUser.value)
  const isSuperAdmin = computed(() =>
    userRoles.value.some((r) => r.code === 'super_admin' && r.status === 'active'),
  )

  /** 登录后默认落地页：取第一个有权限的 IAM 页面，都没有则去 403 */
  const landingPath = computed(() => {
    const hit = LANDING_CANDIDATES.find((c) => permCodes.value.has(c.perm))
    return hit ? hit.path : '/403'
  })

  // ---- actions ----
  function hydrate(user: User, roles: Array<Pick<Role, 'id' | 'name' | 'code' | 'status'>>,
                  permissions: string[], token: string) {
    currentUser.value = user
    userRoles.value = roles as Role[]
    const codes = new Set(permissions)
    // 前端兜底：超管动态拥有静态菜单树全部权限（权限以服务端返回为准）
    if (userRoles.value.some((r) => r.status === 'active' && r.code === 'super_admin')) {
      for (const p of collectAllPerms()) codes.add(p.code)
    }
    permCodes.value = codes
    menuTree.value = filterMenuByPerms(MENU_TREE, codes)
    session.value = {
      token,
      userId: user.id,
      username: user.username,
      name: user.name,
      loginAt: new Date().toISOString(),
      expireAt: new Date(Date.now() + 12 * 60 * 60 * 1000).toISOString(),
    }
    hydrated.value = true
  }

  function reset() {
    session.value = null
    currentUser.value = null
    userRoles.value = []
    permCodes.value = new Set()
    menuTree.value = []
    iamApi.token.clearToken()
    hydrated.value = true
  }

  /** 登录（调用 IAM 后端） */
  async function login(req: LoginRequest): Promise<LoginResponse> {
    try {
      const result: LoginResult = await iamApi.login(req.username.trim(), req.password)
      iamApi.token.setToken(result.token)
      hydrate(result.user, result.roles, result.permissions, result.token)
      return {
        success: true,
        session: session.value || undefined,
        user: currentUser.value || undefined,
        permCodes: Array.from(permCodes.value),
      }
    } catch (e) {
      return { success: false, message: e instanceof Error ? e.message : '登录失败' }
    }
  }

  /** 登出（尽力调用后端记录日志，无论成败都清空本地会话） */
  async function logout() {
    try {
      await iamApi.logout()
    } catch {
      // 忽略：即使后端不可达也要允许本地登出
    }
    reset()
  }

  /** 刷新页面后凭 token 调用 /auth/me 恢复会话 */
  async function restoreFromSession() {
    const token = iamApi.token.getToken()
    if (!token) {
      reset()
      return
    }
    try {
      const me: MeResult = await iamApi.me()
      const user: User = {
        id: me.user.id,
        username: me.user.username,
        name: me.user.name,
        password: '',
        roleIds: me.roles.map((r) => r.id),
        status: 'active',
        createdAt: '',
        updatedAt: '',
      }
      hydrate(user, me.roles.map((r) => ({ ...r, status: 'active', permCodes: [] })),
        me.permissions, token)
    } catch {
      reset()
    } finally {
      hydrated.value = true
    }
  }

  /** 变更当前用户资料/角色后重新拉取身份与权限；用户失效则登出 */
  async function refreshCurrentUser() {
    const token = iamApi.token.getToken()
    if (!token) {
      reset()
      return
    }
    try {
      const me: MeResult = await iamApi.me()
      const user: User = {
        id: me.user.id,
        username: me.user.username,
        name: me.user.name,
        password: '',
        roleIds: me.roles.map((r) => r.id),
        status: 'active',
        createdAt: currentUser.value?.createdAt || '',
        updatedAt: '',
      }
      hydrate(user, me.roles.map((r) => ({ ...r, status: 'active', permCodes: [] })),
        me.permissions, token)
    } catch {
      reset()
    }
  }

  return {
    session,
    currentUser,
    userRoles,
    permCodes,
    menuTree,
    hydrated,
    isLoggedIn,
    isSuperAdmin,
    landingPath,
    login,
    logout,
    refreshCurrentUser,
    restoreFromSession,
  }
})
