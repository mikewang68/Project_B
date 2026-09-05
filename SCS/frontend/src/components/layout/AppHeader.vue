<script setup lang="ts">
import { computed } from 'vue'
import { ArrowDown, Bell, Expand } from '@element-plus/icons-vue'
import type { LiveState } from '@/api/live'
import { useOperationsStore } from '@/stores/operations'

const props = defineProps<{
  title: string
  subtitle: string
  shift: string
  connection: LiveState
  usingFallback: boolean
}>()

defineEmits<{ menu: [] }>()

const ops = useOperationsStore()
const autonomy = computed(() => ops.linkState === 'disconnected' || ops.linkState === 'link-error')
const recovering = computed(() => ops.linkState === 'recovering')

function connectionText(): string {
  if (autonomy.value) return '离线自治运行'
  if (recovering.value) return '链路恢复中'
  if (props.usingFallback) return '演示数据'
  if (props.connection === 'online') return '云边连接正常'
  if (props.connection === 'connecting') return '正在连接'
  return '云边连接中断'
}
function chipState(): string {
  if (autonomy.value || recovering.value) return 'autonomy'
  return props.usingFallback ? 'fallback' : props.connection
}
</script>

<template>
  <header class="app-header">
    <button class="header-menu-button" type="button" aria-label="打开导航" @click="$emit('menu')">
      <el-icon><Expand /></el-icon>
    </button>

    <div class="header-heading">
      <div class="header-eyebrow">B PROJECT / SAFETY OPERATIONS</div>
      <h1>{{ title }}</h1>
      <p>{{ subtitle }}</p>
    </div>

    <div class="header-actions">
      <div class="shift-chip">
        <small>当前班次</small>
        <b>{{ shift }}</b>
      </div>

      <div class="connection-chip" :data-state="chipState()">
        <i></i><span>{{ connectionText() }}</span>
      </div>

      <button class="notification-button" type="button" aria-label="通知">
        <el-icon><Bell /></el-icon><i></i>
      </button>

      <button class="header-user" type="button" aria-label="当前用户菜单">
        <span class="header-avatar">李</span>
        <span class="header-user__copy"><b>李娜</b><small>安全员</small></span>
        <el-icon><ArrowDown /></el-icon>
      </button>
    </div>
  </header>
</template>
