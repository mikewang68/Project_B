<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { FALSE_REASONS } from '@/types/ai'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; confirm: [reason: string, note: string] }>()

const reason = ref<string>('遮挡误判')
const note = ref('')

watch(() => props.modelValue, (open) => {
  if (open) { reason.value = '遮挡误判'; note.value = '' }
})

function submit(): void {
  emit('confirm', reason.value, note.value.trim())
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="460px" class="review-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="review-dialog-heading"><span>FALSE POSITIVE REVIEW</span><h2>标记误报</h2><p>请选择 AI 误判原因，将用于模型复盘</p></div>
    </template>
    <div class="review-reason-list">
      <button v-for="item in FALSE_REASONS" :key="item" type="button" :class="{ active: reason === item }" @click="reason = item">
        <i></i>{{ item }}
      </button>
    </div>
    <label v-if="reason === '其他'" class="review-note-label">
      <small>补充说明</small>
      <el-input v-model="note" placeholder="请描述误报的具体情况" />
    </label>
    <template #footer>
      <button type="button" @click="emit('update:modelValue', false)">取消</button>
      <button type="button" class="primary" @click="submit">确认标记误报</button>
    </template>
  </ElDialog>
</template>
