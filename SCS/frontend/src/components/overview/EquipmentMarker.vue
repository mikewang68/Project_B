<script setup lang="ts">
import { Camera, Monitor, SetUp, Van } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import type { EquipmentPoint } from '@/types/overview'

const props = defineProps<{ equipment: EquipmentPoint }>()

const icons: Record<EquipmentPoint['type'], Component> = {
  crane: SetUp,
  tipper: Monitor,
  vehicle: Van,
  camera: Camera,
  device: Monitor,
}
</script>

<template>
  <div
    class="equipment-marker"
    :data-state="equipment.state"
    :style="{ left: `${equipment.x}%`, top: `${equipment.y}%` }"
  >
    <span><el-icon><component :is="icons[props.equipment.type]" /></el-icon></span>
    <i></i>
    <div class="marker-tooltip"><b>{{ equipment.name }}</b><small>{{ equipment.state === 'normal' ? '运行正常' : '需要关注' }}</small></div>
  </div>
</template>
