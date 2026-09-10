<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import { CircleCheckFilled, Loading } from '@element-plus/icons-vue'
import { operationsApi, type BackendEdgeNode } from '@/api/operations'
import { toRecoverySteps } from '@/adapters/operations'
import type { RecoveryStep } from '@/types/operations'

/**
 * 云边链路恢复对话框：步骤完全由后端恢复状态机驱动
 * CONNECTIVITY → CLOCK_RECONCILIATION → RULE_RECONCILIATION → EVENT_REPLAY → FINAL_CHECK → ONLINE。
 * 前端只在阻塞点（时间对账 / 规则对账 / 补传失败重试）发起对应 API，不做本地定时器动画。
 */
const props = withDefaults(defineProps<{
  modelValue: boolean
  /** 恢复节点，默认 EDGE-03（断网自治演示节点） */
  nodeId?: string
}>(), { nodeId: 'EDGE-03' })

const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  /** 任一步推进后通知父组件局部刷新节点 / 队列 */
  changed: []
  finished: []
}>()

type WaitPhase = 'running' | 'time-wait' | 'rule-wait' | 'replay-wait' | 'done'
const phase = ref<WaitPhase>('running')
const steps = ref<RecoveryStep[]>([])
const busy = ref(false)
const lastNode = ref<BackendEdgeNode | null>(null)
const ruleDiffOpen = ref(false)

const mismatchVersion = computed(() => {
  const n = lastNode.value
  return n && n.activeRuleVersion !== n.expectedRuleVersion
    ? { edge: n.activeRuleVersion, expected: n.expectedRuleVersion }
    : null
})
const allDone = computed(() => phase.value === 'done')

const STATIC_STEPS: { key: string; label: string }[] = [
  { key: 'CONNECTIVITY', label: '恢复连接' },
  { key: 'CLOCK_RECONCILIATION', label: '时间对账' },
  { key: 'RULE_RECONCILIATION', label: '规则版本对账' },
  { key: 'EVENT_REPLAY', label: '缓存事件补传' },
  { key: 'FINAL_CHECK', label: '最终检查' },
  { key: 'ONLINE', label: '恢复在线' },
]

function reset(): void {
  phase.value = 'running'
  busy.value = false
  ruleDiffOpen.value = false
  lastNode.value = null
  steps.value = STATIC_STEPS.map((s) => ({ key: s.key, label: s.label, state: 'wait', detail: '等待执行' }))
}

/** 用后端节点状态渲染步骤，并判断停在哪个阻塞点 */
function applyNode(node: BackendEdgeNode): void {
  lastNode.value = node
  const backendSteps = toRecoverySteps(node.recoveryPhases)
  steps.value = STATIC_STEPS.map((s) => {
    const hit = backendSteps.find((b) => b.key === s.key)
    return hit ?? { key: s.key, label: s.label, state: 'wait', detail: '等待执行' }
  })
  emit('changed')
  if (node.status === 'ONLINE') {
    const online = steps.value.find((s) => s.key === 'ONLINE')
    if (online) { online.state = 'done'; online.detail = '全部完成，系统恢复在线运行' }
    phase.value = 'done'
    emit('finished')
    return
  }
  const blocked = node.recoveryPhases?.find((p) => p.status === 'BLOCKED' || p.status === 'FAILED')
  if (!blocked) {
    phase.value = 'running'
    return
  }
  if (blocked.key === 'CLOCK_RECONCILIATION') phase.value = 'time-wait'
  else if (blocked.key === 'RULE_RECONCILIATION') phase.value = 'rule-wait'
  else if (blocked.key === 'EVENT_REPLAY') phase.value = 'replay-wait'
  else phase.value = 'running'
}

async function drive(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const node = await operationsApi.recover(props.nodeId)
    applyNode(node)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '恢复流程执行失败')
  } finally {
    busy.value = false
  }
}

