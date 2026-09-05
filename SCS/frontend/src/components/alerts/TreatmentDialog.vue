<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { Paperclip } from '@element-plus/icons-vue'
import { TREATMENT_MEASURES } from '@/types/alert'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { measures: string[]; result: string; attachment: string; note: string }]
}>()

const measures = ref<string[]>([])
const note = ref('')

watch(() => props.modelValue, (open) => {
  if (open) { measures.value = []; note.value = '' }
})
function toggle(m: string): void {
  const i = measures.value.indexOf(m)
  if (i >= 0) measures.value.splice(i, 1)
  else measures.value.push(m)
}
function submit(): void {
  if (!measures.value.length) return
  emit('confirm', {
    measures: [...measures.value], result: '风险已解除',
    attachment: '现场处置照片_' + new Date().toLocaleTimeString('zh-CN', { hour12: false }).replace(/:/g, '') + '.jpg（Mock）',
    note: note.value.trim(),
  })
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="520px" class="treatment-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="alert-dialog-heading"><span>FIELD TREATMENT</span><h2>提交处置结果</h2>
        <p>记录现场处置措施，提交后进入复核环节（严重 / 紧急事件不可直接关闭）</p></div>
    </template>
    <div class="alert-form">
      <label class="treatment-measures"><small>处置措施（多选）</small>
        <button v-for="m in TREATMENT_MEASURES" :key="m" type="button" :class="{ active: measures.includes(m) }" @click="toggle(m)">
          <i></i>{{ m }}
        </button>
      </label>
      <div class="treatment-result"><small>处置结果</small><b>风险已解除</b></div>
      <div class="treatment-attach"><el-icon><Paperclip /></el-icon><span>现场处置照片（Mock 附件，提交时自动生成）</span></div>
      <label><small>备注</small>
        <el-input v-model="note" type="textarea" :rows="3" placeholder="补充现场情况（选填）" />
      </label>
    </div>
    <template #footer>
      <button type="button" @click="emit('update:modelValue', false)">取消</button>
      <button type="button" class="primary" :disabled="!measures.length" @click="submit">提交处置结果</button>
    </template>
  </ElDialog>
</template>
