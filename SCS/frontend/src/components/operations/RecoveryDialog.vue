<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { CircleCheckFilled, Loading } from '@element-plus/icons-vue'
import type { EdgeNode, LocalEvent } from '@/types/operations'

const props = defineProps<{
  modelValue: boolean
  queue: LocalEvent[]
  nodes: EdgeNode[]
  /** 是否存在时间偏差待处理 */
  timeDrift: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  /** 补传进度：index 之前的事件均已处理 */
  progress: [doneCount: number]
  /** EDGE-03 重新下发规则 */
  ruleRedeliver: []
  /** 保持 EDGE-03 当前版本 */
  ruleKeep: []
  /** 重新同步时间 */
  timeResync: []
  finished: []
}>()

interface Step { key: string; label: string; state: 'wait' | 'running' | 'done' | 'fail'; detail: string }
const steps = ref<Step[]>([])
const phase = ref<'running' | 'rule-wait' | 'time-wait' | 'done'>('running')
const uploadTotal = ref(0)
const uploadDone = ref(0)
const duplicateCount = 2
const ruleDiffOpen = ref(false)
const ruleKept = ref(false)
let timers: number[] = []

const mismatchNode = computed(() => props.nodes.find((n) => n.ruleVersion !== n.platformVersion))
const successCount = computed(() => Math.max(uploadTotal.value - duplicateCount, 0))
const allDone = computed(() => phase.value === 'done')

function reset(): void {
  timers.forEach((t) => window.clearTimeout(t))
  timers = []
  uploadTotal.value = props.queue.length
  uploadDone.value = 0
  ruleDiffOpen.value = false
  ruleKept.value = false
  phase.value = 'running'
  steps.value = [
    { key: 'net', label: '网络恢复', state: 'wait', detail: '等待中心链路重新建立' },
    { key: 'stable', label: '链路稳定性检测', state: 'wait', detail: '检测丢包率与抖动' },
    { key: 'upload', label: '补传本地事件', state: 'wait', detail: `待补传 ${uploadTotal.value} 条` },
    { key: 'dedup', label: '事件对账去重', state: 'wait', detail: '按事件唯一编号比对' },
    { key: 'rule', label: '规则版本对账', state: 'wait', detail: '比对平台与边缘规则版本' },
    { key: 'time', label: '时间同步', state: 'wait', detail: props.timeDrift ? 'EDGE-02 存在时间偏差' : 'NTP 偏差检查' },
    { key: 'online', label: '恢复在线', state: 'wait', detail: '边缘节点切回在线模式' },
  ]
}

function setStep(key: string, patch: Partial<Step>): void {
  const s = steps.value.find((x) => x.key === key)
  if (s) Object.assign(s, patch)
}
function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    const t = window.setTimeout(resolve, ms)
    timers.push(t)
  })
}

async function run(): Promise<void> {
  setStep('net', { state: 'running', detail: '正在建立中心链路…' })
  await delay(650)
  setStep('net', { state: 'done', detail: '中心链路已恢复' })

  setStep('stable', { state: 'running', detail: '稳定性检测中…' })
  await delay(900)
  setStep('stable', { state: 'done', detail: '丢包率 0.2%，链路稳定' })

  // 补传：分批推进
  setStep('upload', { state: 'running', detail: `补传中 0 / ${uploadTotal.value}` })
  const batch = Math.max(1, Math.ceil(uploadTotal.value / 4))
  while (uploadDone.value < uploadTotal.value) {
    await delay(420)
    uploadDone.value = Math.min(uploadDone.value + batch, uploadTotal.value)
    setStep('upload', { detail: `补传中 ${uploadDone.value} / ${uploadTotal.value}` })
    emit('progress', uploadDone.value)
  }
  setStep('upload', { state: 'done', detail: `补传完成 ${uploadTotal.value} / ${uploadTotal.value}` })

  // 去重
  setStep('dedup', { state: 'running', detail: '正在比对事件唯一编号…' })
  await delay(700)
  setStep('dedup', {
    state: 'done',
    detail: `补传成功 ${successCount.value} 条 · 重复 ${duplicateCount} 条（已去重，未生成重复告警）· 失败 0`,
  })

  // 规则对账
  setStep('rule', { state: 'running', detail: '正在比对 4 个边缘节点规则版本…' })
  await delay(800)
  if (mismatchNode.value && !ruleKept.value) {
    setStep('rule', { state: 'fail', detail: `发现 1 个节点版本异常：${mismatchNode.value.id} ${mismatchNode.value.ruleVersion} → 平台 ${mismatchNode.value.platformVersion}` })
    phase.value = 'rule-wait'
    return
  }
  await afterRule()
}

