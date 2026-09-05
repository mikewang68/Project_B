<script setup lang="ts">
import { Close, Location, Message, VideoPlay } from '@element-plus/icons-vue'
import { ElDrawer } from 'element-plus'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { PersonnelRecord } from '@/types/personnel'

defineProps<{ modelValue: boolean; person: PersonnelRecord | undefined }>()
defineEmits<{
  'update:modelValue': [value: boolean]
  locate: []
  playback: []
  remind: []
  alerts: []
}>()
</script>

<template>
  <ElDrawer :model-value="modelValue" size="390px" :with-header="false" class="personnel-detail-drawer" @update:model-value="$emit('update:modelValue', $event)">
    <template v-if="person">
      <header class="detail-drawer-header">
        <div><span>PERSONNEL PROFILE</span><h2>{{ person.name }}</h2><p>{{ person.team }} · {{ person.jobNo }}</p></div>
        <button type="button" aria-label="关闭" @click="$emit('update:modelValue', false)"><el-icon><Close /></el-icon></button>
      </header>
      <section class="detail-risk-summary">
        <span class="person-large-avatar" :data-state="person.state">{{ person.name.slice(0, 1) }}</span>
        <div><small>当前风险</small><StatusBadge :status="person.risk" /><p>{{ person.area }} · {{ person.coordinate }}</p></div>
      </section>
      <dl class="person-detail-grid">
        <div><dt>手环编号</dt><dd>{{ person.bracelet }}</dd></div><div><dt>在线状态</dt><dd>{{ person.status }}</dd></div>
        <div><dt>手环电量</dt><dd :class="{ warning: person.battery < 20 }">{{ person.battery }}%</dd></div><div><dt>定位质量</dt><dd>{{ person.positioningQuality }}</dd></div>
        <div><dt>当前区域</dt><dd>{{ person.area }}</dd></div><div><dt>最后更新</dt><dd>{{ person.lastUpdated }}</dd></div>
        <div><dt>今日移动距离</dt><dd>{{ person.distanceToday }} km</dd></div><div><dt>今日告警</dt><dd>{{ person.alertsToday }} 条</dd></div>
      </dl>
      <div class="person-detail-actions">
        <button type="button" @click="$emit('locate')"><el-icon><Location /></el-icon>定位人员</button>
        <button type="button" class="primary" @click="$emit('playback')"><el-icon><VideoPlay /></el-icon>轨迹回放</button>
        <button type="button" @click="$emit('remind')"><el-icon><Message /></el-icon>发送提醒</button>
        <button type="button" @click="$emit('alerts')">查看相关告警</button>
      </div>
    </template>
  </ElDrawer>
</template>
