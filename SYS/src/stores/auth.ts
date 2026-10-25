/**
 * 认证与权限 Store（SYS 独立系统）
 *
 * 已接入统一认证中心：
 * - 统一入口使用原生 Cookie；旧开发登录仍保留 IAM Bearer 兼容流程
 * - 刷新通过 SYS /api/v1/sys/auth/me 恢复身份、当前权限与只读状态
 * - Cookie 会话不依赖 localStorage token，业务数据通过 REST 获取
 */

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AuthSession, LoginRequest, LoginResponse, SysUser, SysRole } from '@/sys/types'
import { MENU_TREE, filterMenuByPerms, collectAllPerms, type MenuNode } from '@/sys/sys-menu'
import { sysApi, type LoginResult, type MeResult } from '@/api/sys'

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
  const hydrated = ref(false)
  const readOnly = ref(false)
  const fullAdmin = ref(false)

  const isLoggedIn = computed(() => session.value !== null && currentUser.value !== null)
  const isSuperAdmin = computed(() => fullAdmin.value && !readOnly.value)

  const landingPath = computed(() => {
    const hit = LANDING_CANDIDATES.find((c) => permCodes.value.has(c.perm))
    return hit ? hit.path : '/403'
  })

  function hydrate(user: { id: string; username: string; name: string },
                  roles: Array<{ id: string; name: string; code: string }>,
                  permissions: string[], token: string, superAdmin: boolean, readonly = false) {
    readOnly.value = readonly
    fullAdmin.value = superAdmin && !readonly
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
    if (fullAdmin.value) {
      for (const p of collectAllPerms()) codes.add(p.code)
    }
    permCodes.value = codes
    if (readOnly.value) for (const p of codes) if (!/:(view|read|list|query|detail)$/.test(p)) codes.delete(p)
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
    readOnly.value = false
    fullAdmin.value = false
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
        result.superAdmin, result.readOnly)
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
    await sysApi.logout()
    reset()
  }

  /** 刷新页面后凭 token 调用 SYS /auth/me 恢复会话 */
  async function restoreFromSession() {
    const token = sysApi.token.getToken()
    try {
      const me: MeResult = await sysApi.me()
      hydrate(me.user, me.roles, me.permissions, token || '', me.superAdmin, me.readOnly)
    } catch {
      reset()
    } finally {
      hydrated.value = true
    }
  }

  return {
    session, currentUser, userRoles, permCodes, menuTree, hydrated, readOnly,
    isLoggedIn, isSuperAdmin, landingPath,
    login, logout, restoreFromSession,
  }
})
