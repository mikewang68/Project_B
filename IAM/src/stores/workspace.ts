import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { scopeApi, type WorkspaceProject } from '@/api/scope'
import { resolveWorkspace, selectionForWorkspace, selectionMatches, type WorkspaceTarget } from '@/iam/project-workspace'
import { useAuthStore } from './auth'

export const useWorkspaceStore = defineStore('workspace', () => {
  const projects = ref<WorkspaceProject[]>([])
  const target = ref<WorkspaceTarget>({ mode: 'projects', projectId: null })
  const error = ref('')
  const loading = ref(false)
  const project = computed(() => projects.value.find(row => row.projectId === target.value.projectId))

  async function prepare(query: Record<string, unknown>) {
    loading.value = true
    error.value = ''
    try {
      const result = await scopeApi.workspace()
      if (result.legacy || !result.userContext) throw new Error('当前账号尚未启用分级项目工作台')
      const resolved = resolveWorkspace(result.userContext, result.projects, query)
      const auth = useAuthStore()
      const selection = selectionForWorkspace(result.userContext, resolved)
      if (selection && !selectionMatches(result.userContext, selection)) await auth.selectScope(selection)
      else if (JSON.stringify([auth.userContext?.authorizationVersion, auth.userContext?.adminLevel, auth.userContext?.scopeMode, auth.userContext?.activeScope])
          !== JSON.stringify([result.userContext.authorizationVersion, result.userContext.adminLevel, result.userContext.scopeMode, result.userContext.activeScope])) {
        await auth.refreshScopeState()
      }
      projects.value = result.projects
      target.value = resolved
      return true
    } catch (cause) {
      projects.value = []
      target.value = { mode: 'projects', projectId: null }
      error.value = cause instanceof Error ? cause.message : '项目工作台加载失败，请重试'
      return false
    } finally { loading.value = false }
  }
  function reset() {
    projects.value = []
    target.value = { mode: 'projects', projectId: null }
    error.value = ''
  }
  return { projects, target, project, error, loading, prepare, reset }
})
