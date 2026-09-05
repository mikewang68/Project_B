<script setup lang="ts">
import { computed } from 'vue'
import { formatDuration, type TeamEfficiency } from '@/types/analytics'

const props = defineProps<{ items: TeamEfficiency[]; activeTeam: string | undefined }>()
const emit = defineEmits<{ drill: [value: string] }>()

const maxClose = computed(() => Math.max(...props.items.map((t) => t.closeSec), 1))
// 效率阈值：确认 >2min、到场 >6min、关闭 >20min 视为偏慢
function slow(sec: number, limit: number): boolean { return sec > limit }
function rowTone(t: TeamEfficiency): 'bad' | 'warn' | 'ok' {
  if (t.closeSec > 1300 || t.arriveSec > 400) return 'bad'
  if (t.closeSec > 1100 || t.arriveSec > 340) return 'warn'
  return 'ok'
}
</script>

<template>
  <div class="chart-card treatment-efficiency" :data-active="!!activeTeam">
    <header>
      <div><span>RESPONSE EFFICIENCY</span><h3>告警处置效率（按班组）</h3></div>
      <small>点击班组下钻明细 · 偏慢指标自动标色</small>
    </header>
    <div class="efficiency-table">
      <div class="efficiency-table__head">
        <span class="eff-team">班组</span><span>平均确认</span><span>平均到场</span><span class="eff-close">平均关闭</span>
      </div>
      <div v-for="t in items" :key="t.team" class="efficiency-row" :data-tone="rowTone(t)"
        :class="{ active: activeTeam === t.team }" @click="emit('drill', t.team)">
        <div class="eff-team">
          <b>{{ t.team }}</b><small>{{ t.eventCount }} 起事件</small>
          <i v-if="rowTone(t)==='bad'" class="eff-flag bad">处置偏慢</i>
          <i v-else-if="rowTone(t)==='warn'" class="eff-flag warn">关注</i>
        </div>
        <span class="eff-cell" :data-slow="slow(t.confirmSec, 120)">{{ formatDuration(t.confirmSec) }}</span>
        <span class="eff-cell" :data-slow="slow(t.arriveSec, 360)">{{ formatDuration(t.arriveSec) }}</span>
        <div class="eff-close">
          <span class="eff-cell" :data-slow="slow(t.closeSec, 1200)">{{ formatDuration(t.closeSec) }}</span>
          <span class="eff-bar"><i :style="{ width: (t.closeSec / maxClose * 100) + '%' }"></i></span>
        </div>
      </div>
    </div>
  </div>
</template>
