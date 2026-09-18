/**
 * 认证与权限 Store（SYS 独立系统）
 *
 * 已接入统一认证中心：
 * - 登录/登出调用 IAM（/api/v1/iam/auth/*），JWT 由 IAM 签发
 * - 刷新页面通过 SYS /api/v1/sys/auth/me 恢复会话（SYS 本地验签 + 跨库读 IAM 权限）
 * - 仅 token 存 localStorage，业务数据不再落本地
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthSession, LoginRequest, LoginResponse, SysUser, SysRole } from '@/sys/types'
import { MENU_TREE, filterMenuByPerms, collectAllPerms, type MenuNode } from '@/sys/sys-menu'
import { sysApi, TOKEN_KEY, type LoginResult, type MeResult } from '@/api/sys'

/** 登录落地页候选：取第一个有权限的页面，都没有则去 403 */
const LANDING_CANDIDATES = [
  { path: '/maintain/dict', perm: 'sys:dict:type:view' },
  { path: '/maintain/logs', perm: 'sys:log:list:view' },
  { path: '/maintain/config', perm: 'sys:config:list:view' },
]

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AuthSession | null>(null)
  const currentUser = ref<SysUser | null>(null)
  const userRoles = ref<SysRole[]>([])
  const permCodes = ref<Set<string>>(new Set())
  const menuTree = ref<MenuNode[]>([])
  /** 是否已完成 /auth/me 恢复（路由守卫等待） */
  const hydrated = ref(!localStorage.getItem(TOKEN_KEY))

  const isLoggedIn = computed(() => session.value !== null && currentUser.value !== null)
  const isSuperAdmin = computed(() => userRoles.value.some((r) => r.code === 'super_admin' && r.status === 'active'))

  const landingPath = computed(() => {
    const hit = LANDING_CANDIDATES.find((c) => permCodes.value.has(c.perm))
    return hit ? hit.path : '/403'
  })

  function hydrate(user: { id: string; username: string; name: string },
                  roles: Array<{ id: string; name: string; code: string }>,
                  permissions: string[], token: string, superAdmin: boolean) {
    currentUser.value = {
      id: user.id,
      username: user.username,
      name: user.name,
      password: '',
      roleIds: roles.map((r) => r.id),
      status: 'active',
    }
    const mappedRoles: SysRole[] = roles.map((r) => ({
      id: r.id, name: r.name, code: r.code, permCodes: [], status: 'active',
    }))
    userRoles.value = mappedRoles
    const codes = new Set(permissions)
    // 前端兜底：超管拥有 SYS 菜单树全部权限（权限仍以服务端返回为准）
    if (superAdmin || mappedRoles.some((r) => r.code === 'super_admin')) {
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
    sysApi.token.clearToken()
    hydrated.value = true
  }

  /** 登录：直接调用 IAM 认证中心 */
  async function login(req: LoginRequest): Promise<LoginResponse> {
    try {
      const result: LoginResult = await sysApi.login(req.username.trim(), req.password)
      sysApi.token.setToken(result.token)
      hydrate(result.user, result.roles, result.permissions, result.token,
        result.roles.some((r) => r.code === 'super_admin'))
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

  /** 登出（尽力调用 IAM 记录日志，无论成败都清空本地会话） */
  async function logout() {
    try {
      await sysApi.logout()
    } catch {
      // 后端不可达也要允许本地登出
    }
    reset()
  }

  /** 刷新页面后凭 token 调用 SYS /auth/me 恢复会话 */
  async function restoreFromSession() {
    const token = sysApi.token.getToken()
    if (!token) {
      reset()
      return
    }
    try {
      const me: MeResult = await sysApi.me()
      hydrate(me.user, me.roles, me.permissions, token, me.superAdmin)
    } catch {
      reset()
    } finally {
      hydrated.value = true
    }
  }

  return {
    session, currentUser, userRoles, permCodes, menuTree, hydrated,
    isLoggedIn, isSuperAdmin, landingPath,
    login, logout, restoreFromSession,
  }
})
