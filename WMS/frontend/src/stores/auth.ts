import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/http'
import type { CurrentUser, LoginCredentials } from '@/api/auth'

const READ_ONLY_ROLES = new Set(['AUDITOR', 'READONLY', 'ROLE_READONLY', 'READONLY_AUDITOR', 'PLATFORM_AUDITOR_READONLY'])
export const SESSION_REFRESH_MS = 15_000

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const sessionError = ref<string | null>(null)
  const authenticated = computed(() => user.value !== null)
  const readOnly = computed(() => user.value?.readOnly === true || user.value?.roles.some(role => READ_ONLY_ROLES.has(role.toUpperCase())) === true)
  let checkedAt = 0
  let generation = 0
  let pending: Promise<void> | null = null

  function clearSession(): void {
    generation += 1 // Late /me responses cannot resurrect a logged-out session.
    user.value = null
    initialized.value = true
    checkedAt = Date.now()
    sessionError.value = null
    pending = null
  }

  async function refreshSession(options: { force?: boolean } = {}): Promise<void> {
    if (pending) return pending
    if (options.force === false && initialized.value && Date.now() - checkedAt < SESSION_REFRESH_MS) return
    const requestedGeneration = generation
    const task = (async () => {
      try {
        const current = await authApi.currentUser()
        if (generation !== requestedGeneration) return
        if (JSON.stringify(current) !== JSON.stringify(user.value)) user.value = current
        sessionError.value = null
      } catch (reason) {
        if (generation !== requestedGeneration) return
        if (reason instanceof ApiError && (reason.status === 401 || reason.status === 403)) clearSession()
        else {
          sessionError.value = '登录或权限服务暂不可用，已暂停写操作；请稍后重试。'
          throw reason
        }
      } finally {
        if (generation === requestedGeneration) {
          initialized.value = true
          checkedAt = Date.now()
        }
      }
    })()
    pending = task
    try { await task } finally { if (pending === task) pending = null }
  }

  async function restoreSession(): Promise<boolean> {
    await refreshSession({ force: false })
    return authenticated.value
  }

  async function signIn(credentials: LoginCredentials): Promise<void> {
    generation += 1
    pending = null
    await authApi.prepareCsrf()
    user.value = await authApi.login(credentials)
    initialized.value = true
    checkedAt = Date.now()
    sessionError.value = null
  }

  async function signOut(): Promise<void> {
    try {
      await authApi.prepareCsrf()
      await authApi.logout()
      clearSession()
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 401 ||
        (reason.code === 'SSO_GLOBAL_LOGOUT_INCOMPLETE' && reason.localLogoutComplete))) clearSession()
      throw reason
    }
  }

  function hasPermission(permission?: string): boolean {
    return !permission || user.value?.permissions.includes(permission) === true
  }

  function canWrite(permission?: string): boolean {
    return authenticated.value && !readOnly.value && !sessionError.value && hasPermission(permission)
  }

  async function selectTenant(warehouseCode: string, ownerCode: string): Promise<void> {
    if (!user.value) return
    await authApi.prepareCsrf()
    await authApi.switchTenant(warehouseCode, ownerCode)
    // A pre-switch /me may still be in flight. It must neither overwrite the new
    // scope nor satisfy this post-switch refresh with the old authorization.
    generation += 1
    pending = null
    checkedAt = 0
    await refreshSession()
  }

  async function updatePassword(currentPassword: string, newPassword: string): Promise<void> {
    await authApi.prepareCsrf()
    await authApi.changePassword(currentPassword, newPassword)
    if (user.value) user.value.passwordChangeRequired = false
  }

  return { user, initialized, authenticated, readOnly, sessionError, restoreSession, refreshSession, signIn, signOut, hasPermission, canWrite, selectTenant, updatePassword }
})
