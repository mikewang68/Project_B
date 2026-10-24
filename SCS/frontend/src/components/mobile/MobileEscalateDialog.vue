<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import { ESCALATE_REASONS } from '@/types/incident'
import type { AlertRisk } from '@/types/alert'

const props = defineProps<{ modelValue: boolean; currentRisk: AlertRisk }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [payload: { reason: string; level: AlertRisk; targets: string }]
}>()

// 交互复查-P1：只提供比当前等级高的选项（同级/降级会被后端 409 拒绝）
const RISK_ORDER: AlertRisk[] = ['一般', '预警', '严重', '紧急']
const higherLevels = computed<AlertRisk[]>(() => {
  const idx = RISK_ORDER.indexOf(props.currentRisk)
  return idx < 0 ? [] : RISK_ORDER.slice(idx + 1)
})

const reason = ref('')
const level = ref<AlertRisk>('紧急')
const targets = ref<string[]>(['调度员', '管理人员'])

watch(() => props.modelValue, (v) => {
  if (v) {
    reason.value = ''
    level.value = higherLevels.value[0] ?? '紧急'
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
  if (higherLevels.value.length === 0 || !higherLevels.value.includes(level.value)) {
    ElMessage.warning('当前已是最高风险等级，无需升级')
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
      <label>升级等级（当前：{{ props.currentRisk }}{{ higherLevels.length === 0 ? '，已是最高' : '' }}）</label>
      <div v-if="higherLevels.length > 0" class="m-segmented m-segmented--mini">
        <button v-for="lv in higherLevels" :key="lv" type="button" :data-active="level === lv" @click="level = lv">{{ lv }}</button>
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
