import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getCurrentUser } from '@/api/auth'
import { ApiError } from '@/api/http'
import type { CurrentUser } from '@/types/auth'
import { sharedLiveSocket } from '@/api/live'
import { pageMenu, pagePermission } from '@/permissions'

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
  const readonlyActions = new Set(['VIEW', 'DOWNLOAD', 'EXPORT', 'AUDIT_VERIFY'])
  const canAction = (menu:string, action:string, nativePermission:string, adapted=true, globalManagement=false):boolean => {
    const current=user.value
    if (!current?.permissions?.includes(nativePermission)) return false
    if (current.readOnly && !readonlyActions.has(action)) return false
    if (current.menuOperations != null && globalManagement && current.centralAdminLevel !== 'L1_SUPER') return false
    return current.menuOperations == null || adapted && current.menuOperations[menu]?.includes(action) === true
  }
  const canPage = (path:string):boolean => {
    const normalized=path.replace(/\/+$/,'')||'/'
    const permission=pagePermission(normalized), menu=pageMenu(normalized)
    return !!permission && !!menu && canAction(menu,'VIEW',permission)
  }
  const can = (permission:string):boolean => {
    const current=user.value
    const groups: Record<string,string[]> = {
      'safety:sys:view':['/rules','/operations'],
      'safety:work:view':['/people','/fences','/devices','/ai','/ai/models'],
      'safety:event:view':['/alarms','/analytics'],
    }
    if (current?.menuOperations!=null && groups[permission]) return groups[permission].some(canPage)
    if (!current?.permissions?.includes(permission)) return false
    if (current.menuOperations == null) return true
    const mappings: Record<string,[string,string[]]> = {
      'safety:module:rule:view':['SCS:rules',['VIEW']],
      'safety:module:rule:edit':['SCS:rules',['ADD','EDIT']],
      'safety:module:rule:approve':['SCS:rules',['CONFIRM']],
      'safety:module:rule:execute':['SCS:rules',['CONFIRM']],
      'safety:fence:list:view':['SCS:fences',['VIEW']],
      'safety:fence:edit':['SCS:fences',['ADD','EDIT']],
      'safety:fence:publish':['SCS:fences',['CONFIRM']],
      'safety:home:map:view':['SCS:overview',['VIEW']],
      'safety:person:map:view':['SCS:people',['VIEW']],
      'safety:person:config:edit':['SCS:people',['EDIT']],
      'safety:person:detail:execute':['SCS:people',['CONFIRM']],
      'safety:module:collision:view':['SCS:devices',['VIEW']],
      'safety:module:collision:execute':['SCS:devices',['CONFIRM']],
      'safety:module:ai:view':['SCS:ai',['VIEW']],
      'safety:module:ai:edit':['SCS:ai',['EDIT']],
      'safety:module:ai:assign':['SCS:ai',['EDIT']],
      'safety:module:ai:model:view':['SCS:ai-models',['VIEW']],
      'safety:module:alert:view':['SCS:alarms',['VIEW']],
      'safety:module:alert:handle':['SCS:alarms',['EDIT']],
      'safety:module:alert:approve':['SCS:alarms',['CONFIRM']],
      'safety:module:stat:view':['SCS:analytics',['VIEW']],
      'safety:module:stat:export':['SCS:analytics',['EXPORT']],
      'safety:module:ops:view':['SCS:operations',['VIEW']],
      'safety:module:ops:execute':['SCS:operations',['CONFIRM']],
      'safety:screen:view':['SCS:safety-screen',['VIEW']],
    }
    const mapped=mappings[permission]
    return !!mapped && mapped[1].some(action=>canAction(mapped[0],action,permission))
  }
  function clear():void {
    generation++
    sharedLiveSocket.setEnabled(false)
    user.value=null; loaded.value=false
  }

  function setCurrentUser(nextUser: CurrentUser): void {
    sharedLiveSocket.setEnabled(nextUser.menuOperations == null || nextUser.scopedRealtime === true)
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
          sharedLiveSocket.setEnabled(false)
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
    canPage,
    canAction,
    clear,
    setCurrentUser,
  }
})
