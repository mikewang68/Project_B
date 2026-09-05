<script setup lang="ts">
import { Monitor, SetUp, Van } from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { CollisionEquipment } from '@/types/collision'

const props = defineProps<{ equipment: CollisionEquipment; role: 'active' | 'related'; compact?: boolean }>()
defineEmits<{ select: [equipment: CollisionEquipment] }>()

const icon = computed<Component>(() => ({ '转运车辆': Van, '翻箱机': Monitor, '龙门吊': SetUp })[props.equipment.type])
</script>

<template>
  <button type="button" class="collision-equipment-node" :class="{ 'is-compact': compact }" :data-role="role" @click="$emit('select', equipment)">
    <span class="collision-equipment-node__icon"><el-icon><component :is="icon" /></el-icon><i></i></span>
    <span class="collision-equipment-node__copy">
      <small>{{ role === 'active' ? 'CURRENT DEVICE' : 'RELATED DEVICE' }}</small>
      <b>{{ equipment.name }}</b>
      <em>{{ equipment.area }}</em>
    </span>
    <StatusBadge :status="equipment.status" />
    <dl>
      <div><dt>{{ equipment.type === '转运车辆' ? '速度' : '状态' }}</dt><dd>{{ equipment.type === '转运车辆' ? `${equipment.speed.toFixed(1)} km/h` : equipment.status }}</dd></div>
      <div><dt>{{ equipment.type === '转运车辆' ? '方向' : '安全区域' }}</dt><dd>{{ equipment.type === '转运车辆' ? equipment.direction : '已启用' }}</dd></div>
      <div><dt>雷达</dt><dd>{{ equipment.radarStatus }}</dd></div>
    </dl>
    <span class="node-open-tip">点击查看详情</span>
  </button>
</template>
