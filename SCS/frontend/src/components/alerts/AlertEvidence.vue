<script setup lang="ts">
import { computed } from 'vue'
import AIDetectionImage from '@/components/ai/AIDetectionImage.vue'
import type { AlertEvidence } from '@/types/alert'

const props = defineProps<{ evidence: AlertEvidence }>()

const trackPoints = computed(() => {
  if (props.evidence.kind !== 'personnel') return ''
  return props.evidence.track.map((p) => `${p.x * 3.2},${p.y * 1.6}`).join(' ')
})
const trackHead = computed(() => {
  if (props.evidence.kind !== 'personnel') return null
  const last = props.evidence.track[props.evidence.track.length - 1]
  return last ? { x: last.x * 3.2, y: last.y * 1.6 } : { x: 0, y: 0 }
})
const trendPoints = computed(() => {
  if (props.evidence.kind !== 'collision') return ''
  const data = props.evidence.trend
  const w = 300
  const h = 70
  const max = 12
  const min = 0
  return data.map((v, i) => `${(i / (data.length - 1)) * w},${h - ((v - min) / (max - min)) * h}`).join(' ')
})
</script>

<template>
  <section class="alert-evidence">
    <h4>事件证据</h4>

    <!-- 人员事件：轨迹 + 手环/围栏 -->
    <template v-if="evidence.kind === 'personnel'">
      <div class="evidence-track">
        <svg viewBox="0 0 320 160" preserveAspectRatio="none">
          <rect x="0" y="0" width="320" height="160" fill="#f5f7fa" />
          <rect x="150" y="18" width="140" height="110" rx="6" fill="rgb(245 108 108 / 6%)" stroke="#f78989" stroke-width="1.4" stroke-dasharray="6 4" />
          <text x="158" y="34" fill="#c45656" font-size="9" font-weight="700">危险区域边界</text>
          <polyline :points="trackPoints" fill="none" stroke="#409eff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="0" />
          <g v-if="trackHead">
            <circle :cx="trackHead.x" :cy="trackHead.y" r="9" fill="rgb(64 158 255 / 18%)" />
            <circle :cx="trackHead.x" :cy="trackHead.y" r="4.5" fill="#409eff" stroke="#fff" stroke-width="1.5" />
          </g>
        </svg>
        <span class="evidence-track__tag">人员移动轨迹</span>
      </div>
      <div class="evidence-grid">
        <div><small>当前位置</small><b>{{ evidence.currentPosition }}</b></div>
        <div><small>电子围栏</small><b>{{ evidence.fence }}</b></div>
        <div><small>手环状态</small><b>{{ evidence.band }} · {{ evidence.bandState }}</b></div>
        <div><small>心率</small><b>{{ evidence.heartRate }}</b></div>
      </div>
    </template>

    <!-- 设备事件：距离/速度/趋势/雷达 -->
    <template v-else-if="evidence.kind === 'collision'">
      <div class="evidence-collision">
        <div class="evidence-collision__metrics">
          <div><small>当前距离</small><b :class="{ danger: evidence.distance < 6 }">{{ evidence.distance.toFixed(1) }} m</b></div>
          <div><small>相对速度</small><b>{{ evidence.relSpeed.toFixed(1) }} m/s</b></div>
          <div><small>制动评估</small><b>{{ evidence.brakeDistance }}</b></div>
        </div>
        <div class="evidence-trend">
          <small>风险距离趋势（m，持续收敛）</small>
          <svg viewBox="0 0 300 70" preserveAspectRatio="none">
            <line x1="0" y1="35" x2="300" y2="35" stroke="#eebe77" stroke-width="1" stroke-dasharray="4 3" />
            <polyline :points="trendPoints" fill="none" stroke="#f56c6c" stroke-width="2" stroke-linecap="round" />
          </svg>
        </div>
        <p class="evidence-radar">雷达状态：{{ evidence.radar }}</p>
      </div>
    </template>

    <!-- AI 事件：复用 AIDetectionImage -->
    <template v-else-if="evidence.kind === 'ai'">
      <AIDetectionImage :scene="evidence.scene" :boxes="evidence.boxes" :camera="evidence.camera" :time="evidence.time" health="正常" />
      <div class="evidence-grid">
        <div><small>置信度</small><b>{{ evidence.confidence.toFixed(1) }}%</b></div>
        <div><small>模型版本</small><b>{{ evidence.model }}</b></div>
      </div>
    </template>

    <!-- 设备/系统异常：指标网格 -->
    <template v-else>
      <p class="evidence-desc">{{ evidence.description }}</p>
      <div class="evidence-grid">
        <div v-for="m in evidence.metrics" :key="m.label"><small>{{ m.label }}</small>
          <b :class="m.tone">{{ m.value }}</b></div>
      </div>
    </template>
  </section>
</template>
