<script setup lang="ts">
import { computed } from 'vue'
import type { AiSceneKind, CameraHealth, DetectionBox } from '@/types/ai'

const props = defineProps<{
  scene: AiSceneKind
  boxes: DetectionBox[]
  health?: CameraHealth
  camera: string
  time: string
  compact?: boolean
}>()

const W = 320
const H = 200
const px = (v: number): number => (v / 100) * W
const py = (v: number): number => (v / 100) * H
const corner = 9

const personBoxes = computed(() => props.boxes.filter((b) => b.tone === 'person'))
const degraded = computed(() => props.health === '画面质量下降' || props.health === '离线')

/** 在人物检测框内绘制简影，增强场景真实感 */
function figure(b: DetectionBox) {
  const cx = px(b.x + b.w / 2)
  const headR = Math.max(4, px(b.w) * 0.16)
  const headY = py(b.y) + headR + 1
  const bodyTop = headY + headR
  const bodyBottom = py(b.y + b.h)
  const shoulder = px(b.w) * 0.42
  return { cx, headR, headY, bodyTop, bodyBottom, shoulder }
}

function labelText(b: DetectionBox): string {
  return b.score ? `${b.label} ${b.score.toFixed(1)}%` : b.label
}
</script>

<template>
  <div class="ai-detection-image" :data-compact="compact ? 'y' : 'n'" :data-health="degraded ? 'fault' : 'ok'">
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <defs>
        <linearGradient :id="`sky-${camera}-${time}`" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#eef2f5" />
          <stop offset="1" stop-color="#e2e8ed" />
        </linearGradient>
      </defs>

      <!-- 基础场地 -->
      <rect x="0" y="0" :width="W" :height="H" :fill="`url(#sky-${camera}-${time})`" />
      <rect x="0" y="148" :width="W" :height="52" fill="#d7dfe5" />
      <g stroke="#cdd6dd" stroke-width="1">
        <line x1="0" y1="148" x2="320" y2="148" />
        <line x1="40" y1="200" x2="120" y2="148" />
        <line x1="160" y1="200" x2="160" y2="148" />
        <line x1="280" y1="200" x2="200" y2="148" />
      </g>

      <!-- 场景设备轮廓 -->
      <g v-if="scene === 'helmet' || scene === 'intrusion'" fill="#c3cdd7">
        <rect x="34" y="34" width="10" height="114" />
        <rect x="276" y="34" width="10" height="114" />
        <rect x="30" y="30" width="260" height="9" rx="2" />
        <rect x="146" y="39" width="28" height="14" fill="#b6c2ce" />
      </g>
      <g v-else-if="scene === 'fence'" stroke="#aebbc8" stroke-width="3" fill="none">
        <line x1="10" y1="118" x2="310" y2="118" />
        <line x1="10" y1="132" x2="310" y2="132" />
        <line v-for="x in [30,70,110,150,190,230,270]" :key="x" :x1="x" y1="104" :x2="x" y2="146" />
      </g>
      <g v-else-if="scene === 'linger'" fill="#c8d2db">
        <rect x="14" y="96" width="52" height="52" rx="3" />
        <rect x="254" y="92" width="52" height="56" rx="3" />
      </g>
      <g v-else-if="scene === 'camera'">
        <polygon points="0,0 320,0 320,86 232,70 168,96 88,74 0,92" fill="#c9d2da" opacity="0.92" />
        <polygon points="70,200 132,118 196,142 250,200" fill="#c2ccd5" opacity="0.8" />
      </g>

      <!-- 人物简影 -->
      <g v-for="b in personBoxes" :key="`fig-${b.id}`" fill="#93a1b1">
        <circle :cx="figure(b).cx" :cy="figure(b).headY" :r="figure(b).headR" />
        <path
          :d="`M ${figure(b).cx - figure(b).shoulder} ${figure(b).bodyBottom}
               L ${figure(b).cx - figure(b).shoulder * 0.7} ${figure(b).bodyTop + 4}
               Q ${figure(b).cx} ${figure(b).bodyTop - 3} ${figure(b).cx + figure(b).shoulder * 0.7} ${figure(b).bodyTop + 4}
               L ${figure(b).cx + figure(b).shoulder} ${figure(b).bodyBottom} Z`"
        />
      </g>

      <!-- AI 检测框 -->
      <g v-for="b in boxes" :key="b.id" class="det-box" :data-tone="b.tone">
        <template v-if="b.tone === 'zone'">
          <rect :x="px(b.x)" :y="py(b.y)" :width="px(b.w)" :height="py(b.h)" rx="3"
            fill="rgb(230 162 60 / 6%)" stroke="#e6a23c" stroke-width="1.4" stroke-dasharray="6 4" />
        </template>
        <template v-else>
          <rect :x="px(b.x)" :y="py(b.y)" :width="px(b.w)" :height="py(b.h)" fill="none" stroke-width="1.6" />
          <path class="det-corners" :d="`
            M ${px(b.x)} ${py(b.y) + corner} V ${py(b.y)} H ${px(b.x) + corner}
            M ${px(b.x + b.w) - corner} ${py(b.y)} H ${px(b.x + b.w)} V ${py(b.y) + corner}
            M ${px(b.x + b.w)} ${py(b.y + b.h) - corner} V ${py(b.y + b.h)} H ${px(b.x + b.w) - corner}
            M ${px(b.x) + corner} ${py(b.y + b.h)} H ${px(b.x)} V ${py(b.y + b.h) - corner}`"
            fill="none" stroke-width="2.4" />
        </template>
        <g class="det-label" :transform="`translate(${px(b.x) + 1}, ${b.tone === 'person' ? py(b.y + b.h) - 15 : py(b.y) + 3})`">
          <rect :width="labelText(b).length * 6.4 + 12" height="14" rx="3" />
          <text x="6" y="10">{{ labelText(b) }}</text>
        </g>
      </g>
    </svg>

    <div v-if="degraded" class="ai-image-degrade"></div>
    <div class="ai-image-osd">
      <span><i></i>{{ camera }}</span>
      <span>{{ time }}</span>
    </div>
    <div v-if="degraded" class="ai-image-fault-tag">摄像头降级 · AI 能力受限</div>
  </div>
</template>
