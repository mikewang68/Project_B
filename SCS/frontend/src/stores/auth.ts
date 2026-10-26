import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentUser } from '@/api/auth'
import { ApiError } from '@/api/http'
import type { CurrentUser } from '@/types/auth'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref<string | null>(null)
  const lastLoadedAt = ref<string | null>(null)
  let inflight: Promise<CurrentUser | null> | null = null
  // 会话代际：clear() 自增。登出后迟到的 /auth/me 响应据此丢弃，防止会话被"复活"。
  let generation = 0

  const displayName = computed(() => user.value?.name ?? '')
  const roleLabel = computed(() => user.value?.role ?? '')
  const team = computed(() => user.value?.team ?? '')
  const shift = computed(() => user.value?.shift ?? null)
  const can = (permission:string):boolean => !!user.value?.permissions?.includes(permission)
  function clear():void {
    generation++
    user.value=null; loaded.value=false
  }

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
    const currentGeneration = generation
    inflight = getCurrentUser()
      .then((nextUser) => {
        if (currentGeneration !== generation) return null
        setCurrentUser(nextUser)
        return nextUser
      })
      .catch((err: unknown) => {
        if (currentGeneration !== generation) return null
        // 仅认证失效（401）才清会话；网络中断/超时/5xx 等瞬时故障保留已有会话，避免误登出
        if (err instanceof ApiError && err.status === 401) {
          user.value = null
          loaded.value = false
        }
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
    can,
    clear,
    setCurrentUser,
  }
})
