<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Connection, Monitor, VideoPlay, Back } from '@element-plus/icons-vue'
import { useLiveStore } from '@/stores/live'

const props = defineProps<{ breachActive: boolean }>()
const emit = defineEmits<{ toggleDemo: [] }>()

const liveStore = useLiveStore()
const now = ref(new Date())
let timer: number | undefined

onMounted(() => {
  timer = window.setInterval(() => { now.value = new Date() }, 1000)
})
onBeforeUnmount(() => window.clearInterval(timer))

function fmt(d: Date): string {
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${week} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<template>
  <header class="bs-header">
    <div class="bs-header__side">
      <span class="bs-chip"><el-icon><Monitor /></el-icon>系统状态 · 运行正常</span>
      <span class="bs-chip" :data-offline="!liveStore.online">
        <el-icon><Connection /></el-icon>云端实时 · {{ liveStore.label }}
      </span>
    </div>
    <div class="bs-header__title">
      <h1>装卸作业安全卡控系统 · 安全态势中心</h1>
      <p>{{ fmt(now) }} ｜ 当前班次：夜班 22:00-06:00</p>
    </div>
    <div class="bs-header__side bs-header__side--right">
      <RouterLink to="/overview" class="bs-back-btn" title="返回管理后台">
        <el-icon><Back /></el-icon>返回后台
      </RouterLink>
      <button type="button" class="bs-demo-btn" :class="{ 'is-active': props.breachActive }" @click="emit('toggleDemo')">
        <el-icon><VideoPlay /></el-icon>{{ props.breachActive ? '确认已读紧急事件' : '演示模式 · 定位紧急事件' }}
      </button>
    </div>
  </header>
</template>
