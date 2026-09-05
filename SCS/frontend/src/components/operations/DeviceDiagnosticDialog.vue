<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { CircleCheckFilled, Loading } from '@element-plus/icons-vue'
import type { OpsDevice } from '@/types/operations'
import OpsHealthBadge from './OpsHealthBadge.vue'

const props = defineProps<{ modelValue: boolean; device: OpsDevice | null }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  reconnect: [device: OpsDevice]
}>()

const phase = ref<'idle' | 'running' | 'done'>('idle')
const checks = ref<{ label: string; state: 'wait' | 'running' | 'done'; text: string }[]>([])

watch(
  () => props.modelValue,
  (v) => {
    if (!v || !props.device) return
    phase.value = 'running'
    const seq = [
      { label: '设备供电', t: '供电正常' },
      { label: '网络链路', t: '链路抖动，已切换备用通道' },
      { label: '边缘接入', t: '已重新接入 EDGE 节点' },
      { label: '采集能力', t: '画面 / 数据流恢复' },
    ]
    checks.value = seq.map((s, i) => ({ label: s.label, state: i === 0 ? 'running' : 'wait', text: i === 0 ? '检测中…' : '等待' }))
    seq.forEach((s, idx) => {
      window.setTimeout(() => {
        const target = checks.value.find((c) => c.label === s.label)
        if (target) { target.state = 'done'; target.text = s.t }
        const next = checks.value[idx + 1]
        if (next) next.state = 'running'
        else phase.value = 'done'
      }, 550 * (idx + 1))
    })
  },
)

function reconnect(): void {
  if (props.device) emit('reconnect', props.device)
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="device-diag-dialog"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header>
      <div class="ops-dialog-heading">
        <span>DEVICE DIAGNOSTIC</span>
        <h2>{{ device?.id }} 设备诊断</h2>
      </div>
    </template>
    <div v-if="device" class="diag-body">
      <div class="diag-meta">
        <div><small>设备名称</small><b>{{ device.name }}</b></div>
        <div><small>设备类型</small><b>{{ device.kind }}</b></div>
        <div><small>挂载节点</small><b class="mono">{{ device.edgeId }}</b></div>
        <div><small>当前状态</small><OpsHealthBadge :state="device.state" :label="device.issue || '正常'" /></div>
      </div>
      <ul class="diag-steps">
        <li v-for="c in checks" :key="c.label" :data-state="c.state">
          <span class="diag-steps__icon">
            <el-icon v-if="c.state === 'running'" class="spin"><Loading /></el-icon>
            <el-icon v-else-if="c.state === 'done'"><CircleCheckFilled /></el-icon>
            <em v-else></em>
          </span>
          <b>{{ c.label }}</b>
          <p>{{ c.text }}</p>
        </li>
      </ul>
    </div>
    <template #footer>
      <button type="button" class="ops-btn" @click="emit('update:modelValue', false)">关闭</button>
      <button type="button" class="ops-btn primary" :disabled="phase !== 'done'" @click="reconnect">重新连接设备</button>
    </template>
  </el-dialog>
</template>
