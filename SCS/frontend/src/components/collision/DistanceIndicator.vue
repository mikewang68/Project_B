<script setup lang="ts">
import { computed } from 'vue'
import type { CollisionRisk } from '@/types/collision'

const props = defineProps<{ distance: number; risk: CollisionRisk; radarDown: boolean }>()
const position = computed(() => `${Math.max(2, Math.min(98, (props.distance / 16) * 100))}%`)
</script>

<template>
  <div class="distance-indicator" :data-risk="risk">
    <div class="distance-indicator__head"><div><small>综合距离风险刻度</small><b>{{ radarDown ? '安全距离不可确认' : `当前 ${distance.toFixed(1)}m` }}</b></div><span>{{ radarDown ? '传感器降级' : risk }}</span></div>
    <div class="distance-scale">
      <div class="distance-scale__bar"><i class="emergency"></i><i class="severe"></i><i class="warning"></i><i class="safe"></i><span v-if="!radarDown" :style="{ left: position }"><b>{{ distance.toFixed(1) }}m</b></span></div>
      <div class="distance-scale__labels"><span>紧急<br><b>&lt; 3m</b></span><span>严重<br><b>3–6m</b></span><span>预警<br><b>6–10m</b></span><span>安全<br><b>&gt; 10m</b></span></div>
    </div>
    <p>判定同时考虑当前距离、相对速度、运行方向、制动距离、雷达质量与天气修正。</p>
  </div>
</template>
