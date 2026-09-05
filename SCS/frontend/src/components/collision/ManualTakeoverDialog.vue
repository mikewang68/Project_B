<script setup lang="ts">
import { ElDialog } from 'element-plus'
import { PhoneFilled, WarningFilled } from '@element-plus/icons-vue'

defineProps<{ modelValue: boolean }>()
defineEmits<{ 'update:modelValue': [value: boolean]; confirm: [] }>()
</script>

<template>
  <ElDialog :model-value="modelValue" width="470px" class="collision-confirm-dialog" :show-close="false" @update:model-value="$emit('update:modelValue', $event)">
    <div class="collision-dialog-icon danger"><el-icon><WarningFilled /></el-icon></div>
    <h2>确认已执行现场人工急停？</h2>
    <p>PLC 回执已超时。请仅在现场人员确认设备已经停止后完成此操作。</p>
    <div class="dialog-check-summary"><span><i class="success"></i>停机指令：已发送</span><span><i class="danger"></i>PLC 回执：超时</span><span><i class="warning"></i>设备状态：未确认</span></div>
    <template #footer><button type="button" @click="$emit('update:modelValue', false)">取消</button><button type="button" class="danger" @click="$emit('confirm')"><el-icon><PhoneFilled /></el-icon>确认人工急停</button></template>
  </ElDialog>
</template>
