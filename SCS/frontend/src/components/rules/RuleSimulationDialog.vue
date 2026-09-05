<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { VideoPlay, Loading } from '@element-plus/icons-vue'
import { ruleApi } from '@/api/rules'
import { ElMessage } from 'element-plus'
import type { SafetyRule, SimulationInput, SimulationResult } from '@/types/rule'

const props = defineProps<{ modelValue: boolean; rules: SafetyRule[]; initialId?: string | undefined }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const selectedId = ref('RULE-DEV-003')
const input = reactive<SimulationInput>({ distance: 4.2, relSpeed: 1.8, direction: '接近', radarQuality: 97, weather: '小雨' })
const running = ref(false)
const step = ref(0) // 0 未运行 1 输入 2 规则 3 判定 4 动作
const result = ref<SimulationResult>()
let timer: ReturnType<typeof setTimeout>[] = []

watch(() => props.modelValue, (open) => {
  if (!open) return
  selectedId.value = props.initialId ?? 'RULE-DEV-003'
  Object.assign(input, { distance: 4.2, relSpeed: 1.8, direction: '接近', radarQuality: 97, weather: '小雨' })
  step.value = 0
  result.value = undefined
})

const selectedRule = () => props.rules.find((r) => r.id === selectedId.value)

function run(): void {
  timer.forEach(clearTimeout)
  timer = []
  const rule = selectedRule()
  if (!rule) return
  running.value = true
  step.value = 0
  result.value = undefined
  timer.push(setTimeout(() => { step.value = 1 }, 260))
  timer.push(setTimeout(() => { step.value = 2 }, 620))
  timer.push(setTimeout(async () => {
    try {
      result.value = await ruleApi.simulate({ ruleId: rule.id, ...input })
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '规则仿真失败')
      running.value = false
      return
    }
    step.value = 3
  }, 1000))
  timer.push(setTimeout(() => { step.value = 4; running.value = false }, 1380))
}

function levelTone(): string {
  switch (result.value?.level) {
    case '紧急风险': return 'danger'
    case '严重风险': return 'warning'
    case '预警风险': return 'primary'
    default: return 'success'
  }
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="620px" class="rule-sim-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="rule-dialog-heading"><span>RULE SIMULATION</span>
        <h2>规则仿真</h2><p>输入现场参数，Mock 演示「输入 → 规则 → 判定 → 动作」全过程</p></div>
    </template>

    <div class="sim-form">
      <label class="wide"><small>选择规则</small>
        <el-select v-model="selectedId" style="width:100%">
          <el-option v-for="r in rules" :key="r.id" :label="`${r.id} · ${r.name}（${r.version}）`" :value="r.id" />
        </el-select>
      </label>
      <label><small>当前距离 (m)</small><el-input v-model.number="input.distance" type="number" /></label>
      <label><small>相对速度 (m/s)</small><el-input v-model.number="input.relSpeed" type="number" /></label>
      <label class="wide"><small>设备方向</small>
        <div class="rule-seg">
          <button v-for="d in ['接近', '远离', '静止']" :key="d" type="button" :class="{ active: input.direction === d }"
            @click="input.direction = d as SimulationInput['direction']">{{ d }}</button>
        </div>
      </label>
      <label><small>雷达质量 (%)</small><el-input v-model.number="input.radarQuality" type="number" /></label>
      <label><small>天气</small>
        <el-select v-model="input.weather" style="width:100%">
          <el-option v-for="w in ['晴', '小雨', '雾天', '夜间']" :key="w" :label="w" :value="w" />
        </el-select>
      </label>
    </div>

    <div class="sim-run">
      <button type="button" class="rule-btn primary" :disabled="running" @click="run">
        <el-icon v-if="running"><Loading /></el-icon><el-icon v-else><VideoPlay /></el-icon>{{ running ? '仿真运行中…' : '运行仿真' }}
      </button>
    </div>

    <div v-if="step >= 1" class="sim-flow">
      <div class="sim-step" :data-on="step >= 1">
        <b>1 · 输入采集</b>
        <p>距离 {{ input.distance }}m ｜ 相对速度 {{ input.relSpeed }}m/s（{{ input.direction }}）｜ 雷达质量 {{ input.radarQuality }}% ｜ {{ input.weather }}</p>
      </div>
      <div class="sim-step" :data-on="step >= 2">
        <b>2 · 匹配规则</b>
        <p>{{ selectedRule()?.id }} {{ selectedRule()?.version }} ｜ {{ selectedRule()?.name }}</p>
      </div>
      <div v-if="step >= 3 && result" class="sim-step sim-step--result" :data-on="true" :data-tone="levelTone()">
        <b>3 · 判定结果</b>
        <div class="sim-verdict">
          <span class="sim-verdict__level">{{ result.level }}</span>
          <span class="sim-verdict__rule">触发 {{ result.matchedRule }}</span>
        </div>
        <p class="sim-threshold">{{ result.thresholdNote }}</p>
      </div>
      <div v-if="step >= 4 && result" class="sim-step" :data-on="true">
        <b>4 · 建议动作</b>
        <div class="rule-action-tags">
          <span v-for="a in result.actions" :key="a" class="rule-action-tag">{{ a }}</span>
        </div>
        <p class="sim-escalation">趋势预测：{{ result.escalation }}</p>
      </div>
    </div>

    <template #footer>
      <button type="button" class="rule-btn ghost" @click="emit('update:modelValue', false)">关闭</button>
    </template>
  </ElDialog>
</template>
