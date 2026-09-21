import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentUser } from '@/api/auth'
import type { CurrentUser } from '@/types/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)
  const lastLoadedAt = ref<string | null>(null)
  let inflight: Promise<CurrentUser | null> | null = null

  const displayName = computed(() => user.value?.name ?? '')
  const roleLabel = computed(() => user.value?.role ?? '')
  const team = computed(() => user.value?.team ?? '')
  const shift = computed(() => user.value?.shift ?? null)

  function setCurrentUser(nextUser: CurrentUser): void {
    user.value = nextUser
    loaded.value = true
    error.value = null
    lastLoadedAt.value = new Date().toISOString()
  }

  async function ensureLoaded(force = false): Promise<CurrentUser | null> {
    if (!force && loaded.value) return user.value
    if (inflight) return inflight

    loading.value = true
    error.value = null
    inflight = getCurrentUser()
      .then((nextUser) => {
        setCurrentUser(nextUser)
        return nextUser
      })
      .catch((err: unknown) => {
        user.value = null
        loaded.value = false
        error.value = err instanceof Error ? err.message : '当前用户加载失败'
        return null
      })
      .finally(() => {
        loading.value = false
        inflight = null
      })

    return inflight
  }

  function refresh(): Promise<CurrentUser | null> {
    return ensureLoaded(true)
  }

  return {
    user,
    loading,
    loaded,
    error,
    lastLoadedAt,
    displayName,
    roleLabel,
    team,
    shift,
    ensureLoaded,
    refresh,
    setCurrentUser,
  }
})
