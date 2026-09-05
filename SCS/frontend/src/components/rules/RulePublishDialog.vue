<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { Check, Loading, WarningFilled, CircleCheckFilled } from '@element-plus/icons-vue'
import type { EdgeNodeState, SafetyRule } from '@/types/rule'

const props = defineProps<{ modelValue: boolean; rule: SafetyRule | undefined }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  done: [rule: SafetyRule]
}>()

const stage = ref<'confirm' | 'syncing' | 'done'>('confirm')
const acknowledged = ref(false)
const nodes = ref<EdgeNodeState[]>([])
let timers: ReturnType<typeof setTimeout>[] = []

watch(() => props.modelValue, (open) => {
  if (!open || !props.rule) return
  stage.value = 'confirm'
  acknowledged.value = false
  nodes.value = props.rule.edgeNodes.map((n) => ({ ...n, state: n.state === 'mismatch' ? 'mismatch' : 'synced' }))
})

function startPublish(): void {
  if (!props.rule) return
  stage.value = 'syncing'
  timers.forEach(clearTimeout)
  timers = []
  // 全部节点重新下发
  nodes.value = props.rule.edgeNodes.map((n) => ({ ...n, state: 'syncing' }))
  nodes.value.forEach((n, i) => {
    timers.push(setTimeout(() => { n.state = 'synced'; n.version = props.rule!.platformVersion }, 520 * (i + 1)))
  })
  timers.push(setTimeout(() => {
    stage.value = 'done'
  }, 520 * nodes.value.length + 350))
}

function finish(): void {
  if (props.rule) emit('done', props.rule)
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="520px" class="rule-publish-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="rule-dialog-heading"><span>RULE PUBLISH</span>
        <h2>{{ stage === 'confirm' ? '发布规则' : '边缘节点下发' }}</h2>
        <p>发布后规则同步至全部边缘节点并开始生效（Mock）</p></div>
    </template>

    <template v-if="rule">
      <div v-if="stage === 'confirm'" class="publish-confirm">
        <dl class="publish-info">
          <div><dt>规则名称</dt><dd>{{ rule.name }}</dd></div>
          <div><dt>发布版本</dt><dd class="mono">{{ rule.platformVersion }}</dd></div>
          <div><dt>适用区域</dt><dd>{{ rule.areas.join('、') }}</dd></div>
          <div><dt>下发节点</dt><dd class="mono">{{ rule.edgeNodes.map((n) => n.node).join('  ') }}</dd></div>
        </dl>
        <div v-if="rule.highRisk" class="publish-highrisk">
          <el-icon><WarningFilled /></el-icon>
          <div>
            <b>高危安全参数确认</b>
            <p>该修改涉及高危安全参数，发布后将影响现场设备联动。</p>
            <label class="publish-ack"><input v-model="acknowledged" type="checkbox" />我已评估现场影响，确认发布</label>
          </div>
        </div>
      </div>

      <div v-else class="publish-sync">
        <ul class="publish-nodes">
          <li v-for="n in nodes" :key="n.node" :data-state="n.state">
            <span class="publish-nodes__id">{{ n.node }}</span>
            <span class="publish-nodes__version mono">{{ n.version }}</span>
            <span class="publish-nodes__state">
              <el-icon v-if="n.state === 'synced'"><Check /></el-icon>
              <el-icon v-else-if="n.state === 'syncing'" class="spin"><Loading /></el-icon>
              <el-icon v-else><WarningFilled /></el-icon>
              {{ n.state === 'synced' ? '同步成功' : n.state === 'syncing' ? '下发中…' : '版本不一致' }}
            </span>
          </li>
        </ul>
        <div v-if="stage === 'done'" class="publish-done">
          <el-icon><CircleCheckFilled /></el-icon>
          <b>{{ nodes.length }} / {{ nodes.length }} 同步成功，规则已生效</b>
        </div>
      </div>
    </template>

    <template #footer>
      <template v-if="stage === 'confirm'">
        <button type="button" class="rule-btn ghost" @click="emit('update:modelValue', false)">取消</button>
        <button type="button" class="rule-btn primary" :disabled="rule?.highRisk && !acknowledged" @click="startPublish">确认发布</button>
      </template>
      <template v-else-if="stage === 'syncing'">
        <button type="button" class="rule-btn ghost" disabled>下发中…</button>
      </template>
      <template v-else>
        <button type="button" class="rule-btn primary" @click="finish">完成</button>
      </template>
    </template>
  </ElDialog>
</template>
