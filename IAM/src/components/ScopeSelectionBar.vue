<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { canSelectScope, currentScopeKey, scopeOptions, scopeSummary, selectionForKey } from '@/iam/scope-selection'

const auth = useAuthStore()
const emit = defineEmits<{ changed: [] }>()
const draft = ref<string>()
const error = ref('')
const refreshing = ref(false)
const options = computed(() => auth.userContext ? scopeOptions(auth.userContext) : [])
const editable = computed(() => !!auth.userContext && canSelectScope(auth.userContext))
const summary = computed(() => auth.userContext ? scopeSummary(auth.userContext) : '')
const current = computed(() => auth.userContext ? currentScopeKey(auth.userContext) : undefined)
const busy = computed(() => auth.scopeSelectionPending || refreshing.value)
watch(() => auth.userContext, () => { draft.value = current.value }, { immediate: true })

async function apply() {
  if (!auth.userContext || !draft.value || busy.value) return
  error.value = ''
  try {
    const selection = selectionForKey(auth.userContext, draft.value)
    await auth.selectScope(selection)
    draft.value = current.value
    emit('changed')
    ElMessage.success('当前查看范围和菜单已更新')
  } catch (cause) {
    draft.value = current.value
    error.value = `未完成范围切换：${cause instanceof Error ? cause.message : '请求失败'}。请核对当前范围后重试。`
    if (!auth.scopeRefreshRequired) emit('changed')
  }
}

async function refresh() {
  if (busy.value) return
  refreshing.value = true
  error.value = ''
  try {
    await auth.refreshScopeState()
    draft.value = current.value
    emit('changed')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重新读取权限失败，请重试'
  } finally { refreshing.value = false }
}
</script>

<template>
  <section v-if="auth.userContext" class="scope-bar" aria-label="当前查看范围">
    <div class="scope-line">
      <span class="scope-label">当前范围</span>
      <span class="scope-current" :title="summary" aria-live="polite">{{ summary }}</span>
      <el-tag v-if="!editable" size="small" type="info">归属已锁定</el-tag>
      <template v-else>
        <el-select v-model="draft" class="scope-select" aria-label="选择查看范围" placeholder="选择已授权范围"
          :disabled="busy || auth.scopeRefreshRequired" filterable>
          <el-option v-for="option in options" :key="option.key" :label="option.label" :value="option.key" />
        </el-select>
        <el-button type="primary" :loading="auth.scopeSelectionPending"
          :disabled="busy || auth.scopeRefreshRequired || !draft || draft === current" @click="apply">应用范围</el-button>
      </template>
      <el-button :loading="refreshing" :disabled="busy" @click="refresh">刷新权限</el-button>
    </div>
    <div v-if="editable" class="scope-help">业务查看与后续系统访问使用此范围；人员归属保持不变。</div>
    <el-alert v-if="auth.scopeRefreshRequired" type="warning" :closable="false" show-icon
      title="正在重新核验当前范围与菜单，完成前暂停显示原页面。" />
    <el-alert v-if="error" type="error" :closable="false" :title="error" show-icon role="alert" />
  </section>
</template>

<style scoped>
.scope-bar { padding: 12px 24px; background: var(--iam-topbar-bg); border-bottom: 1px solid var(--iam-topbar-border); flex-shrink: 0; }
.scope-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.scope-label { color: var(--iam-text-muted); font-size: 13px; }
.scope-current { font-size: 13px; color: var(--iam-text-strong); max-width: 420px; overflow-wrap: anywhere; }
.scope-select { width: 350px; max-width: 100%; }
.scope-help { margin-top: 6px; color: var(--iam-text-muted); font-size: 12px; }
.el-alert { margin-top: 8px; }
@media (max-width: 760px) { .scope-bar { padding: 10px 16px; } .scope-current { max-width: 100%; } }
</style>