async function afterRule(): Promise<void> {
  if (!ruleKept.value) setStep('rule', { state: 'done', detail: '4 / 4 节点规则版本一致' })
  // 时间同步
  setStep('time', { state: 'running', detail: '正在校时…' })
  await delay(500)
  if (props.timeDrift) {
    setStep('time', { state: 'fail', detail: 'EDGE-02 时间偏差 +3.8s，请完成时间同步后再结束恢复流程' })
    phase.value = 'time-wait'
    return
  }
  await finish()
}

function redeliverRule(): void {
  emit('ruleRedeliver')
  ruleDiffOpen.value = false
  setStep('rule', { state: 'running', detail: `${mismatchNode.value?.id} 规则重新下发中…` })
  window.setTimeout(() => { void afterRule() }, 1100)
}
function keepRule(): void {
  emit('ruleKeep')
  ruleKept.value = true
  setStep('rule', { state: 'done', detail: `已保持 ${mismatchNode.value?.id} 当前版本，登记待人工处理` })
  void afterRule()
}
function resyncTime(): void {
  emit('timeResync')
  setStep('time', { state: 'running', detail: 'EDGE-02 重新校时中…' })
  window.setTimeout(() => {
    setStep('time', { state: 'done', detail: '校时完成，偏差 32ms · 正常' })
    void finish()
  }, 900)
}
async function finish(): Promise<void> {
  setStep('online', { state: 'running', detail: '边缘节点切换在线模式…' })
  await delay(700)
  setStep('online', { state: 'done', detail: '全部完成，系统恢复在线运行' })
  phase.value = 'done'
  emit('finished')
}
function close(): void {
  emit('update:modelValue', false)
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) { reset(); void run() }
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
        <span>RECOVERY WORKFLOW</span>
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

          <!-- 规则版本对账操作 -->
          <div v-if="s.key === 'rule' && phase === 'rule-wait'" class="recovery-actions">
            <div class="recovery-versions">
              <span v-for="n in nodes" :key="n.id" :data-mismatch="n.ruleVersion !== n.platformVersion">
                {{ n.id }} <b class="mono">{{ n.ruleVersion }}</b>
              </span>
            </div>
            <div class="recovery-actions__btns">
              <button type="button" class="ops-btn small" @click="ruleDiffOpen = !ruleDiffOpen">查看差异</button>
              <button type="button" class="ops-btn small primary" @click="redeliverRule">重新下发</button>
              <button type="button" class="ops-btn small ghost" @click="keepRule">保持当前版本</button>
            </div>
            <div v-if="ruleDiffOpen" class="recovery-diff">
              <p class="mono">RULE-PER-001：v3.2 → v3.3</p>
              <p>紧急撤离时限 20 秒 → 15 秒；动态禁区采样 2 次 → 3 次</p>
            </div>
          </div>

          <!-- 时间同步操作 -->
          <div v-if="s.key === 'time' && phase === 'time-wait'" class="recovery-actions">
            <div class="recovery-actions__btns">
              <button type="button" class="ops-btn small primary" @click="resyncTime">重新同步</button>
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
