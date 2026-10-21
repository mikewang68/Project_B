<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { useDictionaryStore } from '@/stores/dictionary'
import type { AiEvent } from '@/types/ai'

const props = defineProps<{ modelValue: boolean; event: AiEvent | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { assignee: string; priority: '普通' | '紧急'; note: string }]
}>()

const dictStore = useDictionaryStore()
const selectedAssigneeId = ref<string>('')
const priority = ref<'普通' | '紧急'>('普通')
const note = ref('')

const assignees = computed(() => dictStore.dictionaries.assignees)

function syncDefaultAssignee(): void {
  if (assignees.value.length === 0) return
  selectedAssigneeId.value = assignees.value[0]?.value || ''
}

watch(() => props.modelValue, (open) => {
  if (open) {
    priority.value = props.event?.risk === '高' ? '紧急' : '普通'
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
  emit('confirm', { assignee: name, priority: priority.value, note: note.value.trim() })
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="500px" class="assignment-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="review-dialog-heading"><span>INCIDENT ASSIGNMENT</span><h2>派单处理</h2>
        <p v-if="event">{{ event.id }} · {{ event.type }} · {{ event.area }}</p></div>
    </template>
    <div v-if="dictStore.error" class="dict-error-state" style="padding: 16px; color: #f56c6c; text-align: center;">
      <p>人员字典加载失败: {{ dictStore.error }}</p>
      <button type="button" @click="dictStore.refresh().then(() => syncDefaultAssignee())">重试</button>
    </div>
    <div v-else class="assignment-form">
      <label><small>责任人</small>
        <el-select v-model="selectedAssigneeId" style="width: 100%">
          <el-option v-for="person in assignees" :key="person.value" :label="person.label" :value="person.value">
            <span>{{ person.label }}</span>
            <span v-if="person.team" style="margin-left: 8px; color: #909399; font-size: 12px;">{{ person.team }}</span>
          </el-option>
        </el-select>
      </label>
      <label><small>处理优先级</small>
        <div class="assignment-priority">
          <button type="button" :class="{ active: priority === '普通' }" @click="priority = '普通'">普通</button>
          <button type="button" :class="{ active: priority === '紧急' }" @click="priority = '紧急'">紧急</button>
        </div>
      </label>
      <label><small>备注</small>
        <el-input v-model="note" type="textarea" :rows="3" placeholder="可填写现场处置要求（选填）" />
      </label>
    </div>
    <template #footer>
      <button type="button" @click="emit('update:modelValue', false)">取消</button>
      <button type="button" class="primary" :disabled="!!dictStore.error" @click="submit">确认派单</button>
    </template>
  </ElDialog>
</template>