async function resyncTime(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const node = await operationsApi.reconcileTime(props.nodeId)
    ElMessage.success('时间对账完成，偏差回到正常范围')
    applyNode(node)
  } finally {
    busy.value = false
  }
}

async function redeliverRule(): Promise<void> {
  ruleDiffOpen.value = false
  if (busy.value) return
  busy.value = true
  try {
    const node = await operationsApi.reconcileRules('redeliver', props.nodeId)
    ElMessage.success('规则重新下发完成，边缘与平台版本一致')
    applyNode(node)
  } finally {
    busy.value = false
  }
}

async function keepRule(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const node = await operationsApi.reconcileRules('keep', props.nodeId)
    ElMessage.warning('已保持边缘当前版本，登记为待人工处理')
    applyNode(node)
  } finally {
    busy.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) {
      reset()
      void drive()
    }
  },
)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="recovery-dialog"
    width="640px"
    :close-on-click-modal="false"
    :show-close="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="ops-dialog-heading">
        <span>RECOVERY WORKFLOW · SIMULATED EDGE AUTONOMY</span>
        <h2>云边链路恢复</h2>
      </div>
    </template>

    <ul class="recovery-steps">
      <li v-for="s in steps" :key="s.key" :data-state="s.state">
        <span class="recovery-steps__icon">
          <el-icon v-if="s.state === 'running'" class="spin"><Loading /></el-icon>
          <el-icon v-else-if="s.state === 'done'"><CircleCheckFilled /></el-icon>
          <i v-else-if="s.state === 'fail'">!</i>
          <em v-else></em>
        </span>
        <div class="recovery-steps__body">
          <b>{{ s.label }}</b>
          <p>{{ s.detail }}</p>

          <!-- 规则版本对账操作（后端 RULE_RECONCILIATION 阻塞时出现） -->
          <div v-if="s.key === 'RULE_RECONCILIATION' && phase === 'rule-wait'" class="recovery-actions">
            <div class="recovery-versions">
              <span :data-mismatch="true" v-if="lastNode">
                {{ lastNode.id }} <b class="mono">{{ lastNode.activeRuleVersion }}</b>
                → 平台 <b class="mono">{{ lastNode.expectedRuleVersion }}</b>
              </span>
            </div>
            <div class="recovery-actions__btns">
              <button type="button" class="ops-btn small" @click="ruleDiffOpen = !ruleDiffOpen">查看差异</button>
              <button type="button" class="ops-btn small primary" :disabled="busy" @click="redeliverRule">重新下发</button>
              <button type="button" class="ops-btn small ghost" :disabled="busy" @click="keepRule">保持当前版本</button>
            </div>
            <div v-if="ruleDiffOpen && mismatchVersion" class="recovery-diff">
              <p class="mono">RULE-PER-001：{{ mismatchVersion.edge }} → {{ mismatchVersion.expected }}</p>
              <p>以平台最新已生效版本为准，重新下发规则与参数到边缘节点</p>
            </div>
          </div>

          <!-- 时间对账操作（后端 CLOCK_RECONCILIATION 阻塞时出现） -->
          <div v-if="s.key === 'CLOCK_RECONCILIATION' && phase === 'time-wait'" class="recovery-actions">
            <div class="recovery-actions__btns">
              <button type="button" class="ops-btn small primary" :disabled="busy" @click="resyncTime">重新同步时间</button>
            </div>
          </div>

          <!-- 补传失败重试（后端 EVENT_REPLAY 阻塞时出现） -->
          <div v-if="s.key === 'EVENT_REPLAY' && phase === 'replay-wait'" class="recovery-actions">
            <div class="recovery-actions__btns">
              <button type="button" class="ops-btn small primary" :disabled="busy" @click="drive">重试补传</button>
            </div>
          </div>
        </div>
      </li>
    </ul>

    <template #footer>
      <button type="button" class="ops-btn" :disabled="!allDone" @click="close">完成</button>
    </template>
  </el-dialog>
</template>
