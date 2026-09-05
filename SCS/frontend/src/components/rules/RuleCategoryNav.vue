<script setup lang="ts">
import { computed } from 'vue'
import { Grid, User, Aim, Camera, Bell, Connection, Message } from '@element-plus/icons-vue'
import type { RuleCategory, SafetyRule } from '@/types/rule'

const props = defineProps<{ rules: SafetyRule[]; active: string }>()
const emit = defineEmits<{ select: [category: string] }>()

const iconMap = {
  '人员安全': User,
  '设备安全': Aim,
  'AI识别': Camera,
  '告警策略': Bell,
  '联动策略': Connection,
  '通知策略': Message,
}

const items = computed(() => {
  const all = props.rules.length
  const list = [{ key: '全部', label: '全部分类', count: all, icon: Grid }]
  for (const cat of Object.keys(iconMap) as RuleCategory[]) {
    list.push({ key: cat, label: cat, count: props.rules.filter((r) => r.category === cat).length, icon: iconMap[cat] })
  }
  return list
})
</script>

<template>
  <aside class="rule-category dashboard-card">
    <p class="rule-category__title">规则分类</p>
    <button v-for="it in items" :key="it.key" type="button" class="rule-category__item"
      :class="{ active: active === it.key }" @click="emit('select', it.key)">
      <span class="rule-category__icon"><el-icon><component :is="it.icon" /></el-icon></span>
      <span class="rule-category__label">{{ it.label }}</span>
      <span class="rule-category__count">{{ it.count }}</span>
    </button>
  </aside>
</template>
