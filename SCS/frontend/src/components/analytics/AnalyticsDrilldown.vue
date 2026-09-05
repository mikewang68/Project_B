<script setup lang="ts">
import { Close } from '@element-plus/icons-vue'
export interface ActiveChip {
  key: string
  label: string
  /** filter = 顶部筛选；drill = 图表下钻 */
  source: 'filter' | 'drill'
}
defineProps<{ chips: ActiveChip[] }>()
const emit = defineEmits<{ remove: [key: string]; clear: [] }>()
</script>

<template>
  <div v-if="chips.length" class="drilldown-bar">
    <span class="drilldown-bar__label">当前筛选</span>
    <span v-for="chip in chips" :key="chip.key" class="drilldown-chip" :data-source="chip.source">
      {{ chip.label }}<button type="button" aria-label="移除" @click="emit('remove', chip.key)"><el-icon><Close /></el-icon></button>
    </span>
    <button type="button" class="drilldown-clear" @click="emit('clear')">全部清除</button>
  </div>
</template>
