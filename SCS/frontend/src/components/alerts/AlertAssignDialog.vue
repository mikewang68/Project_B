<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { ALERT_ASSIGNEES } from '@/types/alert'

const props = defineProps<{ modelValue: boolean; mode: '派单' | '转派'; current?: string | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { assignee: string; priority: '普通' | '紧急'; limitMin: number; note: string }]
}>()

const assignee = ref('安全员 王建国')
const priority = ref<'普通' | '紧急'>('普通')
const limitMin = ref(30)
const note = ref('')

watch(() => props.modelValue, (open) => {
  if (open) {
    assignee.value = props.current && props.current !== '待分配' ? props.current : '安全员 王建国'
    priority.value = '普通'; limitMin.value = 30; note.value = ''
  }
})

function submit(): void {
  emit('confirm', { assignee: assignee.value, priority: priority.value, limitMin: limitMin.value, note: note.value.trim() })
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
    <div class="alert-form">
      <label><small>责任人</small>
        <el-select v-model="assignee" style="width: 100%">
          <el-option v-for="p in ALERT_ASSIGNEES" :key="p" :label="p" :value="p" />
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
      <button type="button" class="primary" @click="submit">确认{{ mode }}</button>
    </template>
  </ElDialog>
</template>
