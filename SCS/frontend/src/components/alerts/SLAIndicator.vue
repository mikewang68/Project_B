<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Clock } from '@element-plus/icons-vue'
import { remainingSecByDeadline } from '@/adapters/alert'

const props = defineProps<{ remainingSec?: number | undefined; deadline?: string | null | undefined }>()

// 以绝对 slaDeadline 为核心显示依据：本地每秒走秒，无需轮询后端
const now = ref(Date.now())
let timer: number | undefined
watch(
  () => props.deadline,
  (deadline) => {
    window.clearInterval(timer)
    timer = undefined
    if (deadline) timer = window.setInterval(() => (now.value = Date.now()), 1000)
  },
  { immediate: true },
)
onBeforeUnmount(() => window.clearInterval(timer))

const effectiveSec = computed(() => {
  if (props.deadline) return remainingSecByDeadline(props.deadline, new Date(now.value))
  return props.remainingSec
})

function fmt(sec: number): string {
  const abs = Math.abs(sec)
  const m = Math.floor(abs / 60)
  const s = abs % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const tone = computed<'closed' | 'ok' | 'warn' | 'over'>(() => {
  if (effectiveSec.value === undefined) return 'closed'
  if (effectiveSec.value < 0) return 'over'
  if (effectiveSec.value <= 300) return 'warn'
  return 'ok'
})
const text = computed(() => {
  if (effectiveSec.value === undefined) return '已办结'
  return effectiveSec.value < 0 ? `超时 ${fmt(effectiveSec.value)}` : `剩余 ${fmt(effectiveSec.value)}`
})
</script>

<template>
  <span class="sla-indicator" :data-tone="tone">
    <el-icon><Clock /></el-icon>{{ text }}
  </span>
</template>
