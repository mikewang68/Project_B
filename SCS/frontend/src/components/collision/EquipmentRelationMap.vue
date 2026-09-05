<script setup lang="ts">
import { computed } from 'vue'
import { Connection, WarningFilled } from '@element-plus/icons-vue'
import EquipmentNode from './EquipmentNode.vue'
import DistanceIndicator from './DistanceIndicator.vue'
import type { CollisionEquipment, CollisionRisk } from '@/types/collision'

const props = defineProps<{
  current: CollisionEquipment
  related: CollisionEquipment
  distance: number
  risk: CollisionRisk
  radarDown: boolean
}>()
defineEmits<{ selectEquipment: [equipment: CollisionEquipment] }>()

const closingRate = computed(() => Math.max(0, Math.min(1, (12.6 - props.distance) / 9.8)))
</script>

<template>
  <article class="dashboard-card collision-relation-card" :data-risk="risk">
    <header class="workspace-card-header"><div><span>SPATIAL RELATION</span><h2>设备空间关系</h2><p>设备相对位置、运动方向与安全距离综合监测</p></div><span class="relation-live"><i></i>{{ radarDown ? '雷达数据中断' : '实时计算中' }}</span></header>
    <div class="equipment-relation-stage" :class="{ 'sensor-degraded': radarDown }">
      <div class="relation-grid"></div>
      <div class="relation-lane"><span></span></div>
      <div class="relation-node relation-node--left" :style="{ transform: `translateX(${closingRate * 42}px)` }"><EquipmentNode :equipment="current" role="active" @select="$emit('selectEquipment', $event)" /></div>
      <div class="distance-connection" :data-risk="risk">
        <span class="distance-connection__arrow left"></span>
        <div><el-icon><WarningFilled v-if="['严重','紧急','待确认'].includes(risk)" /><Connection v-else /></el-icon><strong>{{ radarDown ? '—' : distance.toFixed(1) }}<small>m</small></strong><em>{{ radarDown ? '无法确认距离' : risk === '安全' ? '间距充足' : '距离持续接近' }}</em></div>
        <span class="distance-connection__arrow right"></span>
      </div>
      <div class="relation-node relation-node--right"><EquipmentNode :equipment="related" role="related" @select="$emit('selectEquipment', $event)" /></div>
      <div v-if="radarDown" class="relation-sensor-mask"><el-icon><WarningFilled /></el-icon><div><b>传感器降级</b><span>无法确认设备间安全距离</span></div></div>
    </div>
    <DistanceIndicator :distance="distance" :risk="risk" :radar-down="radarDown" />
  </article>
</template>
