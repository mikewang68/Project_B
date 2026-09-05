<script setup lang="ts">
import AlertItem from './AlertItem.vue'
import type { OverviewAlert } from '@/types/overview'

defineProps<{ alerts: OverviewAlert[]; pending: number }>()
defineEmits<{ select: [alert: OverviewAlert] }>()
</script>

<template>
  <article class="dashboard-card realtime-feed-card">
    <header class="dashboard-card__header">
      <div><span>REAL-TIME ALERTS</span><h2>实时告警</h2><p>现场风险与设备事件持续更新</p></div>
      <span class="feed-count">{{ pending }} 待处理</span>
    </header>
    <TransitionGroup name="alert-feed" tag="div" class="realtime-feed">
      <AlertItem v-for="alert in alerts" :key="alert.id" :alert="alert" @select="$emit('select', $event)" />
    </TransitionGroup>
    <footer class="feed-footer"><span><i></i>实时监听中</span><RouterLink to="/alarms">进入告警中心</RouterLink></footer>
  </article>
</template>
