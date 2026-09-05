<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import type { CollisionRisk } from '@/types/collision'

const props = defineProps<{
  risk: CollisionRisk
  distance: number
  radarDown: boolean
  deviceStopped: boolean
  plcStatus: string
  controlFailure: boolean
  takeoverDone: boolean
}>()
defineEmits<{ takeover: []; release: [] }>()

const message = computed(() => {
  if (props.radarDown) return ['传感器降级', '无法确认安全距离，建议限制设备运行']
  if (props.controlFailure) return ['未确认设备已停机', 'PLC 回执超时，请立即人工接管']
  if (props.takeoverDone) return ['人工接管完成', '现场已确认设备停止']
  if (props.risk === '紧急') return props.deviceStopped ? ['风险控制成功', 'PLC 已确认设备停止'] : ['紧急碰撞风险', '系统建议立即停止设备运行']
  if (props.risk === '严重') return ['预测距离持续下降', '系统已发送减速请求']
  if (props.risk === '预警') return ['设备距离进入预警范围', '建议减速并持续关注运行趋势']
  return ['当前风险安全', '设备间距充足']
})

const metrics = computed(() => [
  ['相对速度', props.radarDown ? '待确认' : props.risk === '安全' ? '1.8 m/s' : '2.4 m/s'],
  ['预测最小距离', props.radarDown ? '—' : props.risk === '安全' ? '9.8m' : `${Math.max(1.6, props.distance - 1.2).toFixed(1)}m`],
  ['预计碰撞时间 TTC', props.risk === '紧急' && !props.deviceStopped ? '1.2s' : '—'],
  ['制动距离', '4.6m'],
  ['雷达质量', props.radarDown ? '数据中断' : '98%'],
  ['天气修正', '正常'],
])
</script>

<template>
  <aside class="dashboard-card risk-status-panel" :data-risk="risk">
    <header><div><span>LIVE RISK</span><h2>实时风险状态</h2></div><RiskBadge :risk="risk" /></header>
    <section class="risk-hero">
      <div class="risk-hero__icon"><el-icon><CircleCheckFilled v-if="risk === '安全'" /><WarningFilled v-else /></el-icon></div>
      <small>当前设备间距</small>
      <strong>{{ radarDown ? '—' : distance.toFixed(1) }}<em>m</em></strong>
      <h3>{{ message[0] }}</h3><p>{{ message[1] }}</p>
    </section>
    <div class="risk-metric-grid"><div v-for="metric in metrics" :key="metric[0]"><small>{{ metric[0] }}</small><b>{{ metric[1] }}</b></div></div>
    <div class="plc-state" :data-state="controlFailure ? 'failed' : deviceStopped ? 'success' : 'normal'"><span><i></i>PLC 控制状态</span><b>{{ plcStatus }}</b></div>
    <div v-if="controlFailure" class="takeover-alert"><b>设备状态：未确认</b><p>控制链路未闭环，必须由现场人员确认设备已停止。</p><button type="button" @click="$emit('takeover')">人工接管</button></div>
    <button v-if="deviceStopped && !controlFailure" type="button" class="risk-release-button" @click="$emit('release')">解除风险</button>
  </aside>
</template>
