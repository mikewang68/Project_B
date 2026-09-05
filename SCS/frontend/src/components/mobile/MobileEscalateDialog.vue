<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import { ESCALATE_REASONS } from '@/types/incident'
import type { AlertRisk } from '@/types/alert'

const props = defineProps<{ modelValue: boolean; currentRisk: AlertRisk }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { reason: string; level: AlertRisk; targets: string }]
}>()

const reason = ref('')
const level = ref<AlertRisk>('紧急')
const targets = ref<string[]>(['调度员', '管理人员'])

watch(() => props.modelValue, (v) => {
  if (v) {
    reason.value = ''
    level.value = '紧急'
    targets.value = ['调度员', '管理人员']
  }
})

const allTargets = ['调度员', '管理人员', '设备管理员', '相邻班组']
function toggleTarget(t: string): void {
  const i = targets.value.indexOf(t)
  if (i >= 0) targets.value.splice(i, 1)
  else targets.value.push(t)
}
function close(): void { emit('update:modelValue', false) }
function confirm(): void {
  if (!reason.value) {
    ElMessage.warning('请选择升级原因')
    return
  }
  if (targets.value.length === 0) {
    ElMessage.warning('请至少选择一个通知对象')
    return
  }
  emit('confirm', { reason: reason.value, level: level.value, targets: targets.value.join('、') })
  close()
}
</script>

<template>
  <ElDialog
    :model-value="props.modelValue"
    title="事件升级"
    width="330px"
    append-to-body
    class="m-escalate-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="m-field">
      <label>升级原因</label>
      <div class="m-chips">
        <button
          v-for="r in ESCALATE_REASONS"
          :key="r"
          type="button"
          class="m-chip"
          :data-on="reason === r"
          @click="reason = r"
        >{{ r }}</button>
      </div>
    </div>
    <div class="m-field">
      <label>升级等级（当前：{{ props.currentRisk }}）</label>
      <div class="m-segmented m-segmented--mini">
        <button type="button" :data-active="level === '严重'" @click="level = '严重'">严重</button>
        <button type="button" :data-active="level === '紧急'" @click="level = '紧急'">紧急</button>
      </div>
    </div>
    <div class="m-field">
      <label>通知对象</label>
      <div class="m-chips">
        <button
          v-for="t in allTargets"
          :key="t"
          type="button"
          class="m-chip"
          :data-on="targets.includes(t)"
          @click="toggleTarget(t)"
        >{{ t }}</button>
      </div>
    </div>
    <template #footer>
      <button type="button" class="m-btn m-btn--ghost" @click="close">取消</button>
      <button type="button" class="m-btn m-btn--danger" @click="confirm">确认升级</button>
    </template>
  </ElDialog>
</template>
