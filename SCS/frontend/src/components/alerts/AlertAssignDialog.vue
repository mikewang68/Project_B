<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { useDictionaryStore } from '@/stores/dictionary'

const props = defineProps<{ modelValue: boolean; mode: '派单' | '转派'; current?: string | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { assignee: string; priority: '普通' | '紧急'; limitMin: number; note: string; assigneeId?: string | undefined; assigneeName?: string | undefined }]
}>()

const dictStore = useDictionaryStore()
const selectedAssigneeId = ref<string>('')
const priority = ref<'普通' | '紧急'>('普通')
const limitMin = ref(30)
const note = ref('')

const assignees = computed(() => dictStore.dictionaries.assignees)

function syncDefaultAssignee(): void {
  if (assignees.value.length === 0) return
  if (props.current && props.current !== '待分配') {
    const match = assignees.value.find((a) => a.value === props.current || a.label === props.current)
    if (match) {
      selectedAssigneeId.value = match.value
      return
    }
  }
  selectedAssigneeId.value = assignees.value[0]?.value || ''
}

watch(() => props.modelValue, (open) => {
  if (open) {
    priority.value = '普通'
    limitMin.value = 30
    note.value = ''
    void dictStore.ensureLoaded().then(() => {
      syncDefaultAssignee()
    })
  }
}, { immediate: true })

watch(assignees, (list) => {
  if (list.length > 0 && (!selectedAssigneeId.value || !list.some((a) => a.value === selectedAssigneeId.value))) {
    syncDefaultAssignee()
  }
})

function submit(): void {
  const target = assignees.value.find((a) => a.value === selectedAssigneeId.value)
  const name = target ? target.label : selectedAssigneeId.value
  const id = target ? target.value : undefined
  emit('confirm', {
    assignee: name,
    assigneeId: id,
    assigneeName: name,
    priority: priority.value,
    limitMin: limitMin.value,
    note: note.value.trim(),
  })
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="500px" class="alert-assign-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="alert-dialog-heading"><span>INCIDENT {{ mode.toUpperCase() }}</span>
        <h2>{{ mode === '转派' ? '转派责任人' : '事件派单' }}</h2><p>选择责任人和处置要求，派发后进入待处理状态</p></div>
    </template>
    <div v-if="dictStore.error" class="dict-error-state" style="padding: 16px; color: #f56c6c; text-align: center;">
      <p>人员字典加载失败: {{ dictStore.error }}</p>
      <button type="button" @click="dictStore.refresh().then(() => syncDefaultAssignee())">重试</button>
    </div>
    <div v-else class="alert-form">
      <label><small>责任人</small>
        <el-select v-model="selectedAssigneeId" style="width: 100%">
          <el-option v-for="p in assignees" :key="p.value" :label="p.label" :value="p.value">
            <span>{{ p.label }}</span>
            <span v-if="p.team" style="margin-left: 8px; color: #909399; font-size: 12px;">{{ p.team }}</span>
          </el-option>
        </el-select>
      </label>
      <div class="alert-form-row">
        <label><small>处理优先级</small>
          <div class="alert-seg">
            <button type="button" :class="{ active: priority === '普通' }" @click="priority = '普通'">普通</button>
            <button type="button" :class="{ active: priority === '紧急' }" @click="priority = '紧急'">紧急</button>
          </div>
        </label>
        <label><small>处置时限</small>
          <el-select v-model="limitMin" style="width: 100%">
            <el-option :value="15" label="15 分钟内" /><el-option :value="30" label="30 分钟内" />
            <el-option :value="60" label="60 分钟内" /><el-option :value="120" label="2 小时内" />
          </el-select>
        </label>
      </div>
      <label><small>备注</small>
        <el-input v-model="note" type="textarea" :rows="3" placeholder="处置要求或转派原因（选填）" />
      </label>
    </div>
    <template #footer>
      <button type="button" @click="emit('update:modelValue', false)">取消</button>
      <button type="button" class="primary" :disabled="!!dictStore.error" @click="submit">确认{{ mode }}</button>
    </template>
  </ElDialog>
</template>
