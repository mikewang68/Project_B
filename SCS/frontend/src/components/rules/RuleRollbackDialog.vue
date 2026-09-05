<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { bumpVersion, type SafetyRule } from '@/types/rule'

const props = defineProps<{ modelValue: boolean; rule: SafetyRule | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  confirm: [rule: SafetyRule, targetVersion: string, newVersion: string]
}>()

const target = ref('')
const acknowledged = ref(false)
watch(() => props.modelValue, (open) => {
  if (!open) return
  target.value = props.rule?.versions.find((v) => v.state === '历史版本')?.version ?? ''
  acknowledged.value = false
})

const history = computed(() => props.rule?.versions ?? [])
const newVersion = computed(() => (props.rule ? bumpVersion(props.rule.platformVersion) : ''))
function doConfirm(): void {
  if (!props.rule || !target.value) return
  emit('confirm', props.rule, target.value, newVersion.value)
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="500px" class="rule-rollback-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="rule-dialog-heading"><span>VERSION ROLLBACK</span>
        <h2>规则回滚</h2><p>回滚不会覆盖当前版本，将生成新版本并重新进入发布流程</p></div>
    </template>

    <template v-if="rule">
      <ul class="rollback-list">
        <li v-for="v in history" :key="v.version" :class="{ current: v.state === '当前', selected: target === v.version && v.state === '历史版本' }">
          <label v-if="v.state === '历史版本'">
            <input v-model="target" type="radio" :value="v.version" />
            <div><b>{{ v.version }}</b><small>{{ v.date }} · {{ v.note }} · {{ v.author }}</small></div>
          </label>
          <div v-else class="rollback-current">
            <div><b>{{ v.version }}（当前）</b><small>{{ v.date }} · {{ v.note }} · {{ v.author }}</small></div>
            <span>当前版本</span>
          </div>
        </li>
      </ul>

      <div class="rollback-preview">
        <p>回滚后将生成新版本 <b class="mono">{{ newVersion }}</b>，内容来源：<b>基于 {{ target || '—' }} 回滚</b>，状态进入「待评审」，需重新审批、发布。</p>
      </div>

      <div v-if="rule.highRisk" class="publish-highrisk">
        <el-icon><WarningFilled /></el-icon>
        <div>
          <b>高危安全参数确认</b>
          <p>该规则涉及现场设备联动，回滚后判定阈值将变化，请确认已评估影响。</p>
          <label class="publish-ack"><input v-model="acknowledged" type="checkbox" />我已评估现场影响，确认生成回滚版本</label>
        </div>
      </div>
    </template>

    <template #footer>
      <button type="button" class="rule-btn ghost" @click="emit('update:modelValue', false)">取消</button>
      <button type="button" class="rule-btn danger" :disabled="!target || (rule?.highRisk && !acknowledged)"
        @click="doConfirm">确认回滚</button>
    </template>
  </ElDialog>
</template>
