import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/http'
import type { CurrentUser, LoginCredentials } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const authenticated = computed(() => user.value !== null)

  async function restoreSession(): Promise<boolean> {
    if (initialized.value) return authenticated.value
    try {
      user.value = await authApi.currentUser()
    } catch (reason) {
      if (!(reason instanceof ApiError) || reason.status !== 401) throw reason
      user.value = null
    } finally {
      initialized.value = true
    }
    return authenticated.value
  }

  async function refreshSession(): Promise<void> {
    user.value = await authApi.currentUser()
    initialized.value = true
  }

  async function signIn(credentials: LoginCredentials): Promise<void> {
    await authApi.prepareCsrf()
    user.value = await authApi.login(credentials)
    initialized.value = true
  }

  async function signOut(): Promise<void> {
    try {
      await authApi.prepareCsrf()
      await authApi.logout()
    } finally {
      user.value = null
      initialized.value = true
    }
  }

  function hasPermission(permission?: string): boolean {
    return !permission || user.value?.permissions.includes(permission) === true
  }

  async function selectTenant(warehouseCode: string, ownerCode: string): Promise<void> {
    if (!user.value) return
    await authApi.prepareCsrf()
    user.value.tenant = await authApi.switchTenant(warehouseCode, ownerCode)
  }

  async function updatePassword(currentPassword: string, newPassword: string): Promise<void> {
    await authApi.prepareCsrf()
    await authApi.changePassword(currentPassword, newPassword)
    if (user.value) user.value.passwordChangeRequired = false
  }

  return { user, initialized, authenticated, restoreSession, refreshSession, signIn, signOut, hasPermission, selectTenant, updatePassword }
})
