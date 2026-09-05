<script setup lang="ts">
import { ElDialog } from 'element-plus'
import { Check, CircleCheckFilled } from '@element-plus/icons-vue'

defineProps<{ modelValue: boolean; checks: Array<{ label: string; passed: boolean }> }>()
defineEmits<{ 'update:modelValue': [value: boolean]; confirm: [] }>()
</script>

<template>
  <ElDialog :model-value="modelValue" width="480px" class="collision-confirm-dialog" @update:model-value="$emit('update:modelValue', $event)">
    <div class="collision-dialog-icon success"><el-icon><CircleCheckFilled /></el-icon></div>
    <h2>确认当前现场风险已经解除？</h2>
    <p>系统将按“紧急 → 严重 → 预警 → 安全”顺序恢复状态，并持续校验安全距离。</p>
    <div class="release-check-list"><span v-for="item in checks" :key="item.label" :class="{ passed: item.passed }"><el-icon><Check /></el-icon>{{ item.label }}<b>{{ item.passed ? '已确认' : '未满足' }}</b></span></div>
    <template #footer><button type="button" @click="$emit('update:modelValue', false)">取消</button><button type="button" class="primary" :disabled="checks.some(item => !item.passed)" @click="$emit('confirm')">确认解除风险</button></template>
  </ElDialog>
</template>
