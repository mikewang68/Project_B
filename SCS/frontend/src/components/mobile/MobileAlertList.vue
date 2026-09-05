<script setup lang="ts">
import { computed, ref } from 'vue'
import MobileAlertCard from './MobileAlertCard.vue'
import { useIncidentStore } from '@/stores/incident'

const store = useIncidentStore()
const filter = ref<'全部' | '待处理' | '处置中'>('全部')

// 仅展示已派发现场之后的事件（待确认/待派单属于管理端流程，不在移动端出现）
const dispatched = computed(() =>
  store.incidents.filter((i) => !['待确认', '待派单', '已确认'].includes(i.alertStatus)))

const list = computed(() => {
  if (filter.value === '待处理') return dispatched.value.filter((i) => i.status === '待接单')
  if (filter.value === '处置中') return dispatched.value.filter((i) => ['已接单', '已到场', '处理中', '待复核'].includes(i.status))
  return dispatched.value
})
const filters = ['全部', '待处理', '处置中'] as const
</script>

<template>
  <div class="m-list">
    <header class="m-page-head">
      <h2>现场告警</h2>
      <p>按距离与风险等级排序，点击卡片查看详情并处置</p>
    </header>

    <div class="m-segmented">
      <button
        v-for="f in filters"
        :key="f"
        type="button"
        :data-active="filter === f"
        @click="filter = f"
      >{{ f }}</button>
    </div>

    <div class="m-list__body">
      <MobileAlertCard v-for="i in list" :key="i.id" :incident="i" />
      <p v-if="list.length === 0" class="m-list__empty">当前筛选下暂无告警</p>
    </div>
  </div>
</template>
