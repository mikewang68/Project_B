<script setup lang="ts">
import { computed } from 'vue'
import type { AreaRisk } from '@/types/analytics'

const props = defineProps<{ items: AreaRisk[]; activeArea: string | undefined }>()
const emit = defineEmits<{ drill: [value: string] }>()
const maxTotal = computed(() => Math.max(...props.items.map((a) => a.total), 1))
</script>

<template>
  <div class="chart-card area-ranking" :data-active="!!activeArea">
    <header>
      <div><span>HIGH-RISK AREAS</span><h3>高风险区域</h3></div>
      <small>点击区域下钻明细</small>
    </header>
    <ol class="area-ranking__list">
      <li v-for="(item, index) in items" :key="item.area" :class="{ active: activeArea === item.area }" @click="emit('drill', item.area)">
        <span class="area-ranking__rank" :data-top="index < 2">{{ index + 1 }}</span>
        <div class="area-ranking__main">
          <div class="area-ranking__line">
            <b>{{ item.area }}</b>
            <span class="area-ranking__nums">事件 <em>{{ item.total }}</em><i>高风险 {{ item.high }}</i></span>
          </div>
          <div class="area-ranking__bar">
            <span class="area-ranking__bar-total" :style="{ width: (item.total / maxTotal * 100) + '%' }"></span>
            <span class="area-ranking__bar-high" :style="{ width: (item.total / maxTotal * item.high / item.total * 100) + '%' }"></span>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>
