<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import type { AlertEvent } from '@/types/alert'

const props = defineProps<{ modelValue: boolean; alert: AlertEvent | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  approve: []
  reject: [reason: string]
}>()

const reason = ref('')
watch(() => props.modelValue, (open) => { if (open) reason.value = '' })
</script>

<template>
  <ElDialog :model-value="modelValue" width="520px" class="review-alert-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="alert-dialog-heading"><span>SAFETY REVIEW</span><h2>严重 / 紧急事件复核</h2>
        <p v-if="alert">{{ alert.id }} · {{ alert.title }}</p></div>
    </template>
    <div v-if="alert?.treatment" class="review-treatment">
      <div class="review-treatment__head"><small>处置措施</small>
        <span v-for="m in alert.treatment.measures" :key="m" class="review-chip">{{ m }}</span>
      </div>
      <div class="review-treatment__row"><small>处置结果</small><b>{{ alert.treatment.result }}</b></div>
      <div class="review-treatment__row"><small>附件</small><b>{{ alert.treatment.attachment }}</b></div>
      <div class="review-treatment__row"><small>处置人 / 时间</small><b>{{ alert.treatment.handler }} · {{ alert.treatment.submitTime }}</b></div>
      <div v-if="alert.treatment.note" class="review-treatment__row"><small>处置备注</small><b>{{ alert.treatment.note }}</b></div>
      <label class="review-reject-note"><small>驳回原因（驳回继续处理时填写）</small>
        <el-input v-model="reason" type="textarea" :rows="2" placeholder="如：现场仍有风险，请继续处置" />
      </label>
    </div>
    <template #footer>
      <button type="button" class="warning" @click="emit('reject', reason.trim() || '复核未通过，请继续处置')">驳回继续处理</button>
      <button type="button" class="primary" @click="emit('approve')">通过并关闭</button>
    </template>
  </ElDialog>
</template>
