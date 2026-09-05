<script setup lang="ts">
import { Close, Clock, Connection, Lock, WarningFilled } from '@element-plus/icons-vue'
import { ElDrawer } from 'element-plus'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import type { CollisionEquipment } from '@/types/collision'

defineProps<{ modelValue: boolean; equipment: CollisionEquipment | undefined }>()
defineEmits<{ 'update:modelValue': [value: boolean]; history: []; requestRelease: [] }>()
</script>

<template>
  <ElDrawer :model-value="modelValue" size="400px" :with-header="false" class="equipment-detail-drawer" @update:model-value="$emit('update:modelValue', $event)">
    <template v-if="equipment">
      <header class="equipment-drawer-header"><div><span>EQUIPMENT PROFILE</span><h2>{{ equipment.name }}</h2><p>{{ equipment.id }} · {{ equipment.type }}</p></div><button type="button" aria-label="关闭设备详情" @click="$emit('update:modelValue', false)"><el-icon><Close /></el-icon></button></header>
      <div class="equipment-drawer-status"><div><small>当前运行状态</small><StatusBadge :status="equipment.status" /></div><div><small>综合风险</small><RiskBadge :risk="equipment.risk" /></div></div>
      <dl class="equipment-detail-list">
        <div><dt>当前位置</dt><dd>{{ equipment.area }}</dd></div><div><dt>运行状态</dt><dd>{{ equipment.status }}</dd></div>
        <div><dt>当前速度</dt><dd>{{ equipment.speed.toFixed(1) }} km/h</dd></div><div><dt>运行方向</dt><dd>{{ equipment.direction }}</dd></div>
        <div><dt>控制状态</dt><dd>{{ equipment.controlStatus }}</dd></div><div><dt>通信状态</dt><dd>{{ equipment.communication }}</dd></div>
        <div><dt>雷达状态</dt><dd>{{ equipment.radarStatus }}</dd></div><div><dt>最后更新</dt><dd>{{ equipment.lastUpdated }}</dd></div>
        <div class="wide"><dt>关联设备</dt><dd>{{ equipment.relatedEquipment }}</dd></div><div class="wide"><dt>最近告警</dt><dd>{{ equipment.latestAlert }}</dd></div>
      </dl>
      <section class="equipment-control-note"><el-icon><Connection /></el-icon><div><b>控制链路受安全策略保护</b><p>解除限制需要现场状态、PLC 回执和传感器数据共同确认。</p></div></section>
      <div class="equipment-drawer-actions"><button type="button" @click="$emit('history')"><el-icon><Clock /></el-icon>查看历史风险</button><button type="button" class="primary" @click="$emit('requestRelease')"><el-icon><Lock /></el-icon>申请解除限制</button></div>
      <p class="equipment-drawer-footnote"><el-icon><WarningFilled /></el-icon>当前页面数据为 Demo 模拟，不会控制真实设备。</p>
    </template>
  </ElDrawer>
</template>
