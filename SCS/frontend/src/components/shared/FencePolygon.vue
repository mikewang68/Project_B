<script setup lang="ts">
import { computed } from 'vue'
import type { FencePoint, FenceTone } from '@/types/fence'

const props = withDefaults(defineProps<{
  points: FencePoint[]
  bufferPoints?: FencePoint[] | undefined
  tone: FenceTone
  selected?: boolean
  label?: string
  preview?: boolean
}>(), { selected: false, label: '', preview: false, bufferPoints: () => [] })

const polygonPoints = computed(() => props.points.map((point) => `${point.x},${point.y}`).join(' '))
const bufferPolygonPoints = computed(() => (props.bufferPoints ?? []).map((point) => `${point.x},${point.y}`).join(' '))
</script>

<template>
  <g class="fence-polygon" :data-tone="tone" :class="{ 'is-selected': selected, 'is-preview': preview }">
    <!-- 外围逻辑缓冲区（隔离带预警区，不要等真到红线才报警） -->
    <polygon
      v-if="bufferPoints && bufferPoints.length >= 3"
      class="fence-buffer-zone"
      :points="bufferPolygonPoints"
    />
    <!-- 物理/电子围栏核心红线区域 -->
    <polygon v-if="points.length >= 3" :points="polygonPoints" />
    <polyline v-else-if="points.length > 1" :points="polygonPoints" />
    <circle v-for="(point, index) in points" :key="index" :cx="point.x" :cy="point.y" r="0.85" />
    <text v-if="label && points.length >= 3" :x="points[0]!.x + 1.6" :y="points[0]!.y + 3.4">{{ label }}</text>
  </g>
</template>
