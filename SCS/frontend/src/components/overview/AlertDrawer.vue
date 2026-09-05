<script setup lang="ts">
import { ElDrawer } from 'element-plus'
import { Clock, Location, User } from '@element-plus/icons-vue'
import type { OverviewAlert } from '@/types/overview'

defineProps<{ modelValue: boolean; alert: OverviewAlert | undefined }>()
defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <ElDrawer
    :model-value="modelValue"
    direction="rtl"
    size="430px"
    :show-close="false"
    class="alert-detail-drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header="{ close }">
      <div class="drawer-header">
        <div><span>EVENT OVERVIEW</span><h2>告警事件详情</h2></div>
        <button type="button" aria-label="关闭告警详情" @click="close">×</button>
      </div>
    </template>

    <template v-if="alert">
      <div class="drawer-event-title" :data-level="alert.level">
        <span>{{ alert.level }}</span>
        <h3>{{ alert.title }}</h3>
        <p>{{ alert.summary }}</p>
      </div>

      <div class="drawer-facts">
        <div><el-icon><Clock /></el-icon><span><small>发生时间</small><b>{{ alert.time }}</b></span></div>
        <div><el-icon><Location /></el-icon><span><small>所属区域</small><b>{{ alert.area }}</b></span></div>
        <div><el-icon><User /></el-icon><span><small>关联对象</small><b>{{ alert.objectName }}</b></span></div>
        <div><i class="drawer-status-dot"></i><span><small>当前状态</small><b>{{ alert.status }}</b></span></div>
      </div>

      <section class="drawer-timeline">
        <header><span>EVENT TIMELINE</span><h3>事件时间线</h3></header>
        <div v-for="(item, index) in alert.timeline" :key="`${item.time}-${item.title}`" class="timeline-item" :class="{ current: index === alert.timeline.length - 1 }">
          <i></i>
          <time>{{ item.time }}</time>
          <div><b>{{ item.title }}</b><small>{{ item.detail }}</small></div>
        </div>
      </section>

      <div class="drawer-notice">本阶段仅展示事件概览，完整处置闭环将在告警中心模块中实现。</div>
    </template>
  </ElDrawer>
</template>
