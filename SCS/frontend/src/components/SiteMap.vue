<script setup lang="ts">
import type { Device, Fence, Person } from '@/types/safety'
defineProps<{ people: Person[]; fences: Fence[]; devices: Device[]; compact?: boolean }>()
</script>

<template>
  <div class="site-map" :class="{ compact }">
    <div class="map-grid"></div>
    <div class="building loading">原料装卸区<small>LOADING</small></div>
    <div class="building line-a">装卸作业线 A<small>WORK LINE A</small></div>
    <div class="building line-b">装卸作业线 B<small>WORK LINE B</small></div>
    <div class="building storage">集装箱暂存区<small>STORAGE</small></div>
    <svg class="roads" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
      <path d="M8 18H92M8 50H92M8 82H92M8 18V82M54 18V82M92 18V82" />
    </svg>
    <div v-for="fence in fences.filter((item) => item.enabled)" :key="fence.id" class="fence" :data-level="fence.level" :style="{ left: `${fence.x}%`, top: `${fence.y}%`, width: `${fence.width}%`, height: `${fence.height}%` }"><span>{{ fence.name }}</span></div>
    <div v-for="person in people" :key="person.id" class="person-marker" :data-state="person.status" :style="{ left: `${person.x}%`, top: `${person.y}%` }"><i></i><b>{{ person.name }}</b></div>
    <div v-for="device in devices" :key="device.id" class="device-marker" :data-state="device.status" :style="{ left: `${device.x}%`, top: `${device.y}%` }"><i></i><b>{{ device.name }}</b></div>
    <div class="live-chip"><i></i>实时定位 · {{ people.length }} 人</div>
    <div class="north">N<span></span></div>
  </div>
</template>

