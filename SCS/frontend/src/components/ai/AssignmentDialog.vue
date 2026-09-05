<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { ASSIGNEES, type AiEvent } from '@/types/ai'

const props = defineProps<{ modelValue: boolean; event: AiEvent | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { assignee: string; priority: '普通' | '紧急'; note: string }]
}>()

const assignee = ref<string>('安全员 王建国')
const priority = ref<'普通' | '紧急'>('普通')
const note = ref('')

watch(() => props.modelValue, (open) => {
  if (open) {
    assignee.value = '安全员 王建国'
    priority.value = props.event?.risk === '高' ? '紧急' : '普通'
    note.value = ''
  }
})

function submit(): void {
  emit('confirm', { assignee: assignee.value, priority: priority.value, note: note.value.trim() })
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
    <div class="assignment-form">
      <label><small>责任人</small>
        <el-select v-model="assignee" style="width: 100%">
          <el-option v-for="person in ASSIGNEES" :key="person" :label="person" :value="person" />
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
      <button type="button" class="primary" @click="submit">确认派单</button>
    </template>
  </ElDialog>
</template>
